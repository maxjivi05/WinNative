package com.winlator.cmod.feature.stores.ea.service

import android.util.Base64
import com.winlator.cmod.feature.stores.steam.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

data class EaTokenResult(
    val accessToken: String,
    val accountId: String,
    val personaId: String,
    val displayName: String,
    val expiresAt: Long,
    val expiresIn: Int,
)

object EaAuthClient {
    private val httpClient = Net.http

    fun parseTokenJson(body: String): EaTokenResult? {
        return try {
            val json = JSONObject(body)
            val accessToken = json.optString("access_token")
            if (accessToken.isEmpty()) return null
            val expiresIn = json.optInt("expires_in", 0)
            val identity = decodeIdentity(accessToken)
            EaTokenResult(
                accessToken = accessToken,
                accountId = identity.first,
                personaId = identity.second,
                displayName = identity.third,
                expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
                expiresIn = expiresIn,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeIdentity(accessToken: String): Triple<String, String, String> =
        try {
            val parts = accessToken.split(".")
            if (parts.size < 2) {
                Triple("", "", "")
            } else {
                val payload =
                    String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
                val nexus = JSONObject(payload).optJSONObject("nexus")
                if (nexus == null) {
                    Triple("", "", "")
                } else {
                    val personas = nexus.optJSONArray("psif")
                    val display =
                        if (personas != null && personas.length() > 0) {
                            personas.optJSONObject(0)?.optString("dis").orEmpty()
                        } else {
                            ""
                        }
                    Triple(
                        nexus.opt("pid")?.toString().orEmpty(),
                        nexus.opt("psid")?.toString().orEmpty(),
                        display,
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag("EA").w(e, "Failed decoding token identity")
            Triple("", "", "")
        }

    suspend fun silentToken(cookieHeader: String): Result<EaTokenResult> =
        withContext(Dispatchers.IO) {
            if (cookieHeader.isBlank()) {
                return@withContext Result.failure(IllegalStateException("No EA session cookies"))
            }
            try {
                val request =
                    Request
                        .Builder()
                        .url(EaConstants.silentTokenUrl())
                        .header("Cookie", cookieHeader)
                        .get()
                        .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val parsed = parseTokenJson(body)
                    if (parsed == null) {
                        Result.failure(IllegalStateException("EA silent auth did not return a token"))
                    } else {
                        Result.success(parsed)
                    }
                }
            } catch (e: Exception) {
                Timber.tag("EA").w(e, "Silent token request failed")
                Result.failure(e)
            }
        }
}
