package com.privacyguard.android

import com.privacyguard.android.core.Category
import com.privacyguard.android.core.Outcome

/** User-facing explanations use only facts present in the recorded outcome/reason. */
data class RequestText(val title: String, val explanation: String, val suggestion: String) {
    companion object {
        fun forEvent(event: GuardEvent): RequestText = when (event.outcome) {
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
}
