package com.winlator.cmod.feature.stores.battlenet

import org.junit.Assert.*
import org.junit.Test

class BattleNetCatalogTest {
    @Test fun matchesNumericAndModernAccountIdentifiers() {
        val products = BattleNetCatalog.ownedGames(setOf("4613486", "diablo_immortal", "GRYPHON", "w2be"))
            .map { it.product }.toSet()
        assertEquals(setOf("fenris", "anbs", "gryphon", "w2bn"), products)
    }

    @Test fun unknownTitlesDoNotGrantCatalogGames() {
        assertTrue(BattleNetCatalog.ownedGames(setOf("null", "0", "unrecognized")).isEmpty())
        assertEquals(setOf("wow", "wow_classic", "wow_classic_era"),
            BattleNetCatalog.ownedGames(setOf("5730135")).map { it.product }.toSet())
    }
}
