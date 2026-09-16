package com.winlator.cmod.feature.stores.battlenet

import org.junit.Assert.assertEquals
import org.junit.Test

class BattleNetCookieRotationTest {
    @Test fun savesRotatedCookiesAndKeepsUnchangedCookies() {
        assertEquals("session=new=value; locale=enUS", BattleNetCookieRotation.merge(
            "session=old; locale=enUS", listOf("session=new=value; Secure; HttpOnly; Path=/")))
    }
    @Test fun removesExpiredCookies() {
        assertEquals("locale=enUS", BattleNetCookieRotation.merge(
            "session=old; locale=enUS", listOf("session=; Max-Age=0; Path=/")))
    }
    @Test fun rejectsForeignDomainsAndUnrelatedPaths() {
        assertEquals("session=old", BattleNetCookieRotation.merge("session=old", listOf(
            "session=foreign; Domain=example.com; Path=/", "session=unrelated; Path=/other",
        )))
    }
}
