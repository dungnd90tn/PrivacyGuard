package com.privacyguard.android.core

import org.junit.Assert.*
import org.junit.Test

class DnsServersTest {
    @Test fun numericIpv4Ipv6AndRouterAddressesAreAcceptedWithoutHostnameLookup() {
        assertEquals("1.1.1.1", DnsEndpoint.normalized(" 001.001.001.001 ").address)
        assertEquals(16, DnsEndpoint.normalized("2001:db8::53").bytes.size)
        assertEquals("192.168.1.1", DnsEndpoint.normalized("192.168.1.1").address)
        assertEquals(5353, DnsEndpoint.normalized("::1", 5353).port)
    }
    @Test fun hostnamesUrlsMalformedAndAmbiguousAddressesAreRejected() {
        listOf("", "dns.google", "https://dns.google/dns-query", "1.1.1.1:53", "127.1", "1.2.3.256", "-1.1.1.1", "2001:::1", "[2001:db8::1]", "fe80::1%wlan0").forEach {
            assertThrows("$it must fail", IllegalArgumentException::class.java) { DnsEndpoint.normalized(it) }
        }
    }
    @Test fun nonUnicastAndInternalVpnTargetsCannotBeSelected() {
        listOf("0.0.0.0", "::", "224.0.0.1", "ff02::1", "255.255.255.255", "169.254.1.1", "fe80::1", "10.111.0.1", "10.111.0.2", "fd42:7072:6976::2", "::ffff:10.111.0.2").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { DnsEndpoint.normalized(it) }
        }
    }
    @Test fun customPortIsValidatedAndDisplayedUnambiguously() {
        listOf(0, -1, 65536).forEach { port -> assertThrows(IllegalArgumentException::class.java) { DnsEndpoint("1.1.1.1", port) } }
        assertEquals("1.1.1.1:5353", DnsEndpoint("1.1.1.1", 5353).display)
        assertTrue(DnsEndpoint("2001:db8::1", 5353).display.startsWith("[2001:db8::1]:"))
    }
    @Test fun profilesDoNotMixBackupProvidersAndMissingSelectionsUseTheDefault() {
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), DnsSettings("cloudflare").active.endpoints.map { it.address })
        assertEquals("quad9", DnsSettings("missing").active.id)
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), DnsSettings().active.endpoints.map { it.address })
    }
    @Test fun customNamesAndIdsAreBoundedAndDuplicateBackupsAreRemoved() {
        val server = DnsServers.custom("custom-home", " DNS ở nhà ", "192.168.1.1", "192.168.001.001", 5353)
        assertEquals("DNS ở nhà", server.name); assertEquals(1, server.endpoints.size)
        assertEquals(5353, server.primary.port)
        listOf("", "a".repeat(41), "line\nbreak").forEach { name -> assertThrows(IllegalArgumentException::class.java) { DnsServers.custom("custom-home", name, "1.1.1.1") } }
        assertThrows(IllegalArgumentException::class.java) { DnsServers.custom("cloudflare", "Fake", "1.1.1.1") }
    }
}
