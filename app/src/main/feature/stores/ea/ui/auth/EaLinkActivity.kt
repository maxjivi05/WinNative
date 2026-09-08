package com.winlator.cmod.feature.stores.ea.ui.auth

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.darkColorScheme
import com.winlator.cmod.feature.stores.ea.service.EaConstants
import com.winlator.cmod.feature.stores.epic.ui.component.dialog.AuthWebViewDialog
import com.winlator.cmod.shared.android.FixedFontScaleComponentActivity
import com.winlator.cmod.shared.theme.WinNativeTheme

class EaLinkActivity : FixedFontScaleComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WinNativeTheme(colorScheme = darkColorScheme()) {
                AuthWebViewDialog(
                    isVisible = true,
                    acceptThirdPartyCookies = true,
                    url = EaConstants.EA_CONNECTED_ACCOUNTS_URL,
                    onDismissRequest = {
                        setResult(Activity.RESULT_OK)
                        finish()
                    },
                )
            }
        }
    }
}
