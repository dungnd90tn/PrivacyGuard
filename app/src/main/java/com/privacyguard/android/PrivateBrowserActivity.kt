package com.privacyguard.android

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.privacyguard.android.core.Action
import com.privacyguard.android.core.Policy
import com.privacyguard.android.core.Rules
import com.privacyguard.android.core.BrowserRequest
import com.privacyguard.android.core.RequestSession
import com.privacyguard.android.core.UrlAnalyzer
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference

class PrivateBrowserActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var address: android.widget.EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    @Volatile private var policy = Policy()
    private lateinit var ui: Ui
    private val requests = RequestSession()
    private var requestsDialog: AlertDialog? = null
    private var requestDetailDialog: AlertDialog? = null
    private val topHost = AtomicReference<String?>()
    @Volatile private var ready = false
    @Volatile private var closing = false
    private var pendingUrl = ""
    private var sessionGeneration = 0
    private var pageFailed = false

    // This is a general web browser: JavaScript is required. No JS-to-native bridge is exposed.
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Must precede every android.webkit call in this dedicated process.
        if (!directoryConfigured) { WebView.setDataDirectorySuffix("private_session"); directoryConfigured = true }
        policy = PolicyCodec.decode(intent.getStringExtra("policy"))
        pendingUrl = intent.getStringExtra("url").orEmpty()
        intent.removeExtra("url"); intent.removeExtra("policy")
        ui = Ui(this)
        val root = ui.column().apply { setBackgroundColor(ui.background); isSaveEnabled = false }
        val toolbar = ui.column(12)
        toolbar.addView(ui.text("Phiên riêng tư", 21f, true))
        toolbar.addView(ui.text("HTTPS · cookie riêng · không lưu lịch sử · đóng để xóa", 12f, color = ui.muted))
        address = ui.field("https://example.com").apply { setText(pendingUrl) }
        toolbar.addView(address)
        val buttons = ui.row()
        buttons.addView(ui.button("Mở", true) { navigate(address.text.toString()) }, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(4); marginEnd = ui.dp(6) })
        buttons.addView(ui.button("Kết thúc phiên") { endSession() }, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(4); marginStart = ui.dp(6) })
        toolbar.addView(buttons)
        toolbar.addView(ui.button("Yêu cầu trong phiên") { showRequests() })
        status = ui.text("Đang xóa dữ liệu phiên trước…", 12f, color = ui.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; isSaveEnabled = false }
        toolbar.addView(status); root.addView(toolbar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(3)))
        web = WebView(this).apply {
            isSaveEnabled = false; setBackgroundColor(Color.WHITE)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                setGeolocationEnabled(false); setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    this@PrivateBrowserActivity.progress.progress = newProgress
                    this@PrivateBrowserActivity.progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val value = request?.url?.toString().orEmpty()
                    if (!validUrl(value)) { status.setText(R.string.browser_https_only); return true }
                    return false
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (!ready || closing || !validUrl(url.orEmpty())) return
                    pageFailed = false
                    topHost.set(url?.let { Uri.parse(it).host?.lowercase(java.util.Locale.ROOT) })
                    address.setText(url.orEmpty()); status.text = getString(R.string.browser_loading, topHost.get().orEmpty())
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (ready && !closing && !pageFailed && validUrl(url.orEmpty())) status.setText(R.string.browser_active)
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    if (ready && request?.isForMainFrame == true && request.url.scheme == "https") { pageFailed = true; status.setText(R.string.browser_error) }
                }
                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.cancel(); pageFailed = true
                    if (ready) status.setText(R.string.browser_invalid_cert)
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (!ready || closing || request == null) return blocked()
                    if (request.url.scheme != "https") return blocked()
                    val domain = request.url.host?.lowercase(java.util.Locale.ROOT) ?: return blocked()
                    val capture = requests.token()
                    val firstParty = topHost.get()
                    var domainBlocked = false
                    if (!request.isForMainFrame && (firstParty == null || (domain != firstParty && !domain.endsWith(".$firstParty")))) {
                        val decision = runCatching { Rules.decide(domain, packageName, policy) }.getOrNull()
                        domainBlocked = decision?.action == Action.BLOCK
                    }
                    if (capture != null) runCatching {
                        // URL/method only. Do not collect headers, cookies, body or response content.
                        val analysis = UrlAnalyzer.analyze(request.url.toString())
                        requests.append(capture, BrowserRequest(System.currentTimeMillis(), UrlAnalyzer.safe(request.method).take(16), request.isForMainFrame, domainBlocked, analysis))
                    }
                    // URL analysis is display-only. Existing third-party domain rules still make the decision.
                    return if (domainBlocked) blocked() else null
                }
            }
        }
        // Service workers could otherwise retain caches or bypass the request filter.
        ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
            blockNetworkLoads = true; allowFileAccess = false; allowContentAccess = false
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root); ui.edgeToEdge(this, root)
        val generation = ++sessionGeneration
        clearData {
            if (!isDestroyed && !closing && generation == sessionGeneration) { ready = true; navigate(pendingUrl); pendingUrl = "" }
        }
    }

    private fun validUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value.trim())
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (closing) return
        policy = PolicyCodec.decode(intent.getStringExtra("policy"))
        val next = intent.getStringExtra("url").orEmpty()
        intent.removeExtra("url"); intent.removeExtra("policy")
        ready = false
        clearRequests()
        val generation = ++sessionGeneration
        clearData { if (!closing && !isDestroyed && generation == sessionGeneration) { ready = true; navigate(next) } }
    }

    private fun navigate(value: String) {
        if (!ready || closing) return
        if (!validUrl(value)) { status.setText(R.string.browser_invalid_url); return }
        topHost.set(Uri.parse(value.trim()).host?.lowercase(java.util.Locale.ROOT))
        web.loadUrl(value.trim())
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))

    private fun showRequests() {
        if (closing || !ready) return
        requestsDialog?.dismiss()
        val body = ui.column(20)
        val state = ui.text("", 14f, true)
        body.addView(state)
        body.addView(ui.text("Nhận định từ URL và query param của yêu cầu WebView cung cấp. Chỉ giữ 100 yêu cầu gần nhất trong phiên này; giá trị mặc định được ẩn. Không tự chặn theo tham số.", 13f, color = ui.muted))
        val controls = ui.column()
        body.addView(controls)
        val list = ui.column()
        body.addView(list)
        fun renderRequests() {
            controls.removeAllViews(); list.removeAllViews()
            val snapshot = requests.snapshot()
            state.text = if (requests.enabled) "Đang xem · ${snapshot.size}/100 yêu cầu" else "Chưa bật xem yêu cầu"
            controls.addView(ui.button(if (requests.enabled) "Tắt và xóa yêu cầu" else "Bật xem yêu cầu", true) {
                requestDetailDialog?.dismiss(); requestDetailDialog = null
                requests.setEnabled(!requests.enabled); renderRequests()
            })
            if (requests.enabled) {
                controls.addView(ui.button("Tải lại trang để xem") { web.reload() })
                controls.addView(ui.button("Làm mới danh sách") { renderRequests() })
                if (snapshot.isEmpty()) list.addView(ui.text("Chưa thấy yêu cầu. Tải lại trang hoặc tiếp tục duyệt, rồi làm mới danh sách.", 14f, color = ui.muted))
                snapshot.forEach { request ->
                    val row = ui.listRow(list, request.analysis.host, "${request.method} · ${if (request.mainFrame) "Trang chính" else "Tài nguyên trang"}\n${request.analysis.kind.title}\n${if (request.domainBlocked) "Đã chặn theo luật tên miền" else "Cho phép tiếp tục; chưa xác nhận tải thành công"}", "globe") { showRequest(request) }
                    ((row.getChildAt(1) as LinearLayout).getChildAt(1) as TextView).maxLines = 6
                    ui.separator(list, 0)
                }
            }
            body.contentDescription = state.text
        }
        renderRequests()
        body.addView(ui.text("Danh sách có thể thiếu yêu cầu sau chuyển hướng, URL blob/JavaScript hoặc nội dung gửi bằng POST. Không thấy URL của ứng dụng khác. Tắt tính năng hoặc kết thúc phiên sẽ xóa danh sách.", 12f, color = ui.muted))
        requestsDialog = AlertDialog.Builder(this).setTitle("Yêu cầu trong phiên").setView(ScrollView(this).apply { isSaveEnabled = false; addView(body) })
            .setPositiveButton("Đóng", null).create().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show(); it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    private fun showRequest(request: BrowserRequest) {
        if (closing || !requests.enabled) return
        requestDetailDialog?.dismiss()
        val body = ui.column(20)
        body.addView(ui.text("${request.method} · ${if (request.mainFrame) "Trang chính" else "Tài nguyên trang"}", 13f, color = ui.muted))
        body.addView(ui.text(if (request.domainBlocked) "Đã chặn theo luật tên miền" else "Cho phép tiếp tục", 16f, true, if (request.domainBlocked) ui.red else ui.muted))
        body.addView(ui.text("Trạng thái này do luật tên miền quyết định. Nhận định URL dưới đây chỉ để bạn tham khảo.", 12f, color = ui.muted)); ui.gap(body, 12)
        showUrlAnalysis(ui, body, request.analysis)
        requestDetailDialog = AlertDialog.Builder(this).setTitle("Chi tiết URL").setView(ScrollView(this).apply { isSaveEnabled = false; addView(body) })
            .setPositiveButton("Đóng", null).create().also { it.show(); it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    private fun clearRequests() {
        requests.clear()
        requestsDialog?.dismiss(); requestsDialog = null
        requestDetailDialog?.dismiss(); requestDetailDialog = null
    }

    private fun clearData(done: () -> Unit) {
        // Unload the previous document so its JavaScript cannot repopulate cookies/storage during reset.
        web.stopLoading(); web.loadUrl("about:blank"); web.clearHistory(); web.clearCache(true); web.clearFormData()
        WebStorage.getInstance().deleteAllData()
        WebView.clearClientCertPreferences(null)
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            done()
        }
    }

    private fun endSession() {
        if (closing) return
        closing = true; ready = false
        clearRequests()
        sessionGeneration++
        status.setText(R.string.browser_clearing); web.visibility = View.INVISIBLE
        address.setText(""); pendingUrl = ""; topHost.set(null)
        clearData { if (!isDestroyed) { web.destroy(); finishAndRemoveTask() } }
    }

    @Deprecated("Legacy back handling keeps this MVP free of AndroidX dependencies")
    override fun onBackPressed() { if (ready && web.canGoBack()) web.goBack() else endSession() }

    override fun onDestroy() {
        ready = false; pendingUrl = ""; topHost.set(null)
        if (::address.isInitialized) address.setText("")
        clearRequests()
        if (::web.isInitialized && !closing) {
            closing = true
            clearData { web.destroy() }
        }
        super.onDestroy()
    }

    companion object { private var directoryConfigured = false }
}
