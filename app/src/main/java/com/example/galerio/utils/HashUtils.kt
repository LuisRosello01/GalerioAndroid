package com.example.galerio.utils

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Utilidad para calcular hashes de archivos
 * Se usa para la sincronización batch con la nube
 */
object HashUtils {

    private const val BUFFER_SIZE = 8192

    /**
     * Calcula el hash MD5 de un archivo dado su URI
     * @param contentResolver ContentResolver para acceder al archivo
     * @param uri URI del archivo
     * @return Hash MD5 en formato hexadecimal, o null si hay error
     */
    suspend fun calculateMD5(contentResolver: ContentResolver, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val digest = MessageDigest.getInstance("MD5")
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                digest.digest().toHexString()
            } catch (e: Exception) {
                android.util.Log.e("HashUtils", "Error calculating MD5 for $uri", e)
                null
            }
        }

    /**
     * Calcula el hash SHA-256 de un archivo dado su URI
     * @param contentResolver ContentResolver para acceder al archivo
     * @param uri URI del archivo
     * @return Hash SHA-256 en formato hexadecimal, o null si hay error
     */
    suspend fun calculateSHA256(contentResolver: ContentResolver, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                digest.digest().toHexString()
            } catch (e: Exception) {
                android.util.Log.e("HashUtils", "Error calculating SHA-256 for $uri", e)
                null
            }
        }

    /**
     * Calcula hashes para múltiples URIs
     * @param contentResolver ContentResolver para acceder a los archivos
     * @param uris Lista de URIs a procesar
     * @param onProgress Callback para reportar progreso (0.0 a 1.0)
     * @return Mapa de URI string -> hash
     */
    suspend fun calculateHashesForUris(
        contentResolver: ContentResolver,
        uris: List<Uri>,
        onProgress: ((Float) -> Unit)? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()

        uris.forEachIndexed { index, uri ->
            val hash = calculateMD5(contentResolver, uri)
            if (hash != null) {
                result[uri.toString()] = hash
            }
            onProgress?.invoke((index + 1).toFloat() / uris.size)
        }

        result
    }

    /**
     * Extensión para convertir ByteArray a String hexadecimal
     */
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
