package com.winlator.cmod.feature.stores.ea.service

import android.content.Context
import com.winlator.cmod.feature.stores.steam.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class EaOwnedGame(
    val offerId: String,
    val name: String,
    val slug: String,
    val gameType: String,
    val keyArt: String,
)

object EaLibraryClient {
    private val httpClient = Net.http
    private val jsonMedia = "application/json".toMediaType()

    private const val OWNED_QUERY =
        "query WinNativeOwned(\$next: String) { me { ownedGameProducts(" +
            "locale: \"DEFAULT\", entitlementEnabled: true, storefronts: [EA], " +
            "type: [DIGITAL_FULL_GAME, PACKAGED_FULL_GAME], platforms: [PC], " +
            "paging: { limit: 100, next: \$next }) { next items { " +
            "originOfferId product { name slug baseItem { gameType } } } } } }"

    suspend fun fetchOwnedGames(context: Context): Result<List<EaOwnedGame>> =
        withContext(Dispatchers.IO) {
            val token =
                EaAuthManager.getValidAccessToken(context)
                    ?: return@withContext Result.failure(IllegalStateException("No EA access token"))
            val games = mutableListOf<EaOwnedGame>()
            var next: String? = null
            try {
                do {
                    val variables = JSONObject().apply { if (next != null) put("next", next) }
                    val payload =
                        JSONObject()
                            .put("query", OWNED_QUERY)
                            .put("variables", variables)
                            .toString()
                    val request =
                        Request
                            .Builder()
                            .url(EaConstants.EA_GRAPHQL_URL)
                            .header("Authorization", "Bearer $token")
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .header("User-Agent", EaConstants.EA_USER_AGENT)
                            .post(payload.toRequestBody(jsonMedia))
                            .build()
                    val body =
                        httpClient.newCall(request).execute().use { response ->
                            val text = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                android.util.Log.w("EAStore", "Library HTTP " + response.code + ": " + text.take(300))
                                return@withContext Result.failure(
                                    IllegalStateException("EA library HTTP ${response.code}"),
                                )
                            }
                            text
                        }
                    val json = JSONObject(body)
                    json.optJSONArray("errors")?.let { errors ->
                        android.util.Log.w("EAStore", "Library GraphQL errors: " + errors.toString().take(400))
                    }
                    val owned =
                        json
                            .optJSONObject("data")
                            ?.optJSONObject("me")
                            ?.optJSONObject("ownedGameProducts")
                    next = owned?.optString("next").takeIf { !it.isNullOrEmpty() && it != "null" }
                    val items: JSONArray = owned?.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val product = item.optJSONObject("product")
                        games +=
                            EaOwnedGame(
                                offerId = item.optString("originOfferId"),
                                name = product?.optString("name").orEmpty(),
                                slug = product?.optString("slug").orEmpty(),
                                gameType = product?.optJSONObject("baseItem")?.optString("gameType").orEmpty(),
                                keyArt = "",
                            )
                    }
                } while (next != null && games.size < 500)
                Result.success(games)
            } catch (e: Exception) {
                android.util.Log.e("EAStore", "EA library fetch failed", e)
                Result.failure(e)
            }
        }
}
