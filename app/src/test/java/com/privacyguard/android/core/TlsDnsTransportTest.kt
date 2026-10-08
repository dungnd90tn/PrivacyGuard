package com.privacyguard.android.core

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

class TlsDnsTransportTest {
    private val query = dnsQuery("api.example.com")
    private val answer = Packets.dnsError(query, Packets.question(query)!!, 0)
    // Public test key/certificate, never used by the application. Production uses platform trust roots.
    private val keys = KeyStore.getInstance("PKCS12").apply {
        TlsDnsTransportTest::class.java.getResourceAsStream("/dns-test.p12")!!.use { load(it, "privacyguard-test".toCharArray()) }
    }
    private val serverContext = SSLContext.getInstance("TLS").apply {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "privacyguard-test".toCharArray()) }
        init(managers.keyManagers, null, null)
    }
    private val trustedClient = SSLContext.getInstance("TLS").apply {
        val trust = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("test", keys.getCertificate("dns-test")) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        init(null, managers.trustManagers, null)
    }.socketFactory

    @Test fun actualTlsAuthenticatesNameSendsSniAndFramesDnsOnlyAfterProtection() {
        val work = Executors.newSingleThreadExecutor()
        try { (serverContext.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).use { server ->
            server.soTimeout = 4000
            val received = work.submit<ByteArray> {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 4000; socket.startHandshake()
                    val sni = (socket.session as ExtendedSSLSession).requestedServerNames.single() as SNIHostName
                    assertEquals("dns.test.example", sni.asciiName)
                    val input = DataInputStream(socket.inputStream); val bytes = ByteArray(input.readUnsignedShort()); input.readFully(bytes)
                    val output = DataOutputStream(socket.outputStream); output.writeShort(answer.size); output.write(answer); output.flush(); bytes
                }
            }
            var protected = false; var registered = 0; var unregistered = 0
            val transport = SocketDnsTransport({ fail("TLS must never use UDP"); false }, { socket -> assertFalse(socket.isConnected); protected = true; true },
                register = { registered++ }, unregister = { unregistered++ }, timeoutMs = 3000, tlsFactory = trustedClient)
            val endpoint = DnsEndpoint("127.0.0.1", server.localPort, "dns.test.example")
            assertArrayEquals(answer, DnsForwarder.resolve(query, listOf(endpoint), transport::exchange))
            assertArrayEquals(query, received.get(5, TimeUnit.SECONDS)); assertTrue(protected)
            assertEquals(1, registered); assertEquals(1, unregistered)
        } } finally { work.shutdownNow() }
    }
    @Test fun wrongHostnameAndUntrustedCertificateSendNoDnsApplicationData() {
        rejectedTls("wrong.test.example", trustedClient)
        rejectedTls("dns.test.example", SSLSocketFactory.getDefault() as SSLSocketFactory)
    }
    private fun rejectedTls(name: String, factory: SSLSocketFactory) {
        val work = Executors.newSingleThreadExecutor(); val receivedDns = AtomicBoolean(false)
        try { (serverContext.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).use { server ->
            server.soTimeout = 4000
            val received = work.submit {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 4000
                    runCatching { socket.startHandshake(); if (socket.inputStream.read() >= 0) receivedDns.set(true) }
                }
            }
            val transport = SocketDnsTransport({ fail("No plaintext fallback"); false }, { true }, timeoutMs = 3000, tlsFactory = factory)
            assertNull(DnsForwarder.resolve(query, listOf(DnsEndpoint("127.0.0.1", server.localPort, name)), transport::exchange))
            received.get(5, TimeUnit.SECONDS); assertFalse(receivedDns.get())
        } } finally { work.shutdownNow() }
    }
    @Test fun plaintextAndTlsEndpointsCannotBeMixedOrSentUnencryptedTo853() {
        val transport = SocketDnsTransport({ fail("Must reject before opening socket"); false }, { fail("Must reject before connecting"); false })
        assertThrows(IllegalArgumentException::class.java) { transport.exchange(query, DnsEndpoint("127.0.0.1", 853), DnsProtocol.TCP) }
        assertThrows(IllegalArgumentException::class.java) { transport.exchange(query, DnsEndpoint("127.0.0.1", 853, "dns.test.example"), DnsProtocol.UDP) }
    }
}
