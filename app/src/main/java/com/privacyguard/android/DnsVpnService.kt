package com.privacyguard.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.privacyguard.android.core.Action
import com.privacyguard.android.core.Category
import com.privacyguard.android.core.Decision
import com.privacyguard.android.core.DnsFilter
import com.privacyguard.android.core.DnsResult
import com.privacyguard.android.core.Outcome
import com.privacyguard.android.core.Packets
import com.privacyguard.android.core.TcpDns
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object VpnState {
    enum class Status(val label: String) { STOPPED("Chưa bật lọc DNS"), STARTING("Đang khởi động…"), RUNNING("Đang lọc DNS"), ERROR("Không thể bật VPN") }
    @Volatile var status = Status.STOPPED
        private set
    @Volatile var message = ""
        private set
    @Volatile var statisticsError = false
    const val CHANGED = "com.privacyguard.android.VPN_STATE"
    fun update(context: Context, value: Status, detail: String = "") {
        status = value; message = detail
        context.sendBroadcast(Intent(CHANGED).setPackage(context.packageName))
    }
}

class DnsVpnService : VpnService() {
    private var session: Session? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || intent == null) {
            stopSession(); stopSelf(); return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Lọc DNS", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, DnsVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            startForeground(1, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("PrivacyGuard · lọc DNS").setContentText("DNS thường qua VPN cục bộ; không lọc DNS mã hóa.")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Dừng", stop).build()).build())
            VpnState.statisticsError = false
            VpnState.update(this, VpnState.Status.STARTING)
            session = Session().also { current -> Thread({ current.run() }, "privacyguard-tun").start() }
        } catch (_: Exception) {
            VpnState.update(this, VpnState.Status.ERROR, "Android không cho phép khởi động VPN. Hãy mở ứng dụng và cấp quyền VPN lại.")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopSession() {
        val previous = session; session = null
        previous?.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        VpnState.update(this, VpnState.Status.STOPPED)
    }

    override fun onRevoke() { stopSession(); stopSelf(); super.onRevoke() }
    override fun onDestroy() {
        if (session != null) stopSession() else stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private inner class Session : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val writeLock = Any()
        private val sockets = mutableSetOf<AutoCloseable>()
        private val udpWorkers = pool(4)
        private val tcpWorker = pool(1)
        private val tcp = TcpDns()
        private val store = GuardStore(this@DnsVpnService)
        @Volatile private var tunnel: ParcelFileDescriptor? = null
        private var output: FileOutputStream? = null
        private val ipv4 = InetAddress.getByName(DNS4).address
        private val ipv6 = InetAddress.getByName(DNS6).address

        private fun pool(size: Int) = ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64))

        fun run() {
            try {
                val descriptor = Builder().setSession("PrivacyGuard DNS").setMtu(65535)
                    .addAddress("10.111.0.1", 32).addAddress("fd42:7072:6976::1", 128)
                    .addDnsServer(DNS4).addDnsServer(DNS6)
                    .addRoute(DNS4, 32).addRoute(DNS6, 128)
                    .allowFamily(OsConstants.AF_INET).allowFamily(OsConstants.AF_INET6)
                    .setBlocking(false).establish() ?: throw IllegalStateException("VPN consent unavailable")
                synchronized(writeLock) {
                    if (closed.get()) { descriptor.close(); return }
                    tunnel = descriptor; output = FileOutputStream(descriptor.fileDescriptor)
                }
                main.post { if (session === this && !closed.get()) VpnState.update(this@DnsVpnService, VpnState.Status.RUNNING) }
                val input = FileInputStream(descriptor.fileDescriptor)
                val buffer = ByteArray(65535)
                val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                while (!closed.get()) {
                    if (Os.poll(arrayOf(poll), 250) == 0) continue
                    if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) throw IllegalStateException("TUN closed")
                    val size = input.read(buffer)
                    if (size <= 0) continue
                    val bytes = buffer.copyOf(size)
                    val udp = Packets.parseUdp(bytes)
                    if (udp != null && udp.destinationPort == 53 && isDns(udp.destination)) {
                        val app = owner(OsConstants.IPPROTO_UDP, udp.source, udp.sourcePort, udp.destination, udp.destinationPort)
                        submit(udpWorkers, {
                            val result = DnsFilter.resolve(udp.payload, app, store.policy, ::forward) ?: return@submit
                            write(listOf(Packets.udpReply(udp, result.response)), listOf(result), app)
                        }, {
                            val question = Packets.question(udp.payload) ?: return@submit
                            val result = DnsResult(Packets.dnsError(udp.payload, question, 2),
                                Decision(question.domain, Category.UNKNOWN, Action.ALLOW, "Hàng đợi DNS đầy"), Outcome.FAILED)
                            write(listOf(Packets.udpReply(udp, result.response)), listOf(result), app)
                        })
                    } else {
                        val packet = TcpDns.parse(bytes) ?: continue
                        if (packet.destinationPort != 53 || !isDns(packet.destination)) continue
                        val app = owner(OsConstants.IPPROTO_TCP, packet.source, packet.sourcePort, packet.destination, packet.destinationPort)
                        submit(tcpWorker, {
                            val replies = tcp.accept(packet, android.os.SystemClock.elapsedRealtime()) { query ->
                                DnsFilter.resolve(query, app, store.policy, ::forward)
                            }
                            write(replies.packets, replies.results, app)
                        }, { write(listOf(TcpDns.reply(packet, packet.acknowledgment, 0, TcpDns.RST)), emptyList(), app) })
                    }
                }
            } catch (_: Exception) {
                fail()
            } finally { close() }
        }

        private fun isDns(address: ByteArray) = address.contentEquals(ipv4) || address.contentEquals(ipv6)

        private fun submit(executor: ThreadPoolExecutor, work: () -> Unit, rejected: () -> Unit) {
            if (closed.get()) return
            try { executor.execute { try { if (!closed.get()) work() } catch (_: Exception) { fail() } } }
            catch (_: RejectedExecutionException) { if (!closed.get()) rejected() }
        }

        private fun owner(protocol: Int, source: ByteArray, sourcePort: Int, destination: ByteArray, destinationPort: Int): String? {
            return try {
                val uid = getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(protocol,
                    InetSocketAddress(InetAddress.getByAddress(source), sourcePort), InetSocketAddress(InetAddress.getByAddress(destination), destinationPort))
                // Android's shared DNS resolver often owns the socket; its UID does not identify the requesting app.
                if (uid < Process.FIRST_APPLICATION_UID) null else packageManager.getPackagesForUid(uid)?.singleOrNull()
            } catch (_: Exception) { null }
        }

        private fun write(frames: List<ByteArray>, results: List<DnsResult>, app: String?) {
            synchronized(writeLock) {
                if (closed.get()) return
                val target = output ?: return
                frames.forEach { target.write(it) }
                results.forEach { result ->
                    try { store.record(result, app) } catch (_: Exception) { VpnState.statisticsError = true }
                }
            }
        }

        private fun track(socket: AutoCloseable) {
            synchronized(sockets) {
                check(!closed.get())
                sockets += socket
            }
        }

        private fun forward(query: ByteArray): ByteArray? {
            for (server in listOf("9.9.9.9", "1.1.1.1")) {
                if (closed.get()) return null
                try {
                    DatagramSocket().use { socket ->
                        track(socket)
                        try {
                            check(protect(socket))
                            socket.connect(InetAddress.getByName(server), 53); socket.soTimeout = 1500
                            socket.send(DatagramPacket(query, query.size))
                            val data = ByteArray(65507)
                            val response = DatagramPacket(data, data.size)
                            socket.receive(response)
                            val bytes = data.copyOf(response.length)
                            if (Packets.validResponse(query, bytes)) {
                                if (Packets.u16(bytes, 2) and 0x0200 == 0) return bytes
                                forwardTcp(query, server)?.let { return it }
                            }
                        } finally { synchronized(sockets) { sockets.remove(socket) } }
                    }
                } catch (_: Exception) { /* Try the second resolver, then report SERVFAIL. */ }
            }
            return null
        }

        private fun forwardTcp(query: ByteArray, server: String): ByteArray? {
            Socket().use { socket ->
                track(socket)
                try {
                    check(protect(socket))
                    socket.connect(InetSocketAddress(server, 53), 1500); socket.soTimeout = 1500
                    val data = java.io.DataOutputStream(socket.getOutputStream())
                    data.writeShort(query.size); data.write(query); data.flush()
                    val input = java.io.DataInputStream(socket.getInputStream())
                    val size = input.readUnsignedShort()
                    if (size !in 12..65000) return null
                    val response = ByteArray(size); input.readFully(response)
                    return response.takeIf { Packets.validResponse(query, it) }
                } finally { synchronized(sockets) { sockets.remove(socket) } }
            }
        }

        private fun fail() {
            if (closed.get()) return
            main.post {
                if (session === this) {
                    stopSession()
                    VpnState.update(this@DnsVpnService, VpnState.Status.ERROR, "VPN đã dừng do lỗi kết nối nội bộ. Hãy thử bật lại.")
                    stopSelf()
                }
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(writeLock) { runCatching { tunnel?.close() }; tunnel = null; output = null }
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
            udpWorkers.shutdownNow(); tcpWorker.shutdownNow()
            // Workers may still be unwinding; close SQLite only after they release their references.
            Thread({
                udpWorkers.awaitTermination(5, TimeUnit.SECONDS); tcpWorker.awaitTermination(5, TimeUnit.SECONDS)
                store.close()
            }, "privacyguard-stop").start()
        }
    }

    companion object {
        const val START = "com.privacyguard.android.START"
        const val STOP = "com.privacyguard.android.STOP"
        const val DNS4 = "10.111.0.2"
        const val DNS6 = "fd42:7072:6976::2"
        private const val CHANNEL = "dns_filter"
    }
}
