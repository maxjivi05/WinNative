package com.winlator.cmod.app.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.feature.stores.battlenet.BattleNetCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BattleNetGameDetailTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun uninstalledGameShowsArtworkAndDownloadsFromTheSharedDetailScreen() {
        var downloads = 0
        var plays = 0
        composeRule.setContent {
            BattleNetGameDetailDialog(
                game = BattleNetCatalog.byProduct("fenris")!!,
                isInstalled = false,
                installPath = "",
                downloadSize = 0L,
                availableBytes = 100_000_000_000L,
                busy = false,
                onDismiss = {},
                onDownload = { downloads++ },
                onPlay = { plays++ },
            )
        }
        composeRule.onNodeWithContentDescription("Diablo IV artwork").assertIsDisplayed()
        composeRule.onNodeWithText("BATTLE.NET").assertIsDisplayed()
        composeRule.onNodeWithText("Play").assertDoesNotExist()
        composeRule.onNodeWithText("Download").performClick()
        composeRule.runOnIdle { assertEquals(1, downloads); assertEquals(0, plays) }
    }

    @Test fun installedGamePlaysAndBlocksRepeatedLaunchesWhileBusy() {
        var plays = 0
        val busy = mutableStateOf(false)
        composeRule.setContent {
            BattleNetGameDetailDialog(
                game = BattleNetCatalog.byProduct("fenris")!!,
                isInstalled = true,
                installPath = "C:\\Program Files (x86)\\Diablo IV",
                downloadSize = 0L,
                availableBytes = 0L,
                busy = busy.value,
                onDismiss = {},
                onDownload = {},
                onPlay = { plays++; busy.value = true },
            )
        }
        composeRule.onNodeWithText("Download").assertDoesNotExist()
        val playPosition = composeRule.onNodeWithText("Play").fetchSemanticsNode().boundsInRoot.center
        composeRule.onNodeWithText("Play").performClick()
        composeRule.onNodeWithText("Play").assertDoesNotExist()
        composeRule.onNode(isDialog()).performTouchInput { click(playPosition) }
        composeRule.runOnIdle { assertEquals(1, plays) }
    }

    @Test fun existingStoresDoNotGainAPlayActionByDefault() {
        composeRule.setContent {
            StoreGameDetailScreen(
                title = "Existing store game", subtitle = "", sourceLabel = "GOG", heroImageUrl = null,
                isLoading = false, isInstalled = true, installPathDisplay = "C:\\Games",
                downloadSize = 0L, installSize = 0L, availableBytes = 0L,
                isInstallEnabled = true, customPathLabel = "", showUninstall = false, onBack = {},
            )
        }
        composeRule.onNodeWithText("Play").assertDoesNotExist()
        composeRule.onNodeWithText("Download").assertDoesNotExist()
    }
}
