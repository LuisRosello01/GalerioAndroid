package com.example.galerio.data.remote.interceptor

import com.example.galerio.data.local.preferences.AuthManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Interceptor que agrega automáticamente el token de autenticación y un header de cliente a todas las peticiones
 */
class AuthInterceptor(private val authManager: AuthManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val requestBuilder = originalRequest.newBuilder()
            .header("X-Client-Type", "mobile-android") // Header para identificar la app

        // No añadir token a las rutas de login/register
        val path = originalRequest.url.encodedPath
        if (path.endsWith("/login") || path.endsWith("/register")) {
            return chain.proceed(requestBuilder.build())
        }

        // Para el resto de peticiones, obtener el token
        val token = runBlocking { authManager.getToken() }

        // Si hay token, agregarlo al header
        if (!token.isNullOrEmpty()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        return chain.proceed(requestBuilder.build())
    }
}
