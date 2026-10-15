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
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
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
    var voiceStyleId by remember { mutableStateOf("review") }
    var voiceStyleMenuExpanded by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf("Món này dùng khá tiện, nhỏ gọn mà làm việc rất nhanh. Nếu bạn đang cần một lựa chọn đơn giản thì có thể tham khảo nhé.") }
    var previewing by remember { mutableStateOf(false) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var originalVolume by remember { mutableStateOf(0f) }
    var translatedVolume by remember { mutableStateOf(100f) }
    var coverOriginalText by remember { mutableStateOf(true) }
    var textRemovalMode by remember { mutableStateOf("blur") }
    var autoDetectTextRegions by remember { mutableStateOf(true) }
    var coverHeightPercent by remember { mutableStateOf(8f) }
    var coverTopPercent by remember { mutableStateOf(31f) }
    var previewFrame by remember { mutableStateOf<Bitmap?>(null) }
    var detectingRegion by remember { mutableStateOf(false) }
    val voiceLabel = when (voiceId) {
        "25hours_single" -> "Nam trầm — 25hours"
        "vivos" -> "VIVOS — giọng 1"
        in listOf("vivos_1", "vivos_2", "vivos_3", "vivos_4") -> "VIVOS — giọng ${voiceId.substringAfter('_').toInt() + 1}"
        else -> "Nữ tự nhiên — VAIS1000"
    }
    var log by remember { mutableStateOf("") }
    var outputVideoUri by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reviewProductName by remember { mutableStateOf("") }
    var reviewSourceFacts by remember { mutableStateOf("") }
    var reviewStyle by remember { mutableStateOf(ReviewStyle.FRIENDLY) }
    var reviewDuration by remember { mutableStateOf(30) }
    var reviewOnline by remember { mutableStateOf(false) }
    var reviewEndpoint by remember { mutableStateOf("https://api.openai.com/v1/chat/completions") }
    var reviewModel by remember { mutableStateOf("gpt-4o-mini") }
    var reviewApiKey by remember { mutableStateOf("") }
    var reviewDrafts by remember { mutableStateOf<List<ReviewDraft>>(emptyList()) }
    var reviewSelectedIndex by remember { mutableIntStateOf(0) }
    var reviewScript by remember { mutableStateOf("") }
    var reviewBusy by remember { mutableStateOf(false) }
    var useReviewNarration by remember { mutableStateOf(false) }
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

    LaunchedEffect(video) {
        previewFrame = null
        val selected = video
        if (selected != null) {
            previewFrame = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(ctx, selected)
                    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    retriever.getFrameAtTime((durationMs.coerceAtLeast(1000L) / 3L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (_: Exception) { null } finally { retriever.release() }
            }
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
                Text("🛍 AI viết lại & review sản phẩm", style = MaterialTheme.typography.titleMedium)
                Text("Tạo lời giới thiệu mới từ thông tin nguồn. Giai đoạn hiện tại nhận nội dung bạn dán vào; bước sau sẽ nối trực tiếp kết quả nhận dạng giọng nói và OCR từ video.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = reviewProductName,
                    onValueChange = { reviewProductName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tên sản phẩm (nếu biết)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = reviewSourceFacts,
                    onValueChange = { reviewSourceFacts = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nội dung lời thoại, chữ trên video hoặc thông tin sản phẩm") },
                    placeholder = { Text("Dán nội dung đã nhận dạng hoặc mô tả sản phẩm tại đây…") },
                    minLines = 4,
                    maxLines = 8
                )
                OutlinedButton(
                    enabled = video != null && !busy && !reviewBusy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val selectedVideo = video ?: return@OutlinedButton
                        scope.launch {
                            reviewBusy = true
                            log = "Đang lấy nội dung từ video…"
                            try {
                                val interval = ocrInterval.replace(',', '.').toDoubleOrNull() ?: 1.5
                                reviewSourceFacts = Pipeline.extractReviewSource(ctx, selectedVideo, srt, interval) { message ->
                                    scope.launch { log = message }
                                }
                                log = "Đã lấy nội dung lời thoại/chữ trên hình. Hãy kiểm tra lại dữ kiện rồi tạo kịch bản."
                            } catch (e: Exception) {
                                log = "Không lấy được nội dung video: ${e.message ?: "lỗi không rõ"}"
                            } finally { reviewBusy = false }
                        }
                    }
                ) { Text(if (reviewBusy) "Đang phân tích video…" else "🎞 Lấy lời thoại và chữ từ video đã chọn") }
                Text("Phong cách viết", style = MaterialTheme.typography.titleSmall)
                ReviewStyle.values().forEach { option ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = reviewStyle == option, onClick = { reviewStyle = option })
                        Text(option.title, modifier = Modifier.weight(1f))
                    }
                }
                Text("Thời lượng mục tiêu")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { seconds ->
                        FilterChip(
                            selected = reviewDuration == seconds,
                            onClick = { reviewDuration = seconds },
                            label = { Text("$seconds giây") }
                        )
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = reviewOnline, onCheckedChange = { reviewOnline = it })
                    Column(Modifier.weight(1f)) {
                        Text("Dùng AI online")
                        Text("Bỏ chọn để viết lại bằng mẫu offline, không cần khóa API.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (reviewOnline) {
                    OutlinedTextField(
                        value = reviewEndpoint,
                        onValueChange = { reviewEndpoint = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Địa chỉ API tương thích chat-completions (HTTPS)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = reviewModel,
                        onValueChange = { reviewModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tên model") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = reviewApiKey,
                        onValueChange = { reviewApiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API key (chỉ giữ tạm trong màn hình này)") },
                        singleLine = true
                    )
                    Text("Khóa API được gửi đến nhà cung cấp bạn nhập. Chỉ dùng nhà cung cấp đáng tin cậy; không chia sẻ ảnh chụp màn hình có khóa.", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    enabled = !reviewBusy && !busy && reviewSourceFacts.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            reviewBusy = true
                            try {
                                val request = ReviewRequest(
                                    productName = reviewProductName,
                                    sourceFacts = reviewSourceFacts,
                                    style = reviewStyle,
                                    durationSeconds = reviewDuration
                                )
                                reviewDrafts = if (reviewOnline) {
                                    ProductReviewEngine.generateOnline(request, reviewEndpoint.trim(), reviewModel.trim(), reviewApiKey.trim())
                                } else {
                                    withContext(Dispatchers.Default) { ProductReviewEngine.generateOffline(request) }
                                }
                                reviewSelectedIndex = 0
                                reviewScript = reviewDrafts.firstOrNull()?.script.orEmpty()
                                log = if (reviewOnline) "Đã nhận kịch bản từ AI online." else "Đã tạo bản nháp offline từ dữ liệu bạn cung cấp."
                            } catch (e: Exception) {
                                log = "Không tạo được kịch bản: ${e.message ?: "lỗi không rõ"}"
                            } finally { reviewBusy = false }
                        }
                    }
                ) { Text(if (reviewBusy) "Đang viết kịch bản…" else if (reviewOnline) "✨ Tạo 3 kịch bản bằng AI online" else "✍️ Tạo 3 bản offline") }
                if (reviewDrafts.isNotEmpty()) {
                    Text("Chọn bản nháp", style = MaterialTheme.typography.titleSmall)
                    reviewDrafts.forEachIndexed { index, draft ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = reviewSelectedIndex == index, onClick = {
                                reviewSelectedIndex = index
                                reviewScript = draft.script
                            })
                            Column(Modifier.weight(1f)) {
                                Text(draft.title)
                                Text(if (draft.online) "Tạo online" else "Tạo offline", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = reviewScript,
                        onValueChange = { reviewScript = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Sửa lời review trước khi dùng") },
                        minLines = 4,
                        maxLines = 10
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = useReviewNarration, onCheckedChange = { useReviewNarration = it })
                        Column(Modifier.weight(1f)) {
                            Text("Dùng kịch bản này làm lời thuyết minh video")
                            Text("Khi bật, lời thoại gốc sẽ không được dịch để đọc; kịch bản review sẽ thay thế. Chữ trên hình vẫn được xử lý riêng.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(
                        onClick = { previewText = reviewScript; log = "Đã chuyển kịch bản sang ô nghe thử giọng. Hãy chọn giọng và bấm Nghe thử." },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🎙 Dùng kịch bản làm mẫu giọng đọc") }
                }
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
                    text = { Text("Nữ tự nhiên — VAIS1000 (mặc định)") },
                    onClick = { voiceId = "vais1000"; voiceMenuExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text("Nam trầm — 25hours") },
                    onClick = { voiceId = "25hours_single"; voiceMenuExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text("VIVOS — giọng 1") },
                    onClick = { voiceId = "vivos"; voiceMenuExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text("VIVOS — giọng 2") },
                    onClick = { voiceId = "vivos_1"; voiceMenuExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text("VIVOS — giọng 3") },
                    onClick = { voiceId = "vivos_2"; voiceMenuExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text("VIVOS — giọng 4") },
                    onClick = { voiceId = "vivos_3"; voiceMenuExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text("VIVOS — giọng 5") },
                    onClick = { voiceId = "vivos_4"; voiceMenuExpanded = false }
                )
            }
        }
        Text("Chọn phong cách đọc", style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { voiceStyleMenuExpanded = true }, enabled = !busy) {
                Text(when (voiceStyleId) {
                    "narration" -> "Thuyết minh rõ ràng"
                    "story" -> "Kể chuyện chậm, ấm"
                    "sales" -> "Bán hàng năng lượng"
                    "natural" -> "Tự nhiên, cân bằng"
                    else -> "Review ngắn kiểu TikTok"
                })
            }
            DropdownMenu(expanded = voiceStyleMenuExpanded, onDismissRequest = { voiceStyleMenuExpanded = false }) {
                DropdownMenuItem(text = { Text("Review ngắn kiểu TikTok") }, onClick = { voiceStyleId = "review"; voiceStyleMenuExpanded = false })
                DropdownMenuItem(text = { Text("Thuyết minh rõ ràng") }, onClick = { voiceStyleId = "narration"; voiceStyleMenuExpanded = false })
                DropdownMenuItem(text = { Text("Kể chuyện chậm, ấm") }, onClick = { voiceStyleId = "story"; voiceStyleMenuExpanded = false })
                DropdownMenuItem(text = { Text("Bán hàng năng lượng") }, onClick = { voiceStyleId = "sales"; voiceStyleMenuExpanded = false })
                DropdownMenuItem(text = { Text("Tự nhiên, cân bằng") }, onClick = { voiceStyleId = "natural"; voiceStyleMenuExpanded = false })
            }
        }
        OutlinedTextField(
            value = previewText,
            onValueChange = { previewText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Câu dùng để nghe thử giọng") },
            minLines = 2,
            maxLines = 4
        )
        Button(
            enabled = !busy && previewText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    busy = true
                    previewing = false
                    log = "Đang chuẩn bị nghe thử giọng…\n"
                    try {
                        previewPlayer?.let { old ->
                            try { old.stop() } catch (_: Exception) {}
                            try { old.release() } catch (_: Exception) {}
                        }
                        previewPlayer = null
                        previewing = false
                        val sampleFile = Pipeline.createVoicePreview(ctx, voiceId, voiceStyleId, previewText) { message ->
                            scope.launch { log += message + "\n" }
                        }
                        previewPlayer = MediaPlayer().apply {
                            setDataSource(sampleFile.absolutePath)
                            setOnPreparedListener { player ->
                                player.start()
                                previewing = true
                                log += "Đang phát mẫu giọng.\n"
                            }
                            setOnCompletionListener { player ->
                                player.release()
                                if (previewPlayer === player) previewPlayer = null
                                previewing = false
                            }
                            setOnErrorListener { player, _, _ ->
                                player.release()
                                if (previewPlayer === player) previewPlayer = null
                                previewing = false
                                log += "Không phát được mẫu âm thanh.\n"
                                true
                            }
                            prepareAsync()
                        }
                    } catch (e: Exception) {
                        log += "Nghe thử thất bại: ${e.message ?: "lỗi không rõ"}\n"
                    } finally { busy = false }
                }
            }
        ) { Text("▶ Nghe thử giọng đã chọn") }
        if (previewing && previewPlayer != null) {
            TextButton(onClick = {
                previewPlayer?.let { player ->
                    try { player.stop() } catch (_: Exception) {}
                    try { player.release() } catch (_: Exception) {}
                }
                previewPlayer = null
                previewing = false
            }, modifier = Modifier.fillMaxWidth()) { Text("■ Dừng nghe thử") }
        }
        Text("Phong cách hiện điều chỉnh tốc độ đọc; các lựa chọn VIVOS dùng nhiều speaker trong cùng một mô hình, chưa được gán nhãn nam/nữ đã xác minh. Không sao chép giọng người nổi tiếng. Lưu ý: giấy phép mô hình 25hours chưa rõ; VIVOS có điều kiện phi thương mại, nên kiểm tra giấy phép trước khi dùng video kiếm tiền.", style = MaterialTheme.typography.bodySmall)
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
                Text("👁 Xem trước và căn vùng che", style = MaterialTheme.typography.titleMedium)
                if (previewFrame != null) {
                    Box(Modifier.fillMaxWidth().aspectRatio(previewFrame!!.width.toFloat() / previewFrame!!.height.toFloat())) {
                        Image(bitmap = previewFrame!!.asImageBitmap(), contentDescription = "Khung hình video mẫu", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Canvas(Modifier.fillMaxSize()) {
                            if (coverOriginalText) {
                                val y = size.height * coverTopPercent / 100f
                                val h = size.height * coverHeightPercent / 100f
                                drawRect(color = Color(0x99000000), topLeft = Offset(size.width * 0.08f, y), size = Size(size.width * 0.84f, h))
                            }
                        }
                    }
                    Text("Vùng tối trên ảnh là phần sẽ bị che khi xuất video.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Chọn video để hiện khung hình xem trước.", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    enabled = video != null && !busy && !detectingRegion,
                    onClick = {
                        val selected = video ?: return@Button
                        scope.launch {
                            detectingRegion = true
                            try {
                                val region = withContext(Dispatchers.IO) {
                                    val retriever = MediaMetadataRetriever()
                                    val found = mutableListOf<android.graphics.Rect>()
                                    try {
                                        retriever.setDataSource(ctx, selected)
                                        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                        val frameBitmap = previewFrame
                                        val frameH = frameBitmap?.height ?: 1
                                        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                        val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                                        try {
                                            for (fraction in listOf(0.25, 0.5, 0.75)) {
                                                val frame = retriever.getFrameAtTime((durationMs * fraction).toLong() * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                                                try {
                                                    val image = InputImage.fromBitmap(frame, 0)
                                                    val results = listOf(latin.process(image).await(), chinese.process(image).await())
                                                    results.flatMap { it.textBlocks }.mapNotNull { it.boundingBox }.filter {
                                                        it.width() > frame.width * 0.12 && it.height() > frame.height * 0.012 &&
                                                            it.centerY() > frame.height * 0.12 && it.centerY() < frame.height * 0.82
                                                    }.forEach { found.add(android.graphics.Rect(it)) }
                                                } finally { frame.recycle() }
                                            }
                                        } finally { latin.close(); chinese.close() }
                                        if (found.isEmpty()) null else {
                                            val band = maxOf(1, frameH / 20)
                                            val best = found.groupBy { it.centerY() / band }.values.maxByOrNull { boxes ->
                                                boxes.size * boxes.map { it.width() }.average()
                                            } ?: found
                                            val top = best.map { it.top }.average().toFloat() / frameH * 100f
                                            val bottom = best.map { it.bottom }.average().toFloat() / frameH * 100f
                                            top.coerceIn(20f, 65f) to (bottom - top + 4f).coerceIn(6f, 18f)
                                        }
                                    } finally { retriever.release() }
                                }
                                if (region != null) {
                                    coverTopPercent = region.first
                                    coverHeightPercent = region.second
                                    log = "Đã tìm thấy vùng có chữ gần vị trí ${region.first.toInt()}%. Hãy kiểm tra ảnh xem trước và chỉnh lại nếu cần."
                                } else log = "Chưa tự tìm được vùng chữ ổn định. Bạn có thể chỉnh vị trí và chiều cao bằng thanh trượt bên dưới."
                            } catch (e: Exception) {
                                log = "Không tự nhận diện được vùng chữ: ${e.message ?: "hãy chỉnh bằng tay"}"
                            } finally { detectingRegion = false }
                        }
                    }
                ) { Text(if (detectingRegion) "Đang tìm vùng chữ…" else "✨ Tự nhận diện vùng chữ") }
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
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = autoDetectTextRegions, onCheckedChange = { autoDetectTextRegions = it })
                        Column(Modifier.weight(1f)) {
                            Text("Tự dò vị trí chữ theo thời gian")
                            Text("Quét OCR nhiều khung hình; nếu tắt, dùng vùng căn tay.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("Cách xử lý chữ gốc", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = textRemovalMode == "blur", onClick = { textRemovalMode = "blur" })
                        Column(Modifier.weight(1f)) {
                            Text("Làm mờ vùng chữ")
                            Text("Che chữ bằng vùng nền đã làm mờ; thường tự nhiên hơn dải đen.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = textRemovalMode == "remove", onClick = { textRemovalMode = "remove" })
                        Column(Modifier.weight(1f)) {
                            Text("Xóa chữ / phục hồi nền thử nghiệm")
                            Text("Dùng bộ lọc delogo để nội suy nền; có thể để lại vệt nếu nền phức tạp.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = textRemovalMode == "cover", onClick = { textRemovalMode = "cover" })
                        Column(Modifier.weight(1f)) {
                            Text("Che bằng dải đen")
                            Text("Ổn định nhất nhưng dễ nhìn thấy vùng che.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("Vị trí vùng chữ (tính từ trên xuống): ${coverTopPercent.toInt()}%")
                    Slider(value = coverTopPercent, onValueChange = { coverTopPercent = it }, valueRange = 20f..65f, steps = 44)
                    Text("Chiều cao vùng xử lý: ${coverHeightPercent.toInt()}%")
                    Slider(value = coverHeightPercent, onValueChange = { coverHeightPercent = it }, valueRange = 6f..18f, steps = 11)
                    Text("Mẹo: căn vùng vừa đủ bao quanh phụ đề Trung. Xóa nền tự nhiên hoàn toàn cần mô hình inpainting chuyên dụng và có thể nặng với điện thoại.", style = MaterialTheme.typography.bodySmall)
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
                            voiceStyleId = voiceStyleId,
                            originalVolume = originalVolume / 100f,
                            translatedVolume = translatedVolume / 100f,
                            coverOriginalText = coverOriginalText,
                            textRemovalMode = textRemovalMode,
                            autoDetectTextRegions = autoDetectTextRegions,
                            coverHeightPercent = coverHeightPercent,
                            coverTopPercent = coverTopPercent,
                            reviewNarration = if (useReviewNarration) reviewScript else null
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
