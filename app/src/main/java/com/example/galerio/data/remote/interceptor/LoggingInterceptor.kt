package com.example.galerio.data.remote.interceptor

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Interceptor para logging detallado de requests y responses
 */
class LoggingInterceptor : Interceptor {

    companion object {
        private const val TAG = "LoggingInterceptor"
        private const val MAX_BODY_LENGTH = 1000 // Limitar el tamaño del body en el log
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Log request
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "REQUEST: ${request.method} ${request.url}")
        Log.d(TAG, "Headers: ${request.headers}")

        val startTime = System.currentTimeMillis()
        val response = chain.proceed(request)
        val duration = System.currentTimeMillis() - startTime

        // Log response
        Log.d(TAG, "RESPONSE: ${response.code} ${response.message} (${duration}ms)")
        Log.d(TAG, "URL: ${response.request.url}")
        Log.d(TAG, "Headers: ${response.headers}")

        // Leer el body de la respuesta (sin consumirlo)
        val responseBody = response.body
        val source = responseBody?.source()
        source?.request(Long.MAX_VALUE) // Buffer the entire body
        val buffer = source?.buffer

        val bodyString = buffer?.clone()?.readString(Charsets.UTF_8) ?: ""

        // Log body (limitado)
        if (bodyString.isNotEmpty()) {
            val truncatedBody = if (bodyString.length > MAX_BODY_LENGTH) {
                "${bodyString.substring(0, MAX_BODY_LENGTH)}... (truncated, total: ${bodyString.length} chars)"
            } else {
                bodyString
            }
            Log.d(TAG, "Body: $truncatedBody")
        }

        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        return response
    }
}

