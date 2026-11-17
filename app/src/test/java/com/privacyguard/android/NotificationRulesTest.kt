package com.privacyguard.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {
    @Test fun matchesKeywordAndPackage() {
        val rules = listOf(NotificationRule("com.example.shop", "khuyến mãi"))
        assertTrue(NotificationRules.shouldBlock("com.example.shop", "Ưu đãi", "KHUYẾN MÃI hôm nay", rules))
        assertFalse(NotificationRules.shouldBlock("com.example.chat", "khuyến mãi", "", rules))
    }

    @Test fun wildcardRuleAndEmptyContent() {
        val rules = listOf(NotificationRule("*", "voucher"))
        assertTrue(NotificationRules.shouldBlock("com.example.shop", "Voucher mới", "", rules))
        assertFalse(NotificationRules.shouldBlock("com.example.shop", "Đơn hàng", "", rules))
    }
}
