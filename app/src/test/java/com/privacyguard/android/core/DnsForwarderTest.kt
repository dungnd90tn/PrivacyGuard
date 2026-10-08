package com.privacyguard.android.core

import org.junit.Assert.*
import org.junit.Test

class DnsForwarderTest {
    @Test fun encryptedSettingsUseOnlyTls853AndNeverDowngradeOnFailures() {
        val settings = DnsSettings("cloudflare", mode = DnsMode.TLS)
        val calls = mutableListOf<DnsEndpoint>()
        assertNull(DnsForwarder.resolve(query, settings) { _, endpoint, protocol ->
            assertEquals(DnsProtocol.TLS, protocol); assertEquals(853, endpoint.port)
            assertEquals("one.one.one.one", endpoint.tlsName); calls += endpoint; null
        })
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), calls.map { it.address })
        calls.clear()
        assertNull(DnsForwarder.resolve(query, settings) { _, endpoint, protocol ->
            assertEquals(DnsProtocol.TLS, protocol); calls += endpoint; reply().apply { this[2] = (this[2].toInt() or 2).toByte() }
        })
        assertEquals(2, calls.size)
    }
    @Test fun blockedQueriesAreNotForwardedAndTlsFailuresAreFriendlyDnsErrors() {
        val ads = dnsQuery("ads.example.com")
        val blocked = DnsFilter.resolve(ads, null, Policy()) { fail("Filtering must happen before TLS"); null }!!
        assertEquals(Outcome.BLOCKED, blocked.outcome)
        val failed = DnsFilter.resolve(query, null, Policy()) { DnsForwarder.resolve(it, DnsSettings(mode = DnsMode.TLS)) { _, _, _ -> null } }!!
        assertEquals(Outcome.FAILED, failed.outcome); assertEquals(2, Packets.u16(failed.response, 2) and 15)
    }
    private val query = dnsQuery("api.example.com")
    private val endpoints = DnsSettings("cloudflare").active.endpoints
    private fun reply(code: Int = 0) = Packets.dnsError(query, Packets.question(query)!!, code)

    @Test fun successfulPrimaryDoesNotContactBackupOrAnotherProvider() {
        val contacted = mutableListOf<DnsEndpoint>()
        val response = DnsForwarder.resolve(query, endpoints) { _, endpoint, protocol ->
            assertEquals(DnsProtocol.UDP, protocol); contacted += endpoint; reply()
        }
        assertArrayEquals(reply(), response); assertEquals(listOf(endpoints.first()), contacted)
    }
    @Test fun timeoutAndBadRepliesTryOnlyTheSelectedBackup() {
        for (bad in listOf(null, byteArrayOf(0), reply().apply { this[0] = 99 }, reply().copyOf(65001))) {
            val contacted = mutableListOf<DnsEndpoint>()
            assertArrayEquals(reply(), DnsForwarder.resolve(query, endpoints) { _, endpoint, _ ->
                contacted += endpoint; if (endpoint == endpoints.first()) bad else reply()
            })
            assertEquals(endpoints, contacted)
        }
    }
    @Test fun truncatedUdpRetriesTcpOnTheSameCustomAddressAndPort() {
        val endpoint = DnsEndpoint("192.168.1.1", 5353)
        val protocols = mutableListOf<DnsProtocol>()
        assertArrayEquals(reply(), DnsForwarder.resolve(query, listOf(endpoint)) { _, actual, protocol ->
            assertEquals(endpoint, actual); protocols += protocol
            reply().also { if (protocol == DnsProtocol.UDP) it[2] = (it[2].toInt() or 2).toByte() }
        })
        assertEquals(listOf(DnsProtocol.UDP, DnsProtocol.TCP), protocols)
    }
    @Test fun invalidTcpReplyAndProtectionFailuresCanUseTheSelectedBackup() {
        val calls = mutableListOf<Pair<DnsEndpoint, DnsProtocol>>()
        val result = DnsForwarder.resolve(query, endpoints) { _, endpoint, protocol ->
            calls += endpoint to protocol
            if (endpoint != endpoints.first()) reply()
            else if (protocol == DnsProtocol.UDP) reply().apply { this[2] = (this[2].toInt() or 2).toByte() }
            else throw java.io.IOException("protection failed")
        }
        assertArrayEquals(reply(), result)
        assertEquals(listOf(endpoints[0] to DnsProtocol.UDP, endpoints[0] to DnsProtocol.TCP, endpoints[1] to DnsProtocol.UDP), calls)
    }
    @Test fun serverErrorsRetryBackupButKeepTheirReasonWhenNoServerSucceeds() {
        assertEquals(5, Packets.u16(DnsForwarder.resolve(query, endpoints) { _, endpoint, _ -> reply(if (endpoint == endpoints[0]) 2 else 5) }!!, 2) and 15)
        assertArrayEquals(reply(), DnsForwarder.resolve(query, endpoints) { _, endpoint, _ -> reply(if (endpoint == endpoints[0]) 2 else 0) })
    }
    @Test fun upstreamNxDomainIsReturnedWithoutBeingMistakenForAFilterBlock() {
        var calls = 0
        val result = DnsFilter.resolve(query, null, Policy()) {
            DnsForwarder.resolve(it, endpoints) { _, _, _ -> calls++; reply(3) }
        }!!
        assertEquals(1, calls); assertEquals(Outcome.FORWARDED, result.outcome)
        assertEquals(3, Packets.u16(result.response, 2) and 15)
    }
}
