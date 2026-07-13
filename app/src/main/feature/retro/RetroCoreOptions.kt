package com.winlator.cmod.feature.retro

data class RetroCoreOption(
    val key: String,
    val label: String,
    val values: List<String>,
    val valueLabels: List<String>,
    val defaultValue: String,
)

object RetroCoreOptions {
    private val NES_OPTIONS =
        listOf(
            RetroCoreOption(
                key = "fceumm_aspect",
                label = "Aspect Ratio",
                values = listOf("8:7 PAR", "4:3"),
                valueLabels = listOf("8:7 (Native)", "4:3 (TV)"),
                defaultValue = "8:7 PAR",
            ),
        )

    private val SNES_OPTIONS =
        listOf(
            RetroCoreOption(
                key = "snes9x_blargg",
                label = "NTSC Filter",
                values = listOf("disabled", "rf", "composite", "s-video", "rgb"),
                valueLabels = listOf("Off", "RF", "Composite", "S-Video", "RGB"),
                defaultValue = "disabled",
            ),
        )

    private val GB_OPTIONS =
        listOf(
            RetroCoreOption(
                key = "gambatte_gb_colorization",
                label = "Colorization",
                values = listOf("disabled", "auto"),
                valueLabels = listOf("Off", "Auto"),
                defaultValue = "disabled",
            ),
        )

    private val GBA_OPTIONS =
        listOf(
            RetroCoreOption(
                key = "mgba_color_correction",
                label = "Color Correction",
                values = listOf("OFF", "GBA", "GBC"),
                valueLabels = listOf("Off", "GBA Screen", "GBC Screen"),
                defaultValue = "OFF",
            ),
        )

    private val GENESIS_OPTIONS =
        listOf(
            RetroCoreOption(
                key = "genesis_plus_gx_no_sprite_limit",
                label = "Remove Sprite Limit",
                values = listOf("disabled", "enabled"),
                valueLabels = listOf("Off", "On"),
                defaultValue = "disabled",
            ),
            RetroCoreOption(
                key = "genesis_plus_gx_blargg_ntsc_filter",
                label = "NTSC Filter",
                values = listOf("Off", "Composite", "S-Video", "RGB"),
                valueLabels = listOf("Off", "Composite", "S-Video", "RGB"),
                defaultValue = "Off",
            ),
        )

    private val N64_OPTIONS =
        listOf(
            RetroCoreOption(
                key = "mupen64plus-43screensize",
                label = "Resolution",
                values = listOf("320x240", "640x480", "960x720"),
                valueLabels = listOf("320x240 (Native)", "640x480", "960x720"),
                defaultValue = "320x240",
            ),
        )

    private val PSX_OPTIONS =
        listOf(
            RetroCoreOption(
                key = "pcsx_rearmed_neon_enhancement_enable",
                label = "Enhanced Resolution",
                values = listOf("disabled", "enabled"),
                valueLabels = listOf("Off", "On (2x)"),
                defaultValue = "disabled",
            ),
            RetroCoreOption(
                key = "pcsx_rearmed_dithering",
                label = "Dithering",
                values = listOf("enabled", "disabled"),
                valueLabels = listOf("On", "Off"),
                defaultValue = "enabled",
            ),
        )

    fun forSystem(system: RetroSystem?): List<RetroCoreOption> =
        when (system?.id) {
            RetroSystems.NES.id -> NES_OPTIONS
            RetroSystems.SNES.id -> SNES_OPTIONS
            RetroSystems.GAMEBOY.id -> GB_OPTIONS
            RetroSystems.GBA.id -> GBA_OPTIONS
            RetroSystems.GENESIS.id, RetroSystems.MASTER_SYSTEM.id, RetroSystems.GAME_GEAR.id -> GENESIS_OPTIONS
            RetroSystems.N64.id -> N64_OPTIONS
            RetroSystems.PSX.id -> PSX_OPTIONS
            else -> emptyList()
        }
}
