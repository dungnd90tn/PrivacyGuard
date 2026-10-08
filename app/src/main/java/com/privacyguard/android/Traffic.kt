package com.privacyguard.android

import com.privacyguard.android.core.Category
import com.privacyguard.android.core.Outcome
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Counter(val app: String?, val category: Category, val outcome: Outcome, val count: Long)
data class GuardEvent(val time: Long, val app: String?, val domain: String, val category: Category, val outcome: Outcome, val reason: String)
data class DailyCount(val day: LocalDate, val outcome: Outcome, val count: Long)

/** Unknown is a real bucket, never an alias for all apps. No inference from domain names. */
sealed interface TrafficScope {
    data object All : TrafficScope
    data object Unknown : TrafficScope
    data class App(val packageName: String) : TrafficScope
    fun contains(app: String?): Boolean = when (this) {
        All -> true
        Unknown -> app == null
        is App -> app == packageName
    }
    val ruleApp: String get() = if (this is App) packageName else "*"
}

data class DomainTraffic(val domain: String, val events: List<GuardEvent>) {
    val count: Int get() = events.size
    val lastSeen: Long get() = events.maxOf { it.time }
    fun count(outcome: Outcome) = events.count { it.outcome == outcome }
}

object Traffic {
    /** Filter the whole retained history before pagination, using the same calendar window as counters. */
    fun filter(events: List<GuardEvent>, scope: TrafficScope = TrafficScope.All, days: Int = 7,
               query: String = "", outcome: Outcome? = null, names: Map<String, String> = emptyMap(),
               now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<GuardEvent> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = today.minusDays(days.coerceIn(1, 7).toLong() - 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val term = query.trim()
        return events.filter { event ->
            event.time in start..now && scope.contains(event.app) && (outcome == null || event.outcome == outcome) &&
                (term.isEmpty() || listOf(event.domain, event.app.orEmpty(), names[event.app].orEmpty(),
                    if (event.app == null) "Chưa xác định" else "", event.category.label).any { it.contains(term, true) })
        }.sortedByDescending { it.time }
    }

    fun domains(events: List<GuardEvent>): List<DomainTraffic> = events.groupBy { it.domain }
        .map { (domain, values) -> DomainTraffic(domain, values) }
        .sortedWith(compareByDescending<DomainTraffic> { it.count }.thenByDescending { it.lastSeen }.thenBy { it.domain })
}
