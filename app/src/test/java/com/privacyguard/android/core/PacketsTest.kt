package com.privacyguard.android.core

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.Random

internal fun dnsQuery(domain: String = "ads.example.com", type: Int = 1, id: Int = 123): ByteArray {
    val data = java.io.ByteArrayOutputStream()
    val header = ByteArray(12); Packets.put16(header, 0, id); Packets.put16(header, 2, 0x0100); Packets.put16(header, 4, 1)
    data.write(header)
    domain.split('.').forEach { label -> data.write(label.length); data.write(label.toByteArray(Charsets.US_ASCII)) }
    data.write(0); data.write(byteArrayOf((type ushr 8).toByte(), type.toByte(), 0, 1))
    return data.toByteArray()
}

internal fun udpQuery(payload: ByteArray, version: Int = 4): UdpPacket = UdpPacket(version,
    InetAddress.getByName(if (version == 4) "10.111.0.1" else "fd42:7072:6976::1").address,
    InetAddress.getByName(if (version == 4) "10.111.0.2" else "fd42:7072:6976::2").address, 42000, 53, payload)

internal fun udpWire(query: UdpPacket): ByteArray = Packets.udpReply(query.copy(
    source = query.destination, destination = query.source, sourcePort = query.destinationPort, destinationPort = query.sourcePort), query.payload)

class PacketsTest {
    @Test fun responseNameMatchingIsCaseInsensitiveButIdTypeClassAndLabelsAreStillStrict() {
        val query = dnsQuery("Api.Example.COM", type = 28)
        val answer = Packets.dnsError(query, Packets.question(query)!!, 0)
        val lower = answer.copyOf().apply { for (i in 12 until size - 4) if (this[i].toInt() in 65..90) this[i] = (this[i] + 32).toByte() }
        assertTrue(Packets.validResponse(query, lower))
        assertEquals(DnsResponseIssue.TRANSACTION_ID, Packets.responseIssue(query, lower.copyOf().apply { this[0] = 99 }))
        assertEquals(DnsResponseIssue.QUESTION_NAME, Packets.responseIssue(query, lower.copyOf().apply { this[13] = 'z'.code.toByte() }))
        assertEquals(DnsResponseIssue.QUESTION_TYPE_CLASS, Packets.responseIssue(query, lower.copyOf().apply { this[lastIndex - 2] = 1 }))
        assertEquals(DnsResponseIssue.QUESTION_TYPE_CLASS, Packets.responseIssue(query, lower.copyOf().apply { this[lastIndex] = 2 }))
        assertEquals(DnsResponseIssue.QUESTION_COUNT, Packets.responseIssue(query, lower.copyOf().apply { Packets.put16(this, 4, 0) }))
    }
    @Test fun udpRoundTripsBothFamiliesAndValidatesChecksums() {
        for (version in listOf(4, 6)) {
            val query = udpQuery(dnsQuery(), version)
            val wire = udpWire(query)
            val parsed = Packets.parseUdp(wire)!!
            assertEquals(version, parsed.version); assertEquals(42000, parsed.sourcePort); assertEquals(53, parsed.destinationPort)
            assertArrayEquals(query.source, parsed.source); assertArrayEquals(query.payload, parsed.payload)
            val response = Packets.parseUdp(Packets.udpReply(parsed, dnsQuery("api.example.com")))!!
            assertEquals(53, response.sourcePort); assertEquals(42000, response.destinationPort)
            assertArrayEquals(query.source, response.destination)
            val damaged = wire.copyOf(); damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
            assertNull(Packets.parseUdp(damaged))
            assertNull(Packets.parseUdp(wire.copyOf(wire.size - 1)))
            val zeroChecksum = wire.copyOf(); Packets.put16(zeroChecksum, if (version == 4) 26 else 46, 0)
            if (version == 6) assertNull(Packets.parseUdp(zeroChecksum)) else assertNotNull(Packets.parseUdp(zeroChecksum))
        }
    }

    @Test fun fragmentsExtensionHeadersAndBrokenLengthsAreRejected() {
        val ipv4 = udpWire(udpQuery(dnsQuery()))
        Packets.put16(ipv4, 6, 0x2000); Packets.put16(ipv4, 10, 0); Packets.put16(ipv4, 10, Packets.checksum(ipv4.copyOfRange(0, 20)))
        assertNull(Packets.parseUdp(ipv4))
        val ipv6 = udpWire(udpQuery(dnsQuery(), 6)); ipv6[6] = 0
        assertNull(Packets.parseUdp(ipv6))
        val badLength = udpWire(udpQuery(dnsQuery())); Packets.put16(badLength, 24, 7)
        assertNull(Packets.parseUdp(badLength))
    }

    @Test fun dnsQuestionValidationAndErrorReplies() {
        val query = dnsQuery(); val question = Packets.question(query)!!
        assertEquals("ads.example.com", question.domain); assertEquals(1, question.type)
        val blocked = Packets.dnsError(query, question, 3)
        assertTrue(Packets.validResponse(query, blocked)); assertEquals(3, Packets.u16(blocked, 2) and 15)
        assertEquals(0, Packets.u16(blocked, 6)); assertEquals(0, Packets.u16(blocked, 10))
        assertNull(Packets.question(query.copyOf(query.size - 1)))
        assertNull(Packets.question(query.copyOf().apply { this[12] = 0xc0.toByte() }))
        assertNull(Packets.question(query.copyOf().apply { Packets.put16(this, 4, 2) }))
        assertNull(Packets.question(query.copyOf().apply { this[lastIndex] = 3 }))
        assertNull(Packets.question(blocked))
        assertFalse(Packets.validResponse(byteArrayOf(), blocked))
        assertFalse(Packets.validResponse(query, blocked.copyOf().apply { this[1] = 99 }))
        assertFalse(Packets.validResponse(query, blocked.copyOf().apply { this[13] = 'b'.code.toByte() }))
        assertFalse(Packets.validResponse(query, blocked.copyOf().apply { Packets.put16(this, 2, 0x8800) }))
    }

    @Test fun malformedInputNeverThrows() {
        val random = Random(7)
        repeat(15000) {
            val bytes = ByteArray(random.nextInt(512)); random.nextBytes(bytes)
            Packets.parseUdp(bytes); Packets.question(bytes); TcpDns.parse(bytes); Packets.validResponse(bytes, bytes)
        }
    }
}
