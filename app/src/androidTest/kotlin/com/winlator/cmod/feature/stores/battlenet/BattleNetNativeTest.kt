package com.winlator.cmod.feature.stores.battlenet

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BattleNetNativeTest {
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

}
