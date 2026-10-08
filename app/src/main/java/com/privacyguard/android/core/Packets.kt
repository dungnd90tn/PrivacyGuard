package com.privacyguard.android.core

data class UdpPacket(val version: Int, val source: ByteArray, val destination: ByteArray, val sourcePort: Int, val destinationPort: Int, val payload: ByteArray)
data class DnsQuestion(val domain: String, val type: Int, val end: Int)

object Packets {
    fun u16(data: ByteArray, offset: Int) = ((data[offset].toInt() and 255) shl 8) or (data[offset + 1].toInt() and 255)
    fun put16(data: ByteArray, offset: Int, value: Int) { data[offset] = (value ushr 8).toByte(); data[offset + 1] = value.toByte() }
    fun checksum(data: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i < data.size) { sum += ((data[i].toInt() and 255) shl 8) + if (i + 1 < data.size) (data[i + 1].toInt() and 255) else 0; i += 2 }
        while ((sum ushr 16) != 0L) sum = (sum and 65535) + (sum ushr 16)
        return sum.inv().toInt() and 65535
    }
    fun parseUdp(data: ByteArray): UdpPacket? {
        if (data.isEmpty()) return null
        val version = (data[0].toInt() and 255) ushr 4
        val offset: Int; val end: Int; val source: ByteArray; val dest: ByteArray
        if (version == 4) {
            if (data.size < 20) return null
            offset = (data[0].toInt() and 15) * 4
            end = u16(data, 2)
            if (offset < 20 || end > data.size || end < offset + 8 || data[9].toInt() != 17 || u16(data, 6) and 0x3fff != 0) return null
            if (checksum(data.copyOfRange(0, offset)) != 0) return null
            source = data.copyOfRange(12, 16); dest = data.copyOfRange(16, 20)
        } else if (version == 6) {
            if (data.size < 48 || data[6].toInt() != 17) return null // No extension headers/fragments in this MVP.
            offset = 40; end = 40 + u16(data, 4)
            if (end > data.size || end < 48) return null
            source = data.copyOfRange(8, 24); dest = data.copyOfRange(24, 40)
        } else return null
        val length = u16(data, offset + 4)
        if (length < 8 || offset + length != end) return null
        val udp = data.copyOfRange(offset, end)
        val suppliedChecksum = u16(udp, 6)
        if ((version == 6 && suppliedChecksum == 0) || (suppliedChecksum != 0 && checksum(pseudo(version, source, dest, length) + udp) != 0)) return null
        return UdpPacket(version, source, dest, u16(data, offset), u16(data, offset + 2), data.copyOfRange(offset + 8, end))
    }
    private fun pseudo(version: Int, source: ByteArray, dest: ByteArray, length: Int): ByteArray {
        val bytes = ByteArray(if (version == 4) 12 else 40)
        source.copyInto(bytes); dest.copyInto(bytes, source.size)
        if (version == 4) { bytes[9] = 17; put16(bytes, 10, length) }
        else { put16(bytes, 34, length); bytes[39] = 17 }
        return bytes
    }
    fun udpReply(query: UdpPacket, payload: ByteArray): ByteArray {
        require(payload.size <= 65000)
        val udp = ByteArray(8 + payload.size)
        put16(udp, 0, query.destinationPort); put16(udp, 2, query.sourcePort); put16(udp, 4, udp.size)
        payload.copyInto(udp, 8)
        val check = checksum(pseudo(query.version, query.destination, query.source, udp.size) + udp)
        put16(udp, 6, if (check == 0) 65535 else check)
        val ip = ByteArray(if (query.version == 4) 20 else 40)
        if (query.version == 4) {
            ip[0] = 0x45; put16(ip, 2, ip.size + udp.size); ip[8] = 64; ip[9] = 17
            query.destination.copyInto(ip, 12); query.source.copyInto(ip, 16)
            put16(ip, 10, checksum(ip))
        } else {
            ip[0] = 0x60; put16(ip, 4, udp.size); ip[6] = 17; ip[7] = 64
            query.destination.copyInto(ip, 8); query.source.copyInto(ip, 24)
        }
        return ip + udp
    }
    fun question(data: ByteArray): DnsQuestion? {
        if (data.size < 17 || u16(data, 2) and 0xf800 != 0 || u16(data, 4) != 1 || u16(data, 6) != 0 || u16(data, 8) != 0) return null
        var at = 12
        var terminated = false
        val labels = mutableListOf<String>()
        while (at < data.size) {
            val length = data[at++].toInt() and 255
            if (length == 0) { terminated = true; break }
            if (length > 63 || at + length > data.size) return null // Reject compression in incoming single-question queries.
            val label = String(data, at, length, Charsets.US_ASCII)
            if (!label.matches(Regex("[A-Za-z0-9_-]+"))) return null
            labels += label; at += length
        }
        if (!terminated || at + 4 > data.size || labels.isEmpty() || u16(data, at + 2) != 1) return null
        val domain = labels.joinToString(".").lowercase(java.util.Locale.ROOT)
        if (domain.length > 253) return null
        return DnsQuestion(domain, u16(data, at), at + 4)
    }
    fun dnsError(query: ByteArray, question: DnsQuestion, code: Int): ByteArray {
        val reply = query.copyOf(question.end)
        put16(reply, 2, 0x8080 or (u16(query, 2) and 0x0100) or code)
        for (i in listOf(6, 8, 10)) put16(reply, i, 0)
        return reply
    }
    fun validResponse(query: ByteArray, response: ByteArray): Boolean {
        return responseIssue(query, response) == null
    }
    fun responseIssue(query: ByteArray, response: ByteArray): DnsResponseIssue? {
        val q = question(query) ?: return DnsResponseIssue.INVALID_QUERY
        if (response.size > 65000) return DnsResponseIssue.TOO_LARGE
        if (response.size < 12) return DnsResponseIssue.SHORT_HEADER
        if (u16(response, 0) != u16(query, 0)) return DnsResponseIssue.TRANSACTION_ID
        if (u16(response, 2) and 0xf800 != 0x8000) return DnsResponseIssue.FLAGS
        if (u16(response, 4) != 1) return DnsResponseIssue.QUESTION_COUNT
        if (response.size < q.end) return DnsResponseIssue.SHORT_QUESTION
        // ASCII case differences in QNAME are equivalent (RFC 4343); lengths/terminator remain exact.
        fun fold(value: Byte): Int = (value.toInt() and 255).let { if (it in 65..90) it + 32 else it }
        for (i in 12 until q.end - 4) if (fold(query[i]) != fold(response[i])) return DnsResponseIssue.QUESTION_NAME
        for (i in q.end - 4 until q.end) if (query[i] != response[i]) return DnsResponseIssue.QUESTION_TYPE_CLASS
        return null
    }
}
