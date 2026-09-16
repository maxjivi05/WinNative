package com.winlator.cmod.feature.stores.battlenet

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

class BattleNetSharedFiles(val root: File) {
    val database: File get() = File(root, "programdata/Battle.net/Agent/product.db")

    fun bind(containerRoot: File) = synchronized(bindingLock) {
        val drive = File(containerRoot, ".wine/drive_c")
        require(drive.isDirectory) { "The Wine container has not been initialized." }
        link(File(drive, "Program Files (x86)/Battle.net"), File(root, "client"))
        link(File(drive, "Program Files/Battle.net"), File(root, "client"))
        link(File(drive, "ProgramData/Battle.net"), File(root, "programdata/Battle.net"))
        link(File(drive, "ProgramData/Blizzard Entertainment"), File(root, "programdata/Blizzard Entertainment"))
        val users = File(drive, "users").listFiles().orEmpty().filter {
            it.isDirectory && it.name.lowercase(java.util.Locale.ROOT) !in setOf("public", "default", "all users", "default user")
        }
        users.forEach { user ->
            link(File(user, "AppData/Roaming/Battle.net"), File(root, "appdata/roaming/Battle.net"))
            link(File(user, "AppData/Local/Battle.net"), File(root, "appdata/local/Battle.net"))
            link(File(user, "AppData/Local/Blizzard Entertainment"), File(root, "appdata/local/Blizzard Entertainment"))
        }
        link(File(drive, "WinNative/Battle.net"), root)
        gameFolders.forEach { folder ->
            link(File(drive, "Program Files (x86)/$folder"), File(root, "games/$folder"))
            link(File(drive, "Program Files/$folder"), File(root, "games/$folder"))
        }
    }

    internal fun link(destination: File, target: File) {
        val destPath = destination.toPath()
        if (Files.isSymbolicLink(destPath)) {
            if (destination.canonicalFile == target.canonicalFile) return
            throw IOException("Battle.net found an existing link at ${destination.name}; it was left unchanged.")
        }
        if (Files.exists(destPath, LinkOption.NOFOLLOW_LINKS)) {
            if (!destination.isDirectory) throw IOException("Battle.net expected a directory at ${destination.name}.")
            val existing = destination.list() ?: throw IOException("Cannot read ${destination.name}.")
            if (existing.isNotEmpty()) {
                if (target.exists() && target.list()?.isNotEmpty() != false) {
                    throw IOException("Separate Battle.net files already exist at ${destination.path}. Move or back them up before sharing this container.")
                }
                ensureDirectory(target.parentFile!!)
                if (target.exists()) Files.delete(target.toPath())
                Files.move(destPath, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } else {
                Files.delete(destPath)
            }
        }
        ensureDirectory(target)
        ensureDirectory(destination.parentFile!!)
        Files.createSymbolicLink(destPath, target.canonicalFile.toPath())
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Could not create ${directory.name}.")
    }

    companion object {
        private val bindingLock = Any()
        val gameFolders = listOf(
            "World of Warcraft", "StarCraft", "StarCraft II", "Overwatch", "Warcraft III", "Hearthstone",
            "Heroes of the Storm", "Diablo III", "Diablo IV", "Diablo II Resurrected", "Diablo Immortal",
            "Warcraft I Remastered", "Warcraft II Remastered", "Warcraft Rumble", "Warcraft Orcs and Humans", "Warcraft II", "Blizzard Arcade Collection", "Crash Bandicoot 4",
        )
    }
}
