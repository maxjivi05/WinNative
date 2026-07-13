package com.winlator.cmod.feature.retro

import android.content.Context
import android.content.Intent
import com.winlator.cmod.feature.setup.SetupWizardActivity
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.shared.io.FileUtils
import java.io.File
import java.util.UUID

object RetroShortcuts {
    const val KEY_SYSTEM = "retro_system"
    const val KEY_ROM = "rom_path"
    const val KEY_CORE = "retro_core"
    const val KEY_SHADER = "retro_shader"
    const val KEY_TOUCH_CONTROLS = "retro_touch_controls"
    const val KEY_AUDIO = "retro_audio"
    const val VAR_PREFIX = "retro_var_"

    fun coreVariables(shortcut: Shortcut): HashMap<String, String> {
        val vars = HashMap<String, String>()
        val extras = shortcut.extraData
        val keys = extras.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith(VAR_PREFIX)) {
                val value = shortcut.getExtra(key)
                if (value.isNotEmpty()) vars[key.removePrefix(VAR_PREFIX)] = value
            }
        }
        return vars
    }

    @JvmStatic
    fun isRetroShortcut(shortcut: Shortcut): Boolean = shortcut.getExtra(KEY_SYSTEM).isNotEmpty()

    fun systemForShortcut(shortcut: Shortcut): RetroSystem? = RetroSystems.fromId(shortcut.getExtra(KEY_SYSTEM))

    fun romPath(shortcut: Shortcut): String = shortcut.getExtra(KEY_ROM)

    fun create(
        context: Context,
        name: String,
        romPath: String,
        system: RetroSystem,
    ): Boolean {
        val containerManager = ContainerManager(context)
        val container = SetupWizardActivity.getPreferredGameContainer(context, containerManager) ?: return false

        val desktopDir = container.desktopDir
        if (!desktopDir.exists()) desktopDir.mkdirs()

        val safeName = name.replace("/", "_").replace("\\", "_")
        val shortcutFile = File(desktopDir, "$safeName.desktop")
        val shortcutUuid = UUID.randomUUID().toString()

        val content =
            buildString {
                append("[Desktop Entry]\n")
                append("Type=Application\n")
                append("Name=$name\n")
                append("Exec=retro:${system.id}\n")
                append("Icon=custom_game\n")
                append("\n[Extra Data]\n")
                append("game_source=CUSTOM\n")
                append("custom_name=$name\n")
                append("$KEY_SYSTEM=${system.id}\n")
                append("$KEY_ROM=$romPath\n")
                append("$KEY_CORE=${system.coreFileName}\n")
                append("uuid=$shortcutUuid\n")
                append("container_id=${container.id}\n")
                append("use_container_defaults=1\n")
            }

        FileUtils.writeString(shortcutFile, content)
        container.saveData()
        return true
    }

    @JvmStatic
    fun launchIntent(
        context: Context,
        shortcut: Shortcut,
    ): Intent =
        Intent(context, RetroActivity::class.java).apply {
            putExtra(RetroActivity.EXTRA_ROM_PATH, shortcut.getExtra(KEY_ROM))
            putExtra(RetroActivity.EXTRA_SYSTEM_ID, shortcut.getExtra(KEY_SYSTEM))
            putExtra(RetroActivity.EXTRA_GAME_NAME, shortcut.getExtra("custom_name", shortcut.name))
            putExtra(RetroActivity.EXTRA_SHORTCUT_PATH, shortcut.file.absolutePath)
            putExtra(RetroActivity.EXTRA_CONTAINER_ID, shortcut.container.id)
            putExtra(RetroActivity.EXTRA_SHADER, shortcut.getExtra(KEY_SHADER, "default"))
            putExtra(RetroActivity.EXTRA_TOUCH_CONTROLS, shortcut.getExtra(KEY_TOUCH_CONTROLS, "1") != "0")
            putExtra(RetroActivity.EXTRA_AUDIO, shortcut.getExtra(KEY_AUDIO, "1") != "0")
            putExtra(RetroActivity.EXTRA_VARIABLES, coreVariables(shortcut))
        }
}
