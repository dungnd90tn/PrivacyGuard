package com.privacyguard.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.privacyguard.android.core.Action
import com.privacyguard.android.core.Category
import com.privacyguard.android.core.DnsServer
import com.privacyguard.android.core.DnsServers
import com.privacyguard.android.core.DnsMode
import com.privacyguard.android.core.UrlAnalysis
import com.privacyguard.android.core.UrlAnalyzer
import com.privacyguard.android.core.LinkCleaner
import com.privacyguard.android.core.Outcome
import com.privacyguard.android.core.Rules
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: GuardStore
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private data class InstalledApp(val id: String, val name: String)
    private data class Snapshot(val apps: List<InstalledApp>, val counters: List<Counter>, val events: List<GuardEvent>, val timeline: List<DailyCount>)
    private var apps = emptyList<InstalledApp>()
    private var counters = emptyList<Counter>()
    private var events = emptyList<GuardEvent>()
    private var timeline = emptyList<DailyCount>()
    private var page = "overview"
    private var dnsReturnPage = "settings"
    private var dnsSaving = false
    private var selectedApp: String? = null
    private var trafficScope: TrafficScope? = null
    private var days = 7
    private var appsMode = 0
    private var trafficMode = 0
    private var trafficQuery = ""
    private var appQuery = ""
    private var trafficOutcome: Outcome? = null
    private var pageLimit = PAGE_SIZE
    private var cleanerDraft = ""
    private var cleanedUrl: String? = null
    private var urlAnalysis: UrlAnalysis? = null
    private var browserDraft = ""
    private var pendingExport: String? = null
    private var receiverRegistered = false
    private var loading = true
    private var loadError = false
    private var inFlight = false
    private var foreground = false
    private var closing = false
    private var lastUpdated = 0L
    private var refreshUi: () -> Unit = {}
    private val poll = object : Runnable {
        override fun run() {
            if (!foreground || closing) return
            if (VpnState.status == VpnState.Status.RUNNING && page in listOf("overview", "apps")) refresh()
            handler.postDelayed(this, 3000)
        }
    }
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshUi(); refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GuardStore(this); ui = Ui(this)
        page = savedInstanceState?.getString("page") ?: "overview"
        selectedApp = savedInstanceState?.getString("selectedApp")
        days = savedInstanceState?.getInt("days", 7)?.coerceIn(1, 7) ?: 7
        trafficScope = when (val scope = savedInstanceState?.getString("trafficScope")) {
            "*" -> TrafficScope.All; "?" -> TrafficScope.Unknown; null -> null; else -> TrafficScope.App(scope)
        }
        if (page == "activity") { page = "apps"; trafficScope = TrafficScope.All }
        dnsReturnPage = savedInstanceState?.getString("dnsReturnPage") ?: "settings"
        trafficOutcome = savedInstanceState?.getString("trafficOutcome")?.let { runCatching { Outcome.valueOf(it) }.getOrNull() }
        trafficMode = savedInstanceState?.getInt("trafficMode", 0) ?: 0
        handleShare(intent); render(); refresh()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page); outState.putString("selectedApp", selectedApp); outState.putInt("days", days)
        outState.putString("trafficScope", when (val scope = trafficScope) { TrafficScope.All -> "*"; TrafficScope.Unknown -> "?"; is TrafficScope.App -> scope.packageName; null -> null })
        outState.putString("dnsReturnPage", dnsReturnPage)
        outState.putString("trafficOutcome", trafficOutcome?.name)
        outState.putInt("trafficMode", trafficMode)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleShare(intent); render() }
    private fun handleShare(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND || incoming.type != "text/plain") return
        val text = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(8192)
        cleanerDraft = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).find(text)?.value ?: text
        cleanedUrl = null; page = "cleaner"
        incoming.removeExtra(Intent.EXTRA_TEXT); incoming.action = Intent.ACTION_MAIN
    }
    override fun onResume() {
        super.onResume(); foreground = true
        if (!receiverRegistered) {
            registerReceiver(stateReceiver, IntentFilter(VpnState.CHANGED), "com.privacyguard.android.permission.INTERNAL", null,
                if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0)
            receiverRegistered = true
        }
        if (!loading) refresh()
        handler.removeCallbacks(poll); handler.postDelayed(poll, 3000)
    }
    override fun onPause() {
        foreground = false; handler.removeCallbacks(poll)
        if (receiverRegistered) { unregisterReceiver(stateReceiver); receiverRegistered = false }
        super.onPause()
    }
    override fun onDestroy() {
        closing = true; handler.removeCallbacksAndMessages(null)
        cleanerDraft = ""; cleanedUrl = null; urlAnalysis = null; browserDraft = ""
        io.execute { store.close() }; io.shutdown(); super.onDestroy()
    }
    @Suppress("DEPRECATION")
    @Deprecated("Native activity navigation")
    override fun onBackPressed() {
        if (page == "dns") { navigate(dnsReturnPage) }
        else if (page == "apps" && trafficScope != null) { trafficScope = null; render() }
        else if (page != "overview") { page = "overview"; render() }
        else super.onBackPressed()
    }
    private fun refresh() {
        if (closing || inFlight) return
        inFlight = true
        val requestedDays = days
        io.execute {
            val result = runCatching {
                val installed = if (apps.isNotEmpty()) apps else packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
                    .distinctBy { it.id }.sortedBy { it.name.lowercase() }
                Snapshot(installed, store.counters(requestedDays), store.events(), store.timeline(requestedDays))
            }
            runOnUiThread {
                if (closing) return@runOnUiThread
                inFlight = false
                if (days != requestedDays) { refresh(); return@runOnUiThread }
                result.onSuccess { value ->
                    apps = value.apps; counters = value.counters; events = value.events; timeline = value.timeline
                    lastUpdated = System.currentTimeMillis()
                }
                loadError = result.isFailure; loading = false; refreshUi()
            }
        }
    }
    private fun navigate(id: String) {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        page = id; render()
    }
    private fun showTraffic(scope: TrafficScope, outcome: Outcome? = null, mode: Int = 0) {
        trafficScope = scope; trafficQuery = ""; trafficOutcome = outcome; trafficMode = mode; pageLimit = PAGE_SIZE
        navigate("apps")
    }
    private fun render() {
        if (closing) return
        refreshUi = {}
        val root = ui.column().apply { setBackgroundColor(ui.background) }
        content = ui.column(20)
        val frame = FrameLayout(this)
        // Keep lists comfortable on tablets without constraining phone layouts.
        frame.addView(content, FrameLayout.LayoutParams(if (resources.configuration.screenWidthDp >= 760) ui.dp(720) else -1, -2, Gravity.CENTER_HORIZONTAL))
        root.addView(ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(frame) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = ui.row().apply { background = ui.rounded(ui.surface, radius = 0); setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); elevation = ui.dp(8).toFloat() }
        listOf(Triple("overview", "Tổng quan", "overview"), Triple("apps", "Ứng dụng", "apps"), Triple("rules", "Luật", "rules"), Triple("cleaner", "Link", "link"), Triple("settings", "Cài đặt", "settings")).forEach { (id, label, icon) ->
            val selected = page == id || (page == "dns" && id == "settings")
            nav.addView(ui.column().apply {
                gravity = Gravity.CENTER; minimumHeight = ui.dp(58); setPadding(ui.dp(2), ui.dp(5), ui.dp(2), ui.dp(5))
                background = ui.ripple(if (selected) ui.soft else android.graphics.Color.TRANSPARENT, 16)
                addView(ui.glyph(icon, if (selected) ui.accent else ui.muted), LinearLayout.LayoutParams(ui.dp(23), ui.dp(23)))
                addView(ui.text(label, 11f, selected, if (selected) ui.accent else ui.muted).apply { gravity = Gravity.CENTER; maxLines = 2 })
                contentDescription = label; isSelected = selected; isFocusable = true; isScreenReaderFocusable = true
                setOnClickListener { navigate(id) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        setContentView(root); ui.edgeToEdge(this, root)
        when (page) { "rules" -> rulesPage(); "cleaner" -> { pageHeader("Link sạch", "Chia sẻ ít dấu vết hơn."); cleanerPage() }
            "apps" -> if (trafficScope == null) appsPage() else trafficPage(trafficScope!!)
            "dns" -> dnsPage(); "settings" -> settingsPage(); else -> overviewPage() }
        ui.gap(content, 12)
    }
    private fun iconButton(icon: String, label: String, action: () -> Unit): View = ui.glyph(icon).apply {
        contentDescription = label; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); background = ui.ripple(ui.surface, 16)
        isFocusable = true; setOnClickListener { action() }
    }
    private fun pageHeader(title: String, subtitle: String, back: Boolean = false, parentLabel: String = "ỨNG DỤNG", headerAction: (() -> Unit)? = null, goBack: (() -> Unit)? = null) {
        val top = ui.row()
        if (back) top.addView(iconButton("back", if (goBack == null) "Quay lại ứng dụng" else "Quay lại") { if (goBack != null) goBack() else { trafficScope = null; render() } }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        else {
            top.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); contentDescription = "PrivacyGuard" },
                LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)).apply { marginEnd = ui.dp(8) })
            top.addView(ui.text("PrivacyGuard", 13f, true, ui.muted), LinearLayout.LayoutParams(0, -2, 1f))
        }
        if (back) top.addView(ui.text(parentLabel, 11f, true, ui.muted).apply { letterSpacing = .08f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(iconButton(if (headerAction == null) "refresh" else "plus", if (headerAction == null) "Làm mới" else "Thêm DNS tùy chỉnh") {
            if (headerAction == null) refresh() else headerAction()
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        content.addView(top); ui.gap(content, 12)
        content.addView(ui.text(title, 32f, true).apply { maxLines = 3 })
        content.addView(ui.text(subtitle, 14f, color = ui.muted)); ui.gap(content, 20)
    }
    private fun periodControl() {
        ui.segmented(content, listOf("Hôm nay", "7 ngày"), if (days == 1) 0 else 1) { index -> days = if (index == 0) 1 else 7; pageLimit = PAGE_SIZE; render(); refresh() }
    }
    private fun overviewPage() {
        pageHeader("An tâm kết nối.", "Nhìn rõ những gì ứng dụng đang gửi đi.")
        val hero = ui.group(content, 20)
        val heroTop = ui.row()
        heroTop.addView(ui.glyph("shield").apply { background = ui.rounded(ui.soft, radius = 22); setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)) }, LinearLayout.LayoutParams(ui.dp(66), ui.dp(66)).apply { marginEnd = ui.dp(16) })
        val labels = ui.column(); val state = ui.text("", 22f, true); val stateDetail = ui.text("", 13f, color = ui.muted)
        labels.addView(state); labels.addView(stateDetail); heroTop.addView(labels, LinearLayout.LayoutParams(0, -2, 1f)); hero.addView(heroTop)
        val stateError = ui.text("", 12f, color = ui.red); hero.addView(stateError); ui.gap(hero, 12)
        val toggle = ui.button("Bật lọc DNS", true) {
            if (VpnState.status == VpnState.Status.RUNNING) startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.STOP)) else prepareVpn()
        }; hero.addView(toggle, LinearLayout.LayoutParams(-1, -2))
        hero.addView(ui.text("Lọc DNS trên thiết bị · không giải mã HTTPS", 11f, color = ui.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(12), 0, 0) })
        val dns = ui.group(content)
        val dnsRow = ui.listRow(dns, "Máy chủ DNS", dnsLabel(), "globe") { openDns() }
        ui.heading(content, "Hoạt động DNS")
        periodControl()
        val metricRow = ui.row()
        val totalText = metric(metricRow, "Tổng truy vấn", ui.ink)
        val blockedText = metric(metricRow, "Đã chặn", ui.accent) { showTraffic(TrafficScope.All, Outcome.BLOCKED, 1) }
        content.addView(metricRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        val secondRow = ui.row(); val forwardedText = metric(secondRow, "Đã gửi", ui.green); val failedText = metric(secondRow, "Gặp lỗi", ui.orange) { showTraffic(TrafficScope.All, Outcome.FAILED, 1) }
        content.addView(secondRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
        val chartCard = ui.group(content, 16)
        val chart = TrafficChart(this, ui); chartCard.addView(chart, LinearLayout.LayoutParams(-1, ui.dp(164)))
        val legend = ui.text("● Đã chặn     ● Đã gửi     ● Gặp lỗi", 11f, color = ui.muted)
        val legendText = android.text.SpannableString(legend.text)
        var dot = -1
        listOf(ui.accent, ui.green, ui.orange).forEach { color ->
            dot = legendText.toString().indexOf('●', dot + 1)
            if (dot >= 0) legendText.setSpan(android.text.style.ForegroundColorSpan(color), dot, dot + 1, 0)
        }
        legend.text = legendText; legend.gravity = Gravity.CENTER; chartCard.addView(legend)
        val updated = ui.text("", 11f, color = ui.muted).apply { gravity = Gravity.CENTER }; chartCard.addView(updated)
        ui.heading(content, "Theo ứng dụng", "Xem tất cả") { trafficScope = null; navigate("apps") }
        val appList = ui.group(content)
        ui.heading(content, "Tên miền gần đây", "Mở nhật ký") { showTraffic(TrafficScope.All); trafficMode = 1; render() }
        val recent = ui.group(content)
        val shortcuts = ui.group(content)
        ui.listRow(shortcuts, "Dọn link trước khi chia sẻ", "Bỏ tham số quảng cáo và theo dõi", "link") { navigate("cleaner") }
        ui.separator(shortcuts, 68)
        ui.listRow(shortcuts, "Phiên duyệt riêng tư", "Cookie riêng, xóa dữ liệu khi kết thúc", "browser") { navigate("cleaner"); content.post { browserInput?.requestFocus() } }
        refreshUi = {
            val dnsSubtitle = (dnsRow.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            dnsSubtitle.text = dnsLabel()
            val running = VpnState.status == VpnState.Status.RUNNING
            state.text = when (VpnState.status) { VpnState.Status.RUNNING -> "Đang bảo vệ"; VpnState.Status.STARTING -> "Đang kết nối…"; VpnState.Status.ERROR -> "Cần kiểm tra"; else -> "Sẵn sàng bảo vệ" }
            stateDetail.text = if (running) "Bộ lọc DNS đang hoạt động" else "Bật để bắt đầu quan sát DNS"
            stateError.text = VpnState.message; stateError.visibility = if (VpnState.message.isBlank()) View.GONE else View.VISIBLE
            toggle.text = if (running) "Dừng lọc DNS" else "Bật lọc DNS"; toggle.isEnabled = VpnState.status != VpnState.Status.STARTING
            totalText.text = value(counters.sumOf { it.count }); blockedText.text = value(total(Outcome.BLOCKED)); forwardedText.text = value(total(Outcome.FORWARDED)); failedText.text = value(total(Outcome.FAILED))
            chart.days = days; chart.counts = timeline
            updated.text = when { loadError -> "Không đọc được dữ liệu · hãy làm mới"; loading -> "Đang đọc dữ liệu…"; VpnState.statisticsError -> "Một số phản hồi chưa ghi được thống kê"; else -> "Cập nhật ${time(lastUpdated, "HH:mm:ss")} · bộ đếm thực tế" }
            appList.removeAllViews()
            val groups = counters.groupBy { it.app }.entries.sortedByDescending { it.value.sumOf(Counter::count) }.take(3)
            if (groups.isEmpty()) empty(appList, "Chưa có truy vấn", "Bật lọc DNS rồi sử dụng các ứng dụng.", "apps")
            groups.forEachIndexed { index, entry ->
                if (index > 0) ui.separator(appList, 68)
                appRow(appList, entry.key, entry.value)
            }
            recent.removeAllViews()
            if (!store.detailed) logPrompt(recent)
            else {
                val latest = Traffic.filter(events, days = days).take(3)
                if (latest.isEmpty()) empty(recent, "Đang chờ tên miền", "Nhật ký ghi truy vấn mới khi bộ lọc hoạt động.", "globe")
                latest.forEachIndexed { index, event ->
                    if (index > 0) ui.separator(recent, 68)
                    ui.listRow(recent, event.domain, "${appName(event.app)} · ${event.outcome.label}", "globe", outcomeColor(event.outcome), oneLineTitle = true) { domainDetails(DomainTraffic(event.domain, Traffic.filter(events, scopeOf(event.app), days).filter { it.domain == event.domain }), scopeOf(event.app)) }
                }
            }
        }
        refreshUi()
    }
    private fun metric(parent: LinearLayout, label: String, color: Int, tap: (() -> Unit)? = null): TextView {
        val card = ui.column(16).apply { background = ui.rounded(ui.surface, radius = 20) }
        val number = ui.text("—", 27f, true, color).apply { maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(18, 27, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }; card.addView(number); card.addView(ui.text(label, 12f, color = ui.muted))
        if (tap != null) { card.background = ui.ripple(ui.surface, 20); card.contentDescription = "Xem yêu cầu $label"; card.isFocusable = true; card.setOnClickListener { tap() } }
        parent.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { if (parent.childCount == 0) marginEnd = ui.dp(5) else marginStart = ui.dp(5) })
        return number
    }
    private fun appsPage() {
        pageHeader("Ứng dụng", "Từ ứng dụng đến từng tên miền.")
        periodControl()
        val quick = ui.group(content)
        val access = ui.group(content)
        val appSearch = ui.field("Tìm ứng dụng").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT; setText(appQuery); contentDescription = "Tìm ứng dụng" }
        content.addView(appSearch, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(14) })
        ui.segmented(content, listOf("Có truy vấn", "Đã cài"), appsMode) { appsMode = it; render() }
        val list = ui.group(content)
        val note = ui.text("", 12f, color = ui.muted); content.addView(note)
        refreshUi = {
            quick.removeAllViews()
            ui.listRow(quick, "Đã chặn", "${number(total(Outcome.BLOCKED))} yêu cầu · xem vì sao bị chặn", "block", ui.red) { showTraffic(TrafficScope.All, Outcome.BLOCKED, 1) }
            ui.separator(quick, 68)
            ui.listRow(quick, "Gặp lỗi", "${number(total(Outcome.FAILED))} yêu cầu · xem cách xử lý", "info", ui.orange) { showTraffic(TrafficScope.All, Outcome.FAILED, 1) }
            access.removeAllViews()
            ui.listRow(access, "Tất cả tên miền", if (!store.detailed) "Nhật ký đang tắt · bật để ghi tên miền" else "${Traffic.domains(Traffic.filter(events, days = days)).size} tên miền trong nhật ký", "globe") { showTraffic(TrafficScope.All) }
            ui.separator(access, 68)
            ui.listRow(access, "Chưa xác định ứng dụng", "${number(counters.filter { it.app == null }.sumOf { it.count })} truy vấn · DNS hệ thống", "info", ui.orange) { showTraffic(TrafficScope.Unknown) }
            if (!store.detailed) { ui.separator(access); logPrompt(access) }
            list.removeAllViews()
            val observed = counters.filter { it.app != null }.groupBy { it.app!! }
            val ids = if (appsMode == 0) observed.keys.sortedByDescending { id -> observed[id].orEmpty().sumOf { it.count } } else apps.map { it.id }
            val matching = ids.filter { "${appName(it)} $it".contains(appQuery.trim(), true) }
            if (loading) empty(list, "Đang tải ứng dụng…", "", "apps")
            else if (loadError) empty(list, "Không đọc được dữ liệu", "Chạm làm mới để thử lại.", "info")
            else if (matching.isEmpty()) empty(list, if (appQuery.isBlank()) "Chưa thấy app có truy vấn" else "Không tìm thấy ứng dụng",
                if (appQuery.isBlank()) "Mở “Đã cài” để xem ứng dụng, hoặc kiểm tra nhóm Chưa xác định ở trên." else "Thử tên hoặc package khác.", "apps")
            matching.take(pageLimit).forEachIndexed { index, id -> if (index > 0) ui.separator(list, 68); appRow(list, id, observed[id].orEmpty()) }
            if (matching.size > pageLimit) list.addView(ui.button("Xem thêm ${minOf(PAGE_SIZE, matching.size - pageLimit)} ứng dụng") { pageLimit += PAGE_SIZE; refreshUi() })
            note.setText(R.string.attribution_note)
        }
        watch(appSearch) { appQuery = it; pageLimit = PAGE_SIZE; refreshUi() }; refreshUi()
    }
    private fun appRow(parent: LinearLayout, app: String?, values: List<Counter>) {
        val count = values.sumOf { it.count }; val blocked = values.filter { it.outcome == Outcome.BLOCKED }.sumOf { it.count }
        val subtitle = if (count == 0L) "Chưa có truy vấn được xác định cho app này" else "${number(count)} truy vấn · ${number(blocked)} đã chặn"
        val row = ui.listRow(parent, appName(app), subtitle, if (app == null) "info" else "apps", if (app == null) ui.orange else ui.accent) { showTraffic(scopeOf(app)) }
        if (app != null) runCatching { packageManager.getApplicationIcon(app) }.onSuccess { drawable ->
            val icon = ImageView(this).apply { setImageDrawable(drawable); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val old = row.getChildAt(0); val params = old.layoutParams; row.removeViewAt(0); row.addView(icon, 0, params)
        }
    }
    private fun trafficPage(scope: TrafficScope) {
        val title = when (scope) { TrafficScope.All -> when (trafficOutcome) { Outcome.BLOCKED -> "Đã chặn"; Outcome.FAILED -> "Gặp lỗi"; else -> "Tên miền" }; TrafficScope.Unknown -> "Chưa xác định"; is TrafficScope.App -> appName(scope.packageName) }
        pageHeader(title, if (scope is TrafficScope.App) "Những dịch vụ ${appName(scope.packageName)} đã hỏi địa chỉ." else when (trafficOutcome) {
            Outcome.BLOCKED -> "Những yêu cầu PrivacyGuard đã ngăn lại."
            Outcome.FAILED -> "Những yêu cầu chưa xử lý được, cùng cách khắc phục."
            else -> "Các truy vấn DNS đã quan sát."
        }, true)
        periodControl()
        if (scope == TrafficScope.Unknown) {
            val info = ui.card(content, "DNS qua hệ thống", "Android chưa cung cấp app gốc cho các truy vấn này. Luật app sẽ không áp dụng; bạn có thể tạo luật toàn cục từ tên miền.")
            info.addView(ui.text("Không suy đoán app từ tên miền.", 12f, color = ui.orange))
        }
        val summary = ui.group(content, 16); val summaryText = ui.text("", 14f, true); summary.addView(summaryText)
        val logState = ui.group(content)
        val search = ui.field("Tìm tên miền, ứng dụng, nhóm").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT; setText(trafficQuery); contentDescription = "Tìm tên miền" }
        content.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
        ui.segmented(content, listOf("Tên miền", "Nhật ký"), trafficMode) { trafficMode = it; pageLimit = PAGE_SIZE; render() }
        val outcomes = listOf(null, Outcome.BLOCKED, Outcome.FORWARDED, Outcome.FAILED)
        ui.segmented(content, listOf("Tất cả", "Đã chặn", "Đã gửi", "Gặp lỗi"), outcomes.indexOf(trafficOutcome)) {
            trafficOutcome = outcomes[it]; pageLimit = PAGE_SIZE; render()
        }
        val filters = ui.row()
        val listTitle = ui.text("", 15f, true)
        filters.addView(listTitle, LinearLayout.LayoutParams(0, -2, 1f))
        filters.addView(iconButton("export", "Xuất kết quả đang lọc") {
            val matching = matchingEvents(scope)
            if (matching.isEmpty()) toast("Chưa có sự kiện để xuất.")
            else {
                val exportDays = days; val exportQuery = trafficQuery; val exportOutcome = trafficOutcome
                requestExport { store.exportEvents(matching, when (scope) { TrafficScope.All -> "*"; TrafficScope.Unknown -> "?"; is TrafficScope.App -> scope.packageName }, exportDays, exportQuery, exportOutcome) }
            }
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        content.addView(filters); ui.gap(content, 12)
        val list = ui.group(content); val footer = ui.text("", 12f, color = ui.muted); content.addView(footer)
        if (scope is TrafficScope.App) content.addView(ui.button("Luật cho ${appName(scope.packageName)}") { selectedApp = scope.packageName; navigate("rules") })
        refreshUi = {
            val totals = counters.filter { scope.contains(it.app) }
            summaryText.text = if (trafficOutcome == null) getString(R.string.traffic_summary, number(totals.sumOf { it.count }), number(totals.filter { it.outcome == Outcome.BLOCKED }.sumOf { it.count }))
                else getString(R.string.outcome_summary, number(totals.filter { it.outcome == trafficOutcome }.sumOf { it.count }), trafficOutcome!!.label.lowercase(java.util.Locale.forLanguageTag("vi-VN")))
            logState.removeAllViews(); logState.visibility = if (!store.detailed) View.VISIBLE else View.GONE
            if (!store.detailed) logPrompt(logState)
            val matching = matchingEvents(scope); val domains = Traffic.domains(matching)
            listTitle.text = resources.getQuantityString(R.plurals.filtered_requests, matching.size, matching.size)
            list.removeAllViews()
            when {
                loading -> empty(list, "Đang đọc nhật ký…", "", "clock")
                loadError -> empty(list, "Không đọc được nhật ký", "Chạm làm mới để thử lại.", "info")
                !store.detailed -> empty(list, "Chưa ghi tên miền", "Bật nhật ký, sau đó bật lọc DNS và sử dụng app để ghi truy vấn mới.", "globe")
                matching.isEmpty() -> empty(list, when { trafficQuery.isNotBlank() -> "Không có kết quả"; trafficOutcome == Outcome.BLOCKED -> "Chưa có yêu cầu bị chặn"; trafficOutcome == Outcome.FAILED -> "Chưa có yêu cầu gặp lỗi"; else -> "Đang chờ truy vấn mới" },
                    if (scope is TrafficScope.App) "Nếu DNS được xử lý bởi hệ thống, hãy xem nhóm Chưa xác định ứng dụng." else "Truy vấn trước khi bật nhật ký không có tên miền để hiển thị.", "globe")
                trafficMode == 0 -> domains.take(pageLimit).forEachIndexed { index, domain ->
                    if (index > 0) ui.separator(list, 68)
                    val tint = if (domain.count(Outcome.BLOCKED) > 0) ui.red else if (domain.count(Outcome.FAILED) > 0) ui.orange else ui.green
                    ui.listRow(list, domain.domain, "${domain.count} lần · ${domain.count(Outcome.BLOCKED)} chặn · ${domain.count(Outcome.FORWARDED)} đã gửi · ${domain.count(Outcome.FAILED)} gặp lỗi", "globe", tint,
                        time(domain.lastSeen, "HH:mm"), oneLineTitle = true) { domainDetails(domain, scope) }
                }
                else -> matching.take(pageLimit).forEachIndexed { index, event ->
                    if (index > 0) ui.separator(list, 68)
                    ui.listRow(list, event.domain, "${appName(event.app)} · ${time(event.time, "dd/MM HH:mm")}\n${RequestText.forEvent(event).title}", "clock", outcomeColor(event.outcome), oneLineTitle = true) { requestDetails(event, scope) }
                }
            }
            if (matching.isEmpty() && !loading && !loadError && trafficQuery.isBlank() && trafficOutcome == null && store.detailed) {
                if (VpnState.status == VpnState.Status.STOPPED || VpnState.status == VpnState.Status.ERROR)
                    list.addView(ui.button("Bật lọc DNS", true) { prepareVpn() }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(16)) })
                if (scope is TrafficScope.App) list.addView(ui.button("Xem DNS chưa xác định") { showTraffic(TrafficScope.Unknown) })
            }
            val size = if (trafficMode == 0) domains.size else matching.size
            if (size > pageLimit) list.addView(ui.button("Xem thêm ${minOf(PAGE_SIZE, size - pageLimit)}") { pageLimit += PAGE_SIZE; refreshUi() })
            footer.text = getString(R.string.traffic_footer, domains.size, matching.size)
        }
        watch(search) { trafficQuery = it; pageLimit = PAGE_SIZE; refreshUi() }; refreshUi()
    }
    private fun matchingEvents(scope: TrafficScope) = Traffic.filter(events, scope, days, trafficQuery, trafficOutcome, apps.associate { it.id to it.name })
    private fun domainDetails(domain: DomainTraffic, scope: TrafficScope) {
        val detail = ui.column(20)
        detail.addView(ui.text(domain.domain, 22f, true).apply { setTextIsSelectable(true) })
        detail.addView(ui.text("${domain.count} sự kiện trong kết quả đang lọc", 13f, color = ui.muted))
        detail.addView(ui.text("${domain.count(Outcome.BLOCKED)} chặn · ${domain.count(Outcome.FORWARDED)} đã gửi · ${domain.count(Outcome.FAILED)} gặp lỗi", 14f, true))
        detail.addView(ui.text("Phạm vi luật: ${if (scope is TrafficScope.App) appName(scope.packageName) else "Tất cả ứng dụng"}", 14f, color = ui.accent))
        if (scope !is TrafficScope.App) detail.addView(ui.text("Luật từ màn hình này là toàn cục và ảnh hưởng mọi ứng dụng.", 13f, color = ui.orange))
        domain.events.sortedByDescending { it.time }.take(8).forEach { event ->
            ui.gap(detail, 10); detail.addView(ui.text("${time(event.time, "dd/MM HH:mm:ss")} · ${appName(event.app)}", 12f, true))
            detail.addView(ui.text(RequestText.forEvent(event).title, 13f, true, outcomeColor(event.outcome)))
            detail.addView(ui.text(RequestText.forEvent(event).explanation, 12f, color = ui.muted))
        }
        AlertDialog.Builder(this).setTitle("Chi tiết tên miền").setView(ScrollView(this).apply { addView(detail) })
            .setNegativeButton("Đóng", null).setNeutralButton("Cho phép") { _, _ -> saveDomainRule(domain.domain, scope, Action.ALLOW) }
            .setPositiveButton("Chặn") { _, _ -> saveDomainRule(domain.domain, scope, Action.BLOCK) }.show()
    }
    private fun saveDomainRule(domain: String, scope: TrafficScope, action: Action) {
        val verb = if (action == Action.BLOCK) "Chặn" else "Cho phép"
        AlertDialog.Builder(this).setTitle("$verb $domain?")
            .setMessage("Áp dụng cho ${if (scope is TrafficScope.App) appName(scope.packageName) else "tất cả ứng dụng"}. Luật tên miền chính xác này sẽ thay thế ngoại lệ cùng phạm vi; không bao gồm tên miền con.")
            .setNegativeButton("Hủy", null).setPositiveButton("Lưu luật") { _, _ ->
                runCatching { store.saveException(domain, scope.ruleApp, action) }.onSuccess { toast("Đã lưu luật: $verb.") }.onFailure { toast(it.message ?: "Không lưu được luật.") }
            }.show()
    }
    private fun logPrompt(parent: LinearLayout) {
        val box = ui.column(16)
        box.addView(ui.text("Bật nhật ký tên miền", 16f, true))
        box.addView(ui.text("Lưu trên thiết bị, tối đa 2.000 truy vấn / 7 ngày. Bắt đầu ghi từ lúc bật; tắt sẽ xóa các tên miền đã lưu.", 12f, color = ui.muted)); ui.gap(box, 10)
        box.addView(ui.button("Bật nhật ký tên miền", true) { setDetailed(true) }, LinearLayout.LayoutParams(-1, -2)); parent.addView(box)
    }
    private fun setDetailed(enabled: Boolean) {
        io.execute {
            val saved = runCatching { store.detailed = enabled }
            runOnUiThread { if (!closing) {
                if (saved.isFailure) toast("Không thay đổi được nhật ký; hãy thử lại.")
                else { events = emptyList(); render(); refresh() }
            } }
        }
    }
    private fun empty(parent: LinearLayout, title: String, body: String, icon: String) {
        val box = ui.column(24).apply { gravity = Gravity.CENTER }
        box.addView(ui.glyph(icon, ui.muted), LinearLayout.LayoutParams(ui.dp(32), ui.dp(32))); ui.gap(box, 12)
        box.addView(ui.text(title, 16f, true).apply { gravity = Gravity.CENTER })
        if (body.isNotBlank()) box.addView(ui.text(body, 13f, color = ui.muted).apply { gravity = Gravity.CENTER })
        parent.addView(box)
    }
    private fun rulesPage() {
        pageHeader("Luật bảo vệ", "Chọn điều gì được phép kết nối.")
        val policy = store.policy
        val scopeApps = (apps + (counters.mapNotNull { it.app } + events.mapNotNull { it.app } + listOfNotNull(selectedApp)).distinct()
            .filter { id -> apps.none { it.id == id } }.map { InstalledApp(it, appName(it)) }).sortedBy { it.name.lowercase() }
        val categories = ui.card(content, "Phạm vi áp dụng", "Luật app chỉ áp dụng khi Android xác định được ứng dụng gốc.")
        val scopeIds = listOf<String?>(null) + scopeApps.map { it.id }
        categories.addView(spinner(listOf("Tất cả ứng dụng") + scopeApps.map { it.name }, scopeIds.indexOf(selectedApp).coerceAtLeast(0)) { selectedApp = scopeIds[it]; render() })
        val group = ui.group(content)
        listOf(Category.ADS, Category.ANALYTICS, Category.ESSENTIAL).forEachIndexed { index, category ->
            if (index > 0) ui.separator(group, 68)
            val action = if (selectedApp == null) policy.categories[category] else policy.apps[selectedApp]?.get(category)
            ui.listRow(group, category.label, when (category) { Category.ADS -> "Máy chủ phân phối quảng cáo"; Category.ANALYTICS -> "Đo lường và theo dõi sử dụng"; else -> "Chặn có thể làm app ngừng hoạt động" },
                if (category == Category.ESSENTIAL) "globe" else "shield", if (action == Action.BLOCK) ui.red else ui.accent,
                when (action) { Action.BLOCK -> "Chặn"; Action.ALLOW -> "Cho phép"; null -> "Mặc định" }) {
                val labels = if (selectedApp == null) arrayOf("Chặn", "Cho phép") else arrayOf("Chặn", "Cho phép", "Theo mặc định")
                AlertDialog.Builder(this).setTitle(category.label).setItems(labels) { _, choice ->
                    store.setCategory(selectedApp, category, listOf(Action.BLOCK, Action.ALLOW, null)[choice]); render()
                }.show()
            }
        }
        ui.heading(content, "Ngoại lệ tên miền")
        val exceptions = ui.card(content, "Thêm ngoại lệ", "*.example.com chỉ khớp tên miền con. Ngoại lệ app ưu tiên trước toàn cục, sau đó đến luật nhóm.")
        val domain = ui.field("example.com hoặc *.example.com"); exceptions.addView(domain); ui.gap(exceptions, 10)
        val exceptionIds = listOf("*") + scopeApps.map { it.id }
        val scope = spinner(listOf("Tất cả ứng dụng") + scopeApps.map { it.name }, exceptionIds.indexOf(selectedApp ?: "*").coerceAtLeast(0)); exceptions.addView(scope)
        val decision = spinner(listOf("Cho phép", "Chặn")); exceptions.addView(decision)
        val error = ui.text("", color = ui.red).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }; exceptions.addView(error)
        exceptions.addView(ui.button("Lưu ngoại lệ", true) {
            try {
                store.saveException(domain.text.toString(), exceptionIds[scope.selectedItemPosition], if (decision.selectedItemPosition == 0) Action.ALLOW else Action.BLOCK)
                render(); toast("Đã lưu ngoại lệ.")
            } catch (e: IllegalArgumentException) { error.text = e.message }
        }, LinearLayout.LayoutParams(-1, -2))
        if (policy.exceptions.isNotEmpty()) {
            val existing = ui.group(content)
            policy.exceptions.forEachIndexed { index, rule ->
                if (index > 0) ui.separator(existing, 68)
                ui.listRow(existing, rule.domain, "${if (rule.app == "*") "Tất cả ứng dụng" else appName(rule.app)} · ${if (rule.action == Action.BLOCK) "Chặn" else "Cho phép"}", "globe") {
                    AlertDialog.Builder(this).setTitle(rule.domain).setItems(arrayOf("Cho phép", "Chặn", "Xóa ngoại lệ")) { _, choice ->
                        if (choice == 2) store.deleteException(rule) else store.saveException(rule.domain, rule.app, if (choice == 0) Action.ALLOW else Action.BLOCK)
                        render()
                    }.show()
                }
            }
        }
        val tester = ui.card(content, "Thử quyết định", "Kiểm tra luật cục bộ, không truy cập mạng.")
        val testDomain = ui.field("ads.example.com"); tester.addView(testDomain)
        val testScope = spinner(listOf("Chưa xác định ứng dụng") + scopeApps.map { it.name }); tester.addView(testScope)
        val result = ui.text("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        tester.addView(ui.button("Kiểm tra") {
            try {
                val tested = Rules.decide(testDomain.text.toString(), (listOf<String?>(null) + scopeApps.map { it.id })[testScope.selectedItemPosition], store.policy)
                result.text = getString(R.string.test_decision, if (tested.action == Action.BLOCK) "Chặn" else "Cho phép", tested.category.label, tested.reason)
            } catch (e: IllegalArgumentException) { result.text = e.message }
        }); tester.addView(result)
    }
    private fun settingsPage() {
        pageHeader("Cài đặt", "Dữ liệu và quyền riêng tư của bạn.")
        ui.heading(content, "Kết nối")
        val connection = ui.group(content)
        ui.listRow(connection, "Máy chủ DNS", dnsLabel() + " · đổi hoặc thêm máy chủ", "globe") { openDns() }
        ui.heading(content, "Dữ liệu trên thiết bị")
        val privacy = ui.group(content)
        val row = ui.listRow(privacy, "Nhật ký tên miền", "Lưu truy vấn để xem theo ứng dụng", "clock")
        row.addView(Switch(this).apply {
            contentDescription = getString(R.string.detailed_history); isChecked = store.detailed; minHeight = ui.dp(48)
            setOnCheckedChangeListener { _, checked -> setDetailed(checked) }
        })
        ui.separator(privacy, 68)
        ui.listRow(privacy, "Xuất dữ liệu JSON", "Luật, bộ đếm và nhật ký đang lưu", "export") { requestExport { store.export() } }
        ui.separator(privacy, 68)
        ui.listRow(privacy, "Xóa lịch sử quan sát", "Giữ lại luật và tham số tùy chỉnh", "delete", ui.red) {
            AlertDialog.Builder(this).setTitle("Xóa nhật ký và bộ đếm?").setMessage("Luật và tham số tùy chỉnh được giữ lại.")
                .setNegativeButton("Hủy", null).setPositiveButton("Xóa") { _, _ -> io.execute {
                    val cleared = runCatching { store.clearHistory() }
                    runOnUiThread { if (!closing) { events = emptyList(); counters = emptyList(); timeline = emptyList(); refresh(); toast(if (cleared.isSuccess) "Đã xóa dữ liệu." else "Không xóa được dữ liệu.") } }
                } }.show()
        }
        content.addView(ui.text("Tắt nhật ký xóa ngay các tên miền đã lưu. Bộ đếm tổng hợp giữ 7 ngày. Không lưu URL duyệt web, nội dung gói tin hoặc gửi telemetry.", 12f, color = ui.muted))
        ui.heading(content, "Về PrivacyGuard")
        val about = ui.group(content)
        ui.listRow(about, "Phạm vi bảo vệ", "DNS qua VPN, IPv4 và IPv6", "shield") {
            showInfo("Phạm vi bảo vệ", "DNS hệ thống gửi đến PrivacyGuard qua UDP/TCP 53. PrivacyGuard áp dụng luật trước khi gửi lên máy chủ đã chọn; bật mã hóa để dùng TLS 853. Lưu lượng khác đi trực tiếp.\n\nKhông giải mã HTTPS. App tự dùng DoH/DoT, DNS riêng hoặc IP trực tiếp có thể đi vòng. Danh sách tên miền là truy vấn DNS, không chứng minh app đã kết nối thành công.\n\nAndroid thường xử lý DNS qua hệ thống, không cung cấp app gốc. Các truy vấn này xuất hiện trong nhóm Chưa xác định ứng dụng.")
        }
        ui.separator(about, 68)
        ui.listRow(about, "Danh sách tracker", "Danh sách khởi đầu; chưa đầy đủ", "rules") { showInfo("Tên miền nhận diện", Rules.trackers.keys.joinToString("\n") + "\n\nTên miền dùng chung có thể ảnh hưởng chức năng thiết yếu. Bạn có thể tạo ngoại lệ để khôi phục kết nối.") }
        ui.separator(about, 68)
        ui.listRow(about, "Phiên duyệt riêng tư", "Cookie, cache và dữ liệu website riêng", "browser") { showInfo("Phiên riêng tư", "WebView có kho cookie riêng. Dữ liệu được xóa khi kết thúc và trước phiên mới. Chặn tracker bên thứ ba theo tên miền từ danh sách khởi đầu; không kiểm tra lại mọi redirect. Service worker không được truy cập mạng.\n\nTrang web và nhà mạng vẫn có thể thấy địa chỉ IP của bạn.") }
        ui.gap(content, 20); content.addView(ui.text("PrivacyGuard 0.4.0\nKhông tài khoản · không telemetry", 12f, color = ui.muted).apply { gravity = Gravity.CENTER })
    }
    private fun openDns() {
        if (page != "dns") dnsReturnPage = page
        navigate("dns")
    }
    private fun dnsLabel() = store.dnsSettings.let { "${it.active.name} · ${if (it.mode == DnsMode.TLS) "TLS 853" else "DNS thường"}" }
    private fun dnsPage() {
        pageHeader("Máy chủ DNS", "Dịch vụ giúp ứng dụng tìm địa chỉ để kết nối.", true, "KẾT NỐI", headerAction = { editCustomDns() }) { navigate(dnsReturnPage) }
        val configuration = store.dnsSettings
        val selected = configuration.active
        val active = ui.card(content, "Đang chọn ${selected.name}", "Đổi máy chủ nếu kết nối chập chờn. Lựa chọn mới áp dụng cho các yêu cầu tiếp theo, không cần tắt và bật lại bảo vệ.")
        active.addView(ui.text(if (configuration.mode == DnsMode.TLS) "DNS mã hóa · TLS cổng 853" else "DNS thường · chưa mã hóa", 12f, color = ui.muted))
        runCatching { configuration.requireUsable() }.exceptionOrNull()?.let { active.addView(ui.text(it.message.orEmpty(), 13f, color = ui.orange)) }
        val encryption = ui.group(content)
        val encryptionRow = ui.listRow(encryption, "Mã hóa DNS", "PrivacyGuard gửi lên máy chủ qua TLS 853. Lỗi TLS sẽ được báo, không tự chuyển về DNS thường.", "shield", ui.accent)
        val encryptionSwitch = Switch(this).apply {
            contentDescription = "Mã hóa DNS qua TLS 853"
            isChecked = configuration.mode == DnsMode.TLS
            minHeight = ui.dp(48)
            setOnCheckedChangeListener { _, checked ->
                changeDns({ store.setDnsMode(if (checked) DnsMode.TLS else DnsMode.PLAIN) }, if (checked) "Đã bật DNS mã hóa qua TLS 853." else "Đã dùng DNS thường.")
            }
        }
        encryptionRow.addView(encryptionSwitch)
        content.addView(ui.text("Ứng dụng → PrivacyGuard: UDP/TCP 53\nPrivacyGuard lọc tên miền trước khi gửi lên máy chủ. DNS hệ thống dùng tuyến này khi bảo vệ đang bật. App tự dùng DNS riêng hoặc DoH có thể đi vòng.", 13f, color = ui.muted))
        val presets = ui.group(content)
        val custom = ui.group(content)
        val add = ui.button("Thêm DNS tùy chỉnh", true) { editCustomDns() }
        content.addView(add, LinearLayout.LayoutParams(-1, -2))
        ui.gap(content, 14)
        content.addView(ui.text("PrivacyGuard chỉ dùng máy chủ đã chọn và địa chỉ dự phòng của chính máy chủ đó. Máy chủ có bộ lọc riêng có thể từ chối tên miền dù bạn đã cho phép trong PrivacyGuard.", 12f, color = ui.muted))
        refreshUi = {
            presets.removeAllViews(); custom.removeAllViews()
            val settings = store.dnsSettings
            encryptionSwitch.isEnabled = !dnsSaving
            settings.servers.forEach { server ->
                val parent = if (server.custom) custom else presets
                if (parent.childCount > 0) ui.separator(parent, 68)
                val addresses = if (settings.mode == DnsMode.TLS) server.endpoints.joinToString(" · ") { it.copy(port = 853).display } else server.endpoints.joinToString(" · ") { it.display }
                val tls = if (settings.mode == DnsMode.TLS) "\n${server.tlsName.ifEmpty { "Cần thêm tên xác thực TLS" }}" else ""
                val row = ui.listRow(parent, server.name, "${server.description}\n$addresses$tls", "globe", ui.accent,
                    if (settings.active.id == server.id) "Đang dùng" else null) {
                    if (!dnsSaving && settings.active.id != server.id) changeDns({ store.selectDns(server.id) }, "Đã chọn ${server.name}.")
                }
                ((row.getChildAt(1) as LinearLayout).getChildAt(1) as TextView).maxLines = 6
                row.isEnabled = !dnsSaving; row.isSelected = settings.active.id == server.id
                if (server.custom) {
                    row.getChildAt(row.childCount - 1).visibility = View.GONE
                    row.addView(iconButton("settings", "Chỉnh sửa ${server.name}") {
                        AlertDialog.Builder(this).setTitle(server.name).setItems(arrayOf("Sửa máy chủ", "Xóa máy chủ")) { _, item ->
                            if (item == 0) editCustomDns(server) else confirmDeleteDns(server)
                        }.show()
                    }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
                }
            }
            custom.visibility = if (settings.custom.isEmpty()) View.GONE else View.VISIBLE
            add.isEnabled = !dnsSaving
        }
        refreshUi()
    }
    private fun changeDns(change: () -> Unit, message: String, completed: ((Boolean) -> Unit)? = null) {
        if (closing || dnsSaving) return
        dnsSaving = true; refreshUi()
        io.execute {
            val result = runCatching(change)
            runOnUiThread { if (!closing) {
                dnsSaving = false
                completed?.invoke(result.isSuccess)
                result.onSuccess { toast(message) }.onFailure { toast(it.message ?: "Không lưu được lựa chọn. Hãy thử lại.") }
                if (page == "dns") render() else refreshUi()
            } }
        }
    }
    private fun editCustomDns(existing: DnsServer? = null) {
        if (dnsSaving) return
        val form = ui.column(20)
        form.addView(ui.text("Nhập địa chỉ IP của máy chủ riêng hoặc router. Bạn có thể để trống địa chỉ dự phòng.", 13f, color = ui.muted))
        ui.gap(form, 12)
        form.addView(ui.text("Tên dễ nhớ", 13f, true))
        val name = ui.field("Ví dụ: DNS ở nhà").apply { setText(existing?.name.orEmpty()); filters = arrayOf(android.text.InputFilter.LengthFilter(40)); inputType = android.text.InputType.TYPE_CLASS_TEXT }
        form.addView(name); ui.gap(form, 10)
        form.addView(ui.text("Địa chỉ IP chính", 13f, true))
        val primary = ui.field("1.1.1.1 hoặc 2606:4700:4700::1111").apply { setText(existing?.primary?.address.orEmpty()); contentDescription = "Địa chỉ IP chính" }
        form.addView(primary); ui.gap(form, 10)
        form.addView(ui.text("Địa chỉ dự phòng · không bắt buộc", 13f, true))
        val secondary = ui.field("Ví dụ: 1.0.0.1").apply { setText(existing?.secondary?.address.orEmpty()); contentDescription = "Địa chỉ IP dự phòng" }
        form.addView(secondary); ui.gap(form, 10)
        form.addView(ui.text("Cổng DNS thường · mặc định 53", 13f, true))
        val port = ui.field("53").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(String.format(java.util.Locale.ROOT, "%d", existing?.primary?.port ?: 53)); contentDescription = "Cổng DNS" }
        form.addView(port)
        ui.gap(form, 10)
        form.addView(ui.text("Tên xác thực TLS · nếu dùng mã hóa", 13f, true))
        val tlsName = ui.field("Ví dụ: dns.quad9.net").apply { setText(existing?.tlsName.orEmpty()); contentDescription = "Tên xác thực TLS" }
        form.addView(tlsName)
        form.addView(ui.text("Bật mã hóa sẽ luôn dùng cổng 853. Nhập tên trên chứng chỉ do nhà cung cấp công bố; IP ở trên vẫn là địa chỉ kết nối. Không nhập URL https:// hay tls://.", 12f, color = ui.muted))
        val error = ui.text("", 13f, color = ui.red).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }; form.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Thêm DNS tùy chỉnh" else "Sửa DNS tùy chỉnh")
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton("Hủy", null).setPositiveButton("Lưu và dùng", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val validated = runCatching {
                    val number = if (port.text.isBlank()) 53 else port.text.toString().toIntOrNull()
                        ?: throw IllegalArgumentException("Cổng phải là số từ 1 đến 65535.")
                    DnsServers.custom(existing?.id ?: "custom-${java.util.UUID.randomUUID()}", name.text.toString(), primary.text.toString(), secondary.text.toString(), number, tlsName.text.toString())
                        .also { server -> store.dnsSettings.copy(custom = store.dnsSettings.custom.filterNot { it.id == server.id } + server, selectedId = server.id).requireUsable() }
                }
                validated.onSuccess { server ->
                    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = false
                    changeDns({ store.saveCustomDns(server) }, "Đã lưu và chọn ${server.name}.") { saved ->
                        if (saved) dialog.dismiss() else {
                            error.text = getString(R.string.dns_save_error)
                            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = true
                        }
                    }
                }.onFailure { error.text = it.message }
            }
        }
        dialog.show()
    }
    private fun confirmDeleteDns(server: DnsServer) {
        val active = store.dnsSettings.active.id == server.id
        AlertDialog.Builder(this).setTitle("Xóa ${server.name}?")
            .setMessage(if (active) "Máy chủ này đang được chọn. Sau khi xóa, PrivacyGuard sẽ dùng Quad9." else "Máy chủ này sẽ được gỡ khỏi danh sách tùy chỉnh.")
            .setNegativeButton("Hủy", null).setPositiveButton("Xóa") { _, _ -> changeDns({ store.deleteCustomDns(server.id) }, "Đã xóa máy chủ.") }.show()
    }
    private fun requestDetails(event: GuardEvent, scope: TrafficScope) {
        var dialog: AlertDialog? = null
        val words = RequestText.forEvent(event)
        val detail = ui.column(20)
        detail.addView(ui.text(words.title, 22f, true, outcomeColor(event.outcome)))
        detail.addView(ui.text(event.domain, 18f, true).apply { setTextIsSelectable(true) })
        detail.addView(ui.text("${appName(event.app)} · ${time(event.time, "dd/MM HH:mm")}", 13f, color = ui.muted)); ui.gap(detail, 12)
        detail.addView(ui.text(words.explanation, 15f)); ui.gap(detail, 10)
        detail.addView(ui.text("Bạn có thể làm gì?", 16f, true)); detail.addView(ui.text(words.suggestion, 14f, color = ui.muted))
        ui.gap(detail, 12)
        detail.addView(ui.text("URL và tham số", 16f, true))
        detail.addView(ui.text("DNS chỉ cho biết tên miền, không chứa đường dẫn hoặc query param của HTTPS. Phân tích URL bạn có trong Link sạch hoặc bật xem yêu cầu trong phiên riêng tư.", 13f, color = ui.muted))
        detail.addView(ui.button("Phân tích URL") { dialog?.dismiss(); navigate("cleaner") })
        if (event.outcome == Outcome.BLOCKED) {
            ui.gap(detail, 12)
            detail.addView(ui.text("Phạm vi luật: ${if (scope is TrafficScope.App) appName(scope.packageName) else "Tất cả ứng dụng"}", 14f, color = ui.accent))
            if (scope !is TrafficScope.App) detail.addView(ui.text("Cho phép từ màn hình này sẽ áp dụng cho mọi ứng dụng.", 13f, color = ui.orange))
        }
        ui.gap(detail, 14)
        detail.addView(ui.button("Chi tiết kỹ thuật") {
            showInfo("Thông tin yêu cầu", "Tên miền: ${event.domain}\nỨng dụng: ${event.app ?: "Android chưa cung cấp ứng dụng gốc"}\nThời điểm: ${time(event.time, "dd/MM/yyyy HH:mm:ss")}\nNhóm: ${event.category.label}\nTrạng thái: ${event.outcome.label}\nLý do ghi nhận: ${event.reason}")
        })
        val builder = AlertDialog.Builder(this).setTitle(if (event.outcome == Outcome.BLOCKED) "Yêu cầu bị chặn" else if (event.outcome == Outcome.FAILED) "Yêu cầu gặp lỗi" else "Chi tiết yêu cầu")
            .setView(ScrollView(this).apply { addView(detail) }).setNegativeButton("Đóng", null)
        when (event.outcome) {
            Outcome.BLOCKED -> builder.setPositiveButton("Cho phép") { _, _ -> saveDomainRule(event.domain, scope, Action.ALLOW) }
                .setNeutralButton("Xem luật") { _, _ -> selectedApp = (scope as? TrafficScope.App)?.packageName; navigate("rules") }
            Outcome.FAILED -> builder.setPositiveButton("Đổi máy chủ DNS") { _, _ -> openDns() }
            Outcome.FORWARDED -> builder.setPositiveButton("Chặn") { _, _ -> saveDomainRule(event.domain, scope, Action.BLOCK) }
        }
        dialog = builder.show()
    }

    private fun showInfo(title: String, body: String) = AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Đóng", null).show()
    private fun requestExport(produce: () -> String) {
        io.execute {
            val exported = runCatching(produce)
            runOnUiThread { if (!closing) exported.onSuccess { json ->
                pendingExport = json
                runCatching { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "privacyguard.json"), EXPORT_REQUEST) }
                    .onFailure { pendingExport = null; toast("Không mở được trình lưu tệp.") }
            }.onFailure { toast("Không xuất được dữ liệu.") } }
        }
    }
    private var browserInput: EditText? = null
    private fun scopeOf(app: String?): TrafficScope = if (app == null) TrafficScope.Unknown else TrafficScope.App(app)
    private fun total(outcome: Outcome) = counters.filter { it.outcome == outcome }.sumOf { it.count }
    private fun number(count: Long) = NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("vi-VN")).format(count)
    private fun value(count: Long) = if (loading || loadError) "—" else number(count)
    private fun time(value: Long, pattern: String) = DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
    private fun outcomeColor(outcome: Outcome) = when (outcome) { Outcome.BLOCKED -> ui.red; Outcome.FORWARDED -> ui.green; Outcome.FAILED -> ui.orange }
    private fun watch(input: EditText, changed: (String) -> Unit) = input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed(s.toString())
        override fun afterTextChanged(s: Editable?) = Unit
    })
    private fun prepareVpn() {
        try {
            val consent = VpnService.prepare(this)
            if (consent != null) startActivityForResult(consent, VPN_REQUEST) else startVpn()
        } catch (_: Exception) { toast("Thiết bị không cho phép tạo VPN.") }
    }

    private fun startVpn() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
        try { startForegroundService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.START)) }
        catch (_: Exception) { toast("Không khởi động được dịch vụ VPN.") }
    }

    private fun cleanerPage() {
        val cleaner = ui.card(content, "Link gọn, chia sẻ an tâm", "Bỏ utm_*, fbclid, gclid và tham số tùy chỉnh. URL chỉ được xử lý trong bộ nhớ; không lưu vào lịch sử.")
        val input = ui.field("https://example.com/?utm_source=email", true).apply { setText(cleanerDraft); maxLines = 6 }
        cleaner.addView(input); ui.gap(cleaner, 12)
        val result = ui.text("").apply { setTextIsSelectable(true); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val actions = ui.column()
        fun showResult(url: String, removed: List<String>) {
            cleanedUrl = url
            result.text = getString(R.string.clean_result, if (removed.isEmpty()) getString(R.string.link_already_clean)
                else resources.getQuantityString(R.plurals.link_removed, removed.size, removed.size, removed.joinToString(", ")), url)
            actions.removeAllViews()
            actions.addView(ui.button("Sao chép link sạch", true) {
                val clip = ClipData.newPlainText("Link sạch", url)
                if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip); toast("Đã sao chép.")
            })
            actions.addView(ui.button("Chia sẻ link sạch") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Chia sẻ link sạch"))
            })
            actions.addView(ui.button("Mở trong phiên riêng tư") { openBrowser(url) })
        }
        cleaner.addView(ui.button("Làm sạch link", true) {
            cleanerDraft = input.text.toString()
            try { val cleaned = LinkCleaner.clean(cleanerDraft, store.customParams); showResult(cleaned.url, cleaned.removed) }
            catch (e: IllegalArgumentException) { cleanedUrl = null; actions.removeAllViews(); result.text = e.message }
        })
        val analysisPanel = ui.column()
        cleaner.addView(ui.button("Phân tích URL") {
            urlAnalysis = null; analysisPanel.removeAllViews()
            runCatching { UrlAnalyzer.analyze(input.text.toString()) }.onSuccess { analysis ->
                urlAnalysis = analysis; showUrlAnalysis(ui, analysisPanel, analysis)
            }.onFailure { analysisPanel.addView(ui.text(it.message ?: "Không đọc được URL.", 14f, color = ui.red)) }
        })
        cleaner.addView(analysisPanel)
        urlAnalysis?.let { showUrlAnalysis(ui, analysisPanel, it) }
        ui.gap(cleaner, 12); cleaner.addView(result); cleaner.addView(actions)
        cleanedUrl?.let { url -> showResult(url, runCatching { LinkCleaner.clean(cleanerDraft, store.customParams).removed }.getOrDefault(emptyList())) }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { cleanerDraft = s.toString(); cleanedUrl = null; urlAnalysis = null; analysisPanel.removeAllViews(); result.text = ""; actions.removeAllViews() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val custom = ui.card(content, "Tham số tùy chỉnh", "Phân cách bằng dấu phẩy; dấu * ở cuối để khớp tiền tố, ví dụ ref, campaign_*. Hãy kiểm tra link trước khi chia sẻ.")
        val parameters = ui.field("ref, campaign_*").apply { setText(store.customParams.joinToString(", ")) }; custom.addView(parameters)
        custom.addView(ui.button("Lưu tham số") {
            try { store.saveCustomParams(parameters.text.toString()); cleanedUrl = null; render(); toast("Đã lưu tham số.") }
            catch (e: IllegalArgumentException) { toast(e.message.orEmpty()) }
        })
        browserCard()
    }

    private fun browserCard() {
        val browser = ui.card(content, "Phiên duyệt riêng tư", "Kho cookie riêng, chặn tracker của bên thứ ba từ danh sách khởi đầu. Cookie, cache và dữ liệu website được xóa khi kết thúc và trước phiên tiếp theo.")
        val url = ui.field("https://example.com").apply { setText(browserDraft) }; browser.addView(url); browserInput = url; ui.gap(browser, 12)
        browser.addView(ui.button("Bắt đầu phiên riêng tư", true) { browserDraft = url.text.toString(); openBrowser(browserDraft) })
        browser.addView(ui.text("Chỉ HTTPS · cookie riêng · không lưu lịch sử duyệt web. Website và nhà mạng vẫn có thể thấy địa chỉ IP của bạn.", 12f, color = ui.muted))
    }

    private fun openBrowser(input: String) {
        try {
            val cleaned = LinkCleaner.clean(input, store.customParams)
            require(java.net.URI(cleaned.url).scheme.equals("https", ignoreCase = true)) { "Phiên riêng tư chỉ mở URL HTTPS." }
            startActivity(Intent(this, PrivateBrowserActivity::class.java).putExtra("url", cleaned.url).putExtra("policy", PolicyCodec.encode(store.policy)))
        } catch (e: IllegalArgumentException) { toast(e.message.orEmpty()) }
    }

    private fun spinner(labels: List<String>, selected: Int = 0, changed: ((Int) -> Unit)? = null): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
        minimumHeight = ui.dp(48); setSelection(selected)
        if (changed != null) onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var current = selected
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { if (position != current) { current = position; changed(position) } }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun appName(id: String?) = if (id == null) "Chưa xác định ứng dụng" else apps.find { it.id == id }?.name ?: id
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    @Deprecated("Legacy Activity result API keeps this MVP free of AndroidX dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            VPN_REQUEST -> if (resultCode == RESULT_OK) startVpn() else toast("Chưa cấp quyền VPN; lọc DNS vẫn tắt.")
            EXPORT_REQUEST -> {
                val json = pendingExport; pendingExport = null
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null && json != null) io.execute {
                    val saved = runCatching { checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(json.toByteArray(Charsets.UTF_8)) } }
                    runOnUiThread { if (!closing) toast(if (saved.isSuccess) "Đã xuất dữ liệu." else "Không ghi được tệp xuất.") }
                }
            }
        }
    }

    companion object { private const val PAGE_SIZE = 30; private const val VPN_REQUEST = 10; private const val EXPORT_REQUEST = 11; private const val NOTIFICATION_REQUEST = 12 }
}
