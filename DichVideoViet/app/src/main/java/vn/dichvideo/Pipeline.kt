package vn.dichvideo

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Seg(val start: Double, val end: Double, val text: String)

object Srt {
    private val TIME = Regex("""(\d+):(\d+):(\d+)[,.](\d+)""")
    private fun sec(s: String): Double {
        val m = TIME.find(s)!!.groupValues
        return m[1].toInt() * 3600 + m[2].toInt() * 60 + m[3].toInt() + m[4].padEnd(3, '0').take(3).toInt() / 1000.0
    }
    private fun fmt(s: Double): String {
        val ms = (s * 1000).toLong()
        return "%02d:%02d:%02d,%03d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    }
    fun parse(text: String): List<Seg> =
        text.replace("\r", "").trim().split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.lines()
            val i = lines.indexOfFirst { "-->" in it }
            if (i < 0) return@mapNotNull null
            val (a, b) = lines[i].split("-->")
            val t = lines.drop(i + 1).joinToString(" ").replace(Regex("<[^>]+>"), "").trim()
            if (t.isEmpty()) null else Seg(sec(a), sec(b), t)
        }
    fun write(segs: List<Seg>) = segs.mapIndexed { i, s ->
        "${i + 1}\n${fmt(s.start)} --> ${fmt(s.end)}\n${s.text}\n"
    }.joinToString("\n")
}

object Pipeline {
    private const val SR = 16000

    private fun ff(vararg a: String) {
        val s = FFmpegKit.executeWithArguments(arrayOf(*a))
        if (!ReturnCode.isSuccess(s.returnCode)) error("FFmpeg lỗi: " + (s.output ?: "").takeLast(300))
    }

    suspend fun run(ctx: Context, video: Uri, srt: Uri?, log: (String) -> Unit): File =
        withContext(Dispatchers.IO) {
            Models.ensure(ctx, log)
            val work = File(ctx.cacheDir, "work").apply { deleteRecursively(); mkdirs() }
            val input = File(work, "in.mp4")
            ctx.contentResolver.openInputStream(video)!!.use { i -> input.outputStream().use { i.copyTo(it) } }

            val segs = if (srt != null) {
                log("Đọc phụ đề…")
                Srt.parse(ctx.contentResolver.openInputStream(srt)!!.bufferedReader().readText())
            } else recognize(ctx, input, work, log)
            if (segs.isEmpty()) error("Không tìm thấy lời thoại.")

            val vi = translateAll(segs, log)
            if (vi.isEmpty()) error("Không dịch được câu nào (ngôn ngữ chưa được hỗ trợ?).")
            val clips = synthesize(ctx, vi, work, log)

            log("Ghép giọng Việt vào video…")
            val outDir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
            val stamp = System.currentTimeMillis()
            val outVideo = File(outDir, "vi_$stamp.mp4")
            File(outDir, "vi_$stamp.srt").writeText(Srt.write(vi))

            val args = mutableListOf("-y", "-i", input.path)
            clips.forEach { args += listOf("-i", it.path) }
            val delays = clips.indices.joinToString(";") { i ->
                val ms = (vi[i].start * 1000).toLong()
                "[${i + 1}:a]adelay=$ms|$ms[a$i]"
            }
            val mix = if (clips.size == 1) "[a0]anull[out]"
            else clips.indices.joinToString("") { "[a$it]" } +
                "amix=inputs=${clips.size}:normalize=0:dropout_transition=0[out]"
            args += listOf("-filter_complex", "$delays;$mix", "-map", "0:v:0", "-map", "[out]",
                "-c:v", "copy", "-c:a", "aac", "-shortest", outVideo.path)
            ff(*args.toTypedArray())
            outVideo
        }

    /** Cắt câu bằng Silero VAD rồi nhận dạng từng câu bằng SenseVoice. */
    private fun recognize(ctx: Context, input: File, work: File, log: (String) -> Unit): List<Seg> {
        log("Tách âm thanh…")
        val wav = File(work, "audio.wav")
        ff("-y", "-i", input.path, "-vn", "-ac", "1", "-ar", "$SR", "-c:a", "pcm_s16le", wav.path)

        val recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = Models.senseModel(ctx).path, language = "", useInverseTextNormalization = true),
                    tokens = Models.senseTokens(ctx).path, numThreads = 2, debug = false, provider = "cpu")))
        val vad = Vad(config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = Models.vad(ctx).path, threshold = 0.5f, minSilenceDuration = 0.4f,
                minSpeechDuration = 0.25f, windowSize = 512, maxSpeechDuration = 20f),
            sampleRate = SR, numThreads = 1, provider = "cpu", debug = false))

        val segs = mutableListOf<Seg>()
        fun drain() {
            while (!vad.empty()) {
                val s = vad.front()
                val stream = recognizer.createStream()
                stream.acceptWaveform(s.samples, SR)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                stream.release()
                if (text.isNotBlank()) segs += Seg(s.start / SR.toDouble(), (s.start + s.samples.size) / SR.toDouble(), text)
                vad.pop()
            }
        }
        try {
            RandomAccessFile(wav, "r").use { raf ->
                raf.seek(dataOffset(raf))
                val total = raf.length()
                val buf = ByteArray(SR * 2)
                var lastPct = -1
                while (true) {
                    val n = raf.read(buf)
                    if (n <= 0) break
                    val count = n / 2
                    if (n % 2 == 1) raf.seek(raf.filePointer - 1)
                    val sh = ByteBuffer.wrap(buf, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val f = FloatArray(count) { sh.get(it) / 32768f }
                    vad.acceptWaveform(f)
                    drain()
                    val pct = (raf.filePointer * 100 / total).toInt() / 10 * 10
                    if (pct != lastPct) { lastPct = pct; log("Nhận dạng giọng nói… $pct%") }
                }
                vad.flush()
                drain()
            }
        } finally {
            vad.release(); recognizer.release()
        }
        return segs
    }

    private fun dataOffset(raf: RandomAccessFile): Long {
        val head = ByteArray(4096)
        raf.seek(0)
        val n = raf.read(head)
        var pos = 12
        while (pos + 8 <= n) {
            val id = String(head, pos, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(head, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") return (pos + 8).toLong()
            pos += 8 + size + (size and 1)
        }
        return 44L
    }

    private fun guessTag(t: String): String? = when {
        t.any { it in '\u3040'..'\u30ff' } -> "ja"
        t.any { it in '\uac00'..'\ud7af' } -> "ko"
        t.any { it in '\u4e00'..'\u9fff' } -> "zh"
        else -> null
    }

    /** Dịch từng câu sang tiếng Việt bằng ML Kit (tự nhận diện ngôn ngữ từng câu). */
    private suspend fun translateAll(segs: List<Seg>, log: (String) -> Unit): List<Seg> {
        val idClient = LanguageIdentification.getClient()
        val translators = mutableMapOf<String, Translator>()
        val out = mutableListOf<Seg>()
        try {
            segs.forEachIndexed { i, s ->
                if (i % 10 == 0) log("Dịch sang tiếng Việt ${i + 1}/${segs.size}…")
                var tag = idClient.identifyLanguage(s.text).await()
                if (tag == "und") tag = guessTag(s.text) ?: "und"
                if (tag == "vi") { out += s; return@forEachIndexed }
                val lang = if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)
                if (lang == null) { log("Bỏ qua 1 câu (chưa nhận diện/hỗ trợ ngôn ngữ): ${s.text.take(30)}"); return@forEachIndexed }
                val tr = translators.getOrPut(lang) {
                    Translation.getClient(TranslatorOptions.Builder()
                        .setSourceLanguage(lang).setTargetLanguage(TranslateLanguage.VIETNAMESE).build())
                }
                tr.downloadModelIfNeeded().await()
                out += s.copy(text = tr.translate(s.text).await())
            }
        } finally {
            idClient.close(); translators.values.forEach { it.close() }
        }
        return out
    }

    /** Đọc từng câu tiếng Việt, tăng tốc nếu dài hơn khoảng thời gian cho phép. */
    private fun synthesize(ctx: Context, segs: List<Seg>, work: File, log: (String) -> Unit): List<File> {
        val tts = OfflineTts(config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = Models.ttsModel(ctx).path, tokens = Models.ttsTokens(ctx).path,
                    dataDir = Models.ttsData(ctx).path),
                numThreads = 2, debug = false, provider = "cpu")))
        try {
            return segs.mapIndexed { i, s ->
                if (i % 5 == 0) log("Tạo giọng đọc ${i + 1}/${segs.size}…")
                // Câu được phép đọc kéo dài đến khi câu kế tiếp bắt đầu.
                val window = maxOf(s.end - s.start, (segs.getOrNull(i + 1)?.start ?: s.end) - s.start)
                var a = tts.generate(s.text, 0, 1.0f)
                var dur = a.samples.size.toDouble() / a.sampleRate
                if (window > 0 && dur > window) {
                    a = tts.generate(s.text, 0, minOf(dur / window * 1.02, 1.5).toFloat())
                    dur = a.samples.size.toDouble() / a.sampleRate
                }
                val f = File(work, "t_$i.wav")
                writeWav(f, a.samples, a.sampleRate)
                if (window > 0 && dur > window) {
                    val o = File(work, "f_$i.wav")
                    ff("-y", "-i", f.path, "-filter:a", "atempo=${minOf(dur / window, 2.0)}", o.path)
                    o
                } else f
            }
        } finally {
            tts.release()
        }
    }

    private fun writeWav(f: File, s: FloatArray, sr: Int) {
        val bb = ByteBuffer.allocate(44 + s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + s.size * 2).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(s.size * 2)
        for (x in s) bb.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        f.writeBytes(bb.array())
    }
}
