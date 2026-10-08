package com.privacyguard.android

import com.privacyguard.android.core.*
import org.junit.Assert.*
import org.junit.Test

class PolicyCodecTest {
    @Test fun policyRoundTripPreservesAppIsolationAndGlobalDefaults() {
        val policy = Policy(apps = mapOf("com.example.one" to mapOf(Category.ADS to Action.ALLOW)),
            exceptions = listOf(DomainRule("*.example.com", Action.BLOCK), DomainRule("ads.example.com", Action.ALLOW, "com.example.one")))
        val restored = PolicyCodec.decode(PolicyCodec.encode(policy))
        assertEquals(policy, restored)
        assertEquals(Action.ALLOW, Rules.decide("ads.example.com", "com.example.one", restored).action)
        assertEquals(Action.BLOCK, Rules.decide("ads.example.com", "com.example.two", restored).action)
    }

    @Test fun corruptedEntriesRecoverIndependentlyAndDuplicateKeysReplace() {
        val restored = PolicyCodec.decode("""{"categories":{"ADS":"invalid","ESSENTIAL":"BLOCK"},"apps":{"com.example.one":{"ADS":"ALLOW"}},"exceptions":[null,{"domain":"https://example.com","action":"BLOCK","app":"*"},{"domain":"*.EXAMPLE.COM.","action":"BLOCK","app":"*"},{"domain":"*.example.com","action":"ALLOW","app":"*"}]}""")
        assertEquals(Action.BLOCK, restored.categories[Category.ADS])
        assertEquals(Action.BLOCK, restored.categories[Category.ESSENTIAL])
        assertEquals(Action.ALLOW, restored.apps["com.example.one"]?.get(Category.ADS))
        assertEquals(listOf(DomainRule("*.example.com", Action.ALLOW)), restored.exceptions)
        for (raw in listOf(null, "{broken", "null", "[]")) assertEquals(Policy(), PolicyCodec.decode(raw))
    }

    @Test fun exceptionsAreBoundedAndInvalidPackageScopesAreIgnored() {
        val entries = (0..600).joinToString(",") { """{"domain":"d$it.example.com","action":"ALLOW","app":"*"}""" }
        assertEquals(500, PolicyCodec.decode("{\"exceptions\":[$entries]}").exceptions.size)
        assertTrue(PolicyCodec.decode("""{"exceptions":[{"domain":"example.com","action":"ALLOW","app":"app with spaces"}]}""").exceptions.isEmpty())
    }
}
