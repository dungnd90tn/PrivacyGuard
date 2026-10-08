package com.privacyguard.android.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

enum class DnsProtocol { UDP, TCP }

object DnsForwarder {
    /** Only the selected profile's endpoints are tried. No silent fallback to a different provider. */
    fun resolve(query: ByteArray, endpoints: List<DnsEndpoint>, exchange: (ByteArray, DnsEndpoint, DnsProtocol) -> ByteArray?): ByteArray? {
        var serverError: ByteArray? = null
        for (endpoint in endpoints) {
            val udp = runCatching { exchange(query, endpoint, DnsProtocol.UDP) }.getOrNull()
            if (!valid(query, udp)) continue
            val response = if (Packets.u16(udp!!, 2) and 0x0200 != 0)
                runCatching { exchange(query, endpoint, DnsProtocol.TCP) }.getOrNull() else udp
            if (!valid(query, response) || Packets.u16(response!!, 2) and 0x0200 != 0) continue
            val code = Packets.u16(response, 2) and 15
            // NXDOMAIN is a genuine upstream answer, not evidence of a PrivacyGuard-enforced block.
            if (code == 0 || code == 3) return response
            serverError = response
        }
        return serverError
    }
    private fun valid(query: ByteArray, response: ByteArray?) = response != null && response.size <= 65000 && Packets.validResponse(query, response)
}

/** Network I/O shared by JVM integration tests and the VPN; both protocols must be protected first. */
class SocketDnsTransport(
    private val protectUdp: (DatagramSocket) -> Boolean,
    private val protectTcp: (Socket) -> Boolean,
    private val register: (AutoCloseable) -> Unit = {},
    private val unregister: (AutoCloseable) -> Unit = {},
    private val stopped: () -> Boolean = { false },
    private val timeoutMs: Int = 1500
) {
    fun exchange(query: ByteArray, endpoint: DnsEndpoint, protocol: DnsProtocol): ByteArray? {
        if (stopped()) return null
        val destination = InetSocketAddress(InetAddress.getByAddress(endpoint.bytes), endpoint.port)
        return when (protocol) {
            DnsProtocol.UDP -> DatagramSocket().use { socket ->
                register(socket)
                try {
                    check(protectUdp(socket)) { "VPN socket protection failed" }
                    socket.connect(destination); socket.soTimeout = timeoutMs
                    socket.send(DatagramPacket(query, query.size))
                    val response = DatagramPacket(ByteArray(65507), 65507)
                    socket.receive(response); response.data.copyOf(response.length)
                } finally { unregister(socket) }
            }
            DnsProtocol.TCP -> Socket().use { socket ->
                register(socket)
                try {
                    check(protectTcp(socket)) { "VPN socket protection failed" }
                    socket.connect(destination, timeoutMs); socket.soTimeout = timeoutMs
                    val output = DataOutputStream(socket.getOutputStream())
                    output.writeShort(query.size); output.write(query); output.flush()
                    val input = DataInputStream(socket.getInputStream())
                    val size = input.readUnsignedShort()
                    if (size !in 12..65000) null else ByteArray(size).also { input.readFully(it) }
                } finally { unregister(socket) }
            }
        }
    }
}
