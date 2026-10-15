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
import androidx.compose.ui.graphics.Color
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
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF087F8C), secondary = Color(0xFF6B5CE7), background = Color(0xFFF4F7FB), surface = Color(0xFFFFFFFF))) { Screen() } }
    }
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    var video by remember { mutableStateOf<Uri?>(null) }
    var srt by remember { mutableStateOf<Uri?>(null) }
    var videoUrl by remember { mutableStateOf("") }
    var ocrInterval by remember { mutableStateOf("1.5") }
    var voiceId by remember { mutableStateOf("vais1000") }
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    var originalVolume by remember { mutableStateOf(0f) }
    var translatedVolume by remember { mutableStateOf(100f) }
    var coverOriginalText by remember { mutableStateOf(true) }
    var coverHeightPercent by remember { mutableStateOf(8f) }
    var coverTopPercent by remember { mutableStateOf(31f) }
    val voiceLabel = when (voiceId) {
        "25hours_single" -> "Giọng 2 — 25hours (trầm hơn)"
        "vivos" -> "Giọng 3 — VIVOS (nhẹ hơn)"
        else -> "Giọng 1 — VAIS1000 (tự nhiên)"
    }
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

    val exportVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { destination ->
        val sourceText = outputVideoUri
        if (destination != null && sourceText != null) {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        val sourceUri = Uri.parse(sourceText)
                        val input = ctx.contentResolver.openInputStream(sourceUri)
                            ?: error("Không mở được video nguồn để xuất.")
                        val output = ctx.contentResolver.openOutputStream(destination, "w")
                            ?: error("Không mở được vị trí bạn chọn để ghi video.")
                        input.use { src -> output.use { dst -> src.copyTo(dst) } }
                    }
                    log += "\\nXuất video thành công tới vị trí bạn vừa chọn."
                } catch (e: Exception) {
                    log += "\\nXuất video thất bại: ${e.message ?: "lỗi không rõ"}"
                } finally { busy = false }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("VIDEO VIỆT HÓA", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("Dịch lời thoại • Lồng tiếng Việt • Phụ đề", style = MaterialTheme.typography.titleMedium)
                Text("Xử lý trên điện thoại sau khi tải mô hình. Lần đầu cần kết nối mạng.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🎙 Chọn giọng đọc tiếng Việt", style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { voiceMenuExpanded = true }, enabled = !busy) {
                Text(voiceLabel)
            }
            DropdownMenu(expanded = voiceMenuExpanded, onDismissRequest = { voiceMenuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Giọng 1 — VAIS1000 (tự nhiên, mặc định)") },
                    onClick = { voiceId = "vais1000"; voiceMenuExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text("Giọng 2 — 25hours (trầm hơn)") },
                    onClick = { voiceId = "25hours_single"; voiceMenuExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text("Giọng 3 — VIVOS (nhẹ hơn)") },
                    onClick = { voiceId = "vivos"; voiceMenuExpanded = false }
                )
            }
        }
        Text("Các giọng này chạy trên máy sau khi tải mô hình; đây không phải bản sao giọng TikTok độc quyền.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🎬 Video đầu vào", style = MaterialTheme.typography.titleMedium)
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
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔊 Cân chỉnh âm thanh", style = MaterialTheme.typography.titleMedium)
                Text("Âm thanh gốc: ${originalVolume.toInt()}%")
                Slider(value = originalVolume, onValueChange = { originalVolume = it }, valueRange = 0f..100f, steps = 19)
                Text("Giọng dịch tiếng Việt: ${translatedVolume.toInt()}%")
                Slider(value = translatedVolume, onValueChange = { translatedVolume = it }, valueRange = 0f..100f, steps = 19)
                Text("Gợi ý: để tiếng gốc 0% nếu lời Trung và lời Việt bị chồng nhau.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🧽 Che chữ phụ đề gốc", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = coverOriginalText, onCheckedChange = { coverOriginalText = it })
                    Text("Che vùng phía dưới video trước khi thêm phụ đề Việt")
                }
                if (coverOriginalText) {
                    Text("Vị trí chữ gốc (tính từ trên xuống): ${coverTopPercent.toInt()}%")
                    Slider(value = coverTopPercent, onValueChange = { coverTopPercent = it }, valueRange = 20f..65f, steps = 44)
                    Text("Chiều cao vùng xoá: ${coverHeightPercent.toInt()}%")
                    Slider(value = coverHeightPercent, onValueChange = { coverHeightPercent = it }, valueRange = 6f..18f, steps = 11)
                    Text("Mặc định đặt vùng che gần vị trí phụ đề trong clip mẫu. Vùng che là dải đen gọn; chỉnh vị trí lên/xuống và chiều cao cho khớp chữ gốc.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚙️ Nhận dạng chữ trong hình", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = ocrInterval,
            onValueChange = { ocrInterval = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Khoảng quét chữ trong hình (giây, 0,5–10)") },
            singleLine = true
        )
            }
        }
        Button(
            enabled = !busy && video != null,
            onClick = {
                scope.launch {
                    busy = true; log = ""
                    try {
                        val interval = ocrInterval.replace(',', '.').toDoubleOrNull()
                        require(interval != null && interval in 0.5..10.0) { "Khoảng quét phải từ 0,5 đến 10 giây." }
                        val out = Pipeline.run(
                            ctx, video!!, srt, interval!!, voiceId,
                            originalVolume = originalVolume / 100f,
                            translatedVolume = translatedVolume / 100f,
                            coverOriginalText = coverOriginalText,
                            coverHeightPercent = coverHeightPercent,
                            coverTopPercent = coverTopPercent
                        ) { message ->
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
            Button(
                enabled = !busy,
                onClick = {
                    val name = outputVideoUri?.let { Uri.parse(it).lastPathSegment }
                        ?.takeIf { it.endsWith(".mp4", ignoreCase = true) }
                        ?: "VideoVietHoa.mp4"
                    exportVideo.launch(name)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("⬇ Xuất video — chọn nơi lưu") }
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
