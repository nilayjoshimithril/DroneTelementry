package com.guardianangel.dronetelementry.domain.interact

import com.guardianangel.dronetelementry.config.AppConfig
import com.guardianangel.dronetelementry.data.local.SessionStore
import com.guardianangel.dronetelementry.data.model.AccessTokenResponseDto
import com.guardianangel.dronetelementry.data.model.CampusDto
import com.guardianangel.dronetelementry.data.model.DistrictResponseDto
import com.guardianangel.dronetelementry.data.model.RequestAccessTokenDto
import com.guardianangel.dronetelementry.data.remote.ApiService
import com.guardianangel.dronetelementry.utils.AppLog
import retrofit2.HttpException

class AuthInteract(
    private val api: ApiService,
    private val sessionStore: SessionStore,
) {
    suspend fun getDistrict(inviteCode: String): DistrictResponseDto {
        AppLog.i(TAG, "API GET districts/invite-code/$inviteCode")
        val response = api.getDistrictFromInviteCode(inviteCode)
        AppLog.d(
            TAG,
            "invite-code response error=${response.error} message=${response.message} " +
                "district=${response.data?.name} id=${response.data?.id}",
        )
        if (response.error == true || response.data == null) {
            throw IllegalStateException(response.message ?: "Invalid invite code")
        }
        sessionStore.saveInviteCode(inviteCode)
        return response.data
    }

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
     * - HTTP 401 → clear and return null (logout)
     * - Network / other errors → keep cached session
     */
    suspend fun restoreSession(): AccessTokenResponseDto? {
        val cached = sessionStore.getAccessTokenResponse()
        val token = cached?.authDetail?.accessToken
        if (cached == null || token.isNullOrBlank()) {
            AppLog.i(TAG, "No stored access token — skip session restore")
            return null
        }
        AppLog.i(
            TAG,
            "Restoring session token=${AppLog.redact(token)} " +
                "district=${sessionStore.getDistrictId()} campus=${sessionStore.getCampusId()}",
        )
        // Prefer persisted district/campus; backfill from cached user if missing.
        ensureCampusFromUser(cached)
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
                ensureCampusFromUser(response.data)
                response.data
            }
        } catch (e: HttpException) {
            if (e.code() == 401) {
                AppLog.e(TAG, "access-token-login 401 — clearing session")
                sessionStore.clear()
                null
            } else {
                AppLog.w(TAG, "access-token-login HTTP ${e.code()} — keeping cached session", e)
                cached
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "access-token-login failed — keeping cached session", e)
            cached
        }
    }

    private suspend fun ensureCampusFromUser(token: AccessTokenResponseDto) {
        if (!sessionStore.getDistrictId().isNullOrBlank()) return
        val districtId = token.userDetail.districts?.firstOrNull()?.id.orEmpty()
        val campus = token.userDetail.campuses?.firstOrNull()
        if (districtId.isNotBlank() && campus != null) {
            sessionStore.saveCampus(
                districtId = campus.districtId?.takeIf { it.isNotBlank() } ?: districtId,
                campusId = campus.id,
                campusName = campus.name.orEmpty(),
            )
            AppLog.i(TAG, "Backfilled campus/district from user_detail")
        }
    }

    /**
     * Every campus of [districtId] — `campuses` is paginated (Mithril passes page/limit), so a
     * single call only returned the first page and dropped campuses such as later ones in the
     * list. Rows from another district are filtered out.
     */
    suspend fun loadCampuses(districtId: String): List<CampusDto> {
        val all = mutableListOf<CampusDto>()
        var page = 1
        var totalPages: Int
        do {
            AppLog.i(TAG, "API GET campuses?district_id=$districtId&page=$page&limit=$CAMPUS_PAGE_SIZE")
            val response = api.getCampuses(districtId, page = page, limit = CAMPUS_PAGE_SIZE)
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

        val campuses = all
            .filter { it.districtId.isBlank() || it.districtId.equals(districtId, ignoreCase = true) }
            .distinctBy { it.id }
        AppLog.i(TAG, "campuses loaded district=$districtId count=${campuses.size} (fetched ${all.size})")
        return campuses
    }

    /**
     * District the user joined with: resolved from the stored invite code (so the campus list
     * is exactly that invite code's campuses), falling back to the stored district id.
     */
    suspend fun inviteDistrictId(): String? {
        val invite = sessionStore.getInviteCode()?.trim()?.takeIf { it.isNotEmpty() }
        if (invite != null) {
            runCatching { api.getDistrictFromInviteCode(invite).data?.id }
                .onFailure { AppLog.w(TAG, "Invite district lookup failed — using stored district", it) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return sessionStore.getDistrictId()?.takeIf { it.isNotBlank() }
    }

    /** Logged-in user's name / email for the Settings profile card. */
    suspend fun currentUser() = sessionStore.getAccessTokenResponse()?.userDetail

    suspend fun selectCampus(districtId: String, campus: CampusDto) {
        AppLog.i(TAG, "Select campus districtId=$districtId campusId=${campus.id} name=${campus.name}")
        sessionStore.saveCampus(districtId, campus.id, campus.name)
    }

    suspend fun hasValidSession(): Boolean =
        !sessionStore.getAccessToken().isNullOrBlank() &&
            !sessionStore.getDistrictId().isNullOrBlank()

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
