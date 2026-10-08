package com.privacyguard.android.core

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

enum class UrlKind(val title: String, val explanation: String) {
    AD_REQUEST("Khớp mẫu yêu cầu quảng cáo", "URL khớp mẫu lấy quảng cáo đã công bố. Đây là nhận định từ URL, chưa chứng minh quảng cáo đã được tải hoặc hiển thị."),
    AD_RELATED("Liên quan đến quảng cáo", "Tên miền nằm trong danh sách quảng cáo. URL cũng có thể dùng để ghi nhận lượt nhấp hoặc đo lường."),
    POSSIBLE_ADS("Có dấu hiệu quảng cáo", "Tên tham số gợi ý vị trí quảng cáo. Đây là phỏng đoán; dịch vụ khác có thể dùng cùng tên tham số."),
    TRACKING("Có dấu hiệu đo lường", "URL có dấu hiệu đo lường hoặc ghi nhận chiến dịch. Điều này không đủ để kết luận đây là yêu cầu tải quảng cáo."),
    INSUFFICIENT("Chưa đủ dấu hiệu", "Chưa tìm thấy dấu hiệu rõ từ tên miền và tham số. Kết quả này không bảo đảm URL không liên quan đến quảng cáo.")
}

data class QueryParameter(val name: String, val value: String?, val explanation: String)
/** Transient URL data: never write this model to preferences, event history or exports. */
data class UrlAnalysis(val host: String, val path: String, val kind: UrlKind, val reasons: List<String>,
                       val parameters: List<QueryParameter>, val omittedParameters: Boolean, val hasFragment: Boolean)

object UrlAnalyzer {
    const val MAX_URL = 8192
    const val MAX_PARAMS = 50
    private val adKeys = setOf("ad_unit", "ad_unit_id", "adunit", "adunit_id", "ad_slot", "adslot", "ad_slot_id")
    private val clickKeys = setOf("gclid", "dclid", "gbraid", "wbraid", "fbclid", "msclkid")

    fun analyze(input: String): UrlAnalysis {
        val value = input.trim()
        require(value.length <= MAX_URL) { "URL quá dài. Hãy dùng URL tối đa 8.192 ký tự." }
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null && uri.scheme?.lowercase(Locale.ROOT) in listOf("https", "http") && !uri.host.isNullOrBlank()) {
            "Nhập URL đầy đủ bắt đầu bằng https:// hoặc http://."
        }
        require(uri.rawUserInfo == null) { "Hãy bỏ tên đăng nhập hoặc mật khẩu khỏi URL." }
        val host = uri.host.lowercase(Locale.ROOT).removeSuffix(".")
        val path = uri.rawPath.orEmpty()
        val parts = uri.rawQuery?.takeIf { it.isNotEmpty() }?.split('&').orEmpty()
        val keys = parts.take(MAX_PARAMS).map { part ->
            val key = decode(part.substringBefore('='))
            key to if ('=' in part) decode(part.substringAfter('=')) else null
        }
        // Exact provider + documented endpoint + required fields. Neither iu nor sz alone identifies an ad request.
        val adManager = (host == "securepubads.g.doubleclick.net" && path == "/gampad/adx") ||
            (host == "pubads.g.doubleclick.net" && path in setOf("/gampad/ads", "/gampad/live/ads"))
        val adPattern = adManager && keys.any { it.first.equals("iu", true) && it.second?.matches(Regex("/[0-9]+/.+")) == true } &&
            keys.any { it.first.equals("sz", true) && it.second?.matches(Regex("[0-9]{1,4}x[0-9]{1,4}(?:\\|[0-9]{1,4}x[0-9]{1,4})*")) == true }
        val category = runCatching { Rules.decide(host, null, Policy()).category }.getOrDefault(Category.UNKNOWN)
        val ads = keys.filter { it.first.lowercase(Locale.ROOT) in adKeys && !it.second.isNullOrBlank() }
        val tracking = keys.filter { val key = it.first.lowercase(Locale.ROOT); key.startsWith("utm_") || key in clickKeys }
        val kind = when {
            adPattern -> UrlKind.AD_REQUEST
            category == Category.ADS -> UrlKind.AD_RELATED
            ads.isNotEmpty() -> UrlKind.POSSIBLE_ADS
            tracking.isNotEmpty() || category == Category.ANALYTICS -> UrlKind.TRACKING
            else -> UrlKind.INSUFFICIENT
        }
        val reasons = buildList {
            if (adPattern) add("Đúng tên miền và đường dẫn Google Ad Manager; có mã vị trí iu và kích thước sz theo mẫu đã công bố.")
            else if (category == Category.ADS) add("Tên miền khớp danh sách quảng cáo khởi đầu của PrivacyGuard.")
            if (ads.isNotEmpty()) add("Có tham số vị trí quảng cáo: ${ads.map { safe(it.first) }.distinct().joinToString(", ")}.")
            if (tracking.isNotEmpty()) add("Có tham số đo lường: ${tracking.map { safe(it.first) }.distinct().joinToString(", ")}. Chỉ dấu này không chứng minh URL tải ads.")
            if (category == Category.ANALYTICS) add("Tên miền khớp danh sách phân tích/đo lường khởi đầu.")
            if (isEmpty()) add("Chưa có mẫu tên miền hoặc tham số đủ rõ trong bộ nhận diện hiện tại.")
        }
        val parameters = keys.map { (name, content) ->
            val low = name.lowercase(Locale.ROOT)
            val meaning = when {
                adManager && low == "iu" -> "Mã vị trí quảng cáo của Google Ad Manager."
                adManager && low == "sz" -> "Kích thước quảng cáo trong mẫu Google Ad Manager."
                adManager && low == "cust_params" -> "Thuộc tính nhắm mục tiêu; không tự chứng minh có quảng cáo."
                low in adKeys -> "Gợi ý vị trí quảng cáo; cần đọc trong ngữ cảnh dịch vụ."
                low.startsWith("utm_") -> "Đo lường chiến dịch/nguồn truy cập; không chứng minh tải ads."
                low == "gclid" -> "Mã lượt nhấp Google Ads, thường có trên link đích."
                low in clickKeys -> "Có thể dùng để ghi nhận lượt nhấp hoặc đo lường."
                else -> "Chưa có giải thích đáng tin cho tham số này."
            }
            QueryParameter(name, content, meaning)
        }
        return UrlAnalysis(host, path, kind, reasons, parameters, parts.size > MAX_PARAMS, uri.rawFragment != null)
    }
    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    fun safe(value: String): String = value.map { if (it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069') '�' else it }.joinToString("").take(1000)
}

data class BrowserRequest(val time: Long, val method: String, val mainFrame: Boolean, val domainBlocked: Boolean, val analysis: UrlAnalysis)

/** Bounded, opt-in, process-memory journal. Tokens reject callbacks from an expired capture/session. */
class RequestSession(private val limit: Int = 100) {
    init { require(limit in 1..100) }
    private var generation = 0L
    private var capturing = false
    private val entries = ArrayDeque<BrowserRequest>()
    val enabled: Boolean get() = synchronized(this) { capturing }
    @Synchronized fun setEnabled(enabled: Boolean) { generation++; capturing = enabled; entries.clear() }
    @Synchronized fun token(): Long? = if (capturing) generation else null
    @Synchronized fun append(token: Long, request: BrowserRequest) {
        if (!capturing || token != generation) return
        while (entries.size >= limit) entries.removeFirst()
        entries.addLast(request)
    }
    @Synchronized fun snapshot(): List<BrowserRequest> = entries.reversed()
    @Synchronized fun clear() { generation++; capturing = false; entries.clear() }
}
