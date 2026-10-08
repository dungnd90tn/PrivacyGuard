package com.privacyguard.android

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.ServiceWorkerController
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerWebSettings
import android.widget.TextView
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
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [TestServiceWorkerController::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateBrowserTest {
    @Test fun toolbarActionsAndRequestButtonsHaveSeparateTouchAreas() {
        setup()
        val root = controller!!.get().window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 390, 844)
        val buttons = views(root).filterIsInstance<android.widget.Button>()
        val open = buttons.single { it.text.toString() == "Mở" }; val end = buttons.single { it.text.toString() == "Kết thúc phiên" }
        assertTrue(end.left - open.right >= 12)
        val dialog = journal(); click(dialog.window!!.decorView, "Bật xem yêu cầu")
        captureDialog("browser-spacing-041", dialog)
        val actions = views(dialog.window!!.decorView).filterIsInstance<android.widget.Button>().filter { it.text.toString() in listOf("Tắt và xóa yêu cầu", "Tải lại trang để xem", "Làm mới danh sách") }
        assertEquals(3, actions.size)
        actions.zipWithNext().forEach { (a, b) -> assertTrue(b.top - a.bottom >= 8) }
        assertTrue(actions.all { it.height >= 48 && it.elevation == 0f })
    }
    private var controller: ActivityController<PrivateBrowserActivity>? = null
    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun texts(root: View) = views(root).filterIsInstance<TextView>().map { it.text.toString() }
    private fun click(root: View, label: String) {
        val matches = views(root).filterIsInstance<TextView>().filter { it.text.toString() == label }
        var target: View = matches.firstOrNull { it.isClickable } ?: matches.first()
        while (!target.isClickable && target.parent is View) target = target.parent as View
        assertTrue(target.performClick()); idle()
    }
    private fun setup(): WebView {
        RuntimeEnvironment.setQualifiers("w390dp-h844dp-port-mdpi")
        val intent = Intent(RuntimeEnvironment.getApplication(), PrivateBrowserActivity::class.java).putExtra("url", "https://shop.example.com/")
        controller = Robolectric.buildActivity(PrivateBrowserActivity::class.java, intent).setup(); idle()
        return views(controller!!.get().window.decorView).filterIsInstance<WebView>().single()
    }
    private fun journal(): AlertDialog {
        click(controller!!.get().window.decorView, "Yêu cầu trong phiên")
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    private fun request(url: String, main: Boolean = false) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = throw AssertionError("Request inspection must not read headers/cookies")
    }
    @After fun close() { controller?.pause()?.stop()?.destroy(); idle() }

    @Test fun urlAnalysisDoesNotAutomaticallyBlockAndExistingDomainRulesStillApply() {
        val web = setup(); val client = Shadows.shadowOf(web).webViewClient
        val dialog = journal(); click(dialog.window!!.decorView, "Bật xem yêu cầu")
        assertNull(client.shouldInterceptRequest(web, request("https://publisher.example.com/content?ad_slot=hero")))
        assertNull(client.shouldInterceptRequest(web, request("https://shop.example.com/?gclid=private-canary", true)))
        assertEquals(403, client.shouldInterceptRequest(web, request("https://securepubads.g.doubleclick.net/gampad/adx?iu=/123/home&sz=320x50"))!!.statusCode)
        click(dialog.window!!.decorView, "Làm mới danh sách")
        val rows = texts(dialog.window!!.decorView)
        assertTrue(rows.contains("Đang xem · 3/100 yêu cầu"))
        assertTrue(rows.any { it.contains("Có dấu hiệu quảng cáo") })
        assertTrue(rows.any { it.contains("Khớp mẫu yêu cầu quảng cáo") })
        assertFalse(rows.any { it.contains("private-canary") })
        captureDialog("browser-inspection", dialog)
    }
    @Test fun viewingIsOptInAndTurningItOffErasesCapturedRequestsAndValues() {
        val web = setup(); val client = Shadows.shadowOf(web).webViewClient
        assertNull(client.shouldInterceptRequest(web, request("https://shop.example.com/?utm_source=before", true)))
        val dialog = journal(); assertTrue(texts(dialog.window!!.decorView).contains("Chưa bật xem yêu cầu"))
        click(dialog.window!!.decorView, "Bật xem yêu cầu")
        assertTrue(texts(dialog.window!!.decorView).contains("Đang xem · 0/100 yêu cầu"))
        client.shouldInterceptRequest(web, request("https://shop.example.com/private?utm_source=canary-value", true))
        click(dialog.window!!.decorView, "Làm mới danh sách")
        click(dialog.window!!.decorView, "shop.example.com")
        val detail = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(texts(detail.window!!.decorView).contains("Giá trị: đã ẩn"))
        captureDialog("browser-url-detail", detail)
        click(detail.window!!.decorView, "Hiện đường dẫn và giá trị")
        assertTrue(texts(detail.window!!.decorView).contains("Giá trị: canary-value"))
        click(dialog.window!!.decorView, "Tắt và xóa yêu cầu")
        assertFalse(detail.isShowing); assertTrue(texts(dialog.window!!.decorView).contains("Chưa bật xem yêu cầu"))
        click(dialog.window!!.decorView, "Bật xem yêu cầu"); assertTrue(texts(dialog.window!!.decorView).contains("Đang xem · 0/100 yêu cầu"))
        GuardStore(RuntimeEnvironment.getApplication()).use { assertTrue(it.events().isEmpty()); assertTrue(it.counters().isEmpty()); assertFalse(it.export().contains("canary-value")) }
    }
    @Test fun newSessionResetsInspectionAndEndingSessionClearsDialogsAndStopsLoading() {
        val web = setup(); val client = Shadows.shadowOf(web).webViewClient
        val dialog = journal(); click(dialog.window!!.decorView, "Bật xem yêu cầu")
        client.shouldInterceptRequest(web, request("https://shop.example.com/?token=old-session", true))
        controller!!.newIntent(Intent().putExtra("url", "https://next.example.com/")); idle()
        assertFalse(dialog.isShowing)
        val next = journal(); assertTrue(texts(next.window!!.decorView).contains("Chưa bật xem yêu cầu"))
        click(next.window!!.decorView, "Bật xem yêu cầu")
        assertTrue(texts(next.window!!.decorView).contains("Đang xem · 0/100 yêu cầu"))
        click(controller!!.get().window.decorView, "Kết thúc phiên")
        assertFalse(next.isShowing); assertTrue(controller!!.get().isFinishing)
        assertEquals(403, client.shouldInterceptRequest(web, request("https://next.example.com/?token=late", true))!!.statusCode)
    }
    private fun captureDialog(name: String, dialog: AlertDialog) {
        val width = 390; val height = 844
        val bitmap = Bitmap.createBitmap(width, height + 24, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
        val activity = controller!!.get().window.decorView
        activity.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); activity.layout(0, 0, width, height); activity.draw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.argb(110, 0, 0, 0) })
        val decor = dialog.window!!.decorView
        decor.forceLayout(); decor.measure(View.MeasureSpec.makeMeasureSpec(width - 32, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height - 80, View.MeasureSpec.AT_MOST)); decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        canvas.save(); canvas.translate(16f, (height - decor.measuredHeight) / 2f); decor.draw(canvas); canvas.restore()
        canvas.drawRect(0f, height.toFloat(), width.toFloat(), height + 24f, Paint().apply { color = Color.WHITE })
        canvas.drawText("NATIVE ANDROID UI · TEST CALLBACK FIXTURES · SDK 35", 8f, height + 15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 78, 90); textSize = 10f })
        File("build/ui-previews").apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}

/** Robolectric has no Chromium provider for ServiceWorkerController; isolate only that platform boundary. */
@Implements(ServiceWorkerController::class)
class TestServiceWorkerController {
    companion object {
        @Implementation @JvmStatic fun getInstance(): ServiceWorkerController = object : ServiceWorkerController() {
            private val settings = object : ServiceWorkerWebSettings() {
                private var cache = 0
                private var content = true
                private var file = true
                private var blocked = false
                override fun setCacheMode(mode: Int) { cache = mode }
                override fun getCacheMode() = cache
                override fun setAllowContentAccess(allow: Boolean) { content = allow }
                override fun getAllowContentAccess() = content
                override fun setAllowFileAccess(allow: Boolean) { file = allow }
                override fun getAllowFileAccess() = file
                override fun setBlockNetworkLoads(block: Boolean) { blocked = block }
                override fun getBlockNetworkLoads() = blocked
            }
            override fun getServiceWorkerWebSettings() = settings
            override fun setServiceWorkerClient(client: ServiceWorkerClient?) = Unit
        }
    }
}
