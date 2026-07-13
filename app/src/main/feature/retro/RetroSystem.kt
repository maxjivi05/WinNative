package com.winlator.cmod.feature.retro

import java.util.Locale

data class RetroSystem(
    val id: String,
    val displayName: String,
    val shortName: String,
    val coreFileName: String,
    val extensions: Set<String>,
    val needsBios: Boolean = false,
    val biosFiles: List<String> = emptyList(),
)

object RetroSystems {
    val NES =
        RetroSystem(
            id = "nes",
            displayName = "Nintendo Entertainment System",
            shortName = "NES",
            coreFileName = "libfceumm_libretro_android.so",
            extensions = setOf("nes", "unf", "unif"),
        )
    val SNES =
        RetroSystem(
            id = "snes",
            displayName = "Super Nintendo",
            shortName = "SNES",
            coreFileName = "libsnes9x_libretro_android.so",
            extensions = setOf("smc", "sfc", "swc", "fig", "bs"),
        )
    val GAMEBOY =
        RetroSystem(
            id = "gb",
            displayName = "Game Boy",
            shortName = "GB",
            coreFileName = "libgambatte_libretro_android.so",
            extensions = setOf("gb"),
        )
    val GAMEBOY_COLOR =
        RetroSystem(
            id = "gbc",
            displayName = "Game Boy Color",
            shortName = "GBC",
            coreFileName = "libgambatte_libretro_android.so",
            extensions = setOf("gbc"),
        )
    val GBA =
        RetroSystem(
            id = "gba",
            displayName = "Game Boy Advance",
            shortName = "GBA",
            coreFileName = "libmgba_libretro_android.so",
            extensions = setOf("gba"),
        )
    val GENESIS =
        RetroSystem(
            id = "genesis",
            displayName = "Sega Genesis / Mega Drive",
            shortName = "Genesis",
            coreFileName = "libgenesis_plus_gx_libretro_android.so",
            extensions = setOf("gen", "md", "smd", "bin"),
        )
    val MASTER_SYSTEM =
        RetroSystem(
            id = "sms",
            displayName = "Sega Master System",
            shortName = "Master System",
            coreFileName = "libgenesis_plus_gx_libretro_android.so",
            extensions = setOf("sms"),
        )
    val GAME_GEAR =
        RetroSystem(
            id = "gg",
            displayName = "Sega Game Gear",
            shortName = "Game Gear",
            coreFileName = "libgenesis_plus_gx_libretro_android.so",
            extensions = setOf("gg"),
        )
    val N64 =
        RetroSystem(
            id = "n64",
            displayName = "Nintendo 64",
            shortName = "N64",
            coreFileName = "libmupen64plus_next_gles3_libretro_android.so",
            extensions = setOf("n64", "z64", "v64"),
        )
    val PSX =
        RetroSystem(
            id = "psx",
            displayName = "Sony PlayStation",
            shortName = "PS1",
            coreFileName = "libpcsx_rearmed_libretro_android.so",
            extensions = setOf("cue", "chd", "pbp", "m3u", "iso"),
            needsBios = false,
            biosFiles = listOf("scph5501.bin", "scph5500.bin", "scph5502.bin", "scph1001.bin"),
        )

    val ALL =
        listOf(NES, SNES, GAMEBOY, GAMEBOY_COLOR, GBA, GENESIS, MASTER_SYSTEM, GAME_GEAR, N64, PSX)

    private val PSX_ONLY_EXTENSIONS = setOf("cue", "chd", "pbp", "m3u")

    val allExtensions: Set<String> = ALL.flatMap { it.extensions }.toSet() - "exe"

    fun fromId(id: String?): RetroSystem? {
        if (id.isNullOrBlank()) return null
        val normalized = id.trim().lowercase(Locale.US)
        return ALL.firstOrNull { it.id == normalized }
    }

    fun fromExtension(extension: String?): RetroSystem? {
        if (extension.isNullOrBlank()) return null
        val ext = extension.trim().lowercase(Locale.US).removePrefix(".")
        if (ext == "exe") return null
        if (ext in PSX_ONLY_EXTENSIONS || ext == "iso") return PSX
        return ALL.firstOrNull { ext in it.extensions }
    }

    fun fromRomPath(path: String?): RetroSystem? {
        if (path.isNullOrBlank()) return null
        val ext = path.substringAfterLast('.', "")
        return fromExtension(ext)
    }

    fun isRetroRom(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in allExtensions
    }
}
