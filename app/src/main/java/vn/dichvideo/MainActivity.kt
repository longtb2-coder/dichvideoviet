package vn.dichvideo

import android.net.Uri
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
import kotlinx.coroutines.launch

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
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { video = it }
    val pickSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { srt = it }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Dịch video sang giọng Việt", style = MaterialTheme.typography.titleLarge)
        Text("Chạy offline. Lần đầu app tải khoảng 300 MB mô hình (cần mạng một lần).")
        Button({ pickVideo.launch(arrayOf("video/*")) }) { Text(if (video == null) "Chọn video" else "Đã chọn video ✓") }
        Button({ pickSrt.launch(arrayOf("*/*")) }) { Text(if (srt == null) "Chọn file phụ đề .srt (tuỳ chọn)" else "Đã chọn phụ đề ✓") }
        if (srt != null) TextButton({ srt = null }) { Text("Bỏ phụ đề, dùng nhận dạng giọng nói") }
        Button(
            enabled = !busy && video != null,
            onClick = {
                scope.launch {
                    busy = true; log = ""
                    try {
                        val out = Pipeline.run(ctx, video!!, srt) { log += it + "\n" }
                        log += "Xong! Video lưu tại:\n${out.path}"
                    } catch (e: Exception) {
                        log += "Lỗi: ${e.message}"
                    }
                    busy = false
                }
            }
        ) { Text(if (busy) "Đang xử lý…" else "Bắt đầu dịch") }
        Text(log)
    }
}
