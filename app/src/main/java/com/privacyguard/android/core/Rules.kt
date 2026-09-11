package com.privacyguard.android.core

import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

enum class Category(val label: String) { ADS("Quảng cáo"), ANALYTICS("Phân tích"), ESSENTIAL("Thiết yếu"), UNKNOWN("Chưa phân loại") }
enum class Action { BLOCK, ALLOW }
data class DomainRule(val domain: String, val action: Action, val app: String = "*")
data class Decision(val domain: String, val category: Category, val action: Action, val reason: String)
data class Policy(
    val categories: Map<Category, Action> = mapOf(Category.ADS to Action.BLOCK, Category.ANALYTICS to Action.BLOCK, Category.ESSENTIAL to Action.ALLOW),
    val apps: Map<String, Map<Category, Action>> = emptyMap(),
    val exceptions: List<DomainRule> = emptyList()
)

object Rules {
    // Small, transparent starter list; not a comprehensive tracker feed.
    val trackers = mapOf(
        "doubleclick.net" to Category.ADS,
        "googleadservices.com" to Category.ADS,
        "googlesyndication.com" to Category.ADS,
        "google-analytics.com" to Category.ANALYTICS,
        "app-measurement.com" to Category.ANALYTICS,
        "ads.example.com" to Category.ADS,
        "metrics.example.com" to Category.ANALYTICS,
        "api.example.com" to Category.ESSENTIAL
    )
    fun normalize(input: String, wildcard: Boolean = false): String {
        var raw = input.trim().lowercase(Locale.ROOT).removeSuffix(".")
        val prefix = if (wildcard && raw.startsWith("*.")) "*." else ""
        if (prefix.isNotEmpty()) raw = raw.substring(2)
        require(raw.isNotBlank() && !raw.any { it.isWhitespace() || it in "/:?#@*\\%" }) { "Nhập tên miền, ví dụ example.com hoặc *.example.com" }
        val domain = try { IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES) } catch (_: Exception) { throw IllegalArgumentException("Tên miền không hợp lệ") }
        require(domain.length <= 253 && domain.contains('.') && domain.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) { "Tên miền không hợp lệ" }
        return prefix + domain
    }
    fun matches(domain: String, pattern: String): Boolean = if (pattern.startsWith("*.")) domain.endsWith(pattern.substring(1)) else domain == pattern
    private fun best(domain: String, rules: List<DomainRule>): DomainRule? = rules.filter { matches(domain, it.domain) }
        .sortedWith(compareBy<DomainRule> { it.domain.startsWith("*.") }.thenByDescending { it.domain.length }).firstOrNull()
    fun decide(input: String, app: String?, policy: Policy): Decision {
        val domain = normalize(input)
        val category = trackers.entries.filter { domain == it.key || domain.endsWith(".${it.key}") }.maxByOrNull { it.key.length }?.value ?: Category.UNKNOWN
        val specific = app?.let { best(domain, policy.exceptions.filter { rule -> rule.app == app }) }
        val global = best(domain, policy.exceptions.filter { it.app == "*" })
        val exception = specific ?: global
        if (exception != null) return Decision(domain, category, exception.action, if (specific != null) "Ngoại lệ ứng dụng" else "Ngoại lệ toàn cục")
        val action = policy.apps[app]?.get(category) ?: policy.categories[category] ?: Action.ALLOW
        return Decision(domain, category, action, if (category == Category.UNKNOWN) "Không có luật khớp" else "Luật ${category.label.lowercase(Locale.ROOT)}")
    }
}

data class CleanedLink(val url: String, val removed: List<String>)
object LinkCleaner {
    fun clean(input: String, custom: List<String> = emptyList()): CleanedLink {
        val value = input.trim()
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("URL không hợp lệ") }
        require(uri.scheme?.lowercase(Locale.ROOT) in listOf("https", "http") && !uri.host.isNullOrBlank()) { "Nhập URL đầy đủ bắt đầu bằng https:// hoặc http://" }
        require(uri.rawUserInfo == null) { "Hãy bỏ tên đăng nhập hoặc mật khẩu khỏi URL" }
        val patterns = (listOf("utm_*", "fbclid", "gclid") + custom).map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        val removed = mutableListOf<String>()
        val query = uri.rawQuery?.split('&')?.filter { part ->
            val key = try { URLDecoder.decode(part.substringBefore('='), "UTF-8") } catch (_: Exception) { part.substringBefore('=') }
            val low = key.lowercase(Locale.ROOT)
            val match = patterns.any { if (it.endsWith('*')) low.startsWith(it.dropLast(1)) else low == it }
            if (match) removed += key
            !match
        }?.joinToString("&")
        val result = buildString {
            append(value.substringBefore('#').substringBefore('?'))
            if (!query.isNullOrEmpty()) append("?$query")
            if (uri.rawFragment != null) append("#${uri.rawFragment}")
        }
        return CleanedLink(result, removed)
    }
}
