package com.guardianangel.mithrildefense.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.guardianangel.mithrildefense.data.model.AccessTokenResponseDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "drone_telemetry_session")

class SessionStore(
    private val context: Context,
    private val json: Json,
) {
    private object Keys {
        val ACCESS_TOKEN_RESPONSE = stringPreferencesKey("access_token_response")
        val DISTRICT_ID = stringPreferencesKey("district_id")
        val CAMPUS_ID = stringPreferencesKey("campus_id")
        val CAMPUS_NAME = stringPreferencesKey("campus_name")
        val INVITE_CODE = stringPreferencesKey("invite_code")
    }

    val accessTokenFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.ACCESS_TOKEN_RESPONSE]
            ?.let { runCatching { json.decodeFromString<AccessTokenResponseDto>(it) }.getOrNull() }
            ?.authDetail
            ?.accessToken
    }

    suspend fun saveAccessTokenResponse(response: AccessTokenResponseDto) {
        context.dataStore.edit {
            it[Keys.ACCESS_TOKEN_RESPONSE] = json.encodeToString(
                AccessTokenResponseDto.serializer(),
                response,
            )
        }
    }

    suspend fun getAccessTokenResponse(): AccessTokenResponseDto? {
        val raw = context.dataStore.data.first()[Keys.ACCESS_TOKEN_RESPONSE] ?: return null
        return runCatching { json.decodeFromString<AccessTokenResponseDto>(raw) }.getOrNull()
    }

    suspend fun getAccessToken(): String? = getAccessTokenResponse()?.authDetail?.accessToken

    suspend fun getRefreshToken(): String? = getAccessTokenResponse()?.authDetail?.refreshToken

    suspend fun updateTokens(
        accessToken: String,
        idToken: String,
        refreshToken: String?,
        expiresIn: Int,
        tokenType: String,
    ) {
        val current = getAccessTokenResponse() ?: return
        val updated = current.copy(
            authDetail = current.authDetail.copy(
                accessToken = accessToken,
                idToken = idToken,
                refreshToken = refreshToken ?: current.authDetail.refreshToken,
                expiresIn = expiresIn,
                tokenType = tokenType,
            )
        )
        saveAccessTokenResponse(updated)
    }

    suspend fun saveCampus(districtId: String, campusId: String, campusName: String) {
        context.dataStore.edit {
            it[Keys.DISTRICT_ID] = districtId
            it[Keys.CAMPUS_ID] = campusId
            it[Keys.CAMPUS_NAME] = campusName
        }
    }

    suspend fun getDistrictId(): String? = context.dataStore.data.first()[Keys.DISTRICT_ID]
    suspend fun getCampusId(): String? = context.dataStore.data.first()[Keys.CAMPUS_ID]
    suspend fun getCampusName(): String? = context.dataStore.data.first()[Keys.CAMPUS_NAME]

    suspend fun saveInviteCode(code: String) {
        context.dataStore.edit { it[Keys.INVITE_CODE] = code }
    }

    suspend fun getInviteCode(): String? = context.dataStore.data.first()[Keys.INVITE_CODE]

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
