package com.privacyguard.android.core

import org.junit.Assert.*
import org.junit.Test

class DnsFilterTest {
    @Test fun blockedRequestsReturnNxDomainWithoutContactingResolver() {
        var forwarded = false
        val result = DnsFilter.resolve(dnsQuery(), null, Policy()) { forwarded = true; null }!!
        assertFalse(forwarded); assertEquals(Outcome.BLOCKED, result.outcome)
        assertEquals(3, Packets.u16(result.response, 2) and 15)
        assertTrue(Packets.validResponse(dnsQuery(), result.response))
    }

    @Test fun allowedRepliesAreReturnedAndAppExceptionsStayIsolated() {
        val query = dnsQuery()
        val policy = Policy(exceptions = listOf(DomainRule("ads.example.com", Action.ALLOW, "facebook")))
        val upstream = Packets.dnsError(query, Packets.question(query)!!, 0)
        val allowed = DnsFilter.resolve(query, "facebook", policy) { upstream }!!
        assertEquals(Outcome.FORWARDED, allowed.outcome); assertArrayEquals(upstream, allowed.response)
        val blocked = DnsFilter.resolve(query, "zalo", policy) { fail("Blocked request must not be forwarded"); null }!!
        assertEquals(Outcome.BLOCKED, blocked.outcome)
    }

    @Test fun timeoutTamperedAndOversizedRepliesAreFailuresNotAllowsOrBlocks() {
        val query = dnsQuery("api.example.com")
        val good = Packets.dnsError(query, Packets.question(query)!!, 0)
        for (response in listOf(null, byteArrayOf(1), good.copyOf().apply { this[0] = 99 }, good.copyOf(65001))) {
            val result = DnsFilter.resolve(query, null, Policy()) { response }!!
            assertEquals(Outcome.FAILED, result.outcome); assertEquals(2, Packets.u16(result.response, 2) and 15)
        }
        assertEquals(Outcome.FAILED, DnsFilter.resolve(query, null, Policy()) { throw java.io.IOException("timeout") }!!.outcome)
    }

    @Test fun malformedQueriesAreDiscardedAndServiceDiscoveryStaysUnknown() {
        assertNull(DnsFilter.resolve(byteArrayOf(), null, Policy()) { fail("Malformed query must not be sent"); null })
        for (name in listOf("_https._tcp.example.com", "localhost")) {
            val query = dnsQuery(name)
            val result = DnsFilter.resolve(query, null, Policy()) { Packets.dnsError(it, Packets.question(it)!!, 0) }!!
            assertEquals(Category.UNKNOWN, result.decision.category); assertEquals(Outcome.FORWARDED, result.outcome)
        }
    }

    @Test fun upstreamFailuresAreCountedSeparatelyFromForwardedOrEnforcedBlocks() {
        val query = dnsQuery("api.example.com")
        for (code in listOf(1, 2, 4, 5)) {
            val result = DnsFilter.resolve(query, null, Policy()) { Packets.dnsError(it, Packets.question(it)!!, code) }!!
            assertEquals(Outcome.FAILED, result.outcome)
            assertEquals(code, Packets.u16(result.response, 2) and 15)
        }
        // A genuine upstream NXDOMAIN is a forwarded answer, not a PrivacyGuard block.
        assertEquals(Outcome.FORWARDED, DnsFilter.resolve(query, null, Policy()) {
            Packets.dnsError(it, Packets.question(it)!!, 3)
        }!!.outcome)
    }
}
