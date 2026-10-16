package vn.dichvideo

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Tải (một lần) và quản lý các mô hình chạy offline. */
object Models {
    private const val ASR = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    private const val TTS = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
    const val SENSE_URL = ASR + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    const val VAD_URL = ASR + "silero_vad.onnx"
    const val DEFAULT_VOICE = "vais1000"

    fun modelVoiceId(voiceId: String): String =
        if (voiceId.startsWith("vivos_")) "vivos" else voiceId

    fun speakerId(voiceId: String): Int =
        if (voiceId.startsWith("vivos_")) voiceId.substringAfter("vivos_").toIntOrNull()?.coerceIn(0, 64) ?: 0 else 0

    private fun voiceArchive(voiceId: String): String = TTS + when (modelVoiceId(voiceId)) {
        "25hours_single" -> "vits-piper-vi_VN-25hours_single-low.tar.bz2"
        "vivos" -> "vits-piper-vi_VN-vivos-x_low.tar.bz2"
        else -> "vits-piper-vi_VN-vais1000-medium.tar.bz2"
    }
    private fun ttsDir(ctx: Context, voiceId: String) = File(root(ctx), "tts-${modelVoiceId(voiceId)}")

    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    private fun root(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun vad(ctx: Context) = File(root(ctx), "vad/silero_vad.onnx")
    fun senseModel(ctx: Context) = File(root(ctx), "sense").walkTopDown().first { it.name == "model.int8.onnx" }
    fun senseTokens(ctx: Context) = File(root(ctx), "sense").walkTopDown().first { it.name == "tokens.txt" }
    fun ttsModel(ctx: Context, voiceId: String = DEFAULT_VOICE) = ttsDir(ctx, voiceId).walkTopDown().first { it.isFile && it.extension == "onnx" }
    fun ttsTokens(ctx: Context, voiceId: String = DEFAULT_VOICE) = ttsDir(ctx, voiceId).walkTopDown().first { it.name == "tokens.txt" }
    fun ttsData(ctx: Context, voiceId: String = DEFAULT_VOICE) = ttsDir(ctx, voiceId).walkTopDown().first { it.isDirectory && it.name == "espeak-ng-data" }

    fun ensureVoice(ctx: Context, voiceId: String = DEFAULT_VOICE, log: (String) -> Unit) {
        fetchArchive(ttsDir(ctx, voiceId), voiceArchive(voiceId), "giọng đọc tiếng Việt ($voiceId)", log)
    }

    /** Chỉ tải mô hình cần để phân tích video; không tải TTS nếu người dùng chưa tạo giọng. */
    fun ensureAnalysis(ctx: Context, log: (String) -> Unit) {
        val base = root(ctx)
        // Speech recognition now processes bounded audio chunks without the native VAD path.
        // This reduces model loading and avoids a VAD-related native process exit on some phones.
        fetchArchive(File(base, "sense"), SENSE_URL, "mô hình nhận dạng (khoảng 230 MB)", log)
    }

    fun ensure(ctx: Context, voiceId: String = DEFAULT_VOICE, log: (String) -> Unit) {
        ensureAnalysis(ctx, log)
        ensureVoice(ctx, voiceId, log)
    }

    private fun fetchArchive(dir: File, url: String, label: String, log: (String) -> Unit) {
        val done = File(dir, ".done")
        if (done.exists() && archiveReady(dir)) return
        dir.deleteRecursively(); dir.mkdirs()
        val tmp = File(dir, "download.tar.bz2")
        download(url, tmp, label, log)
        log("Giải nén $label…")
        extract(tmp, dir)
        tmp.delete()
        require(archiveReady(dir)) { "Gói $label thiếu tệp mô hình cần thiết." }
        done.writeText("ok")
    }

    private fun archiveReady(dir: File): Boolean = when {
        dir.name == "sense" -> dir.walkTopDown().any { it.isFile && it.name == "model.int8.onnx" } &&
            dir.walkTopDown().any { it.isFile && it.name == "tokens.txt" }
        dir.name.startsWith("tts-") -> dir.walkTopDown().any { it.isFile && it.extension == "onnx" } &&
            dir.walkTopDown().any { it.isFile && it.name == "tokens.txt" } &&
            dir.walkTopDown().any { it.isDirectory && it.name == "espeak-ng-data" }
        else -> false
    }

    private fun download(url: String, out: File, label: String, log: (String) -> Unit) {
        log("Tải $label…")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("Không tải được $label (mã ${r.code}). Kiểm tra mạng rồi thử lại.")
            val body = r.body ?: error("Phản hồi rỗng khi tải $label")
            val total = body.contentLength()
            var got = 0L
            var lastLog = 0L
            body.byteStream().use { ins ->
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        got += n
                        if (got - lastLog > 25_000_000) {
                            lastLog = got
                            log("  ${got / 1_000_000}/${if (total > 0) "${total / 1_000_000}" else "?"} MB")
                        }
                    }
                }
            }
        }
    }

    private fun extract(tar: File, dest: File) {
        val destPath = dest.canonicalPath
        TarArchiveInputStream(BZip2CompressorInputStream(tar.inputStream().buffered())).use { t ->
            var e = t.nextTarEntry
            while (e != null) {
                val out = File(dest, e.name).canonicalFile
                require(out.path == destPath || out.path.startsWith(destPath + File.separator)) { "Tệp nén không hợp lệ" }
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { t.copyTo(it) }
                }
                e = t.nextTarEntry
            }
        }
    }
}
