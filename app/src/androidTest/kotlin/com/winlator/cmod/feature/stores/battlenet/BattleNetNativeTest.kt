package com.winlator.cmod.feature.stores.battlenet

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BattleNetNativeTest {
    @Test fun nativeJobLifecycleDoesNotStartContainer() {
        val id = BattleNetNative.createJob()
        org.junit.Assert.assertTrue(id > 0)
        try {
            assertEquals(0L, BattleNetNative.createJob())
            org.junit.Assert.assertTrue(BattleNetNative.commandJob(id, "pause"))
            org.junit.Assert.assertTrue(JSONObject(BattleNetNative.jobStatus(id)).getBoolean("paused"))
            org.junit.Assert.assertTrue(BattleNetNative.commandJob(id, "cancel"))
            val result = JSONObject(BattleNetNative.runJob(id, "{}"))
            org.junit.Assert.assertTrue(result.getBoolean("done"))
            org.junit.Assert.assertFalse(BattleNetNative.commandJob(id, "resume"))
        } finally { BattleNetNative.releaseJob(id) }
    }
    @Test fun nativeInstallPreviewWhenRequested() {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("battlenetLiveMetadata") == "true")
        val result = JSONObject(BattleNetNative.installPreview("wow_classic", "us"))
        org.junit.Assert.assertTrue(result.getLong("downloadBytes") > 20_000_000_000L)
        org.junit.Assert.assertTrue(result.getLong("requiredBytes") > result.getLong("downloadBytes"))
    }
    @Test fun jniRejectsInvalidProductsWithoutMakingANetworkRequest() {
        val result = JSONObject(BattleNetNative.latestBuild("../wow", "us"))
        assertEquals("invalid_product", result.getString("error"))
    }
    @Test fun liveMetadataWhenRequested() {
        org.junit.Assume.assumeTrue(
            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("battlenetLiveMetadata") == "true",
        )
        val result = JSONObject(BattleNetNative.latestBuild("wow", "us"))
        val build = result.getJSONObject("build")
        org.junit.Assert.assertTrue(build.getString("buildKey").matches(Regex("[a-f0-9]{32}")))
        org.junit.Assert.assertTrue(build.getLong("buildId") > 0L)
    }

    @Test fun jniRejectsInvalidPlanInput() {
        assertEquals("invalid_tags", JSONObject(BattleNetNative.downloadPlan("s1", "us", "null")).getString("error"))
        assertEquals("invalid_product", JSONObject(BattleNetNative.downloadPlan("../s1", "us", "[]")).getString("error"))
    }
    @Test fun liveDownloadPlanWhenRequested() {
        org.junit.Assume.assumeTrue(
            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("battlenetLiveMetadata") == "true",
        )
        val result = JSONObject(BattleNetNative.downloadPlan("s1", "us", "[\"Windows\",\"x86_64\",\"enUS\",\"Release\",\"noigr\"]"))
        org.junit.Assert.assertTrue(result.getLong("encodedContentBytes") > 1_000_000_000L)
        org.junit.Assert.assertTrue(result.getLong("contentObjectCount") > 100L)
        assertEquals(5, result.getJSONArray("selectedTags").length())
    }

    @Test fun persistedLibrarySessionWhenRequested() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(
            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("battlenetLibraryDiagnostics") == "true",
        )
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        BattleNetAccount.refreshSession(context)
        val games = BattleNetAccount.games(context)
        org.junit.Assert.assertTrue(games.isNotEmpty())
        val cookie = BattleNetWebSession.read(context)
        org.junit.Assert.assertFalse(cookie.isNullOrBlank())
        val request = okhttp3.Request.Builder().url("${BattleNetAccount.ORIGIN}/api/games-and-subs")
            .header("Cookie", cookie!!).header("Accept", "application/json").build()
        okhttp3.OkHttpClient.Builder().followRedirects(false).callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(request).execute().use { response -> assertEquals(200, response.code) }
    }

}
