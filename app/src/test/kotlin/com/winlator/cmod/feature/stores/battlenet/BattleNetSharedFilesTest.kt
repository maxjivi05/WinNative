package com.winlator.cmod.feature.stores.battlenet

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BattleNetSharedFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun prefix(name: String): File = temporary.newFolder(name).also {
        File(it, ".wine/drive_c/users/xuser/AppData/Roaming").mkdirs()
    }

    @Test fun bothArchitecturesShareClientAgentConfigAndGameFiles() {
        val store = BattleNetSharedFiles(temporary.newFolder("shared"))
        val x86 = prefix("x86")
        val arm = prefix("arm")
        store.bind(x86)
        store.bind(arm)
        val paths = listOf(
            "Program Files (x86)/Battle.net" to "client",
            "ProgramData/Battle.net" to "programdata/Battle.net",
            "users/xuser/AppData/Roaming/Battle.net" to "appdata/roaming/Battle.net",
            "Program Files (x86)/World of Warcraft" to "games/World of Warcraft",
        )
        paths.forEach { (winePath, sharedPath) ->
            val left = File(x86, ".wine/drive_c/$winePath")
            val right = File(arm, ".wine/drive_c/$winePath")
            assertTrue(Files.isSymbolicLink(left.toPath()))
            assertEquals(File(store.root, sharedPath).canonicalFile, left.canonicalFile)
            File(left, "test-file").writeText("shared")
            assertEquals("shared", File(right, "test-file").readText())
        }
        store.bind(x86)
        assertEquals("shared", File(store.root, "client/test-file").readText())
    }

    @Test fun adoptsExistingInstallationWithoutCopyingOrDeletingData() {
        val store = BattleNetSharedFiles(temporary.newFolder("shared"))
        val prefix = prefix("old")
        val client = File(prefix, ".wine/drive_c/Program Files (x86)/Battle.net").apply { mkdirs() }
        File(client, "Battle.net.exe").writeText("existing installation")
        store.bind(prefix)
        assertTrue(Files.isSymbolicLink(client.toPath()))
        assertEquals("existing installation", File(store.root, "client/Battle.net.exe").readText())
    }

    @Test fun refusesToMergeTwoDifferentInstallations() {
        val store = BattleNetSharedFiles(temporary.newFolder("shared"))
        File(store.root, "client").mkdirs()
        File(store.root, "client/Battle.net.exe").writeText("first")
        val prefix = prefix("second")
        val client = File(prefix, ".wine/drive_c/Program Files (x86)/Battle.net").apply { mkdirs() }
        File(client, "Battle.net.exe").writeText("second")
        assertThrows(IOException::class.java) { store.bind(prefix) }
        assertEquals("first", File(store.root, "client/Battle.net.exe").readText())
        assertEquals("second", File(client, "Battle.net.exe").readText())
    }

    @Test fun leavesForeignSymlinksUntouched() {
        val store = BattleNetSharedFiles(temporary.newFolder("shared"))
        val foreign = temporary.newFolder("foreign")
        val prefix = prefix("container")
        val client = File(prefix, ".wine/drive_c/Program Files (x86)/Battle.net")
        client.parentFile.mkdirs()
        Files.createSymbolicLink(client.toPath(), foreign.toPath())
        assertThrows(IOException::class.java) { store.bind(prefix) }
        assertEquals(foreign.canonicalFile, client.canonicalFile)
    }

    @Test fun removingAContainerLinkPreservesSharedFiles() {
        val store = BattleNetSharedFiles(temporary.newFolder("shared"))
        val prefix = prefix("container")
        store.bind(prefix)
        File(store.root, "client/Battle.net.exe").writeText("keep")
        Files.delete(File(prefix, ".wine/drive_c/Program Files (x86)/Battle.net").toPath())
        assertEquals("keep", File(store.root, "client/Battle.net.exe").readText())
    }

    @Test fun concurrentBindingIsIdempotentAcrossStoreInstances() {
        val root = temporary.newFolder("shared")
        val prefix = prefix("container")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (0..1).map { executor.submit { BattleNetSharedFiles(root).bind(prefix) } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(File(root, "client").canonicalFile, File(prefix, ".wine/drive_c/Program Files (x86)/Battle.net").canonicalFile)
        } finally {
            executor.shutdownNow()
        }
    }
}
