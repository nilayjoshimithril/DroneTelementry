package com.guardianangel.mithrildefense.data.remote

import com.guardianangel.mithrildefense.data.local.SessionStore
import com.guardianangel.mithrildefense.utils.AppLog
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * On API 401: clear persisted session and notify UI to show login.
 * Non-API hosts (S3 tiles) are ignored.
 */
class UnauthorizedInterceptor(
    private val sessionStore: SessionStore,
    private val sessionEvents: SessionEvents,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 401) return response
        if (!AuthInterceptor.shouldAttachAuth(request.url)) return response

        AppLog.e(TAG, "HTTP 401 on ${request.method} ${request.url} — forcing logout")
        runBlocking {
            sessionStore.clear()
            sessionEvents.notifyUnauthorized()
        }
        return response
    }

    companion object {
        private const val TAG = "Unauthorized"
    }
}
