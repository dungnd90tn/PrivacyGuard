package com.privacyguard.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.privacyguard.android.core.Action
import com.privacyguard.android.core.Category
import com.privacyguard.android.core.DnsResult
import com.privacyguard.android.core.DnsServer
import com.privacyguard.android.core.DnsSettings
import com.privacyguard.android.core.DnsServers
import com.privacyguard.android.core.DnsMode
import com.privacyguard.android.core.DomainRule
import com.privacyguard.android.core.Outcome
import com.privacyguard.android.core.Policy
import com.privacyguard.android.core.Rules
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object PolicyCodec {
    fun encode(policy: Policy): String = JSONObject().apply {
        put("categories", actions(policy.categories))
        put("apps", JSONObject().apply { policy.apps.forEach { (app, values) -> put(app, actions(values)) } })
        put("exceptions", JSONArray().apply { policy.exceptions.forEach { rule ->
            put(JSONObject().put("domain", rule.domain).put("action", rule.action.name).put("app", rule.app))
        } })
    }.toString()

    private fun actions(values: Map<Category, Action>) = JSONObject().apply {
        values.forEach { (category, action) -> put(category.name, action.name) }
    }

    private fun readActions(json: JSONObject?): Map<Category, Action> = Category.entries.mapNotNull { category ->
        val action = runCatching { Action.valueOf(json?.optString(category.name).orEmpty()) }.getOrNull()
        action?.let { category to it }
    }.toMap()

    fun decode(raw: String?): Policy {
        val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return Policy()
        val apps = json.optJSONObject("apps")
        val exceptions = json.optJSONArray("exceptions") ?: JSONArray()
        // Duplicate normalized domain/scope keys replace one another, even in recovered storage.
        val rules = linkedMapOf<Pair<String, String>, DomainRule>()
        for (i in 0 until minOf(exceptions.length(), 500)) {
            runCatching {
                val entry = exceptions.getJSONObject(i)
                val rule = DomainRule(Rules.normalize(entry.getString("domain"), true),
                    Action.valueOf(entry.getString("action")), entry.getString("app"))
                require(rule.app == "*" || rule.app.matches(Regex("[A-Za-z0-9_.]+")))
                rules[rule.app to rule.domain] = rule
            }
        }
        return Policy(Policy().categories + readActions(json.optJSONObject("categories")),
            apps?.keys()?.asSequence()?.take(500)?.associateWith { readActions(apps.optJSONObject(it)) }.orEmpty(), rules.values.toList())
    }
}

class GuardStore(context: Context) : AutoCloseable {
    private val prefs = context.applicationContext.getSharedPreferences("privacyguard", Context.MODE_PRIVATE)
    private val database = History(context.applicationContext)
    val policy: Policy get() = PolicyCodec.decode(prefs.getString("policy", null))
    val dnsSettings: DnsSettings get() = DnsSettingsCodec.decode(prefs.getString("dnsSettings", null))
    fun selectDns(id: String) = synchronized(prefs) {
        val settings = dnsSettings
        require(settings.servers.any { it.id == id }) { "Máy chủ không còn trong danh sách. Hãy chọn lại." }
        val updated = settings.copy(selectedId = id).also { it.requireUsable() }
        require(prefs.edit().putString("dnsSettings", DnsSettingsCodec.encode(updated)).commit()) { "Không lưu được lựa chọn DNS. Hãy thử lại." }
    }
    fun setDnsMode(mode: DnsMode) = synchronized(prefs) {
        val updated = dnsSettings.copy(mode = mode).also { it.requireUsable() }
        require(prefs.edit().putString("dnsSettings", DnsSettingsCodec.encode(updated)).commit()) { "Không lưu được chế độ DNS. Hãy thử lại." }
    }
    fun saveCustomDns(server: DnsServer, select: Boolean = true) = synchronized(prefs) {
        require(server.custom) { "Chỉ chỉnh sửa máy chủ tùy chỉnh." }
        val settings = dnsSettings
        val remaining = settings.custom.filterNot { it.id == server.id }
        require(remaining.size < DnsServers.MAX_CUSTOM) { "Bạn có thể lưu tối đa 20 máy chủ tùy chỉnh." }
        val updated = settings.copy(custom = remaining + server, selectedId = if (select) server.id else settings.selectedId)
        updated.requireUsable()
        require(prefs.edit().putString("dnsSettings", DnsSettingsCodec.encode(updated)).commit()) { "Không lưu được máy chủ. Hãy thử lại." }
    }
    fun deleteCustomDns(id: String) = synchronized(prefs) {
        val settings = dnsSettings
        val updated = settings.copy(custom = settings.custom.filterNot { it.id == id },
            selectedId = if (settings.selectedId == id) DnsServers.DEFAULT_ID else settings.selectedId)
        require(prefs.edit().putString("dnsSettings", DnsSettingsCodec.encode(updated)).commit()) { "Không xóa được máy chủ. Hãy thử lại." }
    }
    val customParams: List<String> get() {
        val values = runCatching { JSONArray(prefs.getString("customParams", "[]")) }.getOrDefault(JSONArray())
        return (0 until minOf(values.length(), 100)).mapNotNull { values.optString(it).takeIf { p -> p.isNotBlank() && p.length <= 100 } }
    }
    var detailed: Boolean
        get() = prefs.getBoolean("detailed", false)
        set(value) {
            val previous = detailed
            // Disable collection before deleting. Record reads this flag inside its SQLite write transaction,
            // so an in-flight insert either finishes before the delete or sees collection disabled.
            prefs.edit().putBoolean("detailed", value).apply()
            try { if (!value) database.writableDatabase.delete("events", null, null) }
            catch (error: Exception) { prefs.edit().putBoolean("detailed", previous).apply(); throw error }
        }

    fun setCategory(app: String?, category: Category, action: Action?) {
        val current = policy
        val updated = if (app == null) {
            require(action != null)
            current.copy(categories = current.categories + (category to action))
        } else {
            val values = current.apps[app].orEmpty().toMutableMap()
            if (action == null) values.remove(category) else values[category] = action
            current.copy(apps = current.apps.toMutableMap().apply { if (values.isEmpty()) remove(app) else put(app, values) })
        }
        prefs.edit().putString("policy", PolicyCodec.encode(updated)).apply()
    }

    fun saveException(domain: String, app: String, action: Action) {
        val normalized = Rules.normalize(domain, true)
        val current = policy
        val rules = current.exceptions.filterNot { it.domain == normalized && it.app == app }
        require(rules.size < 500) { "Tối đa 500 ngoại lệ; hãy xóa bớt trước khi thêm." }
        prefs.edit().putString("policy", PolicyCodec.encode(current.copy(exceptions = rules + DomainRule(normalized, action, app)))).apply()
    }

    fun deleteException(rule: DomainRule) {
        prefs.edit().putString("policy", PolicyCodec.encode(policy.copy(exceptions = policy.exceptions - rule))).apply()
    }

    fun saveCustomParams(input: String) {
        val values = input.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(values.size <= 100 && values.all { it.length <= 100 && !it.any(Char::isWhitespace) && !it.dropLast(1).contains('*') && it != "*" }) {
            "Tối đa 100 tên tham số; dấu * chỉ được ở cuối một tiền tố, ví dụ campaign_*."
        }
        prefs.edit().putString("customParams", JSONArray(values).toString()).apply()
    }

    /** Called only after the reply has been successfully written to the local interface. */
    fun record(result: DnsResult, app: String?, now: Long = System.currentTimeMillis()) {
        val db = database.writableDatabase
        val day = LocalDate.now().toString()
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO counters(day,app,category,outcome,count) VALUES(?,?,?,?,0)",
                arrayOf(day, app.orEmpty(), result.decision.category.name, result.outcome.name))
            db.execSQL("UPDATE counters SET count=count+1 WHERE day=? AND app=? AND category=? AND outcome=?",
                arrayOf(day, app.orEmpty(), result.decision.category.name, result.outcome.name))
            if (detailed) db.insertOrThrow("events", null, ContentValues().apply {
                put("time", now); put("app", app.orEmpty()); put("domain", result.decision.domain)
                put("category", result.decision.category.name); put("outcome", result.outcome.name); put("reason", result.decision.reason)
            })
            prune(db, now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun prune(db: SQLiteDatabase, now: Long = System.currentTimeMillis()) {
        val today = LocalDate.now()
        db.delete("counters", "day < ? OR day > ?", arrayOf(today.minusDays(6).toString(), today.toString()))
        if (!detailed) db.delete("events", null, null)
        else db.delete("events", "time < ? OR time > ?", arrayOf((now - 7 * 86_400_000L).toString(), now.toString()))
        db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 2000)")
    }

    fun counters(days: Int = 7): List<Counter> {
        val db = database.writableDatabase
        prune(db)
        return db.rawQuery("SELECT app,category,outcome,SUM(count) FROM counters WHERE day>=? GROUP BY app,category,outcome",
            arrayOf(LocalDate.now().minusDays(days.coerceIn(1, 7).toLong() - 1).toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Counter(cursor.getString(0).ifEmpty { null },
                Category.valueOf(cursor.getString(1)), Outcome.valueOf(cursor.getString(2)), cursor.getLong(3))) }
        }
    }

    fun timeline(days: Int = 7): List<DailyCount> {
        val db = database.writableDatabase
        prune(db)
        return db.rawQuery("SELECT day,outcome,SUM(count) FROM counters WHERE day>=? GROUP BY day,outcome ORDER BY day",
            arrayOf(LocalDate.now().minusDays(days.coerceIn(1, 7).toLong() - 1).toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(DailyCount(LocalDate.parse(cursor.getString(0)),
                Outcome.valueOf(cursor.getString(1)), cursor.getLong(2))) }
        }
    }

    fun events(limit: Int = 2000): List<GuardEvent> {
        val db = database.writableDatabase
        prune(db)
        return db.rawQuery("SELECT time,app,domain,category,outcome,reason FROM events ORDER BY id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 2000).toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(GuardEvent(cursor.getLong(0), cursor.getString(1).ifEmpty { null },
                cursor.getString(2), Category.valueOf(cursor.getString(3)), Outcome.valueOf(cursor.getString(4)), cursor.getString(5))) }
        }
    }

    fun exportEvents(events: List<GuardEvent>, scope: String, days: Int, query: String, outcome: Outcome?): String = JSONObject().apply {
        put("version", 2); put("exportedAt", java.time.Instant.now().toString()); put("simulated", false)
        put("filter", JSONObject().put("scope", scope).put("days", days).put("query", query)
            .put("outcome", outcome?.name ?: JSONObject.NULL))
        put("events", eventJson(events))
    }.toString(2)

    private fun eventJson(events: List<GuardEvent>) = JSONArray().apply { events.forEach { event ->
        put(JSONObject().put("time", event.time).put("app", event.app ?: JSONObject.NULL).put("domain", event.domain)
            .put("category", event.category.name).put("outcome", event.outcome.name).put("reason", event.reason))
    } }

    fun export(): String = JSONObject().apply {
        put("version", 1); put("exportedAt", java.time.Instant.now().toString()); put("simulated", false)
        put("dnsSettings", JSONObject(DnsSettingsCodec.encode(dnsSettings)))
        put("policy", JSONObject(PolicyCodec.encode(policy))); put("customParams", JSONArray(customParams))
        put("counters", JSONArray().apply { counters().forEach { counter ->
            put(JSONObject().put("app", counter.app ?: JSONObject.NULL).put("category", counter.category.name)
                .put("outcome", counter.outcome.name).put("count", counter.count))
        } })
        put("events", eventJson(events()))
    }.toString(2)

    fun clearHistory() {
        database.writableDatabase.beginTransaction()
        try {
            database.writableDatabase.delete("events", null, null)
            database.writableDatabase.delete("counters", null, null)
            database.writableDatabase.setTransactionSuccessful()
        } finally { database.writableDatabase.endTransaction() }
    }

    override fun close() = database.close()

    private class History(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE counters(day TEXT NOT NULL,app TEXT NOT NULL,category TEXT NOT NULL,outcome TEXT NOT NULL,count INTEGER NOT NULL,PRIMARY KEY(day,app,category,outcome))")
            db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY,time INTEGER NOT NULL,app TEXT NOT NULL,domain TEXT NOT NULL,category TEXT NOT NULL,outcome TEXT NOT NULL,reason TEXT NOT NULL)")
            db.execSQL("CREATE INDEX events_time ON events(time)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
