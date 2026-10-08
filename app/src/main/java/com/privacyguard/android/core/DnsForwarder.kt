package com.privacyguard.android.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class DnsProtocol { UDP, TCP, TLS }

object DnsForwarder {
    /** Encrypted mode never retries over plaintext, even when configuration or TLS fails. */
    fun resolve(query: ByteArray, settings: DnsSettings, exchange: (ByteArray, DnsEndpoint, DnsProtocol) -> ByteArray?): ByteArray? {
        val endpoints = runCatching { settings.upstream }.getOrNull() ?: return null
        return resolve(query, endpoints, exchange)
    }
    /** Only the selected profile's endpoints are tried. No silent fallback to a different provider. */
    fun resolve(query: ByteArray, endpoints: List<DnsEndpoint>, exchange: (ByteArray, DnsEndpoint, DnsProtocol) -> ByteArray?): ByteArray? {
        var serverError: ByteArray? = null
        for (endpoint in endpoints) {
            val tls = endpoint.tlsName != null
            val udp = runCatching { exchange(query, endpoint, if (tls) DnsProtocol.TLS else DnsProtocol.UDP) }.getOrNull()
            if (!valid(query, udp)) continue
            if (tls && Packets.u16(udp!!, 2) and 0x0200 != 0) continue
            val response = if (!tls && Packets.u16(udp!!, 2) and 0x0200 != 0)
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
    private val timeoutMs: Int = 1500,
    private val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
) {
    fun exchange(query: ByteArray, endpoint: DnsEndpoint, protocol: DnsProtocol): ByteArray? {
        if (stopped()) return null
        require((protocol == DnsProtocol.TLS) == (endpoint.tlsName != null)) { "TLS endpoint/protocol mismatch" }
        require(protocol == DnsProtocol.TLS || endpoint.port != 853) { "Plain DNS must not be sent to TLS port 853" }
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
            DnsProtocol.TCP, DnsProtocol.TLS -> Socket().use { socket ->
                register(socket)
                try {
                    check(protectTcp(socket)) { "VPN socket protection failed" }
                    socket.connect(destination, timeoutMs); socket.soTimeout = timeoutMs
                    if (protocol == DnsProtocol.TLS) {
                        (tlsFactory.createSocket(socket, endpoint.tlsName!!, endpoint.port, true) as SSLSocket).use { tls ->
                            tls.soTimeout = timeoutMs
                            tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                            tls.sslParameters = tls.sslParameters.apply {
                                endpointIdentificationAlgorithm = "HTTPS"
                                serverNames = listOf(SNIHostName(endpoint.tlsName))
                            }
                            // Default platform trust roots + hostname authentication; no application data before validation.
                            tls.startHandshake()
                            framed(query, tls)
                        }
                    } else framed(query, socket)
                } finally { unregister(socket) }
            }
        }
    }
    private fun framed(query: ByteArray, socket: Socket): ByteArray? {
        val output = DataOutputStream(socket.getOutputStream())
        output.writeShort(query.size); output.write(query); output.flush()
        val input = DataInputStream(socket.getInputStream())
        val size = input.readUnsignedShort()
        return if (size !in 12..65000) null else ByteArray(size).also { input.readFully(it) }
    }
}
