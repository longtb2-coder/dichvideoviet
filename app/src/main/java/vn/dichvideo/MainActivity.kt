package vn.dichvideo

import android.net.Uri
import android.content.Intent
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme { Screen() } }
    }
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    var video by remember { mutableStateOf<Uri?>(null) }
    var srt by remember { mutableStateOf<Uri?>(null) }
    var videoUrl by remember { mutableStateOf("") }
    var ocrInterval by remember { mutableStateOf("1.5") }
    var log by remember { mutableStateOf("") }
    var outputVideoUri by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) {
            try { ctx.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            video = it
        }
    }
    val pickSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) {
            try { ctx.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            srt = it
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Dịch video sang giọng Việt", style = MaterialTheme.typography.titleLarge)
        Text("Chạy offline. Lần đầu app tải khoảng 300 MB mô hình (cần mạng một lần).")
        Button(enabled = !busy, onClick = { pickVideo.launch(arrayOf("video/*")) }) {
            Text(if (video == null) "① Chọn video trong điện thoại" else "Đã chọn video ✓")
        }
        OutlinedTextField(
            value = videoUrl,
            onValueChange = { videoUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Hoặc link trực tiếp tới video") },
            placeholder = { Text("https://.../video.mp4") },
            singleLine = true
        )
        Button(
            enabled = !busy && videoUrl.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    log = "Đang tải video từ link trực tiếp…\n"
                    try {
                        video = downloadDirectVideo(ctx, videoUrl.trim())
                        log += "Đã tải video xong.\n"
                    } catch (e: Exception) {
                        log += "Không tải được video: ${e.message}\n"
                    } finally { busy = false }
                }
            }
        ) { Text("Tải video từ link") }
        Button({ pickSrt.launch(arrayOf("*/*")) }) { Text(if (srt == null) "Chọn file phụ đề .srt (tuỳ chọn)" else "Đã chọn phụ đề ✓") }
        if (srt != null) TextButton({ srt = null }) { Text("Bỏ SRT, tự nhận dạng lời thoại") }
        OutlinedTextField(
            value = ocrInterval,
            onValueChange = { ocrInterval = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Khoảng quét chữ trong hình (giây, 0,5–10)") },
            singleLine = true
        )
        Button(
            enabled = !busy && video != null,
            onClick = {
                scope.launch {
                    busy = true; log = ""
                    try {
                        val interval = ocrInterval.replace(',', '.').toDoubleOrNull()
                        require(interval != null && interval in 0.5..10.0) { "Khoảng quét phải từ 0,5 đến 10 giây." }
                        val out = Pipeline.run(ctx, video!!, srt, interval!!) { message ->
                            scope.launch { log += message + "\n" }
                        }
                        outputVideoUri = out.videoUri
                        log += "Xong! Video đã lưu thành công.\nThư mục: ${out.location}\nVideo: ${out.videoName}\nPhụ đề riêng: ${out.subtitleName}\nBấm nút Mở video vừa tạo để xem ngay."
                    } catch (e: Exception) {
                        log += "Lỗi: ${e.message}"
                    }
                    busy = false
                }
            }
        ) { Text(if (busy) "Đang xử lý…" else "Bắt đầu dịch") }
        if (outputVideoUri != null) {
            Button(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(outputVideoUri), "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        log += "\\nKhông mở được video tự động: ${e.message ?: "hãy mở bằng ứng dụng Ảnh/Thư viện"}"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("▶ Mở video vừa tạo") }
        }
        Text(log)
    }
}

private suspend fun downloadDirectVideo(ctx: Context, rawUrl: String): Uri = withContext(Dispatchers.IO) {
    val url = URL(rawUrl)
    require(url.protocol == "http" || url.protocol == "https") { "Chỉ hỗ trợ link HTTP/HTTPS." }
    val dir = File(ctx.filesDir, "imports").apply { mkdirs() }
    val stamp = System.currentTimeMillis()
    val part = File(dir, "video_$stamp.part")
    val final = File(dir, "video_$stamp.mp4")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 60000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "VideoVietHoa/1.0 Android")
    }
    try {
        connection.connect()
        require(connection.responseCode in 200..299) { "Máy chủ trả mã ${connection.responseCode}." }
        val type = connection.contentType?.lowercase() ?: ""
        require(type.isBlank() || type.startsWith("video/") || type.contains("octet-stream") || type.contains("application/mp4")) {
            "Link không trả về tệp video (Content-Type: $type). Hãy dùng link trực tiếp MP4."
        }
        val expected = connection.contentLengthLong
        require(expected < 0 || expected <= 2L * 1024 * 1024 * 1024) { "Video lớn hơn giới hạn 2 GB." }
        connection.inputStream.use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= 2L * 1024 * 1024 * 1024) { "Video vượt giới hạn 2 GB." }
                    output.write(buffer, 0, n)
                }
            }
        }
        require(part.length() > 1024) { "Tệp tải về quá nhỏ hoặc không phải video." }
        if (!part.renameTo(final)) {
            part.copyTo(final, overwrite = true)
            part.delete()
        }
        Uri.fromFile(final)
    } catch (e: Exception) {
        part.delete()
        final.delete()
        throw e
    } finally {
        connection.disconnect()
    }
}
