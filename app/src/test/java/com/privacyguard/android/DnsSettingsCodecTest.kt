package com.privacyguard.android

import com.privacyguard.android.core.DnsServers
import com.privacyguard.android.core.DnsSettings
import org.junit.Assert.*
import org.junit.Test

class DnsSettingsCodecTest {
    @Test fun roundTripsCustomSelectionIpv6BackupAndNonstandardPort() {
        val server = DnsServers.custom("custom-home", "DNS nhà", "192.168.1.1", "2001:db8::53", 5353)
        val decoded = DnsSettingsCodec.decode(DnsSettingsCodec.encode(DnsSettings(server.id, listOf(server))))
        assertEquals(server, decoded.active); assertEquals(1, decoded.custom.size)
    }
    @Test fun corruptEntriesAreSkippedAndMissingSelectionRecoversSafely() {
        val raw = """{"selected":"custom-bad","custom":[{"id":"custom-bad","name":"Broken","primary":"https://evil.example"},{"id":"custom-port","name":"Bad port","primary":"1.1.1.1","port":"abc"},{"id":"custom-good","name":"Home","primary":"192.168.1.1","port":53}]}"""
        val recovered = DnsSettingsCodec.decode(raw)
        assertEquals("quad9", recovered.active.id); assertEquals(listOf("custom-good"), recovered.custom.map { it.id })
        assertEquals(DnsSettings(), DnsSettingsCodec.decode("not json"))
    }
    @Test fun builtinsCannotBeOverwrittenByRecoveredCustomJson() {
        val raw = """{"selected":"cloudflare","custom":[{"id":"cloudflare","name":"Fake","primary":"192.168.1.1"}]}"""
        val result = DnsSettingsCodec.decode(raw)
        assertTrue(result.custom.isEmpty()); assertEquals("1.1.1.1", result.active.primary.address)
    }
}
