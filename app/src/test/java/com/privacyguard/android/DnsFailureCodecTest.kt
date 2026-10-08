package com.privacyguard.android

import com.privacyguard.android.core.*
import org.junit.Assert.*
import org.junit.Test

class DnsFailureCodecTest {
    @Test fun failureSnapshotsRoundTripAndTechnicalTextHasStageResolverTimeoutAndType() {
        val query = dnsQuery("api.example.com", type = 28)
        val result = DnsFilter.resolveDetailed(query, null, Policy()) {
            DnsForwarder.resolveDetailed(it, DnsSettings(mode = DnsMode.TLS)) { _, _, _ ->
                throw DnsTransportException(DnsFailureKind.TIMEOUT, DnsStage.TLS_HANDSHAKE, 1500, java.net.SocketTimeoutException("secret-canary"))
            }
        }!!
        val encoded = DnsFailureCodec.encode(result); val decoded = DnsFailureCodec.decode(encoded)!!
        assertEquals(result.failures, decoded.attempts); assertEquals(28, decoded.queryType)
        assertFalse(encoded.contains("secret-canary"))
        val technical = DnsFailureCodec.technical(encoded)
        for (value in listOf("9.9.9.9:853", "149.112.112.112:853", "TLS_HANDSHAKE", "SocketTimeoutException", "1500 ms", "AAAA (IPv6)")) assertTrue(technical.contains(value))
    }
    @Test fun legacyAndBrokenReasonsRemainReadableAndDifferentFailuresAreNotConflated() {
        val legacy = "Không nhận được phản hồi DNS hợp lệ"
        assertNull(DnsFailureCodec.decode(legacy)); assertEquals(legacy, DnsFailureCodec.technical(legacy))
        assertNull(DnsFailureCodec.decode("DNS_DIAGNOSTIC_V1:bad json"))
        fun event(failure: DnsFailure): GuardEvent {
            val result = DnsResult(byteArrayOf(), Decision("api.example.com", Category.ESSENTIAL, Action.ALLOW, legacy), Outcome.FAILED, listOf(failure))
            return GuardEvent(1, null, "api.example.com", Category.ESSENTIAL, Outcome.FAILED, DnsFailureCodec.encode(result))
        }
        assertTrue(RequestText.forEvent(event(DnsFailure(DnsFailureKind.TIMEOUT, DnsStage.TLS_HANDSHAKE))).title.contains("Bắt tay"))
        assertTrue(RequestText.forEvent(event(DnsFailure(DnsFailureKind.INVALID_RESPONSE, DnsStage.VALIDATE))).explanation.contains("Đã nhận dữ liệu"))
        assertTrue(RequestText.forEvent(event(DnsFailure(DnsFailureKind.VPN_PROTECTION, DnsStage.PROTECT))).explanation.contains("chưa được gửi"))
        assertTrue(RequestText.forEvent(event(DnsFailure(DnsFailureKind.TLS_AUTHENTICATION, DnsStage.TLS_HANDSHAKE))).suggestion.contains("ngày giờ"))
    }
}
