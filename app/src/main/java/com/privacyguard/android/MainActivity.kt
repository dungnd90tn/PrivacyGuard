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
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.privacyguard.android.core.Action
import com.privacyguard.android.core.Category
import com.privacyguard.android.core.DomainRule
import com.privacyguard.android.core.LinkCleaner
import com.privacyguard.android.core.Outcome
import com.privacyguard.android.core.Rules
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: GuardStore
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private data class InstalledApp(val id: String, val name: String)
    private var apps = emptyList<InstalledApp>()
    private var counters = emptyList<Counter>()
    private var events = emptyList<GuardEvent>()
    private var page = "overview"
    private var selectedApp: String? = null
    private var days = 7
    private var cleanerDraft = ""
    private var cleanedUrl: String? = null
    private var browserDraft = ""
    private var pendingExport: String? = null
    private var receiverRegistered = false
    private var loading = true
    private var loadError = false
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (page == "overview") render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GuardStore(this); ui = Ui(this)
        page = savedInstanceState?.getString("page") ?: "overview"
        selectedApp = savedInstanceState?.getString("selectedApp")
        days = savedInstanceState?.getInt("days", 7) ?: 7
        handleShare(intent)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page); outState.putString("selectedApp", selectedApp); outState.putInt("days", days)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleShare(intent); render() }

    private fun handleShare(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND || incoming.type != "text/plain") return
        val text = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(8192)
        cleanerDraft = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).find(text)?.value ?: text
        cleanedUrl = null; page = "cleaner"
        // Remove the shared text from the activity's retained Intent as soon as it is read.
        incoming.removeExtra(Intent.EXTRA_TEXT); incoming.action = Intent.ACTION_MAIN
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            val filter = IntentFilter(VpnState.CHANGED)
            // The signature permission also protects the receiver on Android 10–12.
            registerReceiver(stateReceiver, filter, "com.privacyguard.android.permission.INTERNAL", null,
                if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0)
            receiverRegistered = true
        }
        if (!loading) refresh()
    }

    override fun onPause() {
        if (receiverRegistered) { unregisterReceiver(stateReceiver); receiverRegistered = false }
        super.onPause()
    }

    override fun onDestroy() {
        io.execute { store.close() }; io.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        if (isDestroyed) return
        render()
        io.execute {
            val result = runCatching {
                val installed = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
                    .distinctBy { it.id }.sortedBy { it.name.lowercase() }
                Triple(installed, store.counters(days), store.events())
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { (installed, totals, history) ->
                    apps = installed; counters = totals; events = history
                    if (selectedApp != null && installed.none { it.id == selectedApp }) selectedApp = null
                }
                loadError = result.isFailure; loading = false; render()
            }
        }
    }

    private fun render() {
        if (!::ui.isInitialized || isDestroyed) return
        val root = ui.column().apply { setBackgroundColor(ui.background); fitsSystemWindows = true }
        val header = ui.column(20).apply { setBackgroundColor(ui.ink) }
        val brand = ui.row()
        brand.addView(android.widget.ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher); contentDescription = "Logo PrivacyGuard"
        }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(10) })
        brand.addView(ui.text("PrivacyGuard", 23f, true, Color.WHITE))
        header.addView(brand)
        header.addView(ui.text("Quyền riêng tư, theo cách của bạn.", 13f, color = Color.rgb(205, 220, 199)))
        root.addView(header)
        val navigation = ui.row()
        listOf("overview" to "Tổng quan", "rules" to "Luật", "cleaner" to "Link", "activity" to "Lịch sử", "settings" to "Cài đặt").forEach { (id, label) ->
            navigation.addView(ui.button(label, id == page) { page = id; render() }, LinearLayout.LayoutParams(ui.dp(98), -2))
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(navigation) })
        content = ui.column(16)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (loadError) content.addView(ui.text("Không đọc được thống kê cục bộ. Dữ liệu chưa được xác minh; hãy thử làm mới.", color = Color.rgb(151, 43, 34)))
        when (page) {
            "rules" -> rulesPage()
            "cleaner" -> cleanerPage()
            "activity" -> activityPage()
            "settings" -> settingsPage()
            else -> overviewPage()
        }
    }

    private fun overviewPage() {
        val protection = ui.card(content, VpnState.status.label)
        protection.addView(ui.text("Chỉ lọc DNS thường (UDP/TCP, IPv4/IPv6) gửi tới DNS của VPN. Lưu lượng khác đi trực tiếp; DNS mã hóa và DNS tự chọn có thể bỏ qua bộ lọc.", 14f, color = ui.muted))
        if (VpnState.message.isNotEmpty()) protection.addView(ui.text(VpnState.message, color = Color.rgb(151, 43, 34)))
        protection.addView(ui.button(if (VpnState.status == VpnState.Status.RUNNING) "Dừng lọc DNS" else "Bật lọc DNS", true) {
            if (VpnState.status == VpnState.Status.RUNNING) startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.STOP))
            else prepareVpn()
        }.apply { isEnabled = VpnState.status != VpnState.Status.STARTING })
        val summary = ui.card(content, "Số liệu thực tế", "${if (days == 1) "Hôm nay" else "7 ngày lịch gần nhất"} · không có dữ liệu mô phỏng")
        summary.addView(ui.button(if (days == 7) "Xem hôm nay" else "Xem 7 ngày") { days = if (days == 7) 1 else 7; refresh() })
        if (loading || loadError) summary.addView(ui.text(if (loading) "Đang đọc thống kê…" else "Thống kê không khả dụng"))
        else {
            Outcome.entries.forEach { outcome -> summary.addView(ui.text("${outcome.label}: ${counters.filter { it.outcome == outcome }.sumOf { it.count }}", 21f, true)) }
            summary.addView(ui.text("Đếm mỗi phản hồi DNS đã trả qua VPN. Lỗi resolver được tính riêng; một tên miền được hỏi nhiều lần sẽ được đếm nhiều lần.", 13f, color = ui.muted))
        }
        if (VpnState.statisticsError) summary.addView(ui.text("Một số phản hồi chưa ghi được thống kê; bộ đếm có thể thiếu.", color = Color.rgb(151, 43, 34)))
        summary.addView(ui.button("Làm mới") { refresh() })
        val grouped = ui.card(content, "Theo ứng dụng", "DNS hệ thống thường không cung cấp ứng dụng gốc. Chỉ áp dụng luật ứng dụng khi UID xác định được một package duy nhất.")
        if (!loading && !loadError && counters.isEmpty()) grouped.addView(ui.text("Chưa có truy vấn. Bật lọc DNS rồi sử dụng các ứng dụng trên thiết bị.", 14f, color = ui.muted))
        counters.groupBy { it.app }.entries.sortedByDescending { it.value.sumOf { c -> c.count } }.take(30).forEach { (app, values) ->
            grouped.addView(ui.text("${appName(app)}\n${values.filter { it.outcome == Outcome.BLOCKED }.sumOf { it.count }} chặn · ${values.filter { it.outcome == Outcome.FORWARDED }.sumOf { it.count }} chuyển tiếp · ${values.filter { it.outcome == Outcome.FAILED }.sumOf { it.count }} lỗi", 14f))
        }
        browserCard()
    }

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

    private fun rulesPage() {
        val policy = store.policy
        val categories = ui.card(content, "Luật ứng dụng", "Ưu tiên: ngoại lệ ứng dụng → ngoại lệ toàn cục → nhóm ứng dụng → nhóm toàn cục → cho phép. Chặn nhóm Thiết yếu có thể làm ứng dụng ngừng hoạt động.")
        val scopeIds = listOf<String?>(null) + apps.map { it.id }
        val scopeNames = listOf("Mặc định toàn cục") + apps.map { "${it.name} · ${it.id}" }
        categories.addView(spinner(scopeNames, scopeIds.indexOf(selectedApp).coerceAtLeast(0)) { index ->
            selectedApp = scopeIds[index]; render()
        })
        listOf(Category.ADS, Category.ANALYTICS, Category.ESSENTIAL).forEach { category ->
            val action = if (selectedApp == null) policy.categories[category] else policy.apps[selectedApp]?.get(category)
            val label = "${category.label}: ${when (action) { Action.BLOCK -> "Chặn"; Action.ALLOW -> "Cho phép"; null -> "Theo mặc định" }}"
            categories.addView(ui.button(label) {
                val labels = if (selectedApp == null) arrayOf("Chặn", "Cho phép") else arrayOf("Chặn", "Cho phép", "Theo mặc định")
                AlertDialog.Builder(this).setTitle(category.label).setItems(labels) { _, index ->
                    store.setCategory(selectedApp, category, listOf(Action.BLOCK, Action.ALLOW, null)[index]); render()
                }.show()
            })
        }
        val exceptions = ui.card(content, "Ngoại lệ tên miền", "*.example.com chỉ khớp tên miền con. Lưu cùng tên miền/phạm vi sẽ thay thế luật cũ.")
        val domain = ui.field("example.com hoặc *.example.com")
        exceptions.addView(domain)
        val exceptionIds = listOf("*") + apps.map { it.id }
        val scope = spinner(listOf("Tất cả ứng dụng") + apps.map { it.name })
        exceptions.addView(scope)
        val decision = spinner(listOf("Cho phép", "Chặn")); exceptions.addView(decision)
        val error = ui.text("", color = Color.rgb(151, 43, 34)).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        exceptions.addView(error)
        exceptions.addView(ui.button("Lưu ngoại lệ", true) {
            try {
                store.saveException(domain.text.toString(), exceptionIds[scope.selectedItemPosition], if (decision.selectedItemPosition == 0) Action.ALLOW else Action.BLOCK)
                render(); toast("Đã lưu ngoại lệ.")
            } catch (e: IllegalArgumentException) { error.text = e.message }
        })
        policy.exceptions.forEach { rule ->
            exceptions.addView(ui.text("${rule.domain}\n${if (rule.app == "*") "Toàn cục" else appName(rule.app)} · ${if (rule.action == Action.BLOCK) "Chặn" else "Cho phép"}", 14f, true))
            exceptions.addView(ui.button("Xóa ${rule.domain}") { store.deleteException(rule); render() })
        }
        val tester = ui.card(content, "Thử quyết định", "Chỉ đánh giá luật; không truy cập mạng và không tăng bộ đếm.")
        val testDomain = ui.field("ads.example.com"); tester.addView(testDomain)
        val testScope = spinner(listOf("Không rõ ứng dụng") + apps.map { it.name }); tester.addView(testScope)
        val result = ui.text("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        tester.addView(ui.button("Kiểm tra") {
            try {
                val tested = Rules.decide(testDomain.text.toString(), (listOf<String?>(null) + apps.map { it.id })[testScope.selectedItemPosition], store.policy)
                result.text = getString(R.string.test_decision, if (tested.action == Action.BLOCK) "Chặn" else "Cho phép", tested.category.label, tested.reason)
            } catch (e: IllegalArgumentException) { result.text = e.message }
        }); tester.addView(result)
    }

    private fun cleanerPage() {
        val cleaner = ui.card(content, "Link gọn, chia sẻ an tâm", "Bỏ utm_*, fbclid, gclid và tham số tùy chỉnh. URL chỉ được xử lý trong bộ nhớ; không lưu vào lịch sử.")
        val input = ui.field("https://example.com/?utm_source=email", true).apply { setText(cleanerDraft); maxLines = 6 }
        cleaner.addView(input)
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
        cleaner.addView(result); cleaner.addView(actions)
        cleanedUrl?.let { url -> showResult(url, runCatching { LinkCleaner.clean(cleanerDraft, store.customParams).removed }.getOrDefault(emptyList())) }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { cleanerDraft = s.toString(); cleanedUrl = null; result.text = ""; actions.removeAllViews() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val custom = ui.card(content, "Tham số tùy chỉnh", "Phân cách bằng dấu phẩy; dấu * ở cuối để khớp tiền tố, ví dụ ref, campaign_*. Hãy kiểm tra link trước khi chia sẻ.")
        val parameters = ui.field("ref, campaign_*").apply { setText(store.customParams.joinToString(", ")) }; custom.addView(parameters)
        custom.addView(ui.button("Lưu tham số") {
            try { store.saveCustomParams(parameters.text.toString()); cleanedUrl = null; render(); toast("Đã lưu tham số.") }
            catch (e: IllegalArgumentException) { toast(e.message.orEmpty()) }
        })
    }

    private fun activityPage() {
        val history = ui.card(content, "Nhật ký DNS", "Tối đa 2.000 sự kiện trong 7 ngày. Không lưu nội dung gói tin hoặc URL duyệt web.")
        history.addView(ui.button("Làm mới") { refresh() })
        if (!store.detailed) { history.addView(ui.text("Nhật ký tên miền đang tắt. Bạn vẫn có bộ đếm tổng hợp; bật nhật ký tại Cài đặt nếu cần kiểm tra chi tiết.", 14f)); return }
        if (loading || loadError) { history.addView(ui.text(if (loading) "Đang tải…" else "Không đọc được nhật ký.")); return }
        val search = ui.field("Tìm tên miền hoặc ứng dụng"); history.addView(search)
        val filter = spinner(listOf("Tất cả", "Đã chặn", "Đã chuyển tiếp", "Lỗi DNS")); history.addView(filter)
        val list = ui.column(); history.addView(list)
        val outcomes = listOf(null, Outcome.BLOCKED, Outcome.FORWARDED, Outcome.FAILED)
        fun showEvents() {
            list.removeAllViews()
            val matching = events.filter { event ->
                (outcomes[filter.selectedItemPosition] == null || event.outcome == outcomes[filter.selectedItemPosition]) &&
                    "${event.domain} ${appName(event.app)}".contains(search.text.toString(), ignoreCase = true)
            }
            if (matching.isEmpty()) list.addView(ui.text("Chưa có sự kiện khớp bộ lọc.", 14f, color = ui.muted))
            matching.forEach { event ->
                list.addView(ui.text("${event.outcome.label} · ${event.domain}", 15f, true))
                list.addView(ui.text("${appName(event.app)} · ${event.category.label}\n${DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").format(Instant.ofEpochMilli(event.time).atZone(ZoneId.systemDefault()))} · ${event.reason}", 12f, color = ui.muted))
            }
            list.addView(ui.text("Hiển thị ${matching.size} trên ${events.size} sự kiện gần nhất (tối đa 100 trên màn hình). Xuất JSON để xem toàn bộ.", 12f, color = ui.muted))
        }
        filter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = showEvents()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = showEvents()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        showEvents()
    }

    private fun browserCard() {
        val browser = ui.card(content, "Phiên duyệt riêng tư", "Kho cookie riêng, chặn tracker của bên thứ ba từ danh sách khởi đầu. Cookie, cache và dữ liệu website được xóa khi kết thúc và trước phiên tiếp theo.")
        val url = ui.field("https://example.com").apply { setText(browserDraft) }; browser.addView(url)
        browser.addView(ui.button("Bắt đầu phiên riêng tư", true) { browserDraft = url.text.toString(); openBrowser(browserDraft) })
        browser.addView(ui.text("Chỉ HTTPS. Không tạo ẩn danh với website hoặc nhà mạng. Chính sách xóa bao gồm cookie, cache và WebStorage; cần kiểm chứng trên thiết bị. Không cam kết xóa dấu vết ngoài kho dữ liệu của ứng dụng.", 12f, color = ui.muted))
    }

    private fun openBrowser(input: String) {
        try {
            val cleaned = LinkCleaner.clean(input, store.customParams)
            require(java.net.URI(cleaned.url).scheme.equals("https", ignoreCase = true)) { "Phiên riêng tư chỉ mở URL HTTPS." }
            startActivity(Intent(this, PrivateBrowserActivity::class.java).putExtra("url", cleaned.url).putExtra("policy", PolicyCodec.encode(store.policy)))
        } catch (e: IllegalArgumentException) { toast(e.message.orEmpty()) }
    }

    private fun settingsPage() {
        val privacy = ui.card(content, "Dữ liệu trên thiết bị", "Mặc định chỉ lưu bộ đếm tổng hợp trong 7 ngày. Không có tài khoản, máy chủ thống kê hoặc telemetry.")
        privacy.addView(Switch(this).apply {
            setText(R.string.detailed_history); isChecked = store.detailed; minHeight = ui.dp(48)
            setTextColor(ui.ink)
            setOnCheckedChangeListener { _, checked -> io.execute {
                val saved = runCatching { store.detailed = checked }
                runOnUiThread { if (!isDestroyed) { refresh(); if (saved.isFailure) toast("Không thay đổi được nhật ký; dữ liệu chưa được xóa.") } }
            } }
        })
        privacy.addView(ui.text("Tắt nhật ký sẽ xóa ngay tên miền đã lưu; bộ đếm tổng hợp vẫn còn. Khi lọc hoạt động, truy vấn được gửi tới Quad9 (9.9.9.9), dự phòng Cloudflare (1.1.1.1), qua DNS thường.", 13f, color = ui.muted))
        privacy.addView(ui.button("Xuất dữ liệu JSON") {
            io.execute {
                val exported = runCatching { store.export() }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    exported.onSuccess { json ->
                        pendingExport = json
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "privacyguard.json"), EXPORT_REQUEST)
                    }.onFailure { toast("Không xuất được dữ liệu.") }
                }
            }
        })
        privacy.addView(ui.button("Xóa nhật ký và bộ đếm") {
            AlertDialog.Builder(this).setTitle("Xóa dữ liệu quan sát?").setMessage("Luật và tham số tùy chỉnh được giữ lại.")
                .setNegativeButton("Hủy", null).setPositiveButton("Xóa") { _, _ ->
                    io.execute {
                        val cleared = runCatching { store.clearHistory() }
                        runOnUiThread { if (!isDestroyed) { refresh(); toast(if (cleared.isSuccess) "Đã xóa dữ liệu." else "Không xóa được dữ liệu; hãy thử lại.") } }
                    }
                }.show()
        })
        val coverage = ui.card(content, "Phạm vi MVP 0.1")
        coverage.addView(ui.text("• VPN lọc DNS qua UDP/TCP với IPv4/IPv6; không có đường hầm toàn bộ Internet.\n• Không giải mã HTTPS, không chặn DoH/DoT hoặc DNS tự chọn.\n• Nhận diện ứng dụng khi Android cung cấp UID đáng tin cậy; trường hợp khác hiển thị Không rõ ứng dụng.\n• Danh sách tracker khởi đầu nhỏ, không đầy đủ. Domain dùng chung có thể ảnh hưởng chức năng thiết yếu.\n• Trình duyệt dùng WebView, chặn tracker bên thứ ba theo tên miền; không kiểm tra lại mọi redirect. Service worker không được truy cập mạng.", 14f, color = ui.muted))
        coverage.addView(ui.text(Rules.trackers.keys.joinToString("\n"), 12f, color = ui.muted))
    }

    private fun spinner(labels: List<String>, selected: Int = 0, changed: ((Int) -> Unit)? = null): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
        minimumHeight = ui.dp(48); setSelection(selected)
        if (changed != null) onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { if (position != selected) changed(position) }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun appName(id: String?) = if (id == null) "Không rõ ứng dụng" else apps.find { it.id == id }?.name ?: id
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
                    runOnUiThread { if (!isDestroyed) toast(if (saved.isSuccess) "Đã xuất dữ liệu." else "Không ghi được tệp xuất.") }
                }
            }
        }
    }

    companion object { private const val VPN_REQUEST = 10; private const val EXPORT_REQUEST = 11; private const val NOTIFICATION_REQUEST = 12 }
}
