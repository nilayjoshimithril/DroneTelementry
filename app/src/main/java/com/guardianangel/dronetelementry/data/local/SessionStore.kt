package com.guardianangel.dronetelementry.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.guardianangel.dronetelementry.data.model.AccessTokenResponseDto
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
        /** Default map zoom (multiple of fit). Lives in this store so logout's clear() drops it. */
        val MAP_ZOOM_LEVEL = floatPreferencesKey("map_zoom_level")
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

    suspend fun saveMapZoomLevel(level: Float) {
        context.dataStore.edit { it[Keys.MAP_ZOOM_LEVEL] = level }
    }

    suspend fun getMapZoomLevel(): Float? = context.dataStore.data.first()[Keys.MAP_ZOOM_LEVEL]

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
