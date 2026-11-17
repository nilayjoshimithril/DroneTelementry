package com.guardianangel.dronetelementry.data.remote

import com.guardianangel.dronetelementry.utils.AppLog
import com.guardianangel.dronetelementry.utils.ServerClock
import okhttp3.Interceptor
import okhttp3.Response

/**
 * On API 401: refresh the access token and resend the request once with it
 * (Mithril HttpClient HttpSend interceptor). The user is logged out only when the refresh
 * token is rejected, or the API still answers 401 with the freshly issued token.
 * Non-API hosts (S3 tiles) are ignored.
 */
class UnauthorizedInterceptor(
    private val tokenRefresher: TokenRefresher,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!AuthInterceptor.shouldAttachAuth(request.url)) return response
        response.headers.getDate("Date")?.let { ServerClock.sync(it.time) }
        if (response.code != 401) return response

        val path = request.url.encodedPath
        // Code exchange / refresh calls own their own failures — never refresh-and-resend them.
        if (path.endsWith("/auth/token") || path.endsWith("/" + TokenRefresher.REFRESH_PATH)) {
            return response
        }
        // No session was attached, so there is nothing to refresh.
        val sentToken = request.header("Authorization") ?: return response

        AppLog.w(TAG, "HTTP 401 on ${request.method} $path — refreshing token")
        val newToken = tokenRefresher.freshToken(sentToken) ?: return response

        response.close()
        val retried = chain.proceed(
            request.newBuilder().header("Authorization", newToken).build()
        )
        if (retried.code == 401) {
            AppLog.e(TAG, "Still 401 after token refresh on $path — logging out")
            tokenRefresher.forceLogout()
        } else {
            AppLog.i(TAG, "Resent ${request.method} $path after refresh → ${retried.code}")
        }
        return retried
    }

    companion object {
        private const val TAG = "Unauthorized"
    }
}
