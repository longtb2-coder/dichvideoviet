package vn.dichvideo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class ReviewStyle(val id: String, val title: String) {
    FRIENDLY("friendly", "Review tự nhiên như người dùng chia sẻ"),
    SALES("sales", "Bán hàng, mở đầu thu hút"),
    STORY("story", "Kể chuyện, dẫn dắt cảm xúc"),
    NEUTRAL("neutral", "Giới thiệu khách quan, rõ ràng")
}

data class ReviewRequest(
    val productName: String,
    val sourceFacts: String,
    val style: ReviewStyle = ReviewStyle.FRIENDLY,
    val durationSeconds: Int = 30
)

data class ReviewDraft(val title: String, val script: String, val online: Boolean)

object ProductReviewEngine {
    /**
     * Local fallback. It restructures supplied facts into a spoken script and never
     * invents numeric specifications or claims that the author personally used it.
     */
    fun generateOffline(request: ReviewRequest): List<ReviewDraft> {
        val name = request.productName.trim().ifBlank { "sản phẩm này" }
        val facts = request.sourceFacts
            .replace(Regex("(?i)(https?://\\S+|www\\.\\S+)"), " ")
            .replace(Regex("[\\t ]+"), " ")
            .trim()
        val points = facts.split(Regex("[\\n.!?。；;]+"))
            .map { it.trim().trim(',', ':', '-', '•', ' ') }
            .filter { it.length >= 4 }
            .distinctBy { it.lowercase() }
            .take(8)
        val usable = points.take(4)
        val detail = when {
            usable.isEmpty() -> "Hiện chưa có đủ thông tin cụ thể để đánh giá sâu về sản phẩm."
            usable.size == 1 -> usable[0].trimEnd('.') + "."
            else -> usable.dropLast(1).joinToString(", ") + " và " + usable.last().trimEnd('.') + "."
        }
        val opening = when (request.style) {
            ReviewStyle.FRIENDLY -> "Nếu bạn đang tìm hiểu $name, đây là vài điểm đáng chú ý."
            ReviewStyle.SALES -> "Đang tìm một lựa chọn như $name? Cùng xem nhanh những điểm đáng chú ý nhé."
            ReviewStyle.STORY -> "Khi chọn mua một món như $name, điều đáng quan tâm nhất vẫn là nó có phù hợp nhu cầu hay không."
            ReviewStyle.NEUTRAL -> "Sau đây là phần giới thiệu ngắn về $name dựa trên thông tin hiện có."
        }
        val body = if (usable.isEmpty()) {
            "Mình chưa có đủ dữ liệu xác minh về công dụng, chất liệu hay thông số của sản phẩm. Bạn nên bổ sung thông tin từ nhà sản xuất trước khi giới thiệu."
        } else {
            "Theo thông tin được cung cấp, sản phẩm có những điểm sau: $detail"
        }
        val ending = when (request.style) {
            ReviewStyle.FRIENDLY -> "Bạn nên đối chiếu thêm thông số và nhu cầu thực tế trước khi quyết định nhé."
            ReviewStyle.SALES -> "Nếu những đặc điểm này đúng với nhu cầu của bạn, hãy xem kỹ thông tin sản phẩm trước khi chọn mua."
            ReviewStyle.STORY -> "Cuối cùng, lựa chọn phù hợp vẫn là lựa chọn đáp ứng đúng nhu cầu và ngân sách của bạn."
            ReviewStyle.NEUTRAL -> "Đây là phần tóm tắt từ dữ liệu đầu vào; các thông số và tuyên bố quan trọng cần được xác minh."
        }
        val base = listOf(opening, body, ending).joinToString(" ")
        val compact = base.split(Regex("(?<=[.!?])\\s+"))
        val targetWords = when (request.durationSeconds) {
            in 1..20 -> 42
            in 21..40 -> 75
            else -> 125
        }
        val script = if (base.split(Regex("\\s+")).size > targetWords) {
            compact.take(2).joinToString(" ")
        } else base
        val safe = script.replace(Regex("(?i)\\b(tôi đã dùng|mình đã dùng|đã trải nghiệm thực tế)\\b"), "theo thông tin được cung cấp")
        return listOf(
            ReviewDraft("Bản chính", safe, false),
            ReviewDraft("Bản mở đầu khác", "Bạn đang cân nhắc $name? $body $ending", false),
            ReviewDraft("Bản ngắn gọn", "$opening $body", false)
        ).distinctBy { it.script }
    }

    /**
     * Research public product information with OpenAI Responses API web search.
     * Requires an OpenAI API key with API billing/usage enabled; ChatGPT subscriptions alone
     * do not automatically provide API credits. The returned text includes URLs for checking.
     */
    suspend fun researchProductOnline(productName: String, sourceFacts: String, model: String, apiKey: String): String =
        withContext(Dispatchers.IO) {
            require(productName.isNotBlank()) { "Hãy nhập tên hoặc mã sản phẩm trước khi tìm." }
            require(apiKey.isNotBlank()) { "Hãy nhập OpenAI API key." }
            require(model.isNotBlank()) { "Hãy nhập tên model AI." }
            val connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 90000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            try {
                val prompt = "Tìm kiếm web về sản phẩm có tên/mã chính xác: $productName. Dữ liệu nhận diện từ video (có thể sai OCR): ${sourceFacts.take(5000)}. " +
                    "Ưu tiên trang hãng, trang bán hàng chính thức và nguồn đáng tin. Trả lời bằng tiếng Việt theo các mục: " +
                    "Tên/phiên bản xác định được; thông tin có nguồn xác nhận; thông số/công dụng chưa xác minh; " +
                    "3-6 URL nguồn cụ thể. Không gán thông tin của sản phẩm gần giống sang sản phẩm này. " +
                    "Nếu không xác định chắc đúng mẫu, nói rõ chưa xác minh. Không bịa giá còn hiệu lực, chứng nhận, hiệu quả hoặc trải nghiệm."
                val payload = JSONObject()
                    .put("model", model)
                    .put("tools", JSONArray().put(JSONObject().put("type", "web_search_preview")))
                    .put("input", prompt)
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val message = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    error("Tìm kiếm web thất bại (HTTP $code)${message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."}")
                }
                val json = JSONObject(raw)
                val direct = json.optString("output_text", "").trim()
                val result = if (direct.isNotBlank()) direct else buildString {
                    val output = json.optJSONArray("output") ?: JSONArray()
                    for (i in 0 until output.length()) {
                        val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                        for (j in 0 until content.length()) {
                            val item = content.optJSONObject(j) ?: continue
                            if (item.optString("type") == "output_text") append(item.optString("text")).append("\n")
                        }
                    }
                }.trim()
                require(result.isNotBlank()) { "AI tìm kiếm không trả về thông tin sản phẩm." }
                result.take(9000)
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Optional OpenAI-compatible chat-completions endpoint. API key is supplied at runtime,
     * not persisted by this module. Use HTTPS and a trusted provider.
     */
    suspend fun generateOnline(
        request: ReviewRequest,
        endpoint: String,
        model: String,
        apiKey: String
    ): List<ReviewDraft> = withContext(Dispatchers.IO) {
        require(endpoint.startsWith("https://", ignoreCase = true)) {
            "Địa chỉ AI online phải dùng HTTPS để bảo vệ khóa API."
        }
        require(model.isNotBlank()) { "Hãy nhập tên model AI." }
        require(apiKey.isNotBlank()) { "Hãy nhập khóa API của nhà cung cấp AI." }
        require(request.sourceFacts.isNotBlank()) { "Chưa có nội dung nguồn để viết lại." }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val stylePrompt = when (request.style) {
                ReviewStyle.FRIENDLY -> "giọng bạn bè chia sẻ, tự nhiên, không như MC quảng cáo"
                ReviewStyle.SALES -> "review bán hàng có mở đầu thu hút nhưng không phóng đại"
                ReviewStyle.STORY -> "kể chuyện ngắn, dẫn dắt tự nhiên"
                ReviewStyle.NEUTRAL -> "giới thiệu khách quan, nêu rõ điều đã biết và điều chưa xác minh"
            }
            val systemPrompt = "Bạn là biên tập viên video review sản phẩm tiếng Việt, viết lời nói tự nhiên, linh hoạt như người giới thiệu sản phẩm trong video ngắn. Có thể đổi cách dẫn dắt và cấu trúc câu cho hấp dẫn, nhưng không được bịa thông số, giá, chất liệu, công dụng, hiệu quả, chứng nhận, trải nghiệm cá nhân hay lời chứng thực. BẮT BUỘC giữ nguyên chính xác tên thương hiệu, tên model, mã sản phẩm và chữ Latin như IVSO; không dịch, phiên âm, tự sửa hoặc biến tên riêng thành từ tiếng Việt. Nếu tên sản phẩm đã được người dùng cung cấp thì dùng đúng y nguyên. OCR có thể nhầm chữ trên bao bì với phụ đề: không coi một chuỗi OCR đơn lẻ là tên sản phẩm nếu chưa được xác nhận. Chỉ khẳng định điều có trong dữ liệu nguồn; nếu thiếu thì dùng cách nói thận trọng, không tự thêm tính năng. Viết câu ngắn dễ đọc thành tiếng, có nhịp tự nhiên, tránh câu mở đầu rập khuôn. Trả về JSON hợp lệ dạng {\"drafts\":[{\"title\":\"...\",\"script\":\"...\"}]} gồm 3 phương án khác nhau."
            val userPrompt = "Tên sản phẩm: ${request.productName.ifBlank { "Chưa rõ" }}\nPhong cách: $stylePrompt\nThời lượng mục tiêu: ${request.durationSeconds} giây\nDữ liệu nguồn (có thể chưa đầy đủ, không coi là đã xác minh độc lập):\n${request.sourceFacts.take(12000)}"
            val payload = JSONObject()
                .put("model", model)
                .put("temperature", 0.7)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt)))
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = try { JSONObject(response).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                error("AI online trả mã HTTP $code${message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."}")
            }
            val content = JSONObject(response).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content")
            val draftsJson = JSONObject(content).getJSONArray("drafts")
            (0 until minOf(3, draftsJson.length())).map { i ->
                val item = draftsJson.getJSONObject(i)
                ReviewDraft(item.optString("title", "Phương án ${i + 1}"), item.getString("script").trim(), true)
            }.also { require(it.isNotEmpty()) { "AI không trả về kịch bản hợp lệ." } }
        } finally {
            connection.disconnect()
        }
    }
}
