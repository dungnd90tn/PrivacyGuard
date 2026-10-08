package com.privacyguard.android.core

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class DnsDiagnosticsTest {
    private val query = dnsQuery("api.example.com")
    @Test fun detailedForwardingRetainsEachSelectedResolverProtocolAndFailureStage() {
        val result = DnsForwarder.resolveDetailed(query, DnsSettings(mode = DnsMode.TLS)) { _, endpoint, protocol ->
            assertEquals(853, endpoint.port); assertEquals(DnsProtocol.TLS, protocol)
            throw DnsTransportException(DnsFailureKind.TIMEOUT, DnsStage.TLS_HANDSHAKE, 1500, SocketTimeoutException("do-not-store-message"))
        }
        assertNull(result.response); assertEquals(2, result.failures.size)
        assertEquals(listOf("9.9.9.9:853", "149.112.112.112:853"), result.failures.map { it.resolver })
        assertTrue(result.failures.all { it.stage == DnsStage.TLS_HANDSHAKE && it.kind == DnsFailureKind.TIMEOUT && it.tlsName == "dns.quad9.net" && it.timeoutMs == 1500 })
        assertTrue(result.failures.all { it.exceptionType == "SocketTimeoutException" })
    }
    @Test fun responseMismatchIsDistinguishedFromNetworkAndAuthenticationFailures() {
        val answer = Packets.dnsError(query, Packets.question(query)!!, 0).apply { this[1] = 0 }
        val mismatch = DnsForwarder.resolveDetailed(query, DnsSettings()) { _, _, _ -> answer }
        assertTrue(mismatch.failures.all { it.kind == DnsFailureKind.INVALID_RESPONSE && it.validation == DnsResponseIssue.TRANSACTION_ID && it.responseBytes == answer.size })
        assertEquals(DnsFailureKind.CONNECTION_REFUSED, DnsDiagnostics.kind(ConnectException()))
        assertEquals(DnsFailureKind.INCOMPLETE_RESPONSE, DnsDiagnostics.kind(EOFException()))
        val ssl = SSLHandshakeException("private-message").apply { initCause(CertificateException("private-certificate-message")) }
        assertEquals(DnsFailureKind.TLS_AUTHENTICATION, DnsDiagnostics.kind(ssl))
        val timeout = SSLHandshakeException("private-message").apply { initCause(SocketTimeoutException()) }
        assertEquals(DnsFailureKind.TIMEOUT, DnsDiagnostics.kind(timeout))
    }
    @Test fun diagnosticsSurviveFilteringAndRetriesWithoutChangingEnforcementOrTlsMode() {
        var calls = 0
        val failed = DnsFilter.resolveDetailed(query, null, Policy()) {
            DnsForwarder.resolveDetailed(it, DnsSettings(mode = DnsMode.TLS)) { _, _, protocol ->
                assertEquals(DnsProtocol.TLS, protocol); calls++
                throw DnsTransportException(DnsFailureKind.VPN_PROTECTION, DnsStage.PROTECT, 1500)
            }
        }!!
        assertEquals(2, calls); assertEquals(Outcome.FAILED, failed.outcome); assertEquals(2, failed.failures.size)
        assertEquals(1, failed.queryType); assertEquals(2, Packets.u16(failed.response, 2) and 15)
        DnsFilter.resolveDetailed(dnsQuery("ads.example.com"), null, Policy()) { fail("Blocked query must never connect"); DnsUpstreamResult() }
    }
    @Test fun successfulBackupDoesNotTurnOneQueryIntoAFailureAndServerErrorsKeepTheirCode() {
        var calls = 0
        val result = DnsFilter.resolveDetailed(query, null, Policy()) {
            DnsForwarder.resolveDetailed(it, DnsSettings(mode = DnsMode.TLS)) { _, _, _ ->
                calls++; if (calls == 1) throw SocketTimeoutException()
                else Packets.dnsError(query, Packets.question(query)!!, 0)
            }
        }!!
        assertEquals(Outcome.FORWARDED, result.outcome); assertTrue(result.failures.isEmpty())
        val serverError = DnsFilter.resolveDetailed(query, null, Policy()) {
            DnsForwarder.resolveDetailed(it, DnsSettings()) { _, _, _ -> Packets.dnsError(query, Packets.question(query)!!, 5) }
        }!!
        assertEquals("Resolver trả lỗi DNS (mã 5)", serverError.decision.reason)
        assertTrue(serverError.failures.all { it.kind == DnsFailureKind.SERVER_ERROR && it.serverCode == 5 })
    }
}
