package com.winlator.cmod

import android.content.Context
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.steam.utils.PrefManager

object CloudSaveSettings {
    private const val GOOGLE_PREFS_NAME = "google_store_login_sync"
    private const val KEY_GLOBAL_GOOGLE_CLOUD_SAVES = "cloud_sync_auto_backup"
    private const val KEY_CLOUD_SAVES_OVERRIDE = "cloud_saves_override"
    private const val KEY_GOOGLE_CLOUD_SAVES_OVERRIDE = "google_cloud_saves_override"

    enum class OverrideState {
        FOLLOW_GLOBAL,
        ENABLED,
        DISABLED,
    }

    @JvmStatic
    fun isGlobalCloudSavesEnabled(context: Context): Boolean {
        PrefManager.init(context)
        return PrefManager.globalCloudSavesEnabled
    }

    @JvmStatic
    fun setGlobalCloudSavesEnabled(context: Context, enabled: Boolean) {
        PrefManager.init(context)
        PrefManager.globalCloudSavesEnabled = enabled
    }

    @JvmStatic
    fun isGlobalGoogleCloudSavesEnabled(context: Context): Boolean =
        context.getSharedPreferences(GOOGLE_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GLOBAL_GOOGLE_CLOUD_SAVES, true)

    @JvmStatic
    fun setGlobalGoogleCloudSavesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(GOOGLE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GLOBAL_GOOGLE_CLOUD_SAVES, enabled)
            .apply()
    }

    @JvmStatic
    fun getCloudSavesOverride(shortcut: Shortcut?): OverrideState =
        parseOverride(shortcut?.getExtra(KEY_CLOUD_SAVES_OVERRIDE).orEmpty())

    @JvmStatic
    fun getGoogleCloudSavesOverride(shortcut: Shortcut?): OverrideState =
        parseOverride(shortcut?.getExtra(KEY_GOOGLE_CLOUD_SAVES_OVERRIDE).orEmpty())

    @JvmStatic
    fun areCloudSavesEnabled(context: Context, shortcut: Shortcut?): Boolean =
        when (getCloudSavesOverride(shortcut)) {
            OverrideState.ENABLED -> true
            OverrideState.DISABLED -> false
            OverrideState.FOLLOW_GLOBAL -> isGlobalCloudSavesEnabled(context)
        }

    @JvmStatic
    fun areGoogleCloudSavesEnabled(context: Context, shortcut: Shortcut?): Boolean {
        if (!areCloudSavesEnabled(context, shortcut)) return false
        return when (getGoogleCloudSavesOverride(shortcut)) {
            OverrideState.ENABLED -> true
            OverrideState.DISABLED -> false
            OverrideState.FOLLOW_GLOBAL -> isGlobalGoogleCloudSavesEnabled(context)
        }
    }

    @JvmStatic
    fun setPerGameCloudSavesEnabled(context: Context, shortcut: Shortcut, enabled: Boolean) {
        val globalEnabled = isGlobalCloudSavesEnabled(context)
        shortcut.putExtra(
            KEY_CLOUD_SAVES_OVERRIDE,
            when {
                enabled == globalEnabled -> null
                enabled -> "enabled"
                else -> "disabled"
            },
        )
        if (!enabled) {
            shortcut.putExtra(KEY_GOOGLE_CLOUD_SAVES_OVERRIDE, "disabled")
        }
        shortcut.saveData()
    }

    @JvmStatic
    fun setPerGameGoogleCloudSavesEnabled(context: Context, shortcut: Shortcut, enabled: Boolean) {
        val globalEnabled = isGlobalGoogleCloudSavesEnabled(context)
        shortcut.putExtra(
            KEY_GOOGLE_CLOUD_SAVES_OVERRIDE,
            when {
                enabled == globalEnabled -> null
                enabled -> "enabled"
                else -> "disabled"
            },
        )
        shortcut.saveData()
    }

    @JvmStatic
    fun hasPerGameCloudSavesOverride(shortcut: Shortcut?): Boolean =
        getCloudSavesOverride(shortcut) != OverrideState.FOLLOW_GLOBAL

    @JvmStatic
    fun hasPerGameGoogleCloudSavesOverride(shortcut: Shortcut?): Boolean =
        getGoogleCloudSavesOverride(shortcut) != OverrideState.FOLLOW_GLOBAL

    private fun parseOverride(raw: String): OverrideState =
        when (raw) {
            "enabled" -> OverrideState.ENABLED
            "disabled" -> OverrideState.DISABLED
            else -> OverrideState.FOLLOW_GLOBAL
        }
}
