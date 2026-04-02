package com.winlator.cmod.steam.workshop

import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

class WorkshopModPathDetector {
    enum class Confidence { LOW, MEDIUM, HIGH }

    data class DetectionResult(
        val strategy: WorkshopModPathStrategy,
        val confidence: Confidence,
        val reason: String,
    )

    companion object {
        private const val TAG = "WorkshopModPathDetector"
        private const val MAX_BINARY_SCAN_BYTES = 64L * 1024 * 1024
        private const val MIN_STRING_LEN = 5
        private const val MAX_CONFIG_BYTES = 1L * 1024 * 1024
        private const val MAX_WALK_DEPTH = 3
        private const val INSTALL_SCAN_DEPTH = 2

        val HIGH_CONFIDENCE_NAMES = setOf(
            "mods", "mod", "addons", "addon", "plugins", "plugin",
            "workshop_mods", "usermods", "user_mods",
            "modules", "module", "ugc",
            "resourcepacks", "resource_packs",
        )
        private val MEDIUM_CONFIDENCE_NAMES = setOf(
            "levels", "level",
            "scenarios", "scenario", "missions", "mission",
            "workshop", "override", "gamedata",
            "maps",
        )
        private val LOW_CONFIDENCE_NAMES = setOf(
            "custom", "usercontent", "user_content", "community",
            "packages", "package", "downloads", "download", "downloaded",
            "extras", "expansion", "expansions",
        )
        private val ALL_MOD_DIR_NAMES =
            HIGH_CONFIDENCE_NAMES + MEDIUM_CONFIDENCE_NAMES + LOW_CONFIDENCE_NAMES

        private val APPDATA_TOKENS = listOf(
            "%appdata%", "%localappdata%",
            "appdata\\roaming", "appdata\\local\\", "appdata\\locallow",
        )
        private val BINARY_STANDARD_SIGNALS = listOf(
            "GetItemInstallInfo", "ISteamUGC", "SteamUGC()",
            "workshop\\content", "workshop/content",
        )
        private val CONFIG_EXTENSIONS =
            setOf("ini", "cfg", "conf", "json", "jsonc", "xml", "txt", "yaml", "yml", "toml")
        private val CONFIG_MOD_KEY_FRAGMENTS = listOf(
            "modpath", "mod_path", "modfolder", "mod_folder", "moddir", "mod_dir",
            "moddirectory", "mod_directory",
            "workshoppath", "workshop_path", "addonspath", "addons_path",
            "pluginpath", "plugin_path",
            "mappath", "map_path", "mapdir", "campaignpath", "campaign_path",
            "contentpath", "content_path",
            "localmods", "local_mods", "usermods", "user_mods",
            "custompath", "custom_path", "downloaddir", "download_dir",
        )
        private val CONFIG_SKIP_FILES = setOf(
            "steam_appid.txt", "steam_api.ini", "unins000.dat",
            "changelog.txt", "readme.txt", "readme.md", "license.txt", "license.md", "credits.txt",
        )
        private val CONFIG_SKIP_DIRS = setOf(
            "assets", "resources", "textures", "texture", "sounds", "sound", "audio",
            "shaders", "shader", "fonts", "font", "videos", "video", "movies",
            "localization", "locale", "lang",
        )
        private val INSTALL_SKIP_DIRS = setOf(
            ".depotdownloader", "steam_settings", ".git", ".svn",
            "_commonredist", "__macosx", "directx",
            "engine", "binaries", "help",
        )
        private val APPDATA_SKIP_DIRS = setOf(
            "Microsoft", "Windows", "Packages", "Temp", "Google", "Mozilla", "Apple", "Adobe", "Intel",
        )
    }

    private data class CandidateDir(val dir: File, val confidence: Confidence, val source: String)
    private data class AppDataRoot(val root: File, val envToken: String)
    private data class BSF(val std: Boolean, val appData: List<String>, val install: List<String>)
    private data class BinaryResult(val candidates: List<CandidateDir>, val stdSeen: Boolean)

    fun detect(
        gameInstallDir: File,
        appDataRoaming: File,
        appDataLocal: File,
        appDataLocalLow: File,
        documentsMyGames: File = File(""),
        documentsDir: File = File(""),
        gameName: String,
        developerName: String = "",
    ): DetectionResult {
        if (!gameInstallDir.exists() || !gameInstallDir.isDirectory) {
            return DetectionResult(WorkshopModPathStrategy.Standard, Confidence.LOW, "Install directory missing")
        }

        val appDataRoots = buildList {
            if (appDataRoaming.isDirectory) add(AppDataRoot(appDataRoaming, "%APPDATA%"))
            if (appDataLocal.isDirectory) add(AppDataRoot(appDataLocal, "%LOCALAPPDATA%"))
            if (appDataLocalLow.isDirectory) add(AppDataRoot(appDataLocalLow, "LocalLow"))
            if (documentsMyGames.isDirectory) add(AppDataRoot(documentsMyGames, "Documents\\My Games"))
            if (documentsDir.isDirectory) add(AppDataRoot(documentsDir, "Documents"))
        }

        val found = LinkedHashMap<String, CandidateDir>()
        fun add(candidate: CandidateDir) {
            val key = runCatching { candidate.dir.canonicalPath }.getOrElse { candidate.dir.absolutePath }
            val existing = found[key]
            if (existing == null || candidate.confidence > existing.confidence) {
                found[key] = candidate
            }
        }

        val binaryResult = collectFromBinaries(gameInstallDir, appDataRoots)
        binaryResult.candidates.forEach(::add)
        collectModsDirectories(gameInstallDir, 0).forEach(::add)
        (listOf(gameInstallDir) + appDataRoots.map { it.root })
            .forEach { root -> collectFromConfigFiles(root, appDataRoots).forEach(::add) }
        collectFromAppDataFuzzy(appDataRoots, gameName, developerName).forEach(::add)

        if (found.isEmpty()) {
            return if (binaryResult.stdSeen) {
                DetectionResult(
                    WorkshopModPathStrategy.Standard,
                    Confidence.MEDIUM,
                    "Standard Steam Workshop API detected",
                )
            } else {
                DetectionResult(WorkshopModPathStrategy.Standard, Confidence.LOW, "No mod path signals found")
            }
        }

        val sorted = found.values.sortedByDescending { it.confidence }
        val strongFamilyOf: (CandidateDir) -> String? = { candidate ->
            when {
                candidate.source.startsWith("binary") -> "binary"
                candidate.source.startsWith("config(appdata)") -> "config-appdata"
                else -> null
            }
        }
        val distinctStrongFamilies = sorted
            .filter { it.confidence >= Confidence.MEDIUM && strongFamilyOf(it) != null }
            .mapNotNull(strongFamilyOf)
            .toSet()
        val fanOut = if (distinctStrongFamilies.size >= 2) {
            WorkshopModPathStrategy.FanOutPolicy.ALL_DIRS
        } else {
            WorkshopModPathStrategy.FanOutPolicy.PRIMARY_ONLY
        }
        val reason = sorted.joinToString("; ") { "${it.dir.name}[${it.confidence}](${it.source})" }
        return DetectionResult(
            WorkshopModPathStrategy.SymlinkIntoDir(sorted.map { it.dir }, fanOut),
            sorted.first().confidence,
            reason,
        )
    }

    private fun collectFromBinaries(installDir: File, appDataRoots: List<AppDataRoot>): BinaryResult {
        val exes = findMainExecutables(installDir)
        if (exes.isEmpty()) return BinaryResult(emptyList(), false)
        val findings = exes.map(::scanOneBinary)
        val stdSeen = findings.any { it.std }
        val results = mutableListOf<CandidateDir>()

        findings.flatMap { it.appData }.distinct().forEach { raw ->
            val resolved = resolveAppDataPath(raw, appDataRoots) ?: return@forEach
            if (resolved.isDirectory) {
                results += CandidateDir(resolved, Confidence.HIGH, "binary(appdata)")
            }
        }
        findings.flatMap { it.install }.distinct().forEach { raw ->
            val resolved = resolveInstallPath(raw, installDir) ?: return@forEach
            if (!resolved.isDirectory) return@forEach
            val confidence = when (resolved.name.lowercase()) {
                in HIGH_CONFIDENCE_NAMES -> Confidence.HIGH
                in MEDIUM_CONFIDENCE_NAMES -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
            results += CandidateDir(resolved, confidence, "binary(install)")
        }
        return BinaryResult(results, stdSeen)
    }

    private fun scanOneBinary(file: File): BSF {
        var stdFound = false
        val appDataPaths = mutableListOf<String>()
        val installPaths = mutableListOf<String>()
        val buffer = ByteArray(65_536)
        val stringBuilder = StringBuilder(512)
        var total = 0L

        fun flush() {
            val value = stringBuilder.toString().trimEnd()
            stringBuilder.clear()
            if (value.length < MIN_STRING_LEN) return
            val lower = value.lowercase()
            if (!stdFound && BINARY_STANDARD_SIGNALS.any { lower.contains(it.lowercase()) }) {
                stdFound = true
            }
            if (APPDATA_TOKENS.any { lower.contains(it) }) {
                if (ALL_MOD_DIR_NAMES.any { modName ->
                        lower.contains("\\$modName\\") || lower.contains("/$modName/") ||
                            lower.endsWith("\\$modName") || lower.endsWith("/$modName")
                    }
                ) {
                    appDataPaths += value
                }
                return
            }
            if ((value.contains('\\') || value.contains('/')) && ALL_MOD_DIR_NAMES.any { modName ->
                    lower.contains("\\$modName\\") || lower.contains("/$modName/") ||
                        lower.endsWith("\\$modName") || lower.endsWith("/$modName")
                }
            ) {
                installPaths += value
            }
        }

        try {
            BufferedInputStream(FileInputStream(file), buffer.size).use { stream ->
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    for (index in 0 until read) {
                        val byteValue = buffer[index].toInt() and 0xFF
                        if (byteValue in 0x20..0x7E) {
                            stringBuilder.append(byteValue.toChar())
                        } else {
                            flush()
                        }
                    }
                    total += read
                    if (total >= MAX_BINARY_SCAN_BYTES) break
                }
                flush()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Binary scan failed for ${file.absolutePath}")
        }

        return BSF(stdFound, appDataPaths, installPaths)
    }

    private fun findMainExecutables(root: File): List<File> {
        val files = root.walkTopDown()
            .maxDepth(INSTALL_SCAN_DEPTH + 2)
            .filter { file ->
                file.isFile &&
                    file.extension.equals("exe", ignoreCase = true) &&
                    !file.absolutePath.contains(".DepotDownloader")
            }
            .toList()
        return files.sortedBy { it.absolutePath.length }.take(6)
    }

    private fun resolveAppDataPath(raw: String, appDataRoots: List<AppDataRoot>): File? {
        val normalized = raw.replace('/', '\\')
        val lower = normalized.lowercase()
        val root = appDataRoots.firstOrNull {
            lower.startsWith(it.envToken.lowercase()) ||
                lower.contains(it.envToken.lowercase())
        } ?: return null
        val pathStart = ALL_MOD_DIR_NAMES
            .mapNotNull { name ->
                listOf("\\$name", "/$name").firstOrNull { delimiter ->
                    lower.contains(delimiter.replace('/', '\\'))
                }?.let { lower.indexOf(it) }
            }
            .minOrNull() ?: return null
        val relative = normalized.substring(pathStart + 1).replace('\\', File.separatorChar)
        return File(root.root, relative)
    }

    private fun resolveInstallPath(raw: String, installDir: File): File? {
        val normalized = raw.replace('\\', '/')
        val markerIndex = ALL_MOD_DIR_NAMES
            .mapNotNull { name ->
                listOf("/$name", "\\$name")
                    .map { normalized.lowercase().indexOf(it.replace('\\', '/')) }
                    .filter { it >= 0 }
                    .minOrNull()
            }
            .minOrNull() ?: return null
        val relative = normalized.substring(markerIndex + 1)
        return File(installDir, relative.replace('/', File.separatorChar))
    }

    private fun collectModsDirectories(root: File, depth: Int): List<CandidateDir> {
        if (!root.isDirectory || depth > INSTALL_SCAN_DEPTH) return emptyList()
        val result = mutableListOf<CandidateDir>()
        root.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            if (child.name.lowercase() in INSTALL_SKIP_DIRS) return@forEach
            val lower = child.name.lowercase()
            if (lower in HIGH_CONFIDENCE_NAMES) {
                result += CandidateDir(child, Confidence.HIGH, "install-scan")
            } else if (lower in MEDIUM_CONFIDENCE_NAMES) {
                result += CandidateDir(child, Confidence.MEDIUM, "install-scan")
            } else if (lower in LOW_CONFIDENCE_NAMES) {
                result += CandidateDir(child, Confidence.LOW, "install-scan")
            }
            result += collectModsDirectories(child, depth + 1)
        }
        return result
    }

    private fun collectFromConfigFiles(root: File, appDataRoots: List<AppDataRoot>): List<CandidateDir> {
        if (!root.isDirectory) return emptyList()
        val result = mutableListOf<CandidateDir>()
        root.walkTopDown()
            .maxDepth(MAX_WALK_DEPTH)
            .filter { file ->
                file.isFile &&
                    file.extension.lowercase() in CONFIG_EXTENSIONS &&
                    file.name.lowercase() !in CONFIG_SKIP_FILES &&
                    file.length() in 1..MAX_CONFIG_BYTES
            }
            .forEach { file ->
                if (file.parentFile?.name?.lowercase() in CONFIG_SKIP_DIRS) return@forEach
                runCatching {
                    val text = file.readText()
                    val lower = text.lowercase()
                    if (!CONFIG_MOD_KEY_FRAGMENTS.any { lower.contains(it) }) return@runCatching
                    val lines = text.lineSequence().toList()
                    lines.forEach { line ->
                        val lineLower = line.lowercase()
                        if (!CONFIG_MOD_KEY_FRAGMENTS.any { lineLower.contains(it) }) return@forEach
                        val value = line.substringAfter('=').trim().trim('"', '\'')
                        if (value.isBlank()) return@forEach
                        val resolvedCandidate = if (APPDATA_TOKENS.any { value.lowercase().contains(it) }) {
                            resolveAppDataPath(value, appDataRoots)
                        } else if (value.contains('\\') || value.contains('/')) {
                            resolveInstallPath(value, root)
                        } else {
                            null
                        }
                        val resolved = resolvedCandidate ?: return@forEach
                        if (resolved.isDirectory) {
                            val source = if (resolved.absolutePath.startsWith(root.absolutePath)) {
                                "config(install)"
                            } else {
                                "config(appdata)"
                            }
                            result += CandidateDir(resolved, Confidence.MEDIUM, source)
                        }
                    }
                }
            }
        return result
    }

    private fun collectFromAppDataFuzzy(
        roots: List<AppDataRoot>,
        gameName: String,
        developerName: String,
    ): List<CandidateDir> {
        val tokens = buildFuzzyTokens(gameName, developerName)
        if (tokens.isEmpty()) return emptyList()
        val result = mutableListOf<CandidateDir>()

        roots.forEach { appDataRoot ->
            appDataRoot.root.walkTopDown()
                .maxDepth(MAX_WALK_DEPTH)
                .filter { it.isDirectory && it.name !in APPDATA_SKIP_DIRS }
                .forEach { dir ->
                    val nameLower = dir.name.lowercase()
                    if (!tokens.any { token -> nameLower.contains(token) }) return@forEach
                    dir.listFiles()
                        ?.filter { it.isDirectory }
                        ?.forEach { child ->
                            val lower = child.name.lowercase()
                            val confidence = when (lower) {
                                in HIGH_CONFIDENCE_NAMES -> Confidence.HIGH
                                in MEDIUM_CONFIDENCE_NAMES -> Confidence.MEDIUM
                                in LOW_CONFIDENCE_NAMES -> Confidence.LOW
                                else -> null
                            } ?: return@forEach
                            result += CandidateDir(child, confidence, "appdata-fuzzy")
                        }
                }
        }
        return result
    }

    private fun buildFuzzyTokens(gameName: String, developerName: String): List<String> {
        return listOf(gameName, developerName)
            .flatMap { raw ->
                raw.lowercase()
                    .replace(Regex("[^a-z0-9 ]"), " ")
                    .split(Regex("\\s+"))
                    .filter { it.length >= 3 }
            }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
