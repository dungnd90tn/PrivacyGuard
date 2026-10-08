package com.privacyguard.android

import com.privacyguard.android.core.Category
import com.privacyguard.android.core.Outcome
import org.junit.Assert.*
import org.junit.Test

class RequestTextTest {
    private fun event(outcome: Outcome, reason: String, category: Category = Category.ADS) = GuardEvent(1, null, "ads.example.com", category, outcome, reason)
    @Test fun blockedRequestsExplainTheUserRuleAndEssentialImpact() {
        val blocked = RequestText.forEvent(event(Outcome.BLOCKED, "Ngoại lệ ứng dụng"))
        assertTrue(blocked.explanation.contains("ứng dụng này")); assertTrue(blocked.explanation.contains("không được gửi"))
        assertTrue(RequestText.forEvent(event(Outcome.BLOCKED, "Luật thiết yếu", Category.ESSENTIAL)).suggestion.contains("không hoạt động đúng"))
    }
    @Test fun transportFailuresDoNotClaimThatFilteringOrMalwareCausedThem() {
        val failure = RequestText.forEvent(event(Outcome.FAILED, "Không nhận được phản hồi DNS hợp lệ"))
        assertTrue(failure.explanation.contains("có thể")); assertTrue(failure.explanation.contains("không phải"))
        assertFalse(failure.title.contains("bị chặn")); assertFalse(failure.explanation.contains("nguy hiểm"))
    }
    @Test fun serverAndBusyFailuresHaveSpecificPlainLanguageSuggestions() {
        assertTrue(RequestText.forEvent(event(Outcome.FAILED, "Resolver trả lỗi DNS (mã 5)")).explanation.contains("từ chối"))
        assertTrue(RequestText.forEvent(event(Outcome.FAILED, "Resolver trả lỗi DNS (mã 2)")).title.contains("sự cố"))
        assertTrue(RequestText.forEvent(event(Outcome.FAILED, "Hàng đợi DNS đầy")).suggestion.contains("Đợi"))
        listOf(1, 2, 4, 5).forEach { code -> assertFalse(RequestText.forEvent(event(Outcome.FAILED, "Resolver trả lỗi DNS (mã $code)")).explanation.contains("mã $code")) }
    }
    @Test fun forwardedDoesNotPromiseASuccessfulAppConnection() {
        assertTrue(RequestText.forEvent(event(Outcome.FORWARDED, "Không có luật khớp")).suggestion.contains("không đảm bảo"))
    }
}
