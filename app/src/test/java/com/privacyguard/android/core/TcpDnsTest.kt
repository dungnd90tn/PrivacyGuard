package com.privacyguard.android.core

import org.junit.Assert.*
import org.junit.Test

class TcpDnsTest {
    private fun packet(version: Int = 4, sequence: Long = 100, ack: Long = 0, flags: Int = TcpDns.SYN, payload: ByteArray = byteArrayOf()): TcpPacket {
        val udp = udpQuery(byteArrayOf(), version)
        return TcpPacket(version, udp.source, udp.destination, 42000, 53, sequence, ack, flags, payload)
    }
    private fun framed(query: ByteArray) = ByteArray(query.size + 2).apply { Packets.put16(this, 0, query.size); query.copyInto(this, 2) }

    @Test fun handshakeSplitDnsReplyAndRetransmissionWorkOnBothFamilies() {
        for (version in listOf(4, 6)) {
            val endpoint = TcpDns(); var resolutions = 0
            val resolve: (ByteArray) -> DnsResult? = { query -> resolutions++; DnsFilter.resolve(query, null, Policy()) { null } }
            val syn = packet(version)
            val response = endpoint.accept(syn, 0, resolve)
            val synAck = TcpDns.parse(response.packets.single())!!
            assertEquals(TcpDns.SYN or TcpDns.ACK, synAck.flags); assertEquals(101L, synAck.acknowledgment)
            assertEquals(53, synAck.sourcePort); assertEquals(42000, synAck.destinationPort)
            assertArrayEquals(syn.source, synAck.destination)
            val bytes = framed(dnsQuery())
            val first = packet(version, 101, (synAck.sequence + 1) and 0xffffffffL, TcpDns.ACK or TcpDns.PSH, bytes.copyOfRange(0, 5))
            assertEquals(0, endpoint.accept(first, 1, resolve).results.size)
            val second = packet(version, 106, (synAck.sequence + 1) and 0xffffffffL, TcpDns.ACK or TcpDns.PSH, bytes.copyOfRange(5, bytes.size))
            val reply = endpoint.accept(second, 2, resolve)
            assertEquals(1, resolutions); assertEquals(Outcome.BLOCKED, reply.results.single().outcome)
            val tcpResponse = TcpDns.parse(reply.packets.single())!!
            val dns = tcpResponse.payload.copyOfRange(2, tcpResponse.payload.size)
            assertTrue(Packets.validResponse(dnsQuery(), dns)); assertEquals(3, Packets.u16(dns, 2) and 15)
            val repeated = endpoint.accept(second, 3, resolve)
            assertEquals(1, resolutions); assertTrue(repeated.results.isEmpty())
            assertArrayEquals(reply.packets.single(), repeated.packets.single())
            val fin = packet(version, 101 + bytes.size.toLong(), (tcpResponse.sequence + tcpResponse.payload.size) and 0xffffffffL, TcpDns.ACK or TcpDns.FIN)
            assertEquals(TcpDns.ACK or TcpDns.FIN, TcpDns.parse(endpoint.accept(fin, 4, resolve).packets.single())!!.flags)
        }
    }

    @Test fun pipelinedQueriesAndBoundedConnections() {
        val endpoint = TcpDns()
        val resolve: (ByteArray) -> DnsResult? = { query -> DnsFilter.resolve(query, null, Policy()) { Packets.dnsError(it, Packets.question(it)!!, 0) } }
        val synAck = TcpDns.parse(endpoint.accept(packet(), 0, resolve).packets.single())!!
        val response = endpoint.accept(packet(sequence = 101, ack = (synAck.sequence + 1) and 0xffffffffL, flags = TcpDns.ACK,
            payload = framed(dnsQuery()) + framed(dnsQuery("api.example.com"))), 1, resolve)
        assertEquals(listOf(Outcome.BLOCKED, Outcome.FORWARDED), response.results.map { it.outcome })
        val bad = response.packets.first().copyOf(); bad[bad.lastIndex] = (bad.last().toInt() xor 1).toByte()
        assertNull(TcpDns.parse(bad))
        repeat(63) { endpoint.accept(packet().copy(sourcePort = 43000 + it), 2, resolve) }
        val rejected = TcpDns.parse(endpoint.accept(packet().copy(sourcePort = 60000), 2, resolve).packets.single())!!
        assertEquals(TcpDns.RST or TcpDns.ACK, rejected.flags)
        val expired = TcpDns.parse(endpoint.accept(packet().copy(sourcePort = 60000), 60003, resolve).packets.single())!!
        assertEquals(TcpDns.SYN or TcpDns.ACK, expired.flags)
    }

    @Test fun invalidFramingAndUnexpectedSequenceDoNotResolveDns() {
        val endpoint = TcpDns()
        val resolve: (ByteArray) -> DnsResult? = { fail("Must not resolve malformed or out of sequence data"); null }
        val synAck = TcpDns.parse(endpoint.accept(packet(), 0, resolve).packets.single())!!
        val outOfOrder = endpoint.accept(packet(sequence = 200, ack = (synAck.sequence + 1) and 0xffffffffL, flags = TcpDns.ACK, payload = framed(dnsQuery())), 1, resolve)
        assertEquals(101L, TcpDns.parse(outOfOrder.packets.single())!!.acknowledgment)
        val invalid = endpoint.accept(packet(sequence = 101, ack = (synAck.sequence + 1) and 0xffffffffL, flags = TcpDns.ACK, payload = byteArrayOf(0, 1, 3)), 2, resolve)
        assertEquals(TcpDns.RST or TcpDns.ACK, TcpDns.parse(invalid.packets.single())!!.flags)
    }
}
