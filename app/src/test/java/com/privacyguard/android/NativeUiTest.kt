package com.privacyguard.android

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.privacyguard.android.core.Action
import com.privacyguard.android.core.Category
import com.privacyguard.android.core.Outcome
import com.privacyguard.android.core.Rules
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.LocalDate

/** Executes actual Android views/SQLite; screenshot fixtures are never shipped as production data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeUiTest {
    private var controller: ActivityController<MainActivity>? = null
    private val context get() = RuntimeEnvironment.getApplication()
    private val chrome = "com.example.chrome"
    private val zalo = "com.example.zalo"
    private val fixtureMarker = "older-than-100.example.com"

    @After fun tearDown() { controller?.pause()?.stop()?.destroy(); idle() }
    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun texts(root: View = controller!!.get().window.decorView) = views(root).filterIsInstance<TextView>().map { it.text.toString() }
    private fun click(label: String) {
        val root = controller!!.get().window.decorView
        val target = views(root).firstOrNull { it.contentDescription?.toString() == label && it.isClickable }
            ?: views(root).filterIsInstance<TextView>().filter { it.text.toString() == label }.let { matches -> matches.firstOrNull { it.isClickable } ?: matches.first() }.let { text ->
                var view: View = text
                while (!view.isClickable && view.parent is View) view = view.parent as View
                view
            }
        assertTrue("$label must be clickable", target.isClickable); target.performClick(); idle()
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) { idle(); if (predicate()) return; Thread.sleep(15) }
        fail("Timed out loading native UI: ${texts()}")
    }
    private fun fixture(qualifiers: String = "w390dp-h844dp-port-mdpi", fontScale: Float = 1f) {
        RuntimeEnvironment.setFontScale(fontScale)
        RuntimeEnvironment.setQualifiers(qualifiers)
        val pm = Shadows.shadowOf(context.packageManager)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for ((id, name) in listOf(chrome to "Chrome", zalo to "Zalo", "com.example.instagram" to "Instagram")) {
            val app = ApplicationInfo().apply { packageName = id; nonLocalizedLabel = name }
            pm.installPackage(PackageInfo().apply { packageName = id; applicationInfo = app })
            pm.addResolveInfoForIntent(launcher, ResolveInfo().apply { nonLocalizedLabel = name; activityInfo = ActivityInfo().apply { packageName = id; this.name = "$id.MainActivity"; applicationInfo = app } })
        }
        GuardStore(context).use { it.detailed = true; it.events() }
        val db = context.openOrCreateDatabase("history.db", 0, null)
        val now = System.currentTimeMillis()
        val history = buildList {
            add(GuardEvent(now - 3_600_000, chrome, fixtureMarker, Category.UNKNOWN, Outcome.FORWARDED, "Cho phép theo mặc định"))
            for (i in 0 until 220) {
                val app = when (i % 5) { 0 -> null; 1, 2 -> chrome; 3 -> zalo; else -> "com.example.instagram" }
                val domain = when (i % 4) { 0 -> "ads.example.com"; 1 -> "sync.example.com"; 2 -> "api.example.com"; else -> "metrics.example.com" }
                val category = when (i % 4) { 0 -> Category.ADS; 3 -> Category.ANALYTICS; else -> Category.ESSENTIAL }
                val outcome = when { i % 17 == 0 -> Outcome.FAILED; category in listOf(Category.ADS, Category.ANALYTICS) -> Outcome.BLOCKED; else -> Outcome.FORWARDED }
                add(GuardEvent(now - 600_000 + i * 1500L, app, domain, category, outcome, if (outcome == Outcome.BLOCKED) "Luật nhóm toàn cục" else "Cho phép theo mặc định"))
            }
        }
        db.beginTransaction()
        try {
            history.forEach { e ->
                db.execSQL("INSERT INTO events(time,app,domain,category,outcome,reason) VALUES(?,?,?,?,?,?)", arrayOf<Any>(e.time, e.app.orEmpty(), e.domain, e.category.name, e.outcome.name, e.reason))
                db.execSQL("INSERT OR IGNORE INTO counters(day,app,category,outcome,count) VALUES(?,?,?,?,0)", arrayOf(LocalDate.now().toString(), e.app.orEmpty(), e.category.name, e.outcome.name))
                db.execSQL("UPDATE counters SET count=count+1 WHERE day=? AND app=? AND category=? AND outcome=?", arrayOf(LocalDate.now().toString(), e.app.orEmpty(), e.category.name, e.outcome.name))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction(); db.close() }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        await { texts().any { it.startsWith("Cập nhật") } }
    }

    @Test fun rendersNativeScreensInLightDarkAndCompactWidths() {
        fixture()
        capture("overview-light", 390, 844)
        click("Ứng dụng"); capture("apps-light", 390, 844)
        click("Chrome"); capture("domains-light", 390, 844)
        click("Nhật ký"); capture("timeline-light", 390, 844)
        click("Luật"); capture("rules-light", 390, 844)
        click("Link"); capture("cleaner-light", 390, 844)
        click("Cài đặt"); capture("settings-light", 390, 844)
        click("Tổng quan")
        RuntimeEnvironment.setQualifiers("w320dp-h740dp-port-mdpi")
        controller!!.get().recreate(); idle()
        await { texts().any { it.startsWith("Cập nhật") } }
        capture("overview-compact", 320, 740)
    }
    @Test fun rendersDarkModeWithoutLosingNavigation() {
        fixture("w390dp-h844dp-port-night-mdpi")
        capture("overview-dark", 390, 844)
        click("Ứng dụng"); click("Chrome"); capture("domains-dark", 390, 844)
        val nav = views(controller!!.get().window.decorView).filter { it.contentDescription?.toString() in listOf("Tổng quan", "Ứng dụng", "Luật", "Link", "Cài đặt") && it.isClickable }
        assertEquals(5, nav.size)
        nav.forEach { assertTrue(it.isFocusable) }
    }
    @Test fun domainSearchFindsOlderRowsAndKeepsInputOnRefresh() {
        fixture(); click("Ứng dụng"); click("Tất cả tên miền")
        val input = views(controller!!.get().window.decorView).filterIsInstance<EditText>().single()
        input.setText(fixtureMarker); idle()
        assertTrue(texts().contains(fixtureMarker)); assertTrue(texts().any { it.contains("1 tên miền · 1 sự kiện") })
        click("Làm mới"); await { texts().contains(fixtureMarker) }
        assertSame(input, views(controller!!.get().window.decorView).filterIsInstance<EditText>().single())
        input.setText("does-not-exist"); idle(); assertTrue(texts().contains("Không có kết quả"))
    }
    @Test fun appDomainActionSavesOnlyTheSelectedAppRule() {
        fixture(); click("Ứng dụng"); click("Chrome"); click("sync.example.com")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(texts(dialog.window!!.decorView).any { it == "Phạm vi luật: Chrome" })
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        GuardStore(context).use { store ->
            val rule = store.policy.exceptions.single()
            assertEquals(chrome, rule.app); assertEquals("sync.example.com", rule.domain)
            assertEquals(Action.BLOCK, Rules.decide(rule.domain, chrome, store.policy).action)
            assertEquals(Action.ALLOW, Rules.decide(rule.domain, zalo, store.policy).action)
        }
    }
    @Test fun unknownScopeHasExplicitGlobalRuleAndNoKnownAppRows() {
        fixture(); click("Ứng dụng"); click("Chưa xác định ứng dụng"); click("Nhật ký")
        assertTrue(texts().none { it.startsWith("Chrome ·") || it.startsWith("Zalo ·") })
        click("ads.example.com")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(texts(dialog.window!!.decorView).any { it.contains("Phạm vi luật: Tất cả ứng dụng") })
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        GuardStore(context).use { store ->
            assertEquals("*", store.policy.exceptions.single().app)
            assertEquals(Action.ALLOW, Rules.decide("ads.example.com", chrome, store.policy).action)
        }
    }
    @Test fun disablingLoggingDeletesDomainsButKeepsCounters() {
        fixture()
        GuardStore(context).use { store ->
            assertEquals(221, store.events().size)
            assertEquals(221L, store.counters().sumOf { it.count })
            assertEquals(221L, store.timeline().sumOf { it.count })
            val matches = Traffic.filter(store.events(), TrafficScope.App(chrome), query = fixtureMarker)
            val export = org.json.JSONObject(store.exportEvents(matches, chrome, 7, fixtureMarker, null))
            assertFalse(export.getBoolean("simulated"))
            assertEquals(chrome, export.getJSONObject("filter").getString("scope"))
            assertEquals(1, export.getJSONArray("events").length())
            assertEquals(fixtureMarker, export.getJSONArray("events").getJSONObject(0).getString("domain"))
            store.detailed = false
            assertTrue(store.events().isEmpty())
            assertEquals(221L, store.counters().sumOf { it.count })
        }
        click("Ứng dụng"); click("Tất cả tên miền")
        assertTrue(texts().contains("Chưa ghi tên miền")); assertTrue(texts().contains("Bật nhật ký tên miền"))
    }
    @Test fun linkCleanerAndInvalidRuleStillWorkWithNewTabs() {
        fixture(); click("Link")
        val input = views(controller!!.get().window.decorView).filterIsInstance<EditText>().first()
        input.setText("https://example.com/?utm_source=email&q=a%20b&q=a+b#hello"); click("Làm sạch link")
        assertTrue(texts().any { it.contains("https://example.com/?q=a%20b&q=a+b#hello") })
        input.setText("javascript:alert(1)"); click("Làm sạch link")
        assertFalse(texts().contains("Chia sẻ link sạch"))
        click("Luật"); views(controller!!.get().window.decorView).filterIsInstance<EditText>().first().setText("https://example.com")
        click("Lưu ngoại lệ"); assertTrue(texts().any { it.contains("Nhập tên miền") })
    }
    @Test fun rendersLargeTextAndTabletWithUsableTabs() {
        fixture("w390dp-h844dp-port-mdpi", fontScale = 1.3f)
        capture("overview-large-text", 390, 844)
        click("Ứng dụng"); click("Chrome"); capture("domains-large-text", 390, 844)
        val navigation = views(controller!!.get().window.decorView).filter { it.contentDescription?.toString() in listOf("Tổng quan", "Ứng dụng", "Luật", "Link", "Cài đặt") && it.isClickable }
        assertEquals(5, navigation.size)
        navigation.forEach { assertTrue(it.width >= 48); assertTrue(it.height >= 48) }
        RuntimeEnvironment.setFontScale(1f); RuntimeEnvironment.setQualifiers("w800dp-h1024dp-port-mdpi")
        controller!!.get().recreate(); idle(); click("Tổng quan")
        await { texts().any { it.startsWith("Cập nhật") } }; capture("overview-tablet", 800, 1024)
    }
    @Test fun freshInstallHasAnExplicitWorkingDomainLoggingOptIn() {
        RuntimeEnvironment.setQualifiers("w390dp-h844dp-port-mdpi")
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        await { texts().any { it.startsWith("Cập nhật") } }
        GuardStore(context).use { assertFalse(it.detailed); assertTrue(it.events().isEmpty()); assertTrue(it.counters().isEmpty()) }
        click("Ứng dụng"); capture("apps-logging-off", 390, 844)
        click("Bật nhật ký tên miền")
        await { GuardStore(context).use { it.detailed } && texts().none { it == "Bật nhật ký tên miền" } }
        click("Tất cả tên miền"); assertTrue(texts().contains("Đang chờ truy vấn mới")); assertTrue(texts().contains("Bật lọc DNS"))
        capture("domains-empty", 390, 844)
    }
    @Test fun dnsSelectionPersistsAndSettingsExportIncludesOnlyThatProfileChoice() {
        fixture(); click("Cài đặt"); click("Máy chủ DNS"); click("Cloudflare")
        await { texts().contains("Đang chọn Cloudflare") }
        GuardStore(context).use { store ->
            assertEquals("cloudflare", store.dnsSettings.active.id)
            assertEquals(listOf("1.1.1.1", "1.0.0.1"), store.dnsSettings.active.endpoints.map { it.address })
            assertEquals("cloudflare", org.json.JSONObject(store.export()).getJSONObject("dnsSettings").getString("selected"))
        }
        capture("dns-presets", 390, 844)
        controller!!.get().recreate(); idle()
        assertTrue(texts().contains("Đang chọn Cloudflare"))
        assertEquals(VpnState.Status.STOPPED, VpnState.status) // Choosing a resolver never starts VPN by itself.
    }
    @Test fun customDnsFormRejectsInvalidAddressesAndPersistsIpv6BackupAndPort() {
        fixture(); click("Cài đặt"); click("Máy chủ DNS"); click("Thêm DNS tùy chỉnh")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val fields = views(dialog.window!!.decorView).filterIsInstance<EditText>()
        fields[0].setText("DNS ở nhà"); fields[1].setText("https://dns.example.com/dns-query")
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(dialog.isShowing); assertTrue(texts(dialog.window!!.decorView).any { it.contains("Nhập địa chỉ IP") })
        GuardStore(context).use { assertTrue(it.dnsSettings.custom.isEmpty()) }
        fields[1].setText("192.168.1.1"); fields[2].setText("2001:db8::53"); fields[3].setText("65536")
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(dialog.isShowing); assertTrue(texts(dialog.window!!.decorView).any { it.contains("65535") })
        fields[3].setText("5353"); dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        await { texts().contains("Đang chọn DNS ở nhà") }
        GuardStore(context).use { store ->
            assertEquals(5353, store.dnsSettings.active.primary.port)
            assertEquals(16, store.dnsSettings.active.secondary!!.bytes.size)
            assertEquals(1, store.dnsSettings.custom.size)
        }
        capture("dns-custom", 390, 844)
        click("Chỉnh sửa DNS ở nhà")
        ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null, 0, 0); idle()
        val edit = ShadowAlertDialog.getLatestAlertDialog()
        val editing = views(edit.window!!.decorView).filterIsInstance<EditText>()
        editing[0].setText("DNS riêng"); editing[1].setText("192.168.1.2")
        edit.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        await { texts().contains("Đang chọn DNS riêng") }
        GuardStore(context).use { assertEquals(1, it.dnsSettings.custom.size); assertEquals("192.168.1.2", it.dnsSettings.active.primary.address) }
        click("Chỉnh sửa DNS riêng")
        ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null, 1, 1); idle()
        val confirm = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(texts(confirm.window!!.decorView).any { it.contains("sẽ dùng Quad9") })
        confirm.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        await { texts().contains("Đang chọn Quad9") }
        GuardStore(context).use { assertTrue(it.dnsSettings.custom.isEmpty()); assertEquals("quad9", it.dnsSettings.active.id) }
    }
    @Test fun blockedAndFailedShortcutsShowSeparateListsWithPlainLanguageDetails() {
        fixture(); click("Xem yêu cầu Đã chặn")
        assertTrue(texts().contains("Đã chặn")); assertTrue(texts().any { it.contains("Quảng cáo đã bị chặn") || it.contains("Yêu cầu theo dõi đã bị chặn") })
        assertTrue(texts().none { it.contains("Chưa nhận được câu trả lời") })
        capture("blocked-requests", 390, 844)
        click("Gặp lỗi")
        assertTrue(texts().any { it.contains("Chưa nhận được câu trả lời") })
        assertTrue(texts().none { it.contains("Quảng cáo đã bị chặn") || it.contains("Yêu cầu theo dõi đã bị chặn") })
        capture("failed-requests", 390, 844)
        click("ads.example.com")
        val detail = ShadowAlertDialog.getLatestAlertDialog()
        val visible = texts(detail.window!!.decorView)
        assertTrue(visible.any { it.contains("Đây không phải một yêu cầu bị PrivacyGuard chặn") })
        assertTrue(visible.contains("Bạn có thể làm gì?")); assertTrue(visible.none { it.contains("Resolver trả lỗi") })
        captureDialog("failed-request-help", detail)
        detail.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(texts().contains("Máy chủ DNS"))
        click("Quay lại"); assertTrue(texts().contains("Gặp lỗi"))
    }
    @Test fun blockedRequestExplainsScopeAndAllowsOnlyTheAppWhereItWasObserved() {
        fixture(); click("Ứng dụng"); click("Chrome"); click("Nhật ký"); click("Đã chặn")
        assertTrue(texts().none { it.startsWith("Zalo ·") || it.startsWith("Instagram ·") })
        click("ads.example.com")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(texts(dialog.window!!.decorView).any { it.contains("Yêu cầu không được gửi tới máy chủ DNS") })
        assertTrue(texts(dialog.window!!.decorView).contains("Phạm vi luật: Chrome"))
        captureDialog("blocked-request-help", dialog)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        GuardStore(context).use { store ->
            assertEquals(chrome, store.policy.exceptions.single().app)
            assertEquals(Action.ALLOW, Rules.decide("ads.example.com", chrome, store.policy).action)
            assertEquals(Action.BLOCK, Rules.decide("ads.example.com", zalo, store.policy).action)
        }
    }
    @Test fun allServerErrorsUseReadableReasonsAndRawCodesRequireOpeningTechnicalDetails() {
        fixture()
        // Replace one fixture's reason with a real upstream failure reason.
        context.openOrCreateDatabase("history.db", 0, null).use { db ->
            db.execSQL("UPDATE events SET reason='Resolver trả lỗi DNS (mã 5)' WHERE outcome='FAILED'")
        }
        click("Làm mới"); click("Xem yêu cầu Gặp lỗi")
        await { texts().any { it.contains("Máy chủ DNS không nhận yêu cầu") } }
        click("ads.example.com")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(texts(dialog.window!!.decorView).any { it.contains("đã từ chối") })
        assertTrue(texts(dialog.window!!.decorView).none { it.contains("mã 5") })
        val button = views(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == "Chi tiết kỹ thuật" }
        button.performClick(); idle()
        assertTrue(texts(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView).any { it.contains("mã 5") })
    }
    @Test fun dnsAndRequestScreensRenderInDarkModeAndWithLargeText() {
        fixture("w390dp-h844dp-port-night-mdpi", fontScale = 1.3f)
        click("Cài đặt"); click("Máy chủ DNS"); capture("dns-presets-dark-large", 390, 844)
        click("Thêm DNS tùy chỉnh")
        captureDialog("dns-editor-dark-large", ShadowAlertDialog.getLatestAlertDialog())
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick(); idle()
        click("Ứng dụng"); click("Gặp lỗi"); capture("failed-requests-dark-large", 390, 844)
        click("ads.example.com"); captureDialog("failed-help-dark-large", ShadowAlertDialog.getLatestAlertDialog())
        val add = views(controller!!.get().window.decorView).firstOrNull { it.contentDescription?.toString() == "Thêm DNS tùy chỉnh" }
        assertNull(add) // Request screen has its own actions, not the DNS editor button.
    }
    @Test fun customProfileLimitDoesNotChangeTheActiveChoiceAndClearHistoryPreservesDns() {
        fixture()
        GuardStore(context).use { store ->
            for (i in 0 until 20) store.saveCustomDns(com.privacyguard.android.core.DnsServers.custom("custom-$i", "DNS $i", "192.168.1.1"), select = false)
            store.selectDns("cloudflare")
            assertThrows(IllegalArgumentException::class.java) { store.saveCustomDns(com.privacyguard.android.core.DnsServers.custom("custom-overflow", "Too many", "192.168.1.2")) }
            assertEquals("cloudflare", store.dnsSettings.active.id)
            assertEquals(20, store.dnsSettings.custom.size)
            store.clearHistory()
            assertEquals("cloudflare", store.dnsSettings.active.id); assertEquals(20, store.dnsSettings.custom.size)
            assertTrue(store.events().isEmpty()); assertTrue(store.counters().isEmpty())
        }
    }

    private fun captureDialog(name: String, dialog: android.app.AlertDialog) {
        val width = 390; val height = 844
        val bitmap = Bitmap.createBitmap(width, height + 24, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val activity = controller!!.get().window.decorView
        activity.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); activity.layout(0, 0, width, height); activity.draw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.argb(110, 0, 0, 0) })
        val decor = dialog.window!!.decorView
        decor.forceLayout(); decor.measure(View.MeasureSpec.makeMeasureSpec(width - 32, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height - 80, View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        canvas.save(); canvas.translate(16f, (height - decor.measuredHeight) / 2f); decor.draw(canvas); canvas.restore()
        canvas.drawRect(0f, height.toFloat(), width.toFloat(), height + 24f, Paint().apply { color = Color.WHITE })
        canvas.drawText("NATIVE ANDROID UI · TEST FIXTURE DATA · SDK 35", 8f, height + 15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 78, 90); textSize = 10f })
        File("build/ui-previews").apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    private fun capture(name: String, width: Int, height: Int) {
        val decor = controller!!.get().window.decorView
        decor.forceLayout(); decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); decor.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height + 24, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE); decor.draw(canvas)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 78, 90); textSize = 10f }
        canvas.drawText("NATIVE ANDROID UI · TEST FIXTURE DATA · SDK 35", 8f, height + 15f, paint)
        File("build/ui-previews").apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
