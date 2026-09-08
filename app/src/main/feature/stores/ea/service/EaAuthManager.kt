package com.winlator.cmod.feature.stores.ea.service

import android.content.Context
import android.webkit.CookieManager
import com.winlator.cmod.feature.stores.common.StoreAuthStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import timber.log.Timber

data class EaCredentials(
    val accessToken: String,
    val accountId: String,
    val personaId: String,
    val displayName: String,
    val expiresAt: Long,
)

object EaAuthManager {
    private const val ACCESS_BUFFER_MS = 5L * 60L * 1000L

    private val _isLoggedInFlow = MutableStateFlow(false)
    val isLoggedInFlow: StateFlow<Boolean> = _isLoggedInFlow.asStateFlow()

    fun updateLoginStatus(context: Context) {
        _isLoggedInFlow.value = getAuthStatus(context).isLoggedInForUi
    }

    fun sessionCookies(): String =
        try {
            CookieManager.getInstance().getCookie(EaConstants.EA_ACCOUNTS_ORIGIN).orEmpty()
        } catch (e: Exception) {
            Timber.tag("EA").w(e, "Failed reading EA cookies")
            ""
        }

    fun hasStoredCredentials(context: Context): Boolean = EaSecureStore.exists(context)

    fun getAuthStatus(context: Context): StoreAuthStatus {
        if (!hasStoredCredentials(context)) return StoreAuthStatus.LOGGED_OUT
        val credentials = loadCredentials(context) ?: return StoreAuthStatus.LOGGED_OUT
        val now = System.currentTimeMillis()
        return when {
            credentials.expiresAt > now + ACCESS_BUFFER_MS -> StoreAuthStatus.ACTIVE
            sessionCookies().isNotBlank() -> StoreAuthStatus.REFRESHABLE
            else -> StoreAuthStatus.EXPIRED
        }
    }

    fun isLoggedIn(context: Context): Boolean = getAuthStatus(context).isLoggedInForUi

    fun displayName(context: Context): String = loadCredentials(context)?.displayName.orEmpty()

    fun persistToken(context: Context, token: EaTokenResult) {
        saveCredentials(
            context,
            EaCredentials(
                accessToken = token.accessToken,
                accountId = token.accountId,
                personaId = token.personaId,
                displayName = token.displayName,
                expiresAt = token.expiresAt,
            ),
        )
        EaTokenRefreshWorker.schedule(context, token.expiresIn)
    }

    suspend fun getValidAccessToken(context: Context): String? {
        val credentials = loadCredentials(context)
        if (credentials != null && credentials.expiresAt > System.currentTimeMillis() + ACCESS_BUFFER_MS) {
            return credentials.accessToken
        }
        val refreshed = EaAuthClient.silentToken(sessionCookies()).getOrNull() ?: return null
        persistToken(context, refreshed)
        return refreshed.accessToken
    }

    fun clearStoredCredentials(context: Context) {
        EaSecureStore.clear(context)
        EaTokenRefreshWorker.cancel(context)
        runCatching {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
        }.onFailure { Timber.tag("EA").w(it, "Failed clearing EA cookies") }
        updateLoginStatus(context)
    }

    private fun saveCredentials(context: Context, credentials: EaCredentials) {
        val json =
            JSONObject()
                .put("access_token", credentials.accessToken)
                .put("account_id", credentials.accountId)
                .put("persona_id", credentials.personaId)
                .put("display_name", credentials.displayName)
                .put("expires_at", credentials.expiresAt)
        if (!EaSecureStore.write(context, json.toString())) {
            Timber.tag("EA").w("EA credentials were not persisted")
        }
        updateLoginStatus(context)
    }

    private fun loadCredentials(context: Context): EaCredentials? =
        try {
            EaSecureStore.read(context)?.let { raw ->
                val json = JSONObject(raw)
                EaCredentials(
                    accessToken = json.getString("access_token"),
                    accountId = json.optString("account_id"),
                    personaId = json.optString("persona_id"),
                    displayName = json.optString("display_name"),
                    expiresAt = json.optLong("expires_at", 0L),
                )
            }
        } catch (e: Exception) {
            Timber.tag("EA").w(e, "Failed loading EA credentials")
            EaSecureStore.clear(context)
            null
        }
}
