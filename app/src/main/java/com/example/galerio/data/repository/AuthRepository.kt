package com.example.galerio.data.repository

import android.util.Log
import com.example.galerio.data.local.preferences.AuthManager
import com.example.galerio.data.model.cloud.LoginRequest
import com.example.galerio.data.model.cloud.RegisterRequest
import com.example.galerio.data.model.cloud.RefreshTokenRequest
import com.example.galerio.data.model.cloud.User
import com.example.galerio.data.remote.api.CloudApiService
import com.example.galerio.utils.DeviceInfoProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository para gestionar autenticación con el servicio de nube
 */
@Singleton
class AuthRepository @Inject constructor(
    private val apiService: CloudApiService,
    private val authManager: AuthManager,
    private val deviceInfoProvider: DeviceInfoProvider
) {

    companion object {
        private const val TAG = "AuthRepository"
    }

    private val gson = Gson()

    /**
     * Inicia sesión con usuario y contraseña
     */
    suspend fun login(username: String, password: String): Result<User> = withContext(Dispatchers.IO) {
        try {
            val deviceInfo = deviceInfoProvider.getDeviceInfo()
            Log.d(TAG, "[LOGIN] Attempting login for user: $username")
            val response = apiService.login(LoginRequest(username, password, deviceInfo))

            Log.d(TAG, "[LOGIN] Response code: ${response.code()}")
            Log.d(TAG, "[LOGIN] Response isSuccessful: ${response.isSuccessful}")

            if (response.isSuccessful) {
                val loginResponse = response.body()
                Log.d(TAG, "[LOGIN] Response body: $loginResponse")

                val userFromResponse = loginResponse?.user
                val tokenInfo = loginResponse?.tokenInfo

                Log.d(TAG, "[LOGIN] User from response: $userFromResponse")
                Log.d(TAG, "[LOGIN] Token info from response: $tokenInfo")

                if (userFromResponse != null && tokenInfo != null) {
                    // El servidor devuelve el token en token_info, no en user
                    // Necesitamos construir el User con el token de token_info
                    val token = tokenInfo.token ?: ""
                    val refreshToken = tokenInfo.refreshToken

                    Log.d(TAG, "[LOGIN] Token from token_info present: ${token.isNotEmpty()}")
                    Log.d(TAG, "[LOGIN] Token length: ${token.length}")
                    Log.d(TAG, "[LOGIN] Token preview: ${token.take(20)}...")

                    // Crear el objeto User completo con el token
                    val user = userFromResponse.copy(
                        token = token,
                        refreshToken = refreshToken
                    )

                    // Obtener fechas de expiración
                    val expiresAt = tokenInfo.expiresAt
                    val refreshExpiresAt = tokenInfo.refreshExpiresAt

                    // Guardar credenciales con fechas de expiración
                    authManager.saveCredentials(user, expiresAt, refreshExpiresAt)
                    Log.d(TAG, "[LOGIN] Credentials saved successfully")

                    // Verificar que se guardó correctamente
                    val savedToken = authManager.getToken()
                    Log.d(TAG, "[LOGIN] Verification - Token saved: ${savedToken != null}")
                    if (savedToken != null) {
                        Log.d(TAG, "[LOGIN] Verification - Token length: ${savedToken.length}")
                    }

                    Log.d(TAG, "Login successful for user: ${user.username}")
                    Result.success(user)
                } else {
                    Log.e(TAG, "[LOGIN] User data or token info not found in response")
                    Result.failure(Exception("User data or token info not found in response"))
                }
            } else {
                // Intentar extraer el mensaje de error del cuerpo de la respuesta
                val errorBody = response.errorBody()?.string()
                val errorMessage = if (errorBody != null) {
                    try {
                        // Intentar parsear el JSON de error
                        val jsonError = gson.fromJson(errorBody, JsonObject::class.java)
                        jsonError.get("error")?.asString ?: jsonError.get("message")?.asString
                        ?: "Login failed"
                    } catch (_: Exception) {
                        "Login failed"
                    }
                } else {
                    "Login failed"
                }
                Log.e(TAG, "Login failed: $errorMessage (HTTP ${response.code()})")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            Result.failure(e)
        }
    }

    /**
     * Registra un nuevo usuario
     */
    suspend fun register(username: String, email: String, password: String): Result<User> = withContext(Dispatchers.IO) {
        try {
            val deviceInfo = deviceInfoProvider.getDeviceInfo()
            val response = apiService.register(
                RegisterRequest(
                    username = username,
                    email = email,
                    password = password,
                    deviceInfo = deviceInfo
                )
            )

            if (response.isSuccessful) {
                val user = response.body()?.user
                if (user != null) {
                    // Guardar credenciales automáticamente
                    authManager.saveCredentials(user)
                    Log.d(TAG, "Registration successful for user: ${user.username}")
                    Result.success(user)
                } else {
                    Result.failure(Exception("User data not found in response"))
                }
            } else {
                // Intentar extraer el mensaje de error del cuerpo de la respuesta
                val errorBody = response.errorBody()?.string()
                val errorMessage = if (errorBody != null) {
                    try {
                        // Intentar parsear el JSON de error
                        val jsonError = gson.fromJson(errorBody, JsonObject::class.java)
                        jsonError.get("error")?.getAsString() ?: jsonError.get("message")?.getAsString() ?: "Registration failed"
                    } catch (_: Exception) {
                        "Registration failed"
                    }
                } else {
                    "Registration failed"
                }
                Log.e(TAG, "Registration failed: $errorMessage (HTTP ${response.code()})")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration error", e)
            Result.failure(e)
        }
    }

    /**
     * Cierra sesión y limpia las credenciales
     */
    suspend fun logout(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getToken()
            if (token != null) {
                // Intentar cerrar sesión en el servidor
                apiService.logout("Bearer $token")
            }
            // Siempre limpiar credenciales locales
            authManager.logout()
            Log.d(TAG, "Logout successful")
            Result.success(Unit)
        } catch (e: Exception) {
            // Aunque falle el servidor, limpiar credenciales locales
            authManager.logout()
            Log.e(TAG, "Logout error (credentials cleared anyway)", e)
            Result.success(Unit)
        }
    }

    /**
     * Refresca el token de autenticación
     */
    suspend fun refreshToken(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val refreshToken = authManager.getRefreshToken()
            if (refreshToken.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No refresh token available"))
            }

            val deviceInfo = deviceInfoProvider.getDeviceInfo()
            val response = apiService.refreshToken(
                RefreshTokenRequest(refreshToken, deviceInfo)
            )

            if (response.isSuccessful) {
                val newToken = response.body()?.token
                val newRefreshToken = response.body()?.refreshToken

                if (newToken != null) {
                    authManager.updateToken(newToken, newRefreshToken)
                    Log.d(TAG, "Token refreshed successfully")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Token not found in response"))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMessage = "Token refresh failed"
                Log.e(TAG, "Token refresh failed: $errorMessage")
                Log.e(TAG, "Error body: $errorBody")

                // Si es 401 y el error indica que no hay refresh token, forzar logout
                if (response.code() == 401 && errorBody?.contains("NO_REFRESH_TOKEN") == true) {
                    Log.w(TAG, "NO_REFRESH_TOKEN detected - forcing logout")
                    authManager.logout()
                    return@withContext Result.failure(Exception("Session expired. Please login again."))
                }

                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            Result.failure(e)
        }
    }

    /**
     * Verifica si el usuario está autenticado
     */
    suspend fun isAuthenticated(): Boolean {
        return authManager.isAuthenticated()
    }
}
