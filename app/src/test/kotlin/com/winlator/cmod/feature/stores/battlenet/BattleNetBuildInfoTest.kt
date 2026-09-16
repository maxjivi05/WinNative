package com.winlator.cmod.feature.stores.battlenet

import org.junit.Assert.*
import org.junit.Test

class BattleNetBuildInfoTest {
    private val key = "0123456789abcdef0123456789abcdef"
    private val header = "Active!DEC:1|Build Key!HEX:16|Product!STRING:0\n"

    @Test fun selectsActiveProductAmongSharedWowBuilds() {
        val text = header + "0|$key|wow\n1|${"a".repeat(32)}|wow_classic\n1|$key|wow\n"
        assertEquals(key, BattleNetBuildInfo.activeKey(text, "wow"))
    }

    @Test fun acceptsCrLf() {
        assertEquals(key, BattleNetBuildInfo.activeKey((header + "1|$key|wow\n").replace("\n", "\r\n"), "wow"))
    }

    @Test fun rejectsAmbiguousOrTruncatedBuilds() {
        assertNull(BattleNetBuildInfo.activeKey(header + "1|$key|wow\n1|$key|wow", "wow"))
        assertNull(BattleNetBuildInfo.activeKey(header + "1|$key", "wow"))
        assertNull(BattleNetBuildInfo.activeKey(header + "1|oops|wow", "wow"))
    }

    @Test fun doesNotMistakeAnotherProductOrInactiveBuildForInstalled() {
        assertNull(BattleNetBuildInfo.activeKey(header + "0|$key|wow\n1|$key|wow_classic", "wow"))
    }

    @Test fun comparesLatestBuildAgainstDiskMetadata() {
        val text = header + "1|$key|wow\n"
        assertTrue(BattleNetBuildInfo.isCurrent(text, "wow", key.uppercase()))
        assertFalse(BattleNetBuildInfo.isCurrent(text, "wow", "11111111111111111111111111111111"))
        assertFalse(BattleNetBuildInfo.isCurrent("", "wow", key))
    }
}
