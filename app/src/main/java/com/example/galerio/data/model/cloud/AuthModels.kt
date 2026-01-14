package com.example.galerio.data.model.cloud

import com.google.gson.annotations.SerializedName

/**
 * Información del dispositivo para autenticación
 */
data class DeviceInfo(
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("device_type")
    val deviceType: String = "mobile",
    @SerializedName("user_agent")
    val userAgent: String,
    @SerializedName("device_ip")
    val deviceIp: String? = null
)

/**
 * Requests para autenticación
 */
data class LoginRequest(
    @SerializedName("user")
    val username: String,
    @SerializedName("pass")
    val password: String,
    @SerializedName("device_info")
    val deviceInfo: DeviceInfo? = null
)

data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    @SerializedName("device_info")
    val deviceInfo: DeviceInfo? = null,
    val language: String = "es",
    val timezone: String = "America/Madrid"
)

/**
 * Responses de autenticación
 */
data class LoginResponse(
    val message: String?,
    val user: User?,
    @SerializedName("token_info")
    val tokenInfo: TokenInfo? = null
)

data class RegisterResponse(
    val message: String?,
    val user: User?
)

data class RefreshTokenResponse(
    val token: String?,
    @SerializedName("refresh_token")
    val refreshToken: String?,
    val message: String? = null
)

/**
 * Información del token
 */
data class TokenInfo(
    val token: String?,
    @SerializedName("refresh_token")
    val refreshToken: String?,
    @SerializedName("expires_at")
    val expiresAt: Long?,
    @SerializedName("refresh_expires_at")
    val refreshExpiresAt: Long?
)

/**
 * Request para refrescar token
 */
data class RefreshTokenRequest(
    @SerializedName("refresh_token")
    val refreshToken: String,
    @SerializedName("device_info")
    val deviceInfo: DeviceInfo? = null
)

/**
 * Response para logout
 */
data class LogoutResponse(
    val message: String? = null
)
