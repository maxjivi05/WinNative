package com.winlator.cmod.runtime.linux

import android.content.Context
import android.util.Log
import com.winlator.cmod.feature.library.LinuxApps
import com.winlator.cmod.feature.settings.DXVKConfigUtils
import com.winlator.cmod.feature.settings.GraphicsDriverConfigUtils
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.container.Shortcut
import java.io.File

/**
 * The Vulkan driver and the Direct3D 12 feature level each GameScope game runs with. A session
 * takes both from the entry that started it, and a game the client starts inherits that, so
 * `winnative-proton-launch` reads these files as the game starts: what its shortcut's settings
 * chose, else the container's `*`.
 */
object LinuxGraphicsChoices {
    private const val TAG = "LinuxGraphicsChoices"
    private const val DRIVERS = "etc/winnative/driver-choices"
    private const val FEATURE_LEVELS = "etc/winnative/vkd3d-choices"
    private val lock = Any()

    /** Worker thread. */
    @JvmStatic
    fun write(context: Context) {
        synchronized(lock) {
            if (!LinuxRuntime.isInstalled(context)) return
            val drivers = File(LinuxRuntime.rootDir(context), DRIVERS)
            val featureLevels = File(LinuxRuntime.rootDir(context), FEATURE_LEVELS)
            try {
                val container = LinuxApps.gamescopeContainer(ContainerManager(context))
                if (container == null) {
                    drivers.delete()
                    featureLevels.delete()
                    return
                }
                val containerDriver = container.graphicsDriverConfig.orEmpty()
                val containerDx = container.getDXWrapperConfig().orEmpty()
                val driverLines = ArrayList<String>()
                val levelLines = ArrayList<String>()
                icd(context, containerDriver)?.let { driverLines += "* $it" }
                featureLevel(containerDx)?.let { levelLines += "* $it" }
                for (entry in container.desktopDir.listFiles { f -> f.name.endsWith(".desktop") }.orEmpty()) {
                    val shortcut = Shortcut(container, entry)
                    if (LinuxApps.isLinuxShortcut(shortcut)) continue
                    val appId = LinuxSteamShortcuts.clientAppId(context, shortcut) ?: continue
                    val driver = shortcut.getSettingExtra("graphicsDriverConfig", containerDriver).orEmpty()
                    icd(context, driver)?.let { driverLines += "$appId $it" }
                    val dx = shortcut.getSettingExtra("dxwrapperConfig", containerDx).orEmpty()
                    featureLevel(dx)?.let { levelLines += "$appId $it" }
                }
                store(drivers, driverLines)
                store(featureLevels, levelLines)
            } catch (error: Exception) {
                Log.w(TAG, "Could not record the graphics choices", error)
            }
        }
    }

    /** [write] off the caller's thread, for a settings screen that has just saved. */
    @JvmStatic
    fun update(context: Context) {
        val app = context.applicationContext
        Thread({ write(app) }, "LinuxGraphicsChoices").start()
    }

    private fun store(file: File, lines: List<String>) {
        if (lines.isEmpty()) {
            file.delete()
        } else {
            LinuxSteamVdf.replace(file) { it.writeText(lines.joinToString("\n", postfix = "\n")) }
        }
    }

    /** The manifest a session given [config] would draw with, as the session resolves it. */
    private fun icd(context: Context, config: String): String? {
        val settings = GraphicsDriverConfigUtils.parseGraphicsDriverConfig(Container.DEFAULT_GRAPHICSDRIVERCONFIG)
        settings.putAll(GraphicsDriverConfigUtils.parseGraphicsDriverConfig(config))
        return LinuxRuntime.vulkanIcd(context, settings["version"])?.path?.takeUnless { '\n' in it }
    }

    /** The VKD3D-Proton feature level [config] sets, as a Wine session given it would export it. */
    private fun featureLevel(config: String): String? =
        DXVKConfigUtils.parseConfig(config).get("vkd3dLevel").takeIf { it in DXVKConfigUtils.VKD3D_FEATURE_LEVEL }
}
