package vn.dichvideo

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Seg(val start: Double, val end: Double, val text: String)

data class PipelineOutput(val videoName: String, val subtitleName: String, val location: String, val videoUri: String)

private data class DetectedTextRegion(val start: Double, val end: Double, val x: Int, val y: Int, val w: Int, val h: Int)
private data class TextBoxSample(val time: Double, val rect: android.graphics.Rect)

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

    suspend fun run(
        ctx: Context, video: Uri, srt: Uri?, ocrIntervalSeconds: Double = 1.5,
        voiceId: String = Models.DEFAULT_VOICE,
        originalVolume: Float = 0f,
        translatedVolume: Float = 1f,
        coverOriginalText: Boolean = false,
        coverHeightPercent: Float = 8f,
        coverTopPercent: Float = 31f,
        textRemovalMode: String = "blur",
        autoDetectTextRegions: Boolean = true,
        log: (String) -> Unit
    ): PipelineOutput =
        withContext(Dispatchers.IO) {
            require(ocrIntervalSeconds in 0.5..10.0) { "Khoảng quét chữ phải từ 0,5 đến 10 giây." }
            Models.ensure(ctx, voiceId, log)
            val work = File(ctx.cacheDir, "work").apply { deleteRecursively(); mkdirs() }
            val input = File(work, "in.mp4")
            val source = if (video.scheme == "file") {
                File(video.path ?: error("Đường dẫn video không hợp lệ.")).inputStream()
            } else {
                ctx.contentResolver.openInputStream(video) ?: error("Không mở được video đã chọn.")
            }
            source.use { i -> input.outputStream().use { i.copyTo(it) } }
            require(input.length() > 1024) { "Tệp video rỗng hoặc không hợp lệ." }

            val hasAudio = probeHasAudio(input)
            val speechSegs = if (srt != null) {
                log("Đọc phụ đề nguồn…")
                val text = ctx.contentResolver.openInputStream(srt)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Không mở được tệp SRT.")
                Srt.parse(text)
            } else if (hasAudio) {
                try { recognize(ctx, input, work, log) }
                catch (e: Exception) { log("Không nhận dạng được lời nói; sẽ tiếp tục quét chữ trong hình."); emptyList() }
            } else {
                log("Video không có âm thanh; chuyển sang dịch chữ trong hình.")
                emptyList()
            }
            log("Quét chữ/phụ đề trong hình…")
            val textSegs = try { recognizeText(input, ocrIntervalSeconds, log) }
                catch (e: Exception) { log("Không quét được chữ trong hình; tiếp tục với lời thoại nếu có."); emptyList() }
            val speechVi = if (speechSegs.isNotEmpty()) translateAll(mergeNearbySpeech(mergeNearDuplicates(speechSegs.sortedBy { it.start })), log) else emptyList()
            val textVi = if (textSegs.isNotEmpty()) translateAll(mergeNearDuplicates(textSegs.sortedBy { it.start }), log) else emptyList()
            if (speechVi.isEmpty() && textVi.isEmpty()) error("Không tìm thấy lời nói hoặc chữ có thể dịch. Thử video rõ hơn hoặc chọn file SRT.")
            if (speechVi.isNotEmpty()) log("Lời nói được dịch riêng để tạo giọng Việt.")
            if (textVi.isNotEmpty()) log("Chữ trên hình được dịch riêng thành phụ đề; không dùng OCR để tạo giọng đọc.")
            // Keep translated dialogue and translated on-screen text in subtitles.
            // The Vietnamese voice remains based on speech only, so OCR text is never read aloud.
            val vi = mergeNearDuplicates((speechVi + textVi).sortedBy { it.start })
            val voiceSegs = speechVi
            val clips = if (voiceSegs.isNotEmpty()) synthesize(ctx, voiceSegs, work, voiceId, log) else emptyList()
            log("Ghép giọng Việt và phụ đề…")
            val stamp = System.currentTimeMillis()
            val outVideo = File(work, "vi_" + stamp + ".mp4")
            val subtitleFile = File(work, "vi_" + stamp + ".srt").apply { writeText(Srt.write(vi), Charsets.UTF_8) }
            val metadata = MediaMetadataRetriever()
            val videoInfo = try {
                metadata.setDataSource(input.absolutePath)
                val duration = (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
                val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                Triple(duration, width, height)
            } finally {
                metadata.release()
            }
            val videoDurationSeconds = videoInfo.first
            val videoWidth = videoInfo.second
            val videoHeight = videoInfo.third
            require(videoDurationSeconds > 0.0 && videoWidth > 0 && videoHeight > 0) { "Không đọc được thời lượng/kích thước video đầu vào." }
            val detectedRegions = if (coverOriginalText && autoDetectTextRegions) {
                try {
                    log("Đang tự dò vị trí chữ Trung trên nhiều khung hình…")
                    detectChineseTextRegions(input, videoDurationSeconds, videoWidth, videoHeight, minOf(1.0, ocrIntervalSeconds).coerceAtLeast(0.5), log)
                        .also { log(if (it.isEmpty()) "Chưa tìm thấy vùng chữ Trung ổn định; sẽ dùng vùng căn tay." else "Đã tìm được ${it.size} vùng chữ theo thời gian.") }
                } catch (e: Exception) {
                    log("Tự dò vị trí chữ gặp lỗi; chuyển về vùng căn tay: ${e.message ?: "không rõ lỗi"}")
                    emptyList()
                }
            } else emptyList()

            val args = mutableListOf("-y", "-i", input.path)
            clips.forEach { args += listOf("-i", it.path) }
            args += listOf("-i", subtitleFile.path)
            val subtitleInputIndex = clips.size + 1
            val filters = mutableListOf<String>()
            if (clips.isNotEmpty()) {
                val delays = clips.indices.joinToString(";") { i ->
                    val ms = (voiceSegs[i].start * 1000).toLong()
                    "[" + (i + 1) + ":a]adelay=" + ms + "|" + ms + "[a" + i + "]"
                }
                val voiceMix = if (clips.size == 1) "[a0]volume=" + translatedVolume + ",apad[voice]"
                else clips.indices.joinToString("") { "[a" + it + "]" } + "amix=inputs=" + clips.size + ":duration=longest:normalize=0:dropout_transition=0,volume=" + translatedVolume + ",apad[voice]"
                filters += delays
                filters += voiceMix
                if (hasAudio) filters += "[0:a]volume=" + originalVolume + "[original];[original][voice]amix=inputs=2:duration=longest:normalize=0,apad[out]"
                else filters += "[voice]anull[out]"
            }
            val escapedSubtitlePath = subtitleFile.absolutePath.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")
            val subtitleTextFilter = "subtitles='" + escapedSubtitlePath + "':fontsdir='/system/fonts':force_style='FontName=Noto Sans,FontSize=24,Outline=2,Shadow=1,Alignment=2,MarginV=30'"
            val regionH = coverHeightPercent.coerceIn(6f, 24f) / 100f
            val regionTop = coverTopPercent.coerceIn(20f, 82f) / 100f
            val regionX = (videoWidth * 0.08f).toInt().coerceAtLeast(0)
            val regionY = (videoHeight * regionTop).toInt().coerceAtLeast(0)
            val regionW = (videoWidth * 0.84f).toInt().coerceAtMost(videoWidth - regionX).coerceAtLeast(2)
            val regionPixelH = (videoHeight * regionH).toInt().coerceAtLeast(2).coerceAtMost(videoHeight - regionY)
            val subtitleFilterForGraph = subtitleTextFilter
            var videoGraphUsed = false
            if (coverOriginalText && detectedRegions.isNotEmpty()) {
                var currentLabel = "0:v"
                detectedRegions.forEachIndexed { i, r ->
                    val start = r.start.coerceAtLeast(0.0)
                    val end = r.end.coerceAtMost(videoDurationSeconds)
                    if (end > start && r.w >= 2 && r.h >= 2) {
                        val enable = "between(t\\,$start\\,$end)"
                        when (textRemovalMode) {
                            "blur" -> {
                                filters += "[$currentLabel]split=2[base$i][crop$i]"
                                filters += "[crop$i]crop=${r.w}:${r.h}:${r.x}:${r.y},boxblur=12:2[blur$i]"
                                filters += "[base$i][blur$i]overlay=${r.x}:${r.y}:enable='$enable'[v$i]"
                                currentLabel = "v$i"
                            }
                            "remove" -> {
                                filters += "[$currentLabel]delogo=x=${r.x}:y=${r.y}:w=${r.w}:h=${r.h}:show=0:enable='$enable'[v$i]"
                                currentLabel = "v$i"
                            }
                            else -> {
                                filters += "[$currentLabel]drawbox=x=${r.x}:y=${r.y}:w=${r.w}:h=${r.h}:color=black@1:t=fill:enable='$enable'[v$i]"
                                currentLabel = "v$i"
                            }
                        }
                    }
                }
                if (currentLabel != "0:v") {
                    filters += "[$currentLabel]$subtitleFilterForGraph[vout]"
                    args += listOf("-map", "[vout]")
                    videoGraphUsed = true
                }
            }
            if (!videoGraphUsed && coverOriginalText && textRemovalMode == "blur") {
                filters += "[0:v]split=2[base][tmp]"
                filters += "[tmp]crop=$regionW:$regionPixelH:$regionX:$regionY,boxblur=12:2[blurred]"
                filters += "[base][blurred]overlay=$regionX:$regionY,$subtitleFilterForGraph[vout]"
                args += listOf("-map", "[vout]")
                videoGraphUsed = true
            }
            if (!videoGraphUsed) {
                val coverFilter = if (!coverOriginalText) subtitleTextFilter else when (textRemovalMode) {
                    "remove" -> "delogo=x=$regionX:y=$regionY:w=$regionW:h=$regionPixelH:show=0,$subtitleTextFilter"
                    "cover" -> "drawbox=x=$regionX:y=$regionY:w=$regionW:h=$regionPixelH:color=black@1:t=fill,$subtitleTextFilter"
                    else -> subtitleTextFilter
                }
                args += listOf("-map", "0:v:0", "-vf", coverFilter)
            }
            if (filters.isNotEmpty()) {
                args += listOf("-filter_complex", filters.joinToString(";"))
                if (clips.isNotEmpty()) args += listOf("-map", "[out]")
            }
            if (clips.isEmpty() && hasAudio) args += listOf("-map", "0:a:0", "-af", "volume=" + originalVolume)
            args += listOf("-map", subtitleInputIndex.toString() + ":s:0", "-c:v", "mpeg4", "-q:v", "3", "-pix_fmt", "yuv420p", "-c:s", "mov_text", "-metadata:s:s:0", "language=vie", "-movflags", "+faststart", "-t", "%.3f".format(java.util.Locale.US, videoDurationSeconds), outVideo.path)
            ff(*args.toTypedArray())
            require(outVideo.exists() && outVideo.length() > 1024) { "FFmpeg chưa tạo được MP4 đầu ra hợp lệ." }

            val videoName = outVideo.name
            val subtitleName = subtitleFile.name
            val location: String
            val savedVideoUri: Uri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                log("Đang lưu video vào thư mục Movies để có thể tìm trong Thư viện…")
                savedVideoUri = publishToMediaStore(
                    ctx, outVideo, videoName, "video/mp4",
                    "${Environment.DIRECTORY_MOVIES}/VideoVietHoa",
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                )
                try {
                    publishToMediaStore(
                        ctx, subtitleFile, subtitleName, "application/x-subrip",
                        "${Environment.DIRECTORY_DOWNLOADS}/VideoVietHoa",
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    )
                } catch (e: Exception) {
                    log("Video đã lưu; chưa lưu được file SRT riêng: ${e.message ?: "lỗi không rõ"}.")
                }
                location = "Bộ nhớ trong/Movies/VideoVietHoa"
                log("Đã lưu video vào Thư viện; có thể bấm nút Mở video vừa tạo ngay bên dưới.")
            } else {
                val outDir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(ctx.filesDir, "Movies"), "VideoVietHoa")
                if (!outDir.exists() && !outDir.mkdirs()) error("Không tạo được thư mục đầu ra: $outDir")
                val publicVideoFile = File(outDir, videoName)
                outVideo.copyTo(publicVideoFile, overwrite = true)
                subtitleFile.copyTo(File(outDir, subtitleName), overwrite = true)
                savedVideoUri = Uri.fromFile(publicVideoFile)
                location = outDir.absolutePath
                log("Android phiên bản cũ: video nằm trong thư mục riêng của ứng dụng.")
            }
            log("Đã tạo MP4 có lồng tiếng Việt và phụ đề đốt trực tiếp vào hình.")
            PipelineOutput(videoName, subtitleName, location, savedVideoUri.toString())
        }

    /** Lưu media vào thư mục công khai để người dùng nhìn thấy trong Thư viện/Quản lý tệp. */
    private fun publishToMediaStore(
        ctx: Context,
        source: File,
        displayName: String,
        mimeType: String,
        relativePath: String,
        collection: Uri
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: error("Android không tạo được tệp đầu ra trong $relativePath.")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Không mở được tệp đầu ra để ghi.")
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }


    private fun probeHasAudio(input: File): Boolean {
        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-i", input.absolutePath))
            Regex("(?i)Audio:").containsMatchIn(session.output ?: "")
        } catch (_: Exception) { false }
    }
    private fun mergeNearbySpeech(input: List<Seg>): List<Seg> {
        if (input.size < 2) return input
        val out = mutableListOf<Seg>()
        for (seg in input) {
            val prev = out.lastOrNull()
            if (prev != null && seg.start >= prev.start && seg.start - prev.end <= 0.45 && seg.start - prev.start <= 7.0) {
                out[out.lastIndex] = prev.copy(end = maxOf(prev.end, seg.end), text = (prev.text.trimEnd() + " " + seg.text.trimStart()).trim())
            } else out += seg
        }
        return out
    }

    private fun mergeNearDuplicates(input: List<Seg>): List<Seg> {
        val out = mutableListOf<Seg>()
        for (seg in input) {
            val norm = seg.text.lowercase().replace(Regex("\\s+"), " ").trim()
            val prev = out.lastOrNull()
            val prevNorm = prev?.text?.lowercase()?.replace(Regex("\\s+"), " ")?.trim()
            if (prev != null && norm == prevNorm && seg.start <= prev.end + 0.8 && seg.start >= prev.start) {
                out[out.lastIndex] = prev.copy(end = maxOf(prev.end, seg.end))
            } else out += seg
        }
        return out
    }

    /** OCR cục bộ theo từng mốc thời gian: Latin, Trung, Nhật và Hàn. */
    /**
     * Scan sampled frames for Chinese glyphs and build spatial tracks.
     * Returned coordinates use the original video dimensions.
     */
    private suspend fun detectChineseTextRegions(
        input: File, durationSec: Double, frameWidth: Int, frameHeight: Int,
        intervalSec: Double, log: (String) -> Unit
    ): List<DetectedTextRegion> {
        val retriever = MediaMetadataRetriever()
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val tracks = mutableListOf<MutableList<TextBoxSample>>()
        try {
            retriever.setDataSource(input.absolutePath)
            var t = 0.0
            var scanned = 0
            while (t < durationSec) {
                val frame = retriever.getFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    try {
                        val maxSide = maxOf(frame.width, frame.height)
                        val scale = if (maxSide > 960) 960.0 / maxSide else 1.0
                        val working = if (scale < 1.0) Bitmap.createScaledBitmap(
                            frame, (frame.width * scale).toInt().coerceAtLeast(1),
                            (frame.height * scale).toInt().coerceAtLeast(1), true
                        ) else frame
                        try {
                            val result = recognizer.process(InputImage.fromBitmap(working, 0)).await()
                            val sx = frameWidth.toFloat() / working.width
                            val sy = frameHeight.toFloat() / working.height
                            val boxes = result.textBlocks.filter { block ->
                                block.text.any { it.code in 0x4E00..0x9FFF }
                            }.flatMap { it.lines }.mapNotNull { line ->
                                val b = line.boundingBox ?: return@mapNotNull null
                                val left = (b.left * sx).toInt()
                                val top = (b.top * sy).toInt()
                                val right = (b.right * sx).toInt()
                                val bottom = (b.bottom * sy).toInt()
                                if (right - left < frameWidth * 0.015 || bottom - top < frameHeight * 0.008) null
                                else android.graphics.Rect(left, top, right, bottom)
                            }.filter { it.left >= 0 && it.top >= 0 && it.right <= frameWidth && it.bottom <= frameHeight }
                            boxes.forEach { box ->
                                val cx = box.centerX().toFloat()
                                val cy = box.centerY().toFloat()
                                val track = tracks.filter { tr ->
                                    val last = tr.last()
                                    t - last.time <= intervalSec * 2.2 &&
                                        kotlin.math.abs(last.rect.centerX() - cx) < frameWidth * 0.24f &&
                                        kotlin.math.abs(last.rect.centerY() - cy) < frameHeight * 0.075f
                                }.minByOrNull { tr ->
                                    val last = tr.last().rect
                                    kotlin.math.abs(last.centerX() - cx) / frameWidth +
                                        kotlin.math.abs(last.centerY() - cy) / frameHeight
                                }
                                if (track != null) track += TextBoxSample(t, android.graphics.Rect(box))
                                else if (tracks.size < 24) tracks += mutableListOf(TextBoxSample(t, android.graphics.Rect(box)))
                            }
                        } finally {
                            if (working !== frame) working.recycle()
                        }
                    } finally { frame.recycle() }
                }
                t += intervalSec
                scanned++
                if (scanned % 5 == 0) log("Tìm vùng chữ… ${(t / durationSec * 100).toInt().coerceAtMost(100)}%")
            }
        } finally {
            retriever.release()
            recognizer.close()
        }
        return tracks.mapNotNull { track ->
            if (track.isEmpty()) return@mapNotNull null
            val all = track.map { it.rect }
            val padX = (all.maxOf { it.width() } * 0.12f).toInt().coerceAtLeast(4)
            val padY = (all.maxOf { it.height() } * 0.22f).toInt().coerceAtLeast(3)
            val left = (all.minOf { it.left } - padX).coerceAtLeast(0)
            val top = (all.minOf { it.top } - padY).coerceAtLeast(0)
            val right = (all.maxOf { it.right } + padX).coerceAtMost(frameWidth - 1)
            val bottom = (all.maxOf { it.bottom } + padY).coerceAtMost(frameHeight - 1)
            val start = track.minOf { it.time }
            val end = (track.maxOf { it.time } + intervalSec).coerceAtMost(durationSec)
            if (right <= left || bottom <= top || end <= start) null
            else DetectedTextRegion(start, end, left, top, right - left, bottom - top)
        }.sortedBy { it.start }.take(24)
    }

    private suspend fun recognizeText(input: File, intervalSec: Double, log: (String) -> Unit): List<Seg> {
        val retriever = MediaMetadataRetriever()
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        val korean = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val result = mutableListOf<Seg>()
        try {
            retriever.setDataSource(input.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("Không đọc được thời lượng video để quét chữ.")
            val stepMs = (intervalSec * 1000).toLong().coerceAtLeast(500L)
            var timeMs = 0L
            var lastPct = -1
            while (timeMs < durationMs) {
                val frame = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    var working: Bitmap = frame
                    try {
                        val longest = maxOf(frame.width, frame.height)
                        if (longest > 1280) {
                            val ratio = 1280.0 / longest
                            working = Bitmap.createScaledBitmap(frame,
                                (frame.width * ratio).toInt().coerceAtLeast(1),
                                (frame.height * ratio).toInt().coerceAtLeast(1), true)
                        }
                        val image = InputImage.fromBitmap(working, 0)
                        val candidates = listOf(
                            latin.process(image).await().text,
                            chinese.process(image).await().text,
                            japanese.process(image).await().text,
                            korean.process(image).await().text
                        ).map { it.trim() }.filter { it.isNotBlank() }
                            .distinctBy { it.lowercase().replace(Regex("\\s+"), "") }
                        val text = candidates.joinToString(" ").trim()
                        if (text.isNotBlank()) {
                            val start = timeMs / 1000.0
                            val end = minOf(durationMs / 1000.0, (timeMs + stepMs) / 1000.0).coerceAtLeast(start + 0.1)
                            val prev = result.lastOrNull()
                            val norm = text.lowercase().replace(Regex("\\s+"), " ").trim()
                            val prevNorm = prev?.text?.lowercase()?.replace(Regex("\\s+"), " ")?.trim()
                            if (prev != null && norm == prevNorm && start <= prev.end + intervalSec * 1.5) {
                                result[result.lastIndex] = prev.copy(end = maxOf(prev.end, end))
                            } else result += Seg(start, end, text)
                        }
                    } finally {
                        if (working !== frame) working.recycle()
                        frame.recycle()
                    }
                }
                timeMs += stepMs
                val pct = (timeMs * 100 / durationMs).toInt() / 10 * 10
                if (pct != lastPct) {
                    lastPct = pct
                    log("Quét chữ trong hình… ${pct.coerceAtMost(100)}%")
                }
            }
        } finally {
            retriever.release()
            latin.close()
            chinese.close()
            japanese.close()
            korean.close()
        }
        return result
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

    /** Dịch từng câu sang tiếng Việt trên thiết bị, ưu tiên nhận diện theo chữ viết trước. */
    private suspend fun translateAll(segs: List<Seg>, log: (String) -> Unit): List<Seg> {
        if (segs.isEmpty()) return emptyList()
        val idClient = LanguageIdentification.getClient()
        val translators = mutableMapOf<String, Translator>()
        val downloadedLanguages = mutableSetOf<String>()
        val out = mutableListOf<Seg>()
        try {
            segs.forEachIndexed { i, s ->
                if (i % 5 == 0) log("Dịch sang tiếng Việt ${i + 1}/${segs.size}…")
                val scriptTag = guessTag(s.text)
                var tag = scriptTag ?: try {
                    idClient.identifyLanguage(s.text).await()
                } catch (e: Exception) {
                    log("Không nhận diện được ngôn ngữ câu ${i + 1}; sẽ thử giữ nguyên câu này.")
                    "und"
                }
                if (tag == "yue") tag = "zh"
                if (tag == "vi") {
                    out += s
                    return@forEachIndexed
                }
                val lang = if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)
                if (lang == null) {
                    log("Không xác định hoặc chưa hỗ trợ ngôn ngữ ở câu ${i + 1}; giữ nguyên nội dung nguồn: ${s.text.take(45)}")
                    out += s
                    return@forEachIndexed
                }
                val translator = translators.getOrPut(lang) {
                    Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(lang)
                            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                            .build()
                    )
                }
                if (downloadedLanguages.add(lang)) {
                    try {
                        log("Chuẩn bị mô hình dịch ${lang} → tiếng Việt (chỉ cần tải lần đầu)…")
                        translator.downloadModelIfNeeded().await()
                    } catch (e: Exception) {
                        downloadedLanguages.remove(lang)
                        throw IllegalStateException(
                            "Không tải được mô hình dịch ${lang}. Hãy kết nối mạng, thử lại rồi mới dùng ngoại tuyến.", e
                        )
                    }
                }
                try {
                    val translated = translator.translate(s.text).await().trim()
                    if (translated.isNotBlank()) out += s.copy(text = translated)
                    else {
                        log("Mô hình trả về câu dịch rỗng ở câu ${i + 1}; giữ nguyên câu nguồn.")
                        out += s
                    }
                } catch (e: Exception) {
                    log("Dịch câu ${i + 1} thất bại (${e.message ?: "lỗi xử lý văn bản"}); giữ nguyên câu nguồn.")
                    out += s
                }
            }
        } finally {
            idClient.close()
            translators.values.forEach { it.close() }
        }
        if (out.isEmpty()) error("Không dịch được câu nào. Kiểm tra ngôn ngữ nguồn hoặc kết nối mạng để tải mô hình dịch lần đầu.")
        return out
    }

    /** Đọc từng câu tiếng Việt, tăng tốc nếu dài hơn khoảng thời gian cho phép. */
    private fun synthesize(ctx: Context, segs: List<Seg>, work: File, voiceId: String, log: (String) -> Unit): List<File> {
        val tts = OfflineTts(config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = Models.ttsModel(ctx, voiceId).path, tokens = Models.ttsTokens(ctx, voiceId).path,
                    dataDir = Models.ttsData(ctx, voiceId).path),
                numThreads = 2, debug = false, provider = "cpu")))
        try {
            return segs.mapIndexed { i, s ->
                if (i % 5 == 0) log("Tạo giọng đọc ${i + 1}/${segs.size}…")
                // Câu được phép đọc kéo dài đến khi câu kế tiếp bắt đầu.
                val window = maxOf(s.end - s.start, (segs.getOrNull(i + 1)?.start ?: s.end) - s.start)
                val a = tts.generate(s.text, 0, 1.0f)
                val dur = a.samples.size.toDouble() / a.sampleRate
                val f = File(work, "t_$i.wav")
                writeWav(f, a.samples, a.sampleRate)
                if (window > 0 && dur > window * 1.02) {
                    // Chỉ tăng tốc một lần, tránh vừa tăng tốc TTS vừa tăng tốc FFmpeg gây giọng líu và quá nhanh.
                    val o = File(work, "f_$i.wav")
                    val tempo = minOf(dur / window, 1.35)
                    ff("-y", "-i", f.path, "-filter:a", "atempo=$tempo", o.path)
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
