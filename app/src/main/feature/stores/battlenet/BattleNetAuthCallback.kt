package com.winlator.cmod.feature.stores.battlenet

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

class BattleNetCredential(val account: String, val token: String, val region: String) {
    override fun toString() = "BattleNetCredential(redacted)"
}

object BattleNetAuthCallback {
    fun parse(url: String): BattleNetCredential? = try {
        val uri = URI(url)
        if (uri.scheme != "http" || uri.host != "localhost" || uri.port != 0 || uri.rawUserInfo != null ||
            uri.rawFragment != null || uri.path !in listOf("", "/") || url.length > 8192
        ) null else {
            val values = linkedMapOf<String, String>()
            for (part in uri.rawQuery.orEmpty().split('&')) {
                val name = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                require(values.put(name, value) == null)
            }
            val token = values["ST"].orEmpty()
            val rawAccount = values["accountName"].orEmpty()
            val account = rawAccount.trim().lowercase(Locale.ROOT)
            val region = values["accountRegion"].orEmpty().ifBlank { token.substringBefore('-') }.uppercase(Locale.ROOT)
            if (!token.matches(Regex("[A-Za-z0-9._~-]{16,2048}")) || account.isEmpty() ||
                account.toByteArray(Charsets.UTF_8).size > 320 || rawAccount.any { it.isISOControl() || it == ',' } ||
                region !in setOf("US", "EU", "KR", "CN")
            ) null else BattleNetCredential(account, token, region)
        }
    } catch (_: Exception) { null }
}
