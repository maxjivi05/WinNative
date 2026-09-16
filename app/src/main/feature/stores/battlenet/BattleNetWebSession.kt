package com.winlator.cmod.feature.stores.battlenet

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

internal object BattleNetWebSession {
    private fun preferences(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext, "battlenet_web_session",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun read(context: Context): String? = preferences(context).getString("cookie", null)
        ?.takeIf { it.length <= 65536 && '\r' !in it && '\n' !in it }

    fun save(context: Context, cookie: String) {
        if (cookie.length > 65536 || '\r' in cookie || '\n' in cookie) throw IOException("Invalid Battle.net session.")
        if (!preferences(context).edit().putString("cookie", cookie).commit()) throw IOException("Could not save the Battle.net session.")
    }

    fun clear(context: Context) {
        if (!preferences(context).edit().clear().commit()) throw IOException("Could not remove the Battle.net session.")
    }
}

internal object BattleNetCookieRotation {
    private val endpoint = "${BattleNetAccount.ORIGIN}/api/games-and-subs".toHttpUrl()

    fun merge(current: String, headers: List<String>, now: Long = System.currentTimeMillis()): String {
        val cookies = linkedMapOf<String, String>()
        for (part in current.split(';')) {
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2) cookies[pair[0]] = pair[1]
        }
        for (header in headers) {
            val cookie = Cookie.parse(endpoint, header) ?: continue
            if (!cookie.matches(endpoint)) continue
            if (cookie.expiresAt <= now) cookies.remove(cookie.name) else cookies[cookie.name] = cookie.value
        }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
