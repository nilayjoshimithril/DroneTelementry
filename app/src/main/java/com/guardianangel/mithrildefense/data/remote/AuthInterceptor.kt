package com.guardianangel.mithrildefense.data.remote

import com.guardianangel.mithrildefense.config.AppConfig
import com.guardianangel.mithrildefense.data.local.SessionStore
import com.guardianangel.mithrildefense.utils.AppLog
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the raw access token for **API** hosts only.
 * Never send JWT to S3 / CDN — that causes 400/403 and a black mosaic map.
 */
class AuthInterceptor(
    private val sessionStore: SessionStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!shouldAttachAuth(original.url)) {
            return chain.proceed(original)
        }
        if (original.header("Authorization") != null) {
            return chain.proceed(original)
        }
        val token = runBlocking { sessionStore.getAccessToken() }
        if (token.isNullOrBlank()) {
            AppLog.d(TAG, "${original.method} ${original.url.encodedPath} — no auth token")
            return chain.proceed(original)
        }
        AppLog.d(
            TAG,
            "${original.method} ${original.url.encodedPath} — attaching token=${AppLog.redact(token)}",
        )
        return chain.proceed(
            original.newBuilder().header("Authorization", token).build()
        )
    }

    companion object {
        private const val TAG = "AuthInterceptor"

        fun shouldAttachAuth(url: HttpUrl): Boolean {
            val host = url.host.lowercase()
            // Public mosaic tiles / S3 must stay unauthenticated.
            if (host.contains("amazonaws.com") || host.contains("s3.")) return false
            val apiHost = runCatching {
                AppConfig.apiBaseUrl.toHttpUrlOrNull()?.host
            }.getOrNull() ?: "campusguardianangel.com"
            return host.contains(apiHost) || host.contains("campusguardianangel.com")
        }
    }
}
