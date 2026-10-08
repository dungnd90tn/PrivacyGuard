package com.privacyguard.android.core

import java.io.ByteArrayOutputStream

data class TcpPacket(
    val version: Int, val source: ByteArray, val destination: ByteArray,
    val sourcePort: Int, val destinationPort: Int, val sequence: Long,
    val acknowledgment: Long, val flags: Int, val payload: ByteArray
)

/** A bounded TCP endpoint for DNS at the VPN's two local addresses, not a general TCP proxy. */
class TcpDns {
    private data class Connection(
        var clientNext: Long, var serverNext: Long, val synReply: ByteArray,
        val input: ByteArrayOutputStream = ByteArrayOutputStream(),
        var touched: Long, var lastReply: List<ByteArray> = emptyList(),
        var lastSequence: Long = -1, var closed: Boolean = false
    )
    data class Reply(val packets: List<ByteArray>, val results: List<DnsResult> = emptyList())
    private val connections = linkedMapOf<String, Connection>()

    @Synchronized
    fun accept(packet: TcpPacket, now: Long, resolve: (ByteArray) -> DnsResult?): Reply {
        connections.entries.removeAll { now - it.value.touched > 60_000 }
        val key = "${packet.version}:${packet.source.joinToString(",")}:${packet.sourcePort}:${packet.destination.joinToString(",")}:${packet.destinationPort}"
        if (packet.flags and RST != 0) { connections.remove(key); return Reply(emptyList()) }
        if (packet.flags and SYN != 0) {
            connections[key]?.let { it.touched = now; return Reply(listOf(it.synReply)) }
            if (connections.size >= 64) return Reply(listOf(reply(packet, 0, next(packet.sequence, 1), RST or ACK)))
            val sequence = java.security.SecureRandom().nextInt().toLong() and MASK
            val syn = reply(packet, sequence, next(packet.sequence, 1), SYN or ACK)
            connections[key] = Connection(next(packet.sequence, 1), next(sequence, 1), syn, touched = now)
            return Reply(listOf(syn))
        }
        val connection = connections[key] ?: return Reply(listOf(reply(packet, packet.acknowledgment, 0, RST)))
        connection.touched = now
        if (packet.sequence != connection.clientNext) {
            return Reply(if (packet.sequence == connection.lastSequence) connection.lastReply
                else listOf(reply(packet, connection.serverNext, connection.clientNext, ACK)))
        }
        if (connection.closed || packet.flags and ACK == 0 || packet.acknowledgment != connection.serverNext) {
            return Reply(emptyList())
        }
        if (packet.payload.isEmpty() && packet.flags and FIN == 0) return Reply(emptyList())
        if (connection.input.size() + packet.payload.size > 65537) {
            connections.remove(key)
            return Reply(listOf(reply(packet, connection.serverNext, connection.clientNext, RST or ACK)))
        }
        connection.lastSequence = packet.sequence
        connection.input.write(packet.payload)
        connection.clientNext = next(connection.clientNext, packet.payload.size)
        val frames = mutableListOf<ByteArray>()
        val results = mutableListOf<DnsResult>()
        var pending = connection.input.toByteArray()
        while (pending.size >= 2) {
            val length = Packets.u16(pending, 0)
            if (length < 17) {
                connections.remove(key)
                return Reply(listOf(reply(packet, connection.serverNext, connection.clientNext, RST or ACK)))
            }
            if (pending.size < length + 2) break
            val result = resolve(pending.copyOfRange(2, length + 2))
            if (result == null) {
                connections.remove(key)
                return Reply(listOf(reply(packet, connection.serverNext, connection.clientNext, RST or ACK)))
            }
            val response = ByteArray(2 + result.response.size)
            Packets.put16(response, 0, result.response.size)
            result.response.copyInto(response, 2)
            // Use small segments even though the DNS-only TUN can accept large UDP datagrams.
            response.asList().chunked(1200).forEach { chunk ->
                val bytes = chunk.toByteArray()
                frames += reply(packet, connection.serverNext, connection.clientNext, ACK or PSH, bytes)
                connection.serverNext = next(connection.serverNext, bytes.size)
            }
            results += result
            pending = pending.copyOfRange(length + 2, pending.size)
        }
        connection.input.reset()
        connection.input.write(pending)
        if (packet.flags and FIN != 0) {
            connection.clientNext = next(connection.clientNext, 1)
            frames += reply(packet, connection.serverNext, connection.clientNext, FIN or ACK)
            connection.serverNext = next(connection.serverNext, 1)
            connection.closed = true
        } else if (frames.isEmpty()) frames += reply(packet, connection.serverNext, connection.clientNext, ACK)
        connection.lastReply = frames
        return Reply(frames, results)
    }

    companion object {
        const val FIN = 1
        const val SYN = 2
        const val RST = 4
        const val PSH = 8
        const val ACK = 16
        private const val MASK = 0xffffffffL
        private fun next(value: Long, size: Int) = (value + size) and MASK
        private fun u32(data: ByteArray, at: Int) = (Packets.u16(data, at).toLong() shl 16) or Packets.u16(data, at + 2).toLong()
        private fun put32(data: ByteArray, at: Int, value: Long) {
            Packets.put16(data, at, (value ushr 16).toInt()); Packets.put16(data, at + 2, value.toInt())
        }
        private fun pseudo(version: Int, source: ByteArray, destination: ByteArray, length: Int): ByteArray {
            val data = ByteArray(if (version == 4) 12 else 40)
            source.copyInto(data); destination.copyInto(data, source.size)
            if (version == 4) { data[9] = 6; Packets.put16(data, 10, length) }
            else { Packets.put16(data, 34, length); data[39] = 6 }
            return data
        }
        fun parse(data: ByteArray): TcpPacket? {
            if (data.isEmpty()) return null
            val version = (data[0].toInt() and 255) ushr 4
            val offset: Int; val end: Int; val source: ByteArray; val destination: ByteArray
            if (version == 4) {
                if (data.size < 40) return null
                offset = (data[0].toInt() and 15) * 4; end = Packets.u16(data, 2)
                if (offset < 20 || end > data.size || end < offset + 20 || data[9].toInt() != 6 || Packets.u16(data, 6) and 0x3fff != 0) return null
                if (Packets.checksum(data.copyOfRange(0, offset)) != 0) return null
                source = data.copyOfRange(12, 16); destination = data.copyOfRange(16, 20)
            } else if (version == 6) {
                if (data.size < 60 || data[6].toInt() != 6) return null
                offset = 40; end = 40 + Packets.u16(data, 4)
                if (end > data.size || end < 60) return null
                source = data.copyOfRange(8, 24); destination = data.copyOfRange(24, 40)
            } else return null
            val header = ((data[offset + 12].toInt() and 255) ushr 4) * 4
            if (header < 20 || offset + header > end) return null
            if (Packets.checksum(pseudo(version, source, destination, end - offset) + data.copyOfRange(offset, end)) != 0) return null
            return TcpPacket(version, source, destination, Packets.u16(data, offset), Packets.u16(data, offset + 2),
                u32(data, offset + 4), u32(data, offset + 8), data[offset + 13].toInt() and 255, data.copyOfRange(offset + header, end))
        }
        fun reply(query: TcpPacket, sequence: Long, acknowledgment: Long, flags: Int, payload: ByteArray = byteArrayOf()): ByteArray {
            require(payload.size <= 65000)
            val tcp = ByteArray(20 + payload.size)
            Packets.put16(tcp, 0, query.destinationPort); Packets.put16(tcp, 2, query.sourcePort)
            put32(tcp, 4, sequence); put32(tcp, 8, acknowledgment)
            tcp[12] = 0x50; tcp[13] = flags.toByte(); Packets.put16(tcp, 14, 65535)
            payload.copyInto(tcp, 20)
            Packets.put16(tcp, 16, Packets.checksum(pseudo(query.version, query.destination, query.source, tcp.size) + tcp))
            val ip = ByteArray(if (query.version == 4) 20 else 40)
            if (query.version == 4) {
                ip[0] = 0x45; Packets.put16(ip, 2, ip.size + tcp.size); ip[8] = 64; ip[9] = 6
                query.destination.copyInto(ip, 12); query.source.copyInto(ip, 16); Packets.put16(ip, 10, Packets.checksum(ip))
            } else {
                ip[0] = 0x60; Packets.put16(ip, 4, tcp.size); ip[6] = 6; ip[7] = 64
                query.destination.copyInto(ip, 8); query.source.copyInto(ip, 24)
            }
            return ip + tcp
        }
    }
}
