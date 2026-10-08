package com.privacyguard.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.privacyguard.android.core.*
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Device tests use Android's own Instrumentation API, with no additional runtime dependency. */
class MvpInstrumentation : Instrumentation() {
    private val passed = mutableListOf<String>()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        try {
            case("policy persistence, duplicate replacement and recovery") { policyStorage() }
            case("counter accuracy, opt-in history and bounded retention") { historyStorage() }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            case("cleaner UI success and invalid URL") { cleanerUi(activity) }
            case("rules UI domain validation") { rulesUi(activity) }
            case("VPN UDP/TCP IPv4/IPv6 blocking and forwarding") { network(activity) }
            runOnMainSync { activity.finish() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS ${passed.size} device cases\n${passed.joinToString("\n")}\n") })
        } catch (error: Throwable) {
            targetContext.stopService(Intent(targetContext, DnsVpnService::class.java))
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED after ${passed.size} cases\n${passed.joinToString("\n")}\n${error.stackTraceToString()}") })
        }
    }

    private fun case(name: String, work: () -> Unit) { work(); passed += name }
    private fun checkEquals(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected; got $actual" } }
    private fun isolatedContext(): Context = object : ContextWrapper(targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String) = File(super.getDatabasePath(name).parentFile, "mvp-test-$name")
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) =
            super.openOrCreateDatabase("mvp-test-$name", mode, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, handler: android.database.DatabaseErrorHandler?) =
            super.openOrCreateDatabase("mvp-test-$name", mode, factory, handler)
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("mvp-test-$name", mode)
    }

    private fun policyStorage() {
        val context = isolatedContext()
        context.getSharedPreferences("privacyguard", Context.MODE_PRIVATE).edit().clear().commit()
        GuardStore(context).use { store ->
            store.setCategory("com.example.one", Category.ADS, Action.ALLOW)
            store.saveException("*.EXAMPLE.COM.", "*", Action.BLOCK)
            store.saveException("*.example.com", "*", Action.ALLOW)
            checkEquals(1, store.policy.exceptions.size)
            checkEquals(Action.ALLOW, Rules.decide("ads.example.com", "com.example.two", store.policy).action)
            store.deleteException(store.policy.exceptions.single())
            checkEquals(Action.ALLOW, Rules.decide("ads.example.com", "com.example.one", store.policy).action)
            checkEquals(Action.BLOCK, Rules.decide("ads.example.com", "com.example.two", store.policy).action)
            store.setCategory("com.example.one", Category.ADS, null)
            checkEquals(Action.BLOCK, Rules.decide("ads.example.com", "com.example.one", store.policy).action)
            store.saveCustomParams("ref, campaign_*")
            checkEquals(listOf("ref", "campaign_*"), store.customParams)
        }
        GuardStore(context).use { checkEquals(listOf("ref", "campaign_*"), it.customParams) }
        checkEquals(Policy(), PolicyCodec.decode("{broken"))
        val recovered = PolicyCodec.decode("""{"exceptions":[null,{"domain":"*.EXAMPLE.COM","action":"BLOCK","app":"*"},{"domain":"*.example.com","action":"ALLOW","app":"*"}]}""")
        checkEquals(listOf(DomainRule("*.example.com", Action.ALLOW)), recovered.exceptions)
    }

    private fun historyStorage() {
        val context = isolatedContext()
        GuardStore(context).use { store ->
            store.clearHistory(); store.detailed = false
            val blocked = resolve(query("ads.example.com"))
            val allowed = resolve(query("api.example.com"))
            store.record(blocked, null); store.record(allowed, "com.example.one")
            checkEquals(2L, store.counters().sumOf { it.count }); check(store.events().isEmpty())
            store.detailed = true
            store.record(blocked, null); store.record(blocked, null)
            checkEquals(3L, store.counters().filter { it.outcome == Outcome.BLOCKED }.sumOf { it.count })
            checkEquals(2, store.events().size)
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("history.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            db.use {
                it.beginTransaction()
                try {
                    repeat(2100) { index -> it.execSQL("INSERT INTO events(time,app,domain,category,outcome,reason) VALUES(?,?,?,?,?,?)",
                        arrayOf<Any>(System.currentTimeMillis(), "", "example.com", "UNKNOWN", "FORWARDED", "fixture $index")) }
                    it.execSQL("INSERT INTO events(time,app,domain,category,outcome,reason) VALUES(?,?,?,?,?,?)",
                        arrayOf<Any>(System.currentTimeMillis() - 8 * 86400000L, "", "expired.example.com", "UNKNOWN", "FORWARDED", "fixture"))
                    it.execSQL("INSERT INTO counters(day,app,category,outcome,count) VALUES('2000-01-01','','ADS','BLOCKED',99)")
                    it.execSQL("INSERT INTO counters(day,app,category,outcome,count) VALUES('9999-01-01','','ADS','BLOCKED',99)")
                    it.setTransactionSuccessful()
                } finally { it.endTransaction() }
            }
            checkEquals(2000, store.events(2000).size)
            check(store.events(2000).none { it.domain == "expired.example.com" })
            checkEquals(4L, store.counters().sumOf { it.count })
            store.detailed = false; check(store.events().isEmpty()); checkEquals(4L, store.counters().sumOf { it.count })
            check(!store.export().contains("https://"))
            store.clearHistory(); check(store.counters().isEmpty())
        }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun button(activity: Activity, title: String) = views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == title }
    private fun tab(activity: Activity, title: String) = views(activity.window.decorView).first { it.contentDescription?.toString() == title && it.isClickable }
    private fun texts(activity: Activity) = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }

    private fun cleanerUi(activity: Activity) {
        runOnMainSync { tab(activity, "Link").performClick() }
        runOnMainSync {
            val input = views(activity.window.decorView).filterIsInstance<EditText>().first()
            input.setText("https://example.com/?utm_source=email&q=a%20b&q=a+b#hello")
            button(activity, "Làm sạch link").performClick()
            check(texts(activity).any { it.contains("https://example.com/?q=a%20b&q=a+b#hello") })
            input.setText("javascript:alert(1)"); button(activity, "Làm sạch link").performClick()
            check(views(activity.window.decorView).filterIsInstance<Button>().none { it.text.toString() == "Chia sẻ link sạch" })
        }
    }

    private fun rulesUi(activity: Activity) {
        runOnMainSync {
            tab(activity, "Luật").performClick()
            val input = views(activity.window.decorView).filterIsInstance<EditText>().first()
            input.setText("https://example.com"); button(activity, "Lưu ngoại lệ").performClick()
            check(texts(activity).any { it.contains("Nhập tên miền") })
        }
    }

    private fun network(activity: Activity) {
        check(android.net.VpnService.prepare(targetContext) == null) { "Authorize VPN on the test emulator with appops ACTIVATE_VPN allow before running device tests." }
        GuardStore(targetContext).use { it.clearHistory() }
        runOnMainSync {
            tab(activity, "Tổng quan").performClick()
            button(activity, "Bật lọc DNS").performClick()
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (VpnState.status != VpnState.Status.RUNNING && VpnState.status != VpnState.Status.ERROR && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        checkEquals(VpnState.Status.RUNNING, VpnState.status)
        for (server in listOf(DnsVpnService.DNS4, DnsVpnService.DNS6)) {
            val blocked = query("ads.example.com")
            DatagramSocket().use { socket ->
                socket.soTimeout = 5000; socket.connect(InetAddress.getByName(server), 53)
                socket.send(DatagramPacket(blocked, blocked.size))
                val response = DatagramPacket(ByteArray(65000), 65000); socket.receive(response)
                checkEquals(3, Packets.u16(response.data, 2) and 15)
                check(Packets.validResponse(blocked, response.data.copyOf(response.length)))
            }
            Socket().use { socket ->
                socket.connect(InetSocketAddress(server, 53), 5000); socket.soTimeout = 5000
                val output = java.io.DataOutputStream(socket.getOutputStream()); output.writeShort(blocked.size); output.write(blocked); output.flush()
                val input = java.io.DataInputStream(socket.getInputStream()); val response = ByteArray(input.readUnsignedShort()); input.readFully(response)
                checkEquals(3, Packets.u16(response, 2) and 15); check(Packets.validResponse(blocked, response))
            }
        }
        val allowed = query("example.com")
        DatagramSocket().use { socket ->
            socket.soTimeout = 10000; socket.connect(InetAddress.getByName(DnsVpnService.DNS4), 53)
            socket.send(DatagramPacket(allowed, allowed.size))
            val response = DatagramPacket(ByteArray(65000), 65000); socket.receive(response)
            check(Packets.validResponse(allowed, response.data.copyOf(response.length)))
            checkEquals(0, Packets.u16(response.data, 2) and 15)
            check(Packets.u16(response.data, 6) > 0) { "Allowed domain must reach the upstream resolver and return real answers." }
        }
        GuardStore(targetContext).use { store ->
            check(store.counters().filter { it.outcome == Outcome.BLOCKED }.sumOf { it.count } >= 4L)
            check(store.counters().any { it.outcome == Outcome.FORWARDED })
        }
        targetContext.startService(Intent(targetContext, DnsVpnService::class.java).setAction(DnsVpnService.STOP))
        Thread.sleep(300)
        checkEquals(VpnState.Status.STOPPED, VpnState.status)
        GuardStore(targetContext).use { it.clearHistory() }
    }

    private fun resolve(query: ByteArray) = DnsFilter.resolve(query, null, Policy()) { Packets.dnsError(it, Packets.question(it)!!, 0) }!!
    private fun query(domain: String): ByteArray {
        val data = java.io.ByteArrayOutputStream()
        val header = ByteArray(12); Packets.put16(header, 0, 123); Packets.put16(header, 2, 0x0100); Packets.put16(header, 4, 1)
        data.write(header)
        domain.split('.').forEach { data.write(it.length); data.write(it.toByteArray(Charsets.US_ASCII)) }
        data.write(byteArrayOf(0, 0, 1, 0, 1)); return data.toByteArray()
    }
}
