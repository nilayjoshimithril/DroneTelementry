package com.guardianangel.mithrildefense.data.remote

import com.guardianangel.mithrildefense.data.model.AccessTokenResponseDto
import com.guardianangel.mithrildefense.data.model.AnnotationDataDto
import com.guardianangel.mithrildefense.data.model.CampusesResponseDto
import com.guardianangel.mithrildefense.data.model.DistrictResponseDto
import com.guardianangel.mithrildefense.data.model.IncidentRequestDto
import com.guardianangel.mithrildefense.data.model.JoinIncidentResponseDto
import com.guardianangel.mithrildefense.data.model.MainGenericResponse
import com.guardianangel.mithrildefense.data.model.MapConfigResponseDto
import com.guardianangel.mithrildefense.data.model.OngoingIncidentDto
import com.guardianangel.mithrildefense.data.model.RefreshTokenRequestDto
import com.guardianangel.mithrildefense.data.model.RefreshTokenResponseDto
import com.guardianangel.mithrildefense.data.model.RequestAccessTokenDto
import com.guardianangel.mithrildefense.data.model.WsTicketRequestDto
import com.guardianangel.mithrildefense.data.model.WsTicketResponseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @GET("districts/invite-code/{inviteCode}")
    suspend fun getDistrictFromInviteCode(
        @Path("inviteCode") inviteCode: String,
    ): MainGenericResponse<DistrictResponseDto>

    @POST("auth/token")
    suspend fun exchangeToken(
        @Body body: RequestAccessTokenDto,
    ): MainGenericResponse<AccessTokenResponseDto>

    @POST("auth/refresh-token")
    suspend fun refreshToken(
        @Body body: RefreshTokenRequestDto,
    ): MainGenericResponse<RefreshTokenResponseDto>

    @GET("auth/access-token-login")
    suspend fun accessTokenLogin(): MainGenericResponse<AccessTokenResponseDto>

    @GET("campuses")
    suspend fun getCampuses(
        @Query("district_id") districtId: String,
    ): MainGenericResponse<CampusesResponseDto>

    /** Home list: district-scoped only (no campus_id), same intent as Mithril home fetch. */
    @GET("incidents/ongoing")
    suspend fun getOngoingIncidents(
        @Query("district_id") districtId: String,
    ): MainGenericResponse<List<OngoingIncidentDto>>

    @POST("incidents/join")
    suspend fun joinIncident(
        @Body body: IncidentRequestDto,
    ): MainGenericResponse<JoinIncidentResponseDto>

    @POST("incidents/ws-ticket")
    suspend fun getWsTicket(
        @Body body: WsTicketRequestDto,
    ): MainGenericResponse<WsTicketResponseDto>

    @GET("campuses/maps/{campusId}")
    suspend fun getCampusMap(
        @Path("campusId") campusId: String,
        @Query("client_type") clientType: String = "app",
    ): MainGenericResponse<MapConfigResponseDto>

    @GET("annotations")
    suspend fun getAnnotations(
        @Query("campus_id") campusId: String,
        @Query("page") page: Int = 1,
        @Query("client_type") clientType: String = "app",
    ): MainGenericResponse<AnnotationDataDto>
}
