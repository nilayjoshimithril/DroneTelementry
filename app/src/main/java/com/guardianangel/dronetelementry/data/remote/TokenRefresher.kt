package com.guardianangel.dronetelementry.data.remote

import com.guardianangel.dronetelementry.config.AppConfig
import com.guardianangel.dronetelementry.data.local.SessionStore
import com.guardianangel.dronetelementry.data.model.MainGenericResponse
import com.guardianangel.dronetelementry.data.model.RefreshTokenRequestDto
import com.guardianangel.dronetelementry.data.model.RefreshTokenResponseDto
import com.guardianangel.dronetelementry.utils.AppLog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Refreshes the Cognito access token via `auth/refresh-token` (Mithril TokenManager.refreshTokenSafely).
 *
 * - Concurrent 401s share one refresh: callers serialize on [lock], and a caller whose token
 *   was already replaced just reuses the new one.
 * - Only a genuine rejection of the refresh token (HTTP 4xx except 429) logs the user out.
 *   Network errors / 5xx keep the session so the next 401 tries again.
 * - Uses its own bare client: no auth interceptors, so the refresh call can never recurse.
 */
class TokenRefresher(
    private val sessionStore: SessionStore,
    private val sessionEvents: SessionEvents,
    private val json: Json,
) {
    private val lock = Any()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a token to resend with, or null when the request should not be resent
     * (logged out, or the refresh failed transiently).
     */
    fun freshToken(sentToken: String): String? = synchronized(lock) {
        val auth = runBlocking { sessionStore.getAccessTokenResponse() }?.authDetail
        val current = auth?.accessToken
        if (current.isNullOrBlank()) {
            AppLog.d(TAG, "401 after logout — not refreshing")
            return null
        }
        if (current != sentToken) {
            AppLog.d(TAG, "Token already refreshed by another request — reusing")
            return current
        }
        if (auth.refreshToken.isBlank()) {
            AppLog.e(TAG, "No refresh token stored — logging out")
            forceLogout()
            return null
        }
        refresh(current, auth.refreshToken)
    }

    /** Session is dead (refresh rejected, or the API still says 401 with a fresh token). */
    fun forceLogout() {
        runBlocking {
            if (sessionStore.getAccessToken().isNullOrBlank()) return@runBlocking
            AppLog.e(TAG, "Session rejected — clearing and returning to login")
            sessionStore.clear()
            sessionEvents.notifyUnauthorized()
        }
    }

    private fun refresh(accessToken: String, refreshToken: String): String? {
        AppLog.i(TAG, "401 — refreshing access token")
        val body = json.encodeToString(
            RefreshTokenRequestDto.serializer(),
            RefreshTokenRequestDto(accessToken = accessToken, refreshToken = refreshToken),
        ).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(AppConfig.apiBaseUrl.trimEnd('/') + "/" + REFRESH_PATH)
            .post(body)
            .build()

        val (code, raw) = try {
            client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            AppLog.w(TAG, "Refresh failed (network) — keeping session", e)
            return null
        }

        if (code !in 200..299) {
            if (code in 400..499 && code != 429) {
                AppLog.e(TAG, "Refresh rejected HTTP $code — logging out")
                forceLogout()
            } else {
                AppLog.w(TAG, "Refresh failed HTTP $code — keeping session")
            }
            return null
        }

        val data = runCatching {
            json.decodeFromString(
                MainGenericResponse.serializer(RefreshTokenResponseDto.serializer()),
                raw,
            )
        }.onFailure { AppLog.w(TAG, "Refresh response unparseable — keeping session", it) }
            .getOrNull()
            ?.takeIf { it.error != true }
            ?.data
        if (data == null || data.accessToken.isBlank()) {
            AppLog.w(TAG, "Refresh returned no token — keeping session")
            return null
        }

        runBlocking {
            sessionStore.updateTokens(
                accessToken = data.accessToken,
                idToken = data.idToken,
                refreshToken = data.refreshToken?.takeIf { it.isNotBlank() },
                expiresIn = data.expiresIn,
                tokenType = data.tokenType,
            )
        }
        AppLog.i(TAG, "Access token refreshed token=${AppLog.redact(data.accessToken)}")
        return data.accessToken
    }

    companion object {
        private const val TAG = "TokenRefresher"
        const val REFRESH_PATH = "auth/refresh-token"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
