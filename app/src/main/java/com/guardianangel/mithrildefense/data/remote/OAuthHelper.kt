package com.guardianangel.mithrildefense.data.remote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.guardianangel.mithrildefense.config.AppConfig
import com.guardianangel.mithrildefense.presentation.onboarding.OAuthWebViewActivity
import com.guardianangel.mithrildefense.utils.AppLog
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

class OAuthHelper {

    suspend fun authorize(activity: Activity, inviteCode: String): String {
        val authUrl = AppConfig.microsoftAdAuthorizeUrl
        AppLog.i(TAG, "authorize start inviteCode=$inviteCode baseUrl=$authUrl")
        val uri = authUrl.toUri()
        val clientId = uri.getQueryParameter("client_id").orEmpty()
        val scope = uri.getQueryParameter("scope").orEmpty()
        val responseType = uri.getQueryParameter("response_type") ?: ResponseTypeValues.CODE

        val baseWithoutQuery = authUrl.substringBefore("?")
        val authorizeEndpoint = Uri.parse(baseWithoutQuery).buildUpon()
            .appendQueryParameter("identity_provider", inviteCode)
            .build()
        val origin = "${uri.scheme}://${uri.host}"
        val tokenEndpoint = "$origin/oauth2/token".toUri()
        AppLog.d(TAG, "authorizeEndpoint=$authorizeEndpoint")
        AppLog.d(TAG, "tokenEndpoint=$tokenEndpoint")
        AppLog.d(TAG, "clientId=$clientId responseType=$responseType scope=$scope")
        AppLog.d(TAG, "redirectUri=${AppConfig.oauthRedirectUri}")

        val serviceConfig = AuthorizationServiceConfiguration(authorizeEndpoint, tokenEndpoint)
        val authRequest = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            responseType,
            AppConfig.oauthRedirectUri.toUri(),
        )
            .setPrompt("login")
            .apply {
                val scopes = scope.split(" ", "+").filter { it.isNotBlank() }
                if (scopes.isNotEmpty()) setScopes(*scopes.toTypedArray())
                setState(null)
                setCodeVerifier(null, null, null)
            }
            .build()

        val finalAuthUrl = authRequest.toUri().toString()
        AppLog.i(TAG, "Final OAuth authorize URL: $finalAuthUrl")

        return suspendCancellableCoroutine { continuation ->
            AppAuthCallback.register { resultIntent ->
                val resultUri = resultIntent.data
                AppLog.i(TAG, "OAuth callback uri=$resultUri")
                if (resultUri == null) {
                    continuation.resumeWithException(IllegalStateException("No OAuth result"))
                    return@register
                }
                val error = resultUri.getQueryParameter("error")
                val code = resultUri.getQueryParameter("code")
                when {
                    error != null -> {
                        AppLog.e(TAG, "OAuth error=$error")
                        continuation.resumeWithException(IllegalStateException(error))
                    }
                    code != null -> {
                        AppLog.i(TAG, "OAuth code received code=${AppLog.redact(code)}")
                        continuation.resume(code)
                    }
                    else -> {
                        AppLog.e(TAG, "OAuth callback missing code and error")
                        continuation.resumeWithException(IllegalStateException("Missing auth code"))
                    }
                }
            }

            val webViewIntent = Intent(activity, OAuthWebViewActivity::class.java).apply {
                putExtra(OAuthWebViewActivity.EXTRA_URL, finalAuthUrl)
            }
            AppLog.d(TAG, "Launching OAuthWebViewActivity")
            activity.startActivity(webViewIntent)
        }
    }

    companion object {
        private const val TAG = "OAuth"
    }
}

object AppAuthCallback {
    @Volatile
    private var callback: ((Intent) -> Unit)? = null

    fun register(cb: (Intent) -> Unit) {
        callback = cb
    }

    fun handleResult(intent: Intent) {
        AppLog.d("OAuth", "AppAuthCallback.handleResult data=${intent.data}")
        callback?.invoke(intent)
        callback = null
    }
}
