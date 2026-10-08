package com.privacyguard.android

import android.app.Activity
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
import android.widget.TextView
import com.privacyguard.android.core.Action
import com.privacyguard.android.core.Policy
import com.privacyguard.android.core.Rules
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference

class PrivateBrowserActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var address: android.widget.EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var policy = Policy()
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
        val ui = Ui(this)
        val root = ui.column().apply { setBackgroundColor(ui.background); isSaveEnabled = false }
        val toolbar = ui.column(12)
        toolbar.addView(ui.text("Phiên riêng tư", 21f, true))
        toolbar.addView(ui.text("HTTPS · cookie riêng · không lưu lịch sử · đóng để xóa", 12f, color = ui.muted))
        address = ui.field("https://example.com").apply { setText(pendingUrl) }
        toolbar.addView(address)
        val buttons = ui.row()
        buttons.addView(ui.button("Mở", true) { navigate(address.text.toString()) }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(ui.button("Kết thúc phiên") { endSession() }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(buttons)
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
                    val firstParty = topHost.get()
                    if (!request.isForMainFrame && (firstParty == null || (domain != firstParty && !domain.endsWith(".$firstParty")))) {
                        val decision = runCatching { Rules.decide(domain, packageName, policy) }.getOrNull()
                        if (decision?.action == Action.BLOCK) return blocked()
                    }
                    return null
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
        sessionGeneration++
        status.setText(R.string.browser_clearing); web.visibility = View.INVISIBLE
        address.setText(""); pendingUrl = ""; topHost.set(null)
        clearData { if (!isDestroyed) { web.destroy(); finishAndRemoveTask() } }
    }

    @Deprecated("Legacy back handling keeps this MVP free of AndroidX dependencies")
    override fun onBackPressed() { if (ready && web.canGoBack()) web.goBack() else endSession() }

    override fun onDestroy() {
        if (::web.isInitialized && !closing) {
            closing = true
            clearData { web.destroy() }
        }
        super.onDestroy()
    }

    companion object { private var directoryConfigured = false }
}
