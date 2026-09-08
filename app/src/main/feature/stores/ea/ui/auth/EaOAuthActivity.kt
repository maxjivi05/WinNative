package com.winlator.cmod.feature.stores.ea.ui.auth

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.darkColorScheme
import com.winlator.cmod.feature.stores.ea.service.EaAuthClient
import com.winlator.cmod.feature.stores.ea.service.EaAuthManager
import com.winlator.cmod.feature.stores.ea.service.EaConstants
import com.winlator.cmod.feature.stores.epic.ui.component.dialog.AuthWebViewDialog
import com.winlator.cmod.shared.android.FixedFontScaleComponentActivity
import com.winlator.cmod.shared.theme.WinNativeTheme
import timber.log.Timber

class EaOAuthActivity : FixedFontScaleComponentActivity() {
    private var completed = false
    private var mintRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WinNativeTheme(colorScheme = darkColorScheme()) {
                AuthWebViewDialog(
                    isVisible = true,
                    acceptThirdPartyCookies = true,
                    url = EaConstants.interactiveLoginUrl(),
                    onDismissRequest = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onUrlChange = { current -> Timber.tag("EA").i("nav %s", current.substringBefore('?')) },
                    onPageFinished = { url, webView ->
                        if (completed) return@AuthWebViewDialog
                        if (!mintRequested && EaConstants.isInteractiveRedirect(url)) {
                            mintRequested = true
                            Timber.tag("EA").i("EA interactive login complete, minting token")
                            webView.loadUrl(EaConstants.silentTokenUrl())
                            return@AuthWebViewDialog
                        }
                        if (!mintRequested || !EaConstants.isTokenResponseUrl(url)) {
                            return@AuthWebViewDialog
                        }
                        webView.evaluateJavascript(
                            "(function(){ try { return document.body && document.body.innerText || ''; } catch(e){ return ''; } })();",
                        ) { result ->
                            val body = unquoteJsonString(result)
                            if (body.isNullOrBlank()) return@evaluateJavascript
                            val token = EaAuthClient.parseTokenJson(body)
                            if (token == null) {
                                Timber.tag("EA").w("EA token mint returned no access_token")
                                return@evaluateJavascript
                            }
                            if (completed) return@evaluateJavascript
                            completed = true
                            EaAuthManager.persistToken(applicationContext, token)
                            Timber.tag("EA").i("EA sign-in completed")
                            setResult(Activity.RESULT_OK)
                            finish()
                        }
                    },
                )
            }
        }
    }

    private fun unquoteJsonString(jsResult: String?): String? {
        if (jsResult.isNullOrBlank()) return null
        val raw = jsResult.trim()
        if (raw == "null") return null
        if (!raw.startsWith("\"") || !raw.endsWith("\"")) return raw
        return raw
            .drop(1)
            .dropLast(1)
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
    }
}
