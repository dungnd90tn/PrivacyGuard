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
        return resolveDetailed(query, settings, exchange).response
    }
    fun resolveDetailed(query: ByteArray, settings: DnsSettings, exchange: (ByteArray, DnsEndpoint, DnsProtocol) -> ByteArray?): DnsUpstreamResult {
        val endpoints = try { settings.upstream } catch (error: Exception) {
            return DnsUpstreamResult(failures = listOf(DnsFailure(DnsFailureKind.CONFIGURATION, DnsStage.CONFIGURATION,
                protocol = if (settings.mode == DnsMode.TLS) DnsProtocol.TLS else DnsProtocol.UDP, exceptionType = error.javaClass.simpleName)))
        }
        return resolveDetailed(query, endpoints, exchange)
    }
    /** Only the selected profile's endpoints are tried. No silent fallback to a different provider. */
    fun resolve(query: ByteArray, endpoints: List<DnsEndpoint>, exchange: (ByteArray, DnsEndpoint, DnsProtocol) -> ByteArray?): ByteArray? {
        return resolveDetailed(query, endpoints, exchange).response
    }
    fun resolveDetailed(query: ByteArray, endpoints: List<DnsEndpoint>, exchange: (ByteArray, DnsEndpoint, DnsProtocol) -> ByteArray?): DnsUpstreamResult {
        var serverError: ByteArray? = null
        val failures = mutableListOf<DnsFailure>()
        fun attempt(endpoint: DnsEndpoint, protocol: DnsProtocol): ByteArray? {
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000
            val response = try { exchange(query, endpoint, protocol) } catch (error: Exception) {
                failures += DnsDiagnostics.failure(error, endpoint, protocol, elapsed()); return null
            }
            if (response == null) {
                failures += DnsFailure(DnsFailureKind.NO_RESPONSE, DnsStage.READ, endpoint.display, protocol, endpoint.tlsName, elapsed())
                return null
            }
            val issue = Packets.responseIssue(query, response)
            if (issue != null) {
                failures += DnsFailure(DnsFailureKind.INVALID_RESPONSE, DnsStage.VALIDATE, endpoint.display, protocol, endpoint.tlsName,
                    elapsed(), validation = issue, responseBytes = response.size)
                return null
            }
            return response
        }
        for (endpoint in endpoints) {
            val tls = endpoint.tlsName != null
            var protocol = if (tls) DnsProtocol.TLS else DnsProtocol.UDP
            var response = attempt(endpoint, protocol) ?: continue
            if (Packets.u16(response, 2) and 0x0200 != 0) {
                failures += DnsFailure(DnsFailureKind.TRUNCATED_RESPONSE, DnsStage.VALIDATE, endpoint.display, protocol, endpoint.tlsName)
                if (tls) continue
                protocol = DnsProtocol.TCP
                response = attempt(endpoint, protocol) ?: continue
                if (Packets.u16(response, 2) and 0x0200 != 0) {
                    failures += DnsFailure(DnsFailureKind.TRUNCATED_RESPONSE, DnsStage.VALIDATE, endpoint.display, DnsProtocol.TCP)
                    continue
                }
            }
            val code = Packets.u16(response, 2) and 15
            // NXDOMAIN is a genuine upstream answer, not evidence of a PrivacyGuard-enforced block.
            if (code == 0 || code == 3) return DnsUpstreamResult(response, failures.take(4))
            failures += DnsFailure(DnsFailureKind.SERVER_ERROR, DnsStage.VALIDATE, endpoint.display, protocol, endpoint.tlsName, serverCode = code)
            serverError = response
        }
        return DnsUpstreamResult(serverError, failures.take(4))
    }
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
        var stage = DnsStage.PROTECT
        try { return when (protocol) {
            DnsProtocol.UDP -> DatagramSocket().use { socket ->
                register(socket)
                try {
                    if (!protectUdp(socket)) throw DnsTransportException(DnsFailureKind.VPN_PROTECTION, stage, timeoutMs)
                    stage = DnsStage.CONNECT
                    socket.connect(destination); socket.soTimeout = timeoutMs
                    stage = DnsStage.WRITE
                    socket.send(DatagramPacket(query, query.size))
                    val response = DatagramPacket(ByteArray(65507), 65507)
                    stage = DnsStage.READ
                    socket.receive(response); response.data.copyOf(response.length)
                } finally { unregister(socket) }
            }
            DnsProtocol.TCP, DnsProtocol.TLS -> Socket().use { socket ->
                register(socket)
                try {
                    // Android Socket() allocates its native descriptor lazily. bind creates it without
                    // sending traffic, so VpnService.protect(Socket) receives a valid descriptor.
                    socket.bind(InetSocketAddress(InetAddress.getByAddress(ByteArray(endpoint.bytes.size)), 0))
                    if (!protectTcp(socket)) throw DnsTransportException(DnsFailureKind.VPN_PROTECTION, stage, timeoutMs)
                    stage = DnsStage.CONNECT
                    socket.connect(destination, timeoutMs); socket.soTimeout = timeoutMs
                    if (protocol == DnsProtocol.TLS) {
                        stage = DnsStage.TLS_HANDSHAKE
                        (tlsFactory.createSocket(socket, endpoint.tlsName!!, endpoint.port, true) as SSLSocket).use { tls ->
                            tls.soTimeout = timeoutMs
                            tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                            tls.sslParameters = tls.sslParameters.apply {
                                endpointIdentificationAlgorithm = "HTTPS"
                                serverNames = listOf(SNIHostName(endpoint.tlsName))
                            }
                            // Default platform trust roots + hostname authentication; no application data before validation.
                            tls.startHandshake()
                            framed(query, tls) { stage = it }
                        }
                    } else framed(query, socket) { stage = it }
                } finally { unregister(socket) }
            }
        } } catch (error: Exception) {
            if (error is DnsTransportException) throw error
            throw DnsTransportException(DnsDiagnostics.kind(error), stage, timeoutMs, error)
        }
    }
    private fun framed(query: ByteArray, socket: Socket, stage: (DnsStage) -> Unit): ByteArray {
        stage(DnsStage.WRITE)
        val output = DataOutputStream(socket.getOutputStream())
        output.writeShort(query.size); output.write(query); output.flush()
        stage(DnsStage.READ)
        val input = DataInputStream(socket.getInputStream())
        val size = input.readUnsignedShort()
        if (size !in 12..65000) throw DnsTransportException(DnsFailureKind.INVALID_FRAME_LENGTH, DnsStage.READ, timeoutMs)
        return ByteArray(size).also { input.readFully(it) }
    }
}
