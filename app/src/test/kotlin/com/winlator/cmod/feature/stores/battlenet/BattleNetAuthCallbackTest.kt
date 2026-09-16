package com.winlator.cmod.feature.stores.battlenet

import org.junit.Assert.*
import org.junit.Test

class BattleNetAuthCallbackTest {
    private val callback = "http://localhost:0/?ST=US-01234567-89ab-cdef-0123-456789abcdef&accountName=Player%40example.com&accountRegion=US&rememberMe=true"

    @Test fun parsesDesktopCallbackWithoutExposingToken() {
        val value = BattleNetAuthCallback.parse(callback)!!
        assertEquals("player@example.com", value.account)
        assertEquals("US", value.region)
        assertTrue(value.token.startsWith("US-"))
        assertFalse(value.toString().contains(value.token))
        assertFalse(value.toString().contains(value.account))
    }

    @Test fun rejectsWrongCallbackOriginsAndDuplicateFields() {
        for (url in listOf(
            callback.replace("localhost:0", "localhost:80"), callback.replace("http:", "https:"),
            callback.replace("localhost:0", "localhost.evil:0"), callback.replace("localhost:0", "user@localhost:0"),
            "$callback#ignored", "$callback&ST=another-token-value", callback.replace("/?", "/callback?"),
        )) assertNull(BattleNetAuthCallback.parse(url))
    }

    @Test fun rejectsControlCharactersAndMissingCredentials() {
        for (url in listOf(callback.replace("Player%40example.com", "%0aInjected"),
            callback.replace("Player%40example.com", ""), callback.replace("accountRegion=US", "accountRegion=XX"),
            callback.replace("ST=US-", "ST=%00US-"), callback.replace("accountName=Player", "accountName=%ZZPlayer"))) {
            assertNull(BattleNetAuthCallback.parse(url))
        }
    }

    @Test fun supportsRegionEncodedInToken() {
        assertEquals("EU", BattleNetAuthCallback.parse(callback.replace("ST=US-", "ST=EU-").replace("&accountRegion=US", ""))!!.region)
    }
}
