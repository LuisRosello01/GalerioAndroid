package com.example.galerio.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.galerio.data.model.cloud.User
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Manager para gestionar autenticación y credenciales de forma segura
 * Usa DataStore para almacenar token y datos del usuario
 */
class AuthManager(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

        private val TOKEN_KEY = stringPreferencesKey("auth_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        private val USER_KEY = stringPreferencesKey("user_data")
        private val IS_LOGGED_IN_KEY = stringPreferencesKey("is_logged_in")
        private val TOKEN_EXPIRES_AT_KEY = stringPreferencesKey("token_expires_at")
        private val REFRESH_TOKEN_EXPIRES_AT_KEY = stringPreferencesKey("refresh_token_expires_at")

        // Margen de seguridad para considerar el token como expirado (5 minutos antes)
        private const val TOKEN_EXPIRY_MARGIN_MS = 5 * 60 * 1000L
    }

    private val gson = Gson()

    /**
     * Flow que emite true si el usuario está autenticado
     */
    val isLoggedIn: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_LOGGED_IN_KEY]?.toBoolean() ?: false
    }

    /**
     * Flow que emite el token actual
     */
    val authToken: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[TOKEN_KEY]
    }

    /**
     * Flow que emite los datos del usuario
     */
    val currentUser: Flow<User?> = context.dataStore.data.map { preferences ->
        preferences[USER_KEY]?.let {
            try {
                gson.fromJson(it, User::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Guarda las credenciales del usuario después del login
     */
    suspend fun saveCredentials(user: User, expiresAt: Long? = null, refreshExpiresAt: Long? = null) {
        context.dataStore.edit { preferences ->
            preferences[TOKEN_KEY] = user.token
            preferences[REFRESH_TOKEN_KEY] = user.refreshToken ?: ""
            preferences[USER_KEY] = gson.toJson(user)
            preferences[IS_LOGGED_IN_KEY] = "true"
            expiresAt?.let { preferences[TOKEN_EXPIRES_AT_KEY] = it.toString() }
            refreshExpiresAt?.let { preferences[REFRESH_TOKEN_EXPIRES_AT_KEY] = it.toString() }
        }
    }

    /**
     * Actualiza solo el token (útil para refresh token)
     */
    suspend fun updateToken(newToken: String, newRefreshToken: String? = null, expiresAt: Long? = null, refreshExpiresAt: Long? = null) {
        context.dataStore.edit { preferences ->
            preferences[TOKEN_KEY] = newToken
            newRefreshToken?.let {
                preferences[REFRESH_TOKEN_KEY] = it
            }
            expiresAt?.let { preferences[TOKEN_EXPIRES_AT_KEY] = it.toString() }
            refreshExpiresAt?.let { preferences[REFRESH_TOKEN_EXPIRES_AT_KEY] = it.toString() }
        }
    }

    /**
     * Obtiene el token actual de forma síncrona (para interceptores)
     */
    suspend fun getToken(): String? {
        return context.dataStore.data.first()[TOKEN_KEY]
    }

    /**
     * Obtiene el refresh token
     */
    suspend fun getRefreshToken(): String? {
        return context.dataStore.data.first()[REFRESH_TOKEN_KEY]
    }

    /**
     * Cierra sesión y limpia todas las credenciales
     */
    suspend fun logout() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    /**
     * Verifica si el usuario está autenticado
     */
    suspend fun isAuthenticated(): Boolean {
        return context.dataStore.data.first()[IS_LOGGED_IN_KEY]?.toBoolean() ?: false
    }

    /**
     * Obtiene la fecha de expiración del token
     */
    suspend fun getTokenExpiresAt(): Long? {
        return context.dataStore.data.first()[TOKEN_EXPIRES_AT_KEY]?.toLongOrNull()
    }

    /**
     * Obtiene la fecha de expiración del refresh token
     */
    suspend fun getRefreshTokenExpiresAt(): Long? {
        return context.dataStore.data.first()[REFRESH_TOKEN_EXPIRES_AT_KEY]?.toLongOrNull()
    }

    /**
     * Verifica si el token de acceso está expirado o próximo a expirar
     */
    suspend fun isTokenExpired(): Boolean {
        val expiresAt = getTokenExpiresAt() ?: return false // Si no hay fecha, asumimos que no está expirado
        val currentTime = System.currentTimeMillis()
        return currentTime >= (expiresAt - TOKEN_EXPIRY_MARGIN_MS)
    }

    /**
     * Verifica si el refresh token está expirado
     */
    suspend fun isRefreshTokenExpired(): Boolean {
        val expiresAt = getRefreshTokenExpiresAt() ?: return false
        return System.currentTimeMillis() >= expiresAt
    }

    /**
     * Verifica si se puede intentar refrescar el token
     */
    suspend fun canRefreshToken(): Boolean {
        val refreshToken = getRefreshToken()
        if (refreshToken.isNullOrEmpty()) return false
        return !isRefreshTokenExpired()
    }
}

