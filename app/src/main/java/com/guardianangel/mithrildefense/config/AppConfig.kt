package com.guardianangel.mithrildefense.config

import com.guardianangel.mithrildefense.BuildConfig

/**
 * Single place for environment + OAuth constants that are easy to swap for the POC.
 *
 * To point at a real Microsoft AD / Cognito authorize URL, change
 * [BuildConfig.MICROSOFT_AD_AUTHORIZE_URL] in `app/build.gradle.kts` (preferred).
 */
object AppConfig {

    val apiBaseUrl: String
        get() = BuildConfig.API_BASE_URL

    /**
     * Hardcoded Microsoft AD / Cognito authorize URL (NOT taken from district invite API).
     * Shape: `.../oauth2/authorize?client_id=...&response_type=code&scope=openid+email+profile`
     * Invite code is appended as `identity_provider` at authorize time.
     */
    val microsoftAdAuthorizeUrl: String
        get() = BuildConfig.MICROSOFT_AD_AUTHORIZE_URL

    val oauthRedirectUri: String
        get() = BuildConfig.OAUTH_REDIRECT_URI

    const val CLIENT_TYPE_MOBILE = "mobile"
    const val CLIENT_TYPE_APP = "app"
    const val TELEMETRY_UI_SAMPLE_MS = 33L
    /** Cap between reconnect attempts (Mithril uses 30s). */
    const val SOCKET_RECONNECT_CAP_MS = 30_000L
    /** Home ongoing-incidents poll — Mithril HomeViewModel.POLL_INTERVAL_MS. */
    const val ONGOING_INCIDENTS_POLL_MS = 5_000L
}
