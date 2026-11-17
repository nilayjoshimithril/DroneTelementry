package com.guardianangel.dronetelementry.domain.interact

import com.guardianangel.dronetelementry.config.AppConfig
import com.guardianangel.dronetelementry.data.local.SessionStore
import com.guardianangel.dronetelementry.data.model.AccessTokenResponseDto
import com.guardianangel.dronetelementry.data.model.CampusDto
import com.guardianangel.dronetelementry.data.model.RequestAccessTokenDto
import com.guardianangel.dronetelementry.data.remote.ApiService
import com.guardianangel.dronetelementry.utils.AppLog
import retrofit2.HttpException

class AuthInteract(
    private val api: ApiService,
    private val sessionStore: SessionStore,
) {
    suspend fun exchangeCode(code: String): AccessTokenResponseDto {
        AppLog.i(
            TAG,
            "API POST auth/token code=${AppLog.redact(code)} redirect=${AppConfig.oauthRedirectUri}",
        )
        val response = api.exchangeToken(
            RequestAccessTokenDto(
                code = code,
                redirectUri = AppConfig.oauthRedirectUri,
            )
        )
        AppLog.d(
            TAG,
            "auth/token response error=${response.error} message=${response.message} " +
                "user=${response.data?.userDetail?.email} " +
                "token=${AppLog.redact(response.data?.authDetail?.accessToken)}",
        )
        if (response.error == true || response.data == null) {
            throw IllegalStateException(response.message ?: "Token exchange failed")
        }
        sessionStore.saveAccessTokenResponse(response.data)
        AppLog.i(TAG, "Access token saved to DataStore")
        return response.data
    }

    /**
     * Restore session from DataStore.
     * - No token → null (show login)
     * - Token present → keep session; refresh via access-token-login when possible
     * - HTTP 401 → token refresh is attempted first; logout only if the refresh is rejected
     * - Network / other errors → keep cached session
     */
    suspend fun restoreSession(): AccessTokenResponseDto? {
        val cached = sessionStore.getAccessTokenResponse()
        val token = cached?.authDetail?.accessToken
        if (cached == null || token.isNullOrBlank()) {
            AppLog.i(TAG, "No stored access token — skip session restore")
            return null
        }
        AppLog.i(TAG, "Restoring session token=${AppLog.redact(token)}")
        return try {
            val response = api.accessTokenLogin()
            AppLog.d(
                TAG,
                "access-token-login error=${response.error} message=${response.message} " +
                    "user=${response.data?.userDetail?.email}",
            )
            if (response.error == true || response.data == null) {
                AppLog.w(TAG, "access-token-login business error — keeping cached session")
                cached
            } else {
                sessionStore.saveAccessTokenResponse(response.data)
                response.data
            }
        } catch (e: HttpException) {
            if (e.code() == 401) {
                // The interceptor already tried a token refresh. It clears the session only when
                // the refresh token is rejected; a transient refresh failure keeps the user in.
                if (sessionStore.getAccessToken().isNullOrBlank()) {
                    AppLog.e(TAG, "access-token-login 401 — session rejected")
                    null
                } else {
                    AppLog.w(TAG, "access-token-login 401, refresh unavailable — keeping cached session")
                    cached
                }
            } else {
                AppLog.w(TAG, "access-token-login HTTP ${e.code()} — keeping cached session", e)
                cached
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "access-token-login failed — keeping cached session", e)
            cached
        }
    }

    /**
     * Every campus in every district — internal users can open any campus. `campuses` is
     * paginated (Mithril passes page/limit), so all pages are walked.
     */
    suspend fun loadAllCampuses(): List<CampusDto> {
        val all = mutableListOf<CampusDto>()
        var page = 1
        var totalPages: Int
        do {
            AppLog.i(TAG, "API GET campuses?page=$page&limit=$CAMPUS_PAGE_SIZE")
            val response = api.getCampuses(page = page, limit = CAMPUS_PAGE_SIZE)
            if (response.error == true) {
                throw IllegalStateException(response.message ?: "Failed to load campuses")
            }
            val chunk = response.data?.campuses.orEmpty()
            all += chunk
            val meta = response.data?.meta
            val limit = meta?.limit?.takeIf { it > 0 } ?: CAMPUS_PAGE_SIZE
            val total = meta?.total ?: all.size
            val currentPage = meta?.page?.takeIf { it > 0 } ?: page
            totalPages = ((total + limit - 1) / limit).coerceIn(1, MAX_CAMPUS_PAGES)
            AppLog.d(TAG, "campuses page=$currentPage/$totalPages total=$total chunk=${chunk.size}")
            if (chunk.isEmpty()) break
            page = currentPage + 1
        } while (page <= totalPages)

        val campuses = all.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        AppLog.i(TAG, "campuses loaded count=${campuses.size} (fetched ${all.size})")
        return campuses
    }

    /** Logged-in user's name / email for the Settings profile card. */
    suspend fun currentUser() = sessionStore.getAccessTokenResponse()?.userDetail

    suspend fun logout() {
        AppLog.i(TAG, "Logout — clearing session DataStore")
        sessionStore.clear()
    }

    companion object {
        private const val TAG = "AuthApi"
        private const val CAMPUS_PAGE_SIZE = 50
        private const val MAX_CAMPUS_PAGES = 50
    }
}
