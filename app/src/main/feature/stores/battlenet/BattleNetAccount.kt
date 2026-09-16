package com.winlator.cmod.feature.stores.battlenet

import android.webkit.CookieManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object BattleNetAccount {
    const val ORIGIN = "https://account.battle.net"
    private val mutation = Mutex()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var nativeSession = false
    private val signedIn = MutableStateFlow(false)
    val authenticated = signedIn.asStateFlow()
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun completeSignIn(context: android.content.Context, credential: BattleNetCredential) = mutation.withLock {
        withContext(NonCancellable) {
            generation.incrementAndGet()
            BattleNetRuntime.saveCredential(context, credential)
            refreshSessionUnlocked(context)
            withContext(Dispatchers.IO) { CookieManager.getInstance().flush() }
        }
    }

    suspend fun refreshSession(context: android.content.Context) = mutation.withLock { refreshSessionUnlocked(context) }

    private suspend fun refreshSessionUnlocked(context: android.content.Context) {
        val epoch = generation.get()
        val present = withContext(Dispatchers.IO) {
            val auth = java.io.File(BattleNetRuntime.shared(context).root, "auth")
            java.io.File(auth, "account").isFile && (java.io.File(auth, "pending.token").isFile || java.io.File(auth, "session.bin").isFile)
        }
        withContext(Dispatchers.Main) { if (epoch == generation.get()) { nativeSession = present; signedIn.value = present } }
    }

    class SignInRequired : IOException("Sign in to Battle.net to refresh your games.")

    suspend fun games(): List<BattleNetGame> {
        val epoch = generation.get()
        val cookie = withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie(ORIGIN) }
        if (cookie.isNullOrBlank()) {
            withContext(Dispatchers.Main) { if (epoch == generation.get()) signedIn.value = nativeSession }
            throw SignInRequired()
        }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$ORIGIN/api/games-and-subs")
                .header("Cookie", cookie).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308, 401, 403)) {
                    withContext(Dispatchers.Main) { if (epoch == generation.get()) signedIn.value = nativeSession }
                    throw SignInRequired()
                }
                if (!response.isSuccessful) throw IOException("Battle.net library request failed (${response.code}).")
                val body = response.body ?: throw IOException("Battle.net returned an empty library response.")
                require(body.contentLength() <= 2 * 1024 * 1024) { "Battle.net library response is too large" }
                val bytes = body.byteStream().use { BattleNetRuntime.readLimited(it, 2 * 1024 * 1024) }
                if (bytes.size > 2 * 1024 * 1024) throw IOException("Battle.net library response is too large.")
                val root = try { JSONObject(bytes.toString(Charsets.UTF_8)) } catch (_: Exception) { throw SignInRequired() }
                val accounts = root.optJSONArray("gameAccounts") ?: throw SignInRequired()
                val ids = (0 until accounts.length()).mapNotNull { accounts.optJSONObject(it)?.optString("titleId") }.toSet()
                withContext(Dispatchers.Main) {
                    if (epoch != generation.get()) throw SignInRequired()
                    signedIn.value = nativeSession
                }
                BattleNetCatalog.ownedGames(ids)
            }
        }
    }

    suspend fun signOut(context: android.content.Context) = mutation.withLock {
        withContext(NonCancellable) {
            generation.incrementAndGet()
            BattleNetRuntime.clearCredential(context)
            withContext(Dispatchers.Main) {
                nativeSession = false
                signedIn.value = false
                val manager = CookieManager.getInstance()
                val hosts = listOf(
                    "account.battle.net", "us.account.battle.net", "eu.account.battle.net", "kr.account.battle.net",
                    "cn.account.battle.net", "oauth.battle.net", "us.battle.net", "eu.battle.net", "kr.battle.net", "battle.net",
                )
                for (host in hosts) {
                    val origin = "https://$host"
                    val names = manager.getCookie(origin).orEmpty().split(';')
                        .map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
                    for (name in names) {
                        for (domain in listOf("", "; Domain=$host", "; Domain=.account.battle.net", "; Domain=.battle.net")) {
                            suspendCancellableCoroutine { continuation ->
                                manager.setCookie(origin, "$name=; Path=/; Max-Age=0; Secure$domain") {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }
                            }
                        }
                    }
                }
            }
            withContext(Dispatchers.IO) { CookieManager.getInstance().flush() }
        }
    }
}
