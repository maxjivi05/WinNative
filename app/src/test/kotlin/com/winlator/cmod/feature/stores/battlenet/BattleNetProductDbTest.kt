package com.winlator.cmod.feature.stores.battlenet

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BattleNetProductDbTest {
    private fun varint(value: Long): ByteArray {
        var remaining = value
        val output = java.io.ByteArrayOutputStream()
        do {
            val low = (remaining and 127).toInt()
            remaining = remaining ushr 7
            output.write(low or if (remaining != 0L) 128 else 0)
        } while (remaining != 0L)
        return output.toByteArray()
    }
    private fun message(number: Int, data: ByteArray) = varint((number * 8 + 2).toLong()) + varint(data.size.toLong()) + data
    private fun text(number: Int, value: String) = message(number, value.toByteArray())
    private fun integer(number: Int, value: Long) = varint((number * 8).toLong()) + varint(value)
    private fun double(number: Int, value: Double) = varint((number * 8 + 1).toLong()) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()
    private fun database(progress: Double = 0.25): ByteArray {
        val settings = text(1, "C:\\Games\\Diablo IV")
        val base = integer(1, 1) + integer(2, 1) + integer(3, 0)
        val update = double(2, progress) + integer(4, 40_000_000_000) + integer(5, 30_000_000_000)
        val state = message(1, base) + message(4, update)
        return message(1, text(1, "fenris") + text(2, "fenris") + message(3, settings) + message(4, state))
    }

    @Test fun readsLargeDownloadsAndKeepsPlayableSeparateFromComplete() {
        val game = BattleNetProductDb.parse(database()).single()
        assertEquals("fenris", game.product)
        assertEquals("C:\\Games\\Diablo IV", game.path)
        assertTrue(game.installed)
        assertTrue(game.playable)
        assertFalse(game.complete)
        assertEquals(10_000_000_000, game.downloadedBytes)
        assertEquals(0.25, game.progress!!, 0.0001)
    }

    @Test fun skipsUnknownFieldsForForwardCompatibility() {
        assertEquals(BattleNetProductDb.parse(database()), BattleNetProductDb.parse(database() + text(111, "new field") + integer(112, 42)))
    }

    @Test fun doesNotTreatMissingProgressAsFinished() {
        val db = message(1, text(1, "wow") + text(2, "wow") + message(3, text(1, "C:\\Games\\WoW")))
        val game = BattleNetProductDb.parse(db).single()
        assertNull(game.progress)
        assertFalse(game.complete)
        assertFalse(game.installed)
    }

    @Test fun discardsNonFiniteProgress() {
        assertNull(BattleNetProductDb.parse(database(Double.NaN)).single().progress)
        assertNull(BattleNetProductDb.parse(database(Double.POSITIVE_INFINITY)).single().progress)
        assertNull(BattleNetProductDb.parse(database(1.5)).single().progress)
    }

    @Test fun rejectsPartialWritesWithoutReturningPartialLibrary() {
        val full = database()
        for (length in 1 until full.size) {
            assertThrows(IllegalArgumentException::class.java) { BattleNetProductDb.parse(full.copyOf(length)) }
        }
    }

    @Test fun rejectsMalformedWireData() {
        listOf(byteArrayOf(0), byteArrayOf(10, -1, 127), ByteArray(12) { -128 }, byteArrayOf(15), byteArrayOf(9, 0)).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { BattleNetProductDb.parse(input) }
        }
    }

    @Test fun boundsInputSize() {
        assertThrows(IllegalArgumentException::class.java) { BattleNetProductDb.parse(ByteArray(BattleNetProductDb.MAX_BYTES + 1)) }
    }

    @Test fun calculatesThroughputOnlyAcrossComparableSamples() {
        val sample = BattleNetTransferSample()
        val game = BattleNetProductDb.parse(database()).single()
        assertNull(sample.update(game, 1000))
        assertEquals(1000L, sample.update(game.copy(remainingBytes = game.remainingBytes - 2000), 3000))
        assertNull(sample.update(game, 5000))
        assertNull(sample.update(game.copy(totalBytes = game.totalBytes + 100), 7000))
        assertNull(sample.update(game, 50_000))
        assertEquals(0L, sample.update(game, 52_000))
    }

    @Test fun launcherCommandsUseCatalogIdentifiers() {
        assertEquals("--exec=\"launch Fen\"", BattleNetCatalog.byProduct("fenris")!!.command(false))
        assertEquals("--exec=\"install WoW_wow_classic\"", BattleNetCatalog.byProduct("wow_classic")!!.command(true))
        assertNull(BattleNetCatalog.byProduct("fenris\" --bad"))
        assertEquals(BattleNetCatalog.games.size, BattleNetCatalog.games.map { it.product }.distinct().size)
    }
}
