package com.winlator.cmod.feature.stores.battlenet

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.lifecycleScope
import com.winlator.cmod.feature.stores.epic.ui.component.dialog.AuthWebViewDialog
import com.winlator.cmod.shared.android.FixedFontScaleComponentActivity
import com.winlator.cmod.shared.theme.WinNativeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class BattleNetLoginActivity : FixedFontScaleComponentActivity() {
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { BattleNetSession.requireIdle() } catch (failure: Exception) {
            android.widget.Toast.makeText(this, failure.message, android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }
        CookieManager.getInstance().setAcceptCookie(true)
        val client = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (request.isForMainFrame && uri.scheme == "http" && uri.host == "localhost") {
                    val source = view?.url?.let(Uri::parse)
                    val trusted = source?.scheme == "https" && (source.host == "battle.net" || source.host?.endsWith(".battle.net") == true)
                    val credential = if (trusted) BattleNetAuthCallback.parse(uri.toString()) else null
                    if (credential != null && !saving) {
                        saving = true
                        lifecycleScope.launch {
                            try {
                                BattleNetAccount.completeSignIn(applicationContext, credential)
                                setResult(Activity.RESULT_OK)
                                finish()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                saving = false
                                android.widget.Toast.makeText(this@BattleNetLoginActivity, failure.message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    if (credential == null && !saving) {
                        android.widget.Toast.makeText(this@BattleNetLoginActivity, "Battle.net did not return a usable session. Please try signing in again.", android.widget.Toast.LENGTH_LONG).show()
                        view?.loadUrl("https://account.battle.net/login/en/login.app?app=app")
                    }
                    return true
                }
                return uri.scheme != "https"
            }

        }
        setContent {
            WinNativeTheme(colorScheme = darkColorScheme()) {
                AuthWebViewDialog(
                    isVisible = true,
                    url = "https://account.battle.net/login/en/login.app?app=app",
                    onDismissRequest = { setResult(Activity.RESULT_CANCELED); finish() },
                    customWebViewClient = client,
                )
            }
        }
    }
}
