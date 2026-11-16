package com.guardianangel.dronetelementry.data.remote

import com.guardianangel.dronetelementry.data.model.AccessTokenResponseDto
import com.guardianangel.dronetelementry.data.model.AnnotationDataDto
import com.guardianangel.dronetelementry.data.model.CampusDronePageDto
import com.guardianangel.dronetelementry.data.model.CampusesResponseDto
import com.guardianangel.dronetelementry.data.model.DistrictResponseDto
import com.guardianangel.dronetelementry.data.model.IncidentRequestDto
import com.guardianangel.dronetelementry.data.model.JoinIncidentResponseDto
import com.guardianangel.dronetelementry.data.model.MainGenericResponse
import com.guardianangel.dronetelementry.data.model.MapConfigResponseDto
import com.guardianangel.dronetelementry.data.model.OngoingIncidentDto
import com.guardianangel.dronetelementry.data.model.RefreshTokenRequestDto
import com.guardianangel.dronetelementry.data.model.RefreshTokenResponseDto
import com.guardianangel.dronetelementry.data.model.RequestAccessTokenDto
import com.guardianangel.dronetelementry.data.model.WsTicketRequestDto
import com.guardianangel.dronetelementry.data.model.WsTicketResponseDto
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
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
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

    /** Paginated campus drone roster (`data.meta` limit/page/total). */
    @GET("campuses/drones/")
    suspend fun getCampusDrones(
        @Query("campus_id") campusId: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100,
    ): MainGenericResponse<CampusDronePageDto>

    @GET("annotations")
    suspend fun getAnnotations(
        @Query("campus_id") campusId: String,
        @Query("page") page: Int = 1,
        @Query("client_type") clientType: String = "app",
    ): MainGenericResponse<AnnotationDataDto>
}
