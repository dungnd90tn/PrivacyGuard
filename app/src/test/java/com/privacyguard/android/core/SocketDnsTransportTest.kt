package com.privacyguard.android.core

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SocketDnsTransportTest {
    private val query = dnsQuery("api.example.com")
    private val answer = Packets.dnsError(query, Packets.question(query)!!, 0)
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun actualUdpUsesCustomPortAndProtectsBeforeSending() {
        val executor = Executors.newSingleThreadExecutor()
        try { DatagramSocket(InetSocketAddress(loopback, 0)).use { server ->
            server.soTimeout = 3000
            val expected = executor.submit<ByteArray> {
                val request = DatagramPacket(ByteArray(1024), 1024); server.receive(request)
                server.send(DatagramPacket(answer, answer.size, request.socketAddress)); request.data.copyOf(request.length)
            }
            var protected = false; var registered = 0; var unregistered = 0
            val transport = SocketDnsTransport({ socket -> assertFalse(socket.isConnected); protected = true; true }, { true },
                register = { registered++ }, unregister = { unregistered++ })
            assertArrayEquals(answer, transport.exchange(query, DnsEndpoint("127.0.0.1", server.localPort), DnsProtocol.UDP))
            assertArrayEquals(query, expected.get(4, TimeUnit.SECONDS)); assertTrue(protected)
            assertEquals(1, registered); assertEquals(1, unregistered)
        } } finally { executor.shutdownNow() }
    }
    @Test fun actualTruncatedUdpRetriesLengthPrefixedTcpOnTheCustomPort() {
        val executor = Executors.newFixedThreadPool(2)
        try { ServerSocket(0, 1, loopback).use { tcp ->
            tcp.soTimeout = 3000
            DatagramSocket(InetSocketAddress(loopback, tcp.localPort)).use { udp ->
                udp.soTimeout = 3000
                val udpWork = executor.submit {
                    val request = DatagramPacket(ByteArray(1024), 1024); udp.receive(request)
                    val truncated = answer.copyOf().apply { this[2] = (this[2].toInt() or 2).toByte() }
                    udp.send(DatagramPacket(truncated, truncated.size, request.socketAddress))
                }
                val tcpWork = executor.submit<ByteArray> {
                    tcp.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream()); val body = ByteArray(input.readUnsignedShort()); input.readFully(body)
                        val output = DataOutputStream(socket.getOutputStream()); output.writeShort(answer.size); output.write(answer); output.flush(); body
                    }
                }
                var udpProtected = false; var tcpProtected = false
                val transport = SocketDnsTransport({ udpProtected = true; true }, { socket -> assertFalse(socket.isConnected); tcpProtected = true; true })
                val result = DnsForwarder.resolve(query, listOf(DnsEndpoint("127.0.0.1", tcp.localPort)), transport::exchange)
                assertArrayEquals(answer, result); assertArrayEquals(query, tcpWork.get(4, TimeUnit.SECONDS)); udpWork.get(4, TimeUnit.SECONDS)
                assertTrue(udpProtected); assertTrue(tcpProtected)
            }
        } } finally { executor.shutdownNow() }
    }
    @Test fun deniedProtectionAndStoppedSessionsNeverSendRequests() {
        var calls = 0
        val transport = SocketDnsTransport({ calls++; false }, { calls++; false }, timeoutMs = 100)
        val endpoint = DnsEndpoint("127.0.0.1", 5353)
        assertThrows(IllegalStateException::class.java) { transport.exchange(query, endpoint, DnsProtocol.UDP) }
        assertThrows(IllegalStateException::class.java) { transport.exchange(query, endpoint, DnsProtocol.TCP) }
        assertEquals(2, calls)
        val stopped = SocketDnsTransport({ fail("Must not open a stopped session"); true }, { true }, stopped = { true })
        assertNull(stopped.exchange(query, endpoint, DnsProtocol.UDP))
    }
}
