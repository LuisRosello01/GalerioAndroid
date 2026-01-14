package com.example.galerio.data.model.cloud

import com.google.gson.annotations.SerializedName

/**
 * Modelo de usuario autenticado - Sincronizado con el backend
 */
data class User(
    val id: String,
    val username: String,
    val email: String,
    val token: String = "",  // El token se asigna desde token_info, no viene en user
    @SerializedName("refresh_token")
    val refreshToken: String? = null,

    // Rol del usuario
    val role: UserRole? = UserRole.BASIC,

    // Fechas
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null,
    @SerializedName("last_login")
    val lastLogin: String? = null,

    // Información personal
    @SerializedName("display_name")
    val displayName: String? = null,

    // Preferencias (todos opcionales con valores por defecto)
    val language: String? = "es",
    val timezone: String? = "America/Madrid",
    @SerializedName("theme_preference")
    val themePreference: String? = "auto",

    // Configuración de privacidad (todos opcionales con valores por defecto)
    @SerializedName("profile_public")
    val profilePublic: Boolean? = true,
    @SerializedName("show_activity")
    val showActivity: Boolean? = true,
    @SerializedName("email_notifications")
    val emailNotifications: Boolean? = true,
    @SerializedName("analytics_consent")
    val analyticsConsent: Boolean? = true
)

/**
 * Roles de usuario disponibles
 */
enum class UserRole {
    @SerializedName("admin")
    ADMIN,

    @SerializedName("premium")
    PREMIUM,

    @SerializedName("basico")
    BASIC;

    fun isPremiumOrAbove(): Boolean = this == PREMIUM || this == ADMIN
    fun isAdmin(): Boolean = this == ADMIN
}
