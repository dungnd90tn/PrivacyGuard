package com.privacyguard.android.core

import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    @Test fun normalizationAndLabelBoundaries() {
        assertEquals("ads.example.com", Rules.normalize("  ADS.Example.COM.  "))
        assertEquals("xn--bcher-kva.example", Rules.normalize("bücher.example"))
        assertEquals("*.example.com", Rules.normalize("*.Example.com.", true))
        assertFalse(Rules.matches("example.com", "*.example.com"))
        assertFalse(Rules.matches("notexample.com", "*.example.com"))
        assertFalse(Rules.matches("a.example.com", "example.com"))
        assertTrue(Rules.matches("a.b.example.com", "*.example.com"))
        listOf("https://example.com", "me@example.com", "a..example", "-a.example", "a b.example", "*.example.com", "example.com:443", "example%2ecom").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { Rules.normalize(value) }
        }
    }

    @Test fun precedenceIsSpecificThenGlobalThenCategoryAndDoesNotLeakAcrossApps() {
        val policy = Policy(apps = mapOf("facebook" to mapOf(Category.ADS to Action.ALLOW)), exceptions = listOf(
            DomainRule("ads.example.com", Action.ALLOW), DomainRule("*.example.com", Action.BLOCK, "facebook")
        ))
        assertEquals(Action.BLOCK, Rules.decide("ads.example.com", "facebook", policy).action)
        assertEquals(Action.ALLOW, Rules.decide("ads.example.com", "zalo", policy).action)
        assertEquals(Action.ALLOW, Rules.decide("ads.example.com", null, policy).action)
        val categories = policy.copy(exceptions = emptyList())
        assertEquals(Action.ALLOW, Rules.decide("ads.example.com", "facebook", categories).action)
        assertEquals(Action.BLOCK, Rules.decide("ads.example.com", "zalo", categories).action)
        assertEquals(Action.BLOCK, Rules.decide("metrics.example.com", null, categories).action)
        assertEquals(Action.ALLOW, Rules.decide("api.example.com", null, categories).action)
        assertEquals(Action.ALLOW, Rules.decide("unknown.example.com", null, categories).action)
    }

    @Test fun exactAndLongestWildcardWinWithinOneScope() {
        val policy = Policy(exceptions = listOf(DomainRule("*.example.com", Action.BLOCK),
            DomainRule("*.ads.example.com", Action.ALLOW), DomainRule("special.ads.example.com", Action.BLOCK)))
        assertEquals(Action.ALLOW, Rules.decide("other.ads.example.com", null, policy).action)
        assertEquals(Action.BLOCK, Rules.decide("special.ads.example.com", null, policy).action)
        assertEquals(Action.BLOCK, Rules.decide("cdn.example.com", null, policy).action)
        assertEquals(Action.ALLOW, Rules.decide("example.com", null, policy).action)
    }

    @Test fun cleanerPreservesBytesRepeatedParametersAndFragments() {
        val result = LinkCleaner.clean("https://example.com/read?%75tm_source=email&FBCLID=1&gclid=2&gclid=3&q=a%20b&q=a+b&empty=&flag&next=https%3A%2F%2Fexample.org%2F%3Fa%3Db#utm_keep")
        assertEquals("https://example.com/read?q=a%20b&q=a+b&empty=&flag&next=https%3A%2F%2Fexample.org%2F%3Fa%3Db#utm_keep", result.url)
        assertEquals(listOf("utm_source", "FBCLID", "gclid", "gclid"), result.removed)
        assertEquals("https://example.com/?referral=2&keep=4", LinkCleaner.clean("https://example.com/?ref=1&referral=2&campaign_a=3&keep=4", listOf("ref", "campaign_*")).url)
        assertEquals("https://example.com/#hello", LinkCleaner.clean("https://example.com/?fbclid=1#hello").url)
        assertEquals("https://example.com/read#hello", LinkCleaner.clean("https://example.com/read#hello").url)
    }

    @Test fun cleanerRejectsUnsafeOrMalformedUrls() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "data:text/plain,hi", "not a url", "https://name:password@example.com", "https://example.com/?x=%zz").forEach {
            assertThrows(IllegalArgumentException::class.java) { LinkCleaner.clean(it) }
        }
    }
}
