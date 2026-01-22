package com.example.galerio.data.remote.interceptor

import android.util.Log
import com.example.galerio.data.local.preferences.AuthManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Interceptor que agrega automáticamente el token de autenticación y un header de cliente a todas las peticiones.
 *
 * También verifica si el token está próximo a expirar y marca la petición para que el Authenticator
 * pueda refrescar el token de forma proactiva.
 */
class AuthInterceptor(private val authManager: AuthManager) : Interceptor {

    companion object {
        private const val TAG = "AuthInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val requestBuilder = originalRequest.newBuilder()
            .header("X-Client-Type", "mobile-android") // Header para identificar la app

        // No añadir token a las rutas de login/register
        val path = originalRequest.url.encodedPath
        if (path.endsWith("/login") || path.endsWith("/register")) {
            return chain.proceed(requestBuilder.build())
        }

        // Para el resto de peticiones, obtener el token y verificar expiración
        val tokenInfo = runBlocking {
            val token = authManager.getToken()
            val isExpired = authManager.isTokenExpired()
            val canRefresh = authManager.canRefreshToken()
            Triple(token, isExpired, canRefresh)
        }

        val (token, isExpired, canRefresh) = tokenInfo

        // Si el token está expirado y no podemos refrescar, limpiar sesión
        if (isExpired && !canRefresh) {
            Log.w(TAG, "Token expired and cannot refresh, session will be invalidated")
            runBlocking { authManager.logout() }
            // Continuar con la petición, el servidor devolverá 401
        }

        // Si hay token (aunque esté expirado), agregarlo - el TokenAuthenticator lo refrescará si recibe 401
        if (!token.isNullOrEmpty()) {
            if (isExpired) {
                Log.d(TAG, "Token is expired but will be refreshed by Authenticator if needed")
            }
            requestBuilder.header("Authorization", "Bearer $token")
        }

        return chain.proceed(requestBuilder.build())
    }
}
