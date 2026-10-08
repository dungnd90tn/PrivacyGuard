package com.privacyguard.android.core

import org.junit.Assert.*
import org.junit.Test

class UrlAnalysisTest {
    @Test fun providerEndpointAndRequiredFieldsIdentifyAnAdPattern() {
        val result = UrlAnalyzer.analyze("https://securepubads.g.doubleclick.net/gampad/adx?iu=%2F123%2Fhomepage&sz=320x50%7C300x50&cust_params=section%3Dnews")
        assertEquals(UrlKind.AD_REQUEST, result.kind)
        assertEquals("/123/homepage", result.parameters.first().value)
        assertTrue(result.parameters.last().explanation.contains("nhắm mục tiêu"))
        assertEquals(UrlKind.AD_REQUEST, UrlAnalyzer.analyze("https://pubads.g.doubleclick.net/gampad/ads?iu=/123/video&sz=640x480").kind)
        assertEquals(UrlKind.AD_RELATED, UrlAnalyzer.analyze("https://securepubads.g.doubleclick.net/gampad/adx?iu=/123/home").kind)
    }
    @Test fun campaignAndClickIdentifiersDoNotClaimToLoadAds() {
        for (query in listOf("utm_source=newsletter&utm_medium=email", "gclid=clicked-ad", "fbclid=click", "uTm_Campaign=sale")) {
            val result = UrlAnalyzer.analyze("https://shop.example.com/product?$query")
            assertEquals(UrlKind.TRACKING, result.kind)
            assertTrue(result.explanation().contains("không"))
        }
    }
    @Test fun unrelatedNamesValuesAndFragmentsDoNotTriggerAdClaims() {
        assertEquals(UrlKind.INSUFFICIENT, UrlAnalyzer.analyze("https://api.example.com/?user=admin&id=ad_unit&download=ads#?ad_slot=top").kind)
        assertEquals(UrlKind.INSUFFICIENT, UrlAnalyzer.analyze("https://shop.example.com/?iu=/123/home&sz=320x50").kind)
        assertEquals(UrlKind.INSUFFICIENT, UrlAnalyzer.analyze("https://securepubads.g.doubleclick.net.evil.example/gampad/adx?iu=/123/home&sz=320x50").kind)
        assertEquals(UrlKind.AD_RELATED, UrlAnalyzer.analyze("https://securepubads.g.doubleclick.net/gampad%2Fadx?iu=/123/home&sz=320x50").kind)
        assertEquals(UrlKind.POSSIBLE_ADS, UrlAnalyzer.analyze("https://publisher.example.com/?ad_slot=top").kind)
        assertEquals(UrlKind.INSUFFICIENT, UrlAnalyzer.analyze("https://publisher.example.com/?ad_slot=").kind)
    }
    @Test fun encodedNamesDuplicatesEmptyFlagsAndValuesArePreservedWithoutFetching() {
        val result = UrlAnalyzer.analyze("https://shop.example.com/private/token?%75tm_source=a%2Bb&utm_source=second&flag&empty=&email=me%40example.com#utm_medium=ignored")
        assertEquals(listOf("utm_source", "utm_source", "flag", "empty", "email"), result.parameters.map { it.name })
        assertEquals(listOf("a+b", "second", null, "", "me@example.com"), result.parameters.map { it.value })
        assertEquals("/private/token", result.path); assertTrue(result.hasFragment)
        assertEquals(5, result.parameters.size)
    }
    @Test fun invalidCredentialsSchemesAndOversizedInputsAreRejectedAndLargeQueriesAreBounded() {
        for (url in listOf("ftp://example.com/", "https://user:password@example.com/", "https://example.com/%zz", "https://", "https://example.com/?x=" + "a".repeat(8192))) {
            assertThrows(IllegalArgumentException::class.java) { UrlAnalyzer.analyze(url) }
        }
        val result = UrlAnalyzer.analyze("https://shop.example.com/?" + (1..55).joinToString("&") { "key$it=value$it" })
        assertEquals(50, result.parameters.size); assertTrue(result.omittedParameters)
        assertEquals(UrlKind.INSUFFICIENT, result.kind)
        assertFalse(UrlAnalyzer.safe("token\n\u202efake").contains('\n'))
    }
    @Test fun knownAnalyticsDomainIsOnlyAnInferenceOfMeasurement() {
        assertEquals(UrlKind.TRACKING, UrlAnalyzer.analyze("https://www.google-analytics.com/collect?id=1").kind)
        assertEquals(UrlKind.AD_RELATED, UrlAnalyzer.analyze("https://ad.doubleclick.net/click?gclid=123").kind)
    }
    private fun UrlAnalysis.explanation() = kind.explanation
}

class RequestSessionTest {
    private fun request(i: Int) = BrowserRequest(i.toLong(), "GET", false, false, UrlAnalyzer.analyze("https://shop.example.com/?token=$i"))
    @Test fun collectionIsOptInAndDisablingClearsValues() {
        val session = RequestSession()
        assertNull(session.token()); assertTrue(session.snapshot().isEmpty())
        session.append(0, request(1)); assertTrue(session.snapshot().isEmpty())
        session.setEnabled(true); session.append(session.token()!!, request(2)); assertEquals(1, session.snapshot().size)
        session.setEnabled(false); assertNull(session.token()); assertTrue(session.snapshot().isEmpty())
    }
    @Test fun boundedJournalKeepsNewestEntriesFirst() {
        val session = RequestSession(2); session.setEnabled(true)
        val token = session.token()!!
        (1..4).forEach { session.append(token, request(it)) }
        assertEquals(listOf(4L, 3L), session.snapshot().map { it.time })
    }
    @Test fun expiredBackgroundCallbacksCannotRepopulateNewSessions() {
        val session = RequestSession(); session.setEnabled(true)
        val old = session.token()!!
        session.clear(); session.setEnabled(true)
        session.append(old, request(1)); assertTrue(session.snapshot().isEmpty())
        session.append(session.token()!!, request(2)); assertEquals(1, session.snapshot().size)
    }
}
