package com.winlator.cmod.feature.stores.battlenet

import android.os.Bundle
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BattleNetLibraryDiagnosticsTest {
    @Test fun inspectLibraryShapeWhenRequested() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("battlenetLibraryDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var cookie: String? = null
        instrumentation.runOnMainSync { cookie = CookieManager.getInstance().getCookie(BattleNetAccount.ORIGIN) }
        val diagnostic = JSONObject().put("cookiePresent", !cookie.isNullOrBlank())
        val client = OkHttpClient.Builder().followRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("${BattleNetAccount.ORIGIN}/api/games-and-subs")
            .header("Cookie", cookie.orEmpty()).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            diagnostic.put("httpStatus", response.code)
            val bytes = response.body?.byteStream()?.use { BattleNetRuntime.readLimited(it, 2 * 1024 * 1024) }
            val json = runCatching { JSONObject(bytes?.toString(Charsets.UTF_8).orEmpty()) }.getOrNull()
            diagnostic.put("rootFields", JSONArray(json?.keys()?.asSequence()?.toList().orEmpty()))
            val accounts = json?.optJSONArray("gameAccounts")
            diagnostic.put("accountCount", accounts?.length() ?: -1)
            val titles = (0 until (accounts?.length() ?: 0)).mapNotNull {
                accounts?.optJSONObject(it)?.optString("titleId")?.takeIf { id -> id.matches(Regex("[A-Za-z0-9_]{1,64}")) }
            }.distinct()
            diagnostic.put("titleIds", JSONArray(titles))
            diagnostic.put("mappedProducts", JSONArray(BattleNetCatalog.ownedGames(titles.toSet()).map { it.product }))
            diagnostic.put("entryFields", JSONArray(accounts?.optJSONObject(0)?.keys()?.asSequence()?.toList().orEmpty()))
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("battlenetLibraryShape", diagnostic.toString()) })
    }
}
