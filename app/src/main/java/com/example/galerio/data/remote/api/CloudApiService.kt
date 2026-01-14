package com.example.galerio.data.remote.api

import com.example.galerio.data.model.cloud.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * API Service para comunicación con el servicio de nube
 *
 * IMPORTANTE: Reemplaza la BASE_URL con la URL real de tu servicio
 */
interface CloudApiService {

    companion object {
        const val BASE_URL = "https://galerio.es" //
    }

    // ============ AUTENTICACIÓN ============

    @POST("/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("/refresh")
    suspend fun refreshToken(
        @Body request: RefreshTokenRequest
    ): Response<RefreshTokenResponse>

    @POST("/logout")
    suspend fun logout(
        @Header("Authorization") token: String
    ): Response<LogoutResponse>

    // ============ SINCRONIZACIÓN DE MEDIOS ============

    @GET("media/files")
    suspend fun getMediaList(
        @Header("Authorization") token: String,
        @Query("type") type: String? = null, // "image" o "video"
        @Query("since") since: Long? = null // Timestamp para sincronización incremental
    ): Response<CloudMediaListResponse>

    @GET("media/{id}")
    suspend fun getMediaById(
        @Header("Authorization") token: String,
        @Path("id") mediaId: String
    ): Response<CloudMediaItem>

    @Multipart
    @POST("media/upload")
    suspend fun uploadMedia(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part,
        @Part("metadata") metadata: RequestBody
    ): Response<UploadMediaResponse>

    @GET("media/{id}/download")
    suspend fun downloadMedia(
        @Header("Authorization") token: String,
        @Path("id") mediaId: String
    ): Response<okhttp3.ResponseBody>

    @GET("media/download/access_thumbnail/{id}")
    suspend fun downloadThumbnail(
        @Header("Authorization") token: String,
        @Path("id") mediaId: String
    ): Response<okhttp3.ResponseBody>

    @DELETE("media/{id}")
    suspend fun deleteMedia(
        @Header("Authorization") token: String,
        @Path("id") mediaId: String
    ): Response<Unit>

    // ============ SINCRONIZACIÓN BATCH ============

    @POST("media/sync")
    suspend fun syncMedia(
        @Header("Authorization") token: String,
        @Body localHashes: Map<String, String> // Map<uri, hash>
    ): Response<CloudMediaListResponse>
}
