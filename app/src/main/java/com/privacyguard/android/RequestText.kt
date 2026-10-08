package com.privacyguard.android

import com.privacyguard.android.core.Category
import com.privacyguard.android.core.Outcome
import com.privacyguard.android.core.DnsFailure
import com.privacyguard.android.core.DnsFailureKind
import com.privacyguard.android.core.DnsStage

/** User-facing explanations use only facts present in the recorded outcome/reason. */
data class RequestText(val title: String, val explanation: String, val suggestion: String) {
    companion object {
        fun forEvent(event: GuardEvent): RequestText {
            val details = DnsFailureCodec.decode(event.reason)
            if (event.outcome == Outcome.FAILED && details != null) {
                if (details.summary == "Không nhận được phản hồi DNS hợp lệ" && details.attempts.isNotEmpty()) {
                    val words = details.attempts.map(::forFailure).distinct()
                    if (words.size == 1) return words.first()
                    return RequestText("Không hoàn tất truy vấn DNS", "Các lần thử gặp: ${words.joinToString("; ") { it.title.lowercase(java.util.Locale.ROOT) }}. Xem chi tiết kỹ thuật để biết từng lần thử.",
                        "Kiểm tra mạng và cấu hình DNS. Bạn có thể chọn máy chủ khác; PrivacyGuard không tự tắt mã hóa.")
                }
                return forEvent(event.copy(reason = details.summary))
            }
            return when (event.outcome) {
            Outcome.BLOCKED -> {
                val title = when (event.category) {
                    Category.ADS -> "Quảng cáo đã bị chặn"
                    Category.ANALYTICS -> "Yêu cầu theo dõi đã bị chặn"
                    Category.ESSENTIAL -> "Một yêu cầu cần thiết đã bị chặn"
                    Category.UNKNOWN -> "Đã chặn theo quy tắc của bạn"
                }
                val rule = when (event.reason) {
                    "Ngoại lệ ứng dụng" -> "quy tắc bạn đặt cho ứng dụng này"
                    "Ngoại lệ toàn cục" -> "quy tắc bạn đặt cho tất cả ứng dụng"
                    else -> "quy tắc bảo vệ đang bật"
                }
                RequestText(title, "PrivacyGuard đã ngăn yêu cầu này theo $rule. Yêu cầu không được gửi tới máy chủ DNS.",
                    if (event.category == Category.ESSENTIAL) "Nếu ứng dụng không hoạt động đúng, bạn có thể cho phép tên miền này."
                    else "Bạn có thể giữ chặn. Nếu một tính năng không hoạt động, hãy cân nhắc cho phép tên miền này.")
            }
            Outcome.FAILED -> {
                if (event.reason == "Hàng đợi DNS đầy") RequestText("Có quá nhiều yêu cầu cùng lúc",
                    "PrivacyGuard chưa xử lý kịp yêu cầu này.", "Đợi một chút, sau đó thử lại trong ứng dụng.")
                else when (Regex("Resolver trả lỗi DNS \\(mã (\\d+)\\)").matchEntire(event.reason)?.groupValues?.get(1)?.toIntOrNull()) {
                    2 -> RequestText("Máy chủ DNS đang gặp sự cố", "Máy chủ chưa tìm được địa chỉ dịch vụ lúc đó. PrivacyGuard không chủ động chặn yêu cầu này.", "Thử lại sau hoặc chọn máy chủ DNS khác.")
                    5 -> RequestText("Máy chủ DNS không nhận yêu cầu", "Máy chủ đã từ chối trả lời yêu cầu này.", "Chọn máy chủ DNS khác, hoặc kiểm tra quyền sử dụng máy chủ riêng của bạn.")
                    4 -> RequestText("Máy chủ chưa hỗ trợ yêu cầu này", "Máy chủ DNS không xử lý được loại yêu cầu này.", "Thử chọn một máy chủ DNS khác.")
                    1 -> RequestText("Máy chủ chưa xử lý được yêu cầu", "Máy chủ DNS không đọc được yêu cầu này.", "Thử lại trong ứng dụng. Nếu lỗi lặp lại, hãy chọn máy chủ DNS khác.")
                    else -> RequestText("Chưa nhận được câu trả lời", "Kết nối Internet hoặc máy chủ DNS có thể đang gián đoạn. Đây không phải một yêu cầu bị PrivacyGuard chặn.", "Kiểm tra Wi-Fi hoặc dữ liệu di động, thử lại trong ứng dụng; nếu vẫn lỗi, chọn máy chủ DNS khác.")
                }
            }
            Outcome.FORWARDED -> RequestText("Yêu cầu đã được gửi đi", "PrivacyGuard đã cho phép và gửi yêu cầu tới máy chủ DNS.",
                "Trạng thái này không đảm bảo ứng dụng đã kết nối thành công với dịch vụ.")
            }
        }
        private fun forFailure(failure: DnsFailure): RequestText = when (failure.kind) {
            DnsFailureKind.TIMEOUT -> when (failure.stage) {
                DnsStage.CONNECT -> RequestText("Kết nối máy chủ DNS quá chậm", "Chưa kết nối được máy chủ trong thời gian chờ. Chưa đến bước đọc phản hồi DNS.", "Thử lại trên mạng khác hoặc chọn máy chủ DNS khác.")
                DnsStage.TLS_HANDSHAKE -> RequestText("Bắt tay mã hóa quá thời gian", "Chưa thiết lập xong kết nối mã hóa trong thời gian chờ. Chưa gửi truy vấn DNS.", "Kiểm tra mạng; có thể thử mạng hoặc máy chủ DNS khác. Mã hóa vẫn được giữ bật.")
                DnsStage.WRITE -> RequestText("Gửi yêu cầu DNS quá thời gian", "Chưa gửi xong yêu cầu trong thời gian chờ.", "Kiểm tra mạng và thử lại, hoặc chọn máy chủ DNS khác.")
                else -> RequestText("Máy chủ DNS chưa trả lời kịp", "Chưa đọc được đầy đủ phản hồi trong thời gian chờ.", "Thử lại hoặc chọn máy chủ DNS khác.")
            }
            DnsFailureKind.TLS_AUTHENTICATION -> RequestText("Chưa xác thực được DNS mã hóa", "Kết nối không vượt qua kiểm tra chứng chỉ hoặc tên máy chủ TLS.", "Kiểm tra ngày giờ thiết bị và tên xác thực TLS do nhà cung cấp công bố; có thể chọn máy chủ khác.")
            DnsFailureKind.TLS_HANDSHAKE -> RequestText("Không thiết lập được DNS mã hóa", "Kết nối TLS gặp lỗi. Đây không phải kết quả đọc nội dung phản hồi DNS.", "Kiểm tra cấu hình TLS và mạng, hoặc chọn máy chủ DNS khác.")
            DnsFailureKind.VPN_PROTECTION -> RequestText("Chưa tạo được kết nối DNS", "PrivacyGuard không chuẩn bị được kết nối ra ngoài VPN. Truy vấn chưa được gửi tới máy chủ DNS.", "Dừng và bật lại bảo vệ. Nếu vẫn lỗi, ghi lại thông tin ở Chi tiết kỹ thuật.")
            DnsFailureKind.CONNECTION_REFUSED -> RequestText("Máy chủ không nhận kết nối DNS", "Kết nối tới cổng DNS bị từ chối trước khi đọc phản hồi DNS.", "Kiểm tra địa chỉ/cổng máy chủ hoặc thử mạng, máy chủ khác.")
            DnsFailureKind.NETWORK_UNREACHABLE -> RequestText("Chưa có đường tới máy chủ DNS", "Mạng hiện tại không tìm được đường kết nối tới địa chỉ DNS.", "Kiểm tra Wi-Fi hoặc dữ liệu di động, sau đó thử lại.")
            DnsFailureKind.INCOMPLETE_RESPONSE, DnsFailureKind.INVALID_FRAME_LENGTH, DnsFailureKind.TRUNCATED_RESPONSE -> RequestText("Phản hồi DNS chưa đầy đủ", "Kết nối đóng khi chưa đọc đủ dữ liệu, hoặc máy chủ gửi thông điệp bị rút ngắn/độ dài chưa được hỗ trợ.", "Thử lại hoặc chọn máy chủ DNS khác; xem chi tiết kỹ thuật để phân biệt nguyên nhân.")
            DnsFailureKind.INVALID_RESPONSE -> RequestText("Phản hồi không khớp truy vấn DNS", "Đã nhận dữ liệu, nhưng thông tin kiểm tra DNS bị thiếu hoặc không khớp yêu cầu đã gửi.", "Thử chọn máy chủ DNS khác. Chi tiết kỹ thuật cho biết mã truy vấn, tên miền hay loại truy vấn không khớp.")
            DnsFailureKind.CONFIGURATION -> RequestText("Cấu hình DNS cần kiểm tra", "Cấu hình máy chủ chưa dùng được; chưa gửi truy vấn ra mạng.", "Mở Máy chủ DNS, kiểm tra IP, cổng và tên xác thực nếu bật TLS.")
            DnsFailureKind.IO -> RequestText("Kết nối DNS gặp lỗi", "Gặp lỗi ở bước tạo, gửi hoặc nhận dữ liệu của kết nối DNS.", "Kiểm tra mạng, dừng/bật lại bảo vệ hoặc chọn máy chủ khác.")
            DnsFailureKind.NO_RESPONSE -> RequestText("Chưa nhận được câu trả lời", "Không nhận được phản hồi, nhưng lần thử này chưa cung cấp lỗi cụ thể.", "Thử lại, kiểm tra mạng hoặc chọn máy chủ DNS khác.")
            DnsFailureKind.SERVER_ERROR -> RequestText("Máy chủ DNS trả lỗi", "Máy chủ đã trả lời nhưng không hoàn tất yêu cầu.", "Thử lại hoặc chọn máy chủ DNS khác.")
            DnsFailureKind.UNEXPECTED -> RequestText("PrivacyGuard gặp lỗi xử lý DNS", "Một bước xử lý kết nối gặp lỗi chưa phân loại. Không thể kết luận nguyên nhân từ tên miền.", "Dừng/bật lại bảo vệ; xem loại exception trong chi tiết kỹ thuật.")
        }
    }
}
