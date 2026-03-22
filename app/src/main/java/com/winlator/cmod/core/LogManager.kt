package com.winlator.cmod.core

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File

object LogManager {
    private const val TAG = "LogManager"
    private var process: Process? = null

    fun getLogsDir(context: Context): File {
        val dir = File(context.cacheDir, "WinNativeLogs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isAnyLoggingEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("enable_wine_debug", false) ||
               prefs.getBoolean("enable_box64_logs", false) ||
               prefs.getBoolean("enable_fexcore_logs", false) ||
               prefs.getBoolean("enable_steam_logs", false) ||
               prefs.getBoolean("enable_input_logs", false) ||
               prefs.getBoolean("enable_download_logs", false)
    }

    fun updateLoggingState(context: Context) {
        if (!isAnyLoggingEnabled(context)) {
            stopLogging()
            clearLogs(context)
        }
    }

    fun startLogging(context: Context) {
        clearLogs(context)

        if (!isAnyLoggingEnabled(context)) {
            stopLogging()
            return
        }

        val logsDir = getLogsDir(context)
        val logFile = File(logsDir, "logcat.log")

        try {
            stopLogging()
            Runtime.getRuntime().exec("logcat -c").waitFor()
            process = Runtime.getRuntime().exec(arrayOf("logcat", "-f", logFile.absolutePath, "*:D"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start logcat: ${e.message}")
        }
    }

    fun stopLogging() {
        try {
            process?.destroy()
            process = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop logging: ${e.message}")
        }
    }

    fun clearLogs(context: Context) {
        val logsDir = getLogsDir(context)
        logsDir.listFiles()?.forEach { it.delete() }
    }
}
