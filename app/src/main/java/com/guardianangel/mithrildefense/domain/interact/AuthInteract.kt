package com.guardianangel.mithrildefense.domain.interact

import com.guardianangel.mithrildefense.config.AppConfig
import com.guardianangel.mithrildefense.data.local.SessionStore
import com.guardianangel.mithrildefense.data.model.AccessTokenResponseDto
import com.guardianangel.mithrildefense.data.model.CampusDto
import com.guardianangel.mithrildefense.data.model.DistrictResponseDto
import com.guardianangel.mithrildefense.data.model.RequestAccessTokenDto
import com.guardianangel.mithrildefense.data.remote.ApiService
import com.guardianangel.mithrildefense.utils.AppLog
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

    suspend fun loadCampuses(districtId: String): List<CampusDto> {
        AppLog.i(TAG, "API GET campuses?district_id=$districtId")
        val response = api.getCampuses(districtId)
        val campuses = response.data?.campuses.orEmpty()
        AppLog.d(
            TAG,
            "campuses error=${response.error} message=${response.message} count=${campuses.size}",
        )
        if (response.error == true) {
            throw IllegalStateException(response.message ?: "Failed to load campuses")
        }
        return campuses
    }

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
    }
}
