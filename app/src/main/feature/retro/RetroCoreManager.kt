package com.winlator.cmod.feature.retro

import android.content.Context
import java.io.File

object RetroCoreManager {
    fun coreFile(
        context: Context,
        system: RetroSystem,
    ): File = File(context.applicationInfo.nativeLibraryDir, system.coreFileName)

    fun isCoreAvailable(
        context: Context,
        system: RetroSystem,
    ): Boolean = coreFile(context, system).isFile

    fun systemDir(context: Context): File = File(context.filesDir, "retro/system").also { it.mkdirs() }

    fun savesDir(context: Context): File = File(context.filesDir, "retro/saves").also { it.mkdirs() }

    fun statesDir(context: Context): File = File(context.filesDir, "retro/states").also { it.mkdirs() }

    fun stateFile(
        context: Context,
        gameName: String,
        slot: Int,
    ): File {
        val safe = gameName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val suffix = if (slot <= 0) "state" else "state$slot"
        return File(statesDir(context), "$safe.$suffix")
    }

    fun missingBios(
        context: Context,
        system: RetroSystem,
    ): Boolean {
        if (!system.needsBios) return false
        val dir = systemDir(context)
        return system.biosFiles.none { File(dir, it).isFile }
    }
}
