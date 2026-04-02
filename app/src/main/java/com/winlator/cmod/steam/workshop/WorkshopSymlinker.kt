package com.winlator.cmod.steam.workshop

import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class WorkshopSymlinker {
    companion object {
        private const val TAG = "WorkshopSymlinker"
        private const val COPY_SENTINEL = ".winnative_workshop"
        private val MOD_CONTAINER_NAMES get() = WorkshopModPathDetector.HIGH_CONFIDENCE_NAMES
    }

    data class SyncResult(
        val created: Int,
        val skipped: Int,
        val removed: Int,
        val errors: Map<String, String>,
    ) {
        val hasErrors get() = errors.isNotEmpty()
    }

    fun sync(
        strategy: WorkshopModPathStrategy,
        activeItemDirs: Map<Long, File>,
        workshopContentBase: File,
        itemTitles: Map<Long, String> = emptyMap(),
    ): SyncResult = when (strategy) {
        is WorkshopModPathStrategy.Standard -> SyncResult(0, activeItemDirs.size, 0, emptyMap())
        is WorkshopModPathStrategy.SymlinkIntoDir ->
            syncIntoAllDirs(strategy.effectiveDirs, activeItemDirs, workshopContentBase, true, itemTitles)
        is WorkshopModPathStrategy.CopyIntoDir ->
            syncIntoAllDirs(strategy.effectiveDirs, activeItemDirs, workshopContentBase, false, itemTitles)
    }

    private fun syncIntoAllDirs(
        targetDirs: List<File>,
        activeItemDirs: Map<Long, File>,
        workshopContentBase: File,
        useSymlinks: Boolean,
        itemTitles: Map<Long, String>,
    ): SyncResult {
        var created = 0
        var skipped = 0
        var removed = 0
        val errors = mutableMapOf<String, String>()

        for (dir in targetDirs) {
            val result = syncIntoOneDir(dir, activeItemDirs, workshopContentBase, useSymlinks, itemTitles)
            created += result.created
            skipped += result.skipped
            removed += result.removed
            result.errors.forEach { (key, value) -> errors["${dir.name}/$key"] = value }
        }
        return SyncResult(created, skipped, removed, errors)
    }

    private fun syncIntoOneDir(
        targetDir: File,
        activeItemDirs: Map<Long, File>,
        workshopContentBase: File,
        useSymlinks: Boolean,
        itemTitles: Map<Long, String>,
    ): SyncResult {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            val errors = activeItemDirs.keys.associate { it.toString() to "Could not create ${targetDir.absolutePath}" }
            return SyncResult(0, 0, 0, errors)
        }

        if (useSymlinks) {
            val isModContainerDir = targetDir.name.lowercase() in MOD_CONTAINER_NAMES
            val allSingleFile = !isModContainerDir && activeItemDirs.values.all { srcDir ->
                val children = srcDir.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
                children.size == 1 && children.single().isFile
            }
            if (allSingleFile) {
                return syncFlatFilesIntoDir(targetDir, activeItemDirs, workshopContentBase)
            }
        }

        val usedNames = mutableSetOf<String>()
        val entryNameForId = mutableMapOf<Long, String>()
        for (id in activeItemDirs.keys.sorted()) {
            val title = itemTitles[id]
            val baseName = if (!title.isNullOrBlank()) sanitizeFileName(title) else id.toString()
            var candidate = baseName
            if (candidate in usedNames) candidate = "${baseName}_$id"
            var counter = 1
            while (candidate in usedNames) {
                candidate = "${baseName}_${id}_$counter"
                counter++
            }
            entryNameForId[id] = candidate
            usedNames += candidate
        }
        val expectedNames = entryNameForId.values.toSet()

        var created = 0
        var skipped = 0
        var removed = 0
        val errors = mutableMapOf<String, String>()

        targetDir.listFiles()?.forEach { entry ->
            if (entry.name in expectedNames) return@forEach
            val ours = when {
                Files.isSymbolicLink(entry.toPath()) -> isOurSymlink(entry, workshopContentBase)
                entry.isDirectory -> hasCopySentinel(entry)
                else -> false
            }
            if (ours && deleteEntry(entry)) {
                removed++
            }
        }

        for ((itemId, sourceDir) in activeItemDirs) {
            val entryName = entryNameForId[itemId] ?: itemId.toString()
            val entry = File(targetDir, entryName)
            if (!sourceDir.isDirectory) {
                errors[itemId.toString()] = "Source dir missing: ${sourceDir.absolutePath}"
                continue
            }

            try {
                val result = if (useSymlinks) {
                    ensureSymlink(entry, sourceDir, workshopContentBase)
                } else {
                    ensureCopy(entry, sourceDir, workshopContentBase)
                }
                if (result == LinkResult.CREATED) created++ else skipped++
            } catch (e: Exception) {
                errors[itemId.toString()] = "${e::class.simpleName}: ${e.message}"
            }
        }

        return SyncResult(created, skipped, removed, errors)
    }

    private fun syncFlatFilesIntoDir(
        targetDir: File,
        activeItemDirs: Map<Long, File>,
        workshopContentBase: File,
    ): SyncResult {
        var created = 0
        var skipped = 0
        var removed = 0
        val errors = mutableMapOf<String, String>()
        val expectedFiles = linkedMapOf<String, File>()

        for ((id, sourceDir) in activeItemDirs.entries.sortedBy { it.key }) {
            sourceDir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.forEach { file ->
                    if (file.name !in expectedFiles) {
                        expectedFiles[file.name] = file
                    } else {
                        Timber.tag(TAG).w("Flat-file collision for %s from item %d", file.name, id)
                    }
                }
        }

        targetDir.listFiles()?.forEach { entry ->
            if (entry.name in expectedFiles) return@forEach
            if (Files.isSymbolicLink(entry.toPath()) && isOurSymlink(entry, workshopContentBase) && deleteEntry(entry)) {
                removed++
            }
        }

        for ((fileName, sourceFile) in expectedFiles) {
            val link = File(targetDir, fileName)
            try {
                val linkPath = link.toPath()
                val targetPath = sourceFile.toPath().toAbsolutePath().normalize()
                if (Files.isSymbolicLink(linkPath)) {
                    val currentTarget = resolveSymlinkTarget(linkPath)
                    val resolvedTarget = resolvePath(targetPath) ?: targetPath
                    if (currentTarget == resolvedTarget) {
                        skipped++
                        continue
                    }
                    Files.delete(linkPath)
                } else if (Files.exists(linkPath)) {
                    skipped++
                    continue
                }
                Files.createSymbolicLink(linkPath, targetPath)
                created++
            } catch (e: Exception) {
                errors[fileName] = "${e::class.simpleName}: ${e.message}"
            }
        }

        return SyncResult(created, skipped, removed, errors)
    }

    private fun resolvePath(path: Path): Path? =
        runCatching { path.toRealPath() }.getOrElse {
            runCatching { path.normalize().toAbsolutePath() }.getOrNull()
        }

    private fun resolveSymlinkTarget(symlink: Path): Path? {
        val raw = runCatching { Files.readSymbolicLink(symlink) }.getOrNull() ?: return null
        val resolved = if (raw.isAbsolute) raw else symlink.parent.resolve(raw)
        return resolvePath(resolved)
    }

    private fun isOurSymlink(symlink: File, workshopContentBase: File): Boolean {
        val target = resolveSymlinkTarget(symlink.toPath()) ?: return false
        val base = resolvePath(workshopContentBase.toPath()) ?: return false
        return target.startsWith(base)
    }

    private fun hasCopySentinel(dir: File): Boolean = File(dir, COPY_SENTINEL).exists()

    private enum class LinkResult { CREATED, ALREADY_OK }

    private fun ensureSymlink(link: File, target: File, workshopContentBase: File): LinkResult {
        val linkPath = link.toPath()
        val targetPath = target.toPath().toAbsolutePath().normalize()

        if (Files.isSymbolicLink(linkPath)) {
            val currentTarget = resolveSymlinkTarget(linkPath)
            val resolvedTarget = resolvePath(targetPath) ?: targetPath
            if (currentTarget == resolvedTarget) {
                return LinkResult.ALREADY_OK
            }
            if (!isOurSymlink(link, workshopContentBase)) {
                return LinkResult.ALREADY_OK
            }
            Files.delete(linkPath)
        } else if (link.exists()) {
            return LinkResult.ALREADY_OK
        }

        Files.createSymbolicLink(linkPath, targetPath)
        return LinkResult.CREATED
    }

    private data class FileEntry(val path: String, val size: Long, val mtime: Long)

    private fun fingerprint(dir: File): Set<FileEntry> {
        val base = dir.absolutePath
        return dir.walkTopDown()
            .filter { it.isFile && it.name != COPY_SENTINEL }
            .mapTo(HashSet()) { file ->
                val relative = file.absolutePath.removePrefix(base).trimStart(File.separatorChar)
                FileEntry(relative, file.length(), file.lastModified())
            }
    }

    private fun ensureCopy(dest: File, source: File, workshopContentBase: File): LinkResult {
        if (Files.isSymbolicLink(dest.toPath())) {
            if (!isOurSymlink(dest, workshopContentBase)) {
                return LinkResult.ALREADY_OK
            }
            Files.delete(dest.toPath())
        } else if (dest.isDirectory) {
            if (!hasCopySentinel(dest)) {
                return LinkResult.ALREADY_OK
            }
            if (fingerprint(dest) == fingerprint(source)) {
                return LinkResult.ALREADY_OK
            }
            dest.deleteRecursively()
        }

        if (!source.copyRecursively(dest, overwrite = true)) {
            throw IOException("copyRecursively returned false for ${source.name}")
        }
        File(dest, COPY_SENTINEL).createNewFile()
        return LinkResult.CREATED
    }

    private fun deleteEntry(entry: File): Boolean = try {
        if (Files.isSymbolicLink(entry.toPath())) {
            Files.delete(entry.toPath())
            true
        } else {
            entry.deleteRecursively()
        }
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "deleteEntry failed for ${entry.absolutePath}")
        false
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_")
            .trim()
            .trimEnd('.', ' ')
        return cleaned.ifEmpty { "unnamed" }
    }
}
