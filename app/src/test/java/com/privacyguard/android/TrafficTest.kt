package com.privacyguard.android

import com.privacyguard.android.core.Category
import com.privacyguard.android.core.Outcome
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TrafficTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val now = Instant.parse("2026-10-08T03:00:00Z").toEpochMilli()
    private fun event(domain: String = "api.example.com", app: String? = "app.one", outcome: Outcome = Outcome.FORWARDED, time: Long = now - 1000) =
        GuardEvent(time, app, domain, Category.UNKNOWN, outcome, "DNS")

    @Test fun unknownScopeNeverIncludesKnownApps() {
        val events = listOf(event(), event(app = "app.two"), event(app = null))
        assertEquals(3, Traffic.filter(events, now = now, zone = zone).size)
        assertEquals(listOf(events.last()), Traffic.filter(events, TrafficScope.Unknown, now = now, zone = zone))
        assertEquals(listOf(events.first()), Traffic.filter(events, TrafficScope.App("app.one"), now = now, zone = zone))
        assertEquals("*", TrafficScope.Unknown.ruleApp)
        assertEquals("app.one", TrafficScope.App("app.one").ruleApp)
    }
    @Test fun searchReachesRecordsBeyondTheFormerHundredRowLimit() {
        val history = (0..199).map { event("site$it.example.com", time = now - it * 1000L) }
        assertEquals("site199.example.com", Traffic.filter(history, query = "site199", now = now, zone = zone).single().domain)
    }
    @Test fun searchMatchesAppNamePackageCategoryAndUnknownLabel() {
        val history = listOf(event(), event(app = null))
        assertEquals(1, Traffic.filter(history, query = "CHROME", names = mapOf("app.one" to "Chrome"), now = now, zone = zone).size)
        assertEquals(1, Traffic.filter(history, query = "APP.ONE", now = now, zone = zone).size)
        assertEquals(1, Traffic.filter(history, query = "chưa xác định", now = now, zone = zone).size)
        assertEquals(2, Traffic.filter(history, query = Category.UNKNOWN.label, now = now, zone = zone).size)
    }
    @Test fun todayUsesLocalMidnightAndDropsFutureRecords() {
        val before = event(time = Instant.parse("2026-10-07T16:59:59Z").toEpochMilli())
        val after = event(time = Instant.parse("2026-10-07T17:00:00Z").toEpochMilli())
        assertEquals(listOf(after), Traffic.filter(listOf(before, after, event(time = now + 1)), days = 1, now = now, zone = zone))
        assertEquals(2, Traffic.filter(listOf(before, after), days = 7, now = now, zone = zone).size)
    }
    @Test fun sevenCalendarDaysExcludeThePreviousCalendarDate() {
        val inWindow = event(time = Instant.parse("2026-10-01T17:00:00Z").toEpochMilli())
        val outside = event(time = inWindow.time - 1)
        assertEquals(listOf(inWindow), Traffic.filter(listOf(inWindow, outside), now = now, zone = zone))
    }
    @Test fun domainGroupsKeepAllThreeOutcomesAndSortByVolume() {
        val mixed = listOf(event(outcome = Outcome.BLOCKED), event(), event(outcome = Outcome.FAILED), event("single.example.com"))
        val domains = Traffic.domains(mixed)
        assertEquals("api.example.com", domains.first().domain)
        assertEquals(3, domains.first().count)
        Outcome.entries.forEach { assertEquals(1, domains.first().count(it)) }
    }
    @Test fun filterRunsBeforeAggregationAndPagination() {
        val mixed = listOf(event(outcome = Outcome.BLOCKED), event(), event(app = "app.two", outcome = Outcome.BLOCKED))
        val domain = Traffic.domains(Traffic.filter(mixed, TrafficScope.App("app.one"), outcome = Outcome.BLOCKED, now = now, zone = zone)).single()
        assertEquals(1, domain.count)
        assertEquals("app.one", domain.events.single().app)
    }
    @Test fun recentSortAndCountTieRemainDeterministic() {
        val groups = Traffic.domains(listOf(event("z.example.com", time = now - 300), event("b.example.com", time = now - 100), event("a.example.com", time = now - 100)))
        assertEquals(listOf("a.example.com", "b.example.com", "z.example.com"), groups.map { it.domain })
    }
}
