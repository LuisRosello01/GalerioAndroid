package com.example.galerio.data.remote.interceptor

import android.util.Log
import com.example.galerio.data.local.preferences.AuthManager
import com.example.galerio.data.model.cloud.RefreshTokenRequest
import com.example.galerio.data.remote.api.CloudApiService
import com.example.galerio.utils.DeviceInfoProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Authenticator de OkHttp que maneja la renovación automática de tokens
 * cuando se recibe un 401 Unauthorized.
 *
 * Este es el punto centralizado para el manejo de errores 401.
 */
class TokenAuthenticator(
    private val authManager: AuthManager,
    private val apiServiceProvider: () -> CloudApiService,
    private val deviceInfoProvider: DeviceInfoProvider
) : Authenticator {

    companion object {
        private const val TAG = "TokenAuthenticator"
        private const val MAX_RETRY_COUNT = 2
    }

    // Mutex para evitar múltiples intentos de refresh simultáneos
    private val refreshMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request

        // No intentar refresh para rutas de auth
        val path = request.url.encodedPath
        if (path.endsWith("/login") || path.endsWith("/register") || path.endsWith("/refresh")) {
            Log.d(TAG, "Skipping refresh for auth endpoint: $path")
            return null
        }

        // Contar intentos previos de refresh para esta petición
        val retryCount = response.priorResponse?.let { countRetries(it) } ?: 0
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "Max retry count reached ($MAX_RETRY_COUNT), giving up")
            runBlocking { authManager.logout() }
            return null
        }

        Log.d(TAG, "401 received, attempting token refresh (attempt ${retryCount + 1})")

        return runBlocking {
            refreshMutex.withLock {
                // Verificar si el token ya fue refrescado por otra petición
                val currentToken = authManager.getToken()
                val requestToken = request.header("Authorization")?.removePrefix("Bearer ")

                if (currentToken != null && currentToken != requestToken) {
                    // El token ya fue refrescado, reintentar con el nuevo token
                    Log.d(TAG, "Token already refreshed by another request, retrying")
                    return@runBlocking request.newBuilder()
                        .header("Authorization", "Bearer $currentToken")
                        .build()
                }

                // Intentar refrescar el token
                val refreshResult = refreshToken()
                if (refreshResult) {
                    val newToken = authManager.getToken()
                    if (newToken != null) {
                        Log.d(TAG, "Token refreshed successfully, retrying request")
                        return@runBlocking request.newBuilder()
                            .header("Authorization", "Bearer $newToken")
                            .build()
                    }
                }

                // No se pudo refrescar, forzar logout
                Log.w(TAG, "Token refresh failed, forcing logout")
                authManager.logout()
                null
            }
        }
    }

    private fun countRetries(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private suspend fun refreshToken(): Boolean {
        return try {
            val refreshToken = authManager.getRefreshToken()
            if (refreshToken.isNullOrEmpty()) {
                Log.w(TAG, "No refresh token available")
                return false
            }

            val deviceInfo = deviceInfoProvider.getDeviceInfo()
            val apiService = apiServiceProvider()
            val response = apiService.refreshToken(
                RefreshTokenRequest(refreshToken, deviceInfo)
            )

            if (response.isSuccessful) {
                val body = response.body()
                val newToken = body?.token
                val newRefreshToken = body?.refreshToken

                if (newToken != null) {
                    authManager.updateToken(newToken, newRefreshToken)
                    Log.d(TAG, "Token refreshed and saved successfully")
                    true
                } else {
                    Log.e(TAG, "Token not found in refresh response")
                    false
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Token refresh failed: HTTP ${response.code()}, body: $errorBody")

                // Si es NO_REFRESH_TOKEN, hacer logout
                if (response.code() == 401 && errorBody?.contains("NO_REFRESH_TOKEN") == true) {
                    Log.w(TAG, "NO_REFRESH_TOKEN detected")
                }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error", e)
            false
        }
    }
}
