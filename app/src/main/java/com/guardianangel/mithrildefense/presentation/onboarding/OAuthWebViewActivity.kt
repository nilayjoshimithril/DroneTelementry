package com.guardianangel.mithrildefense.presentation.onboarding

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.guardianangel.mithrildefense.config.AppConfig
import com.guardianangel.mithrildefense.data.remote.AppAuthCallback
import com.guardianangel.mithrildefense.utils.AppLog

class OAuthWebViewActivity : AppCompatActivity() {

    private var hasResult = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetUrl = intent.getStringExtra(EXTRA_URL) ?: return finish()
        AppLog.i(TAG, "WebView loadUrl=$targetUrl")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleUserCancelled()
        })

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val urlStr = request?.url?.toString().orEmpty()
                    AppLog.d(TAG, "nav → $urlStr")
                    val redirect = AppConfig.oauthRedirectUri
                    if (urlStr.startsWith(redirect) ||
                        urlStr.startsWith(redirect.replace("localhost", "127.0.0.1"))
                    ) {
                        AppLog.i(TAG, "Redirect captured: $urlStr")
                        hasResult = true
                        val resultIntent = Intent().apply { data = request?.url }
                        AppAuthCallback.handleResult(resultIntent)
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                        return true
                    }
                    return false
                }
            }
            loadUrl(targetUrl)
        }
        setContentView(webView)
    }

    private fun handleUserCancelled() {
        AppLog.w(TAG, "User cancelled OAuth")
        if (!hasResult) {
            AppAuthCallback.handleResult(
                Intent().apply {
                    data = "${AppConfig.oauthRedirectUri}?error=user_cancelled".toUri()
                }
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val TAG = "OAuthWebView"
    }
}
