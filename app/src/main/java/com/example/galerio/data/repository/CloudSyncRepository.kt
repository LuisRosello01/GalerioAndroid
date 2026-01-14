package com.example.galerio.data.repository

import android.content.Context
import android.util.Log
import com.example.galerio.data.local.dao.MediaItemDao
import com.example.galerio.data.local.preferences.AuthManager
import com.example.galerio.data.model.MediaItem
import com.example.galerio.data.model.cloud.CloudMediaItem
import com.example.galerio.data.model.cloud.SyncStatus
import com.example.galerio.data.remote.api.CloudApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository para sincronización bidireccional con la nube
 */
@Singleton
class CloudSyncRepository @Inject constructor(
    private val context: Context,
    private val apiService: CloudApiService,
    private val authManager: AuthManager,
    private val mediaItemDao: MediaItemDao
) {

    companion object {
        private const val TAG = "CloudSyncRepository"
        private const val CACHE_DURATION_MS = 30_000L // 30 segundos de caché
    }

    // Mutex para evitar múltiples sincronizaciones simultáneas
    private val syncMutex = Mutex()

    // Caché de última sincronización
    private var lastSyncTime = 0L
    private var cachedCloudItems: List<MediaItem>? = null

    // Estado de sincronización
    private val _syncStatus = MutableStateFlow(SyncStatus.SYNCED)
    val syncStatus: Flow<SyncStatus> = _syncStatus.asStateFlow()

    private val _syncProgress = MutableStateFlow(0f)
    val syncProgress: Flow<Float> = _syncProgress.asStateFlow()

    /**
     * Sincroniza los medios locales con la nube
     * Estrategia:
     * 1. Obtener lista de medios en la nube
     * 2. Comparar con medios locales
     * 3. Subir archivos nuevos locales
     * 4. Descargar archivos nuevos de la nube
     * 5. Resolver conflictos
     *
     * Usa Mutex para evitar múltiples sincronizaciones simultáneas
     * y caché para evitar llamadas repetidas en poco tiempo.
     */
    suspend fun syncWithCloud(): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        // Verificar si hay un sync en progreso
        if (syncMutex.isLocked) {
            Log.d(TAG, "[SYNC] Sync already in progress, waiting...")
        }

        syncMutex.withLock {
            try {
                // Verificar caché
                val now = System.currentTimeMillis()
                if (cachedCloudItems != null && (now - lastSyncTime) < CACHE_DURATION_MS) {
                    Log.d(TAG, "[SYNC] Returning cached data (${cachedCloudItems!!.size} items, age: ${(now - lastSyncTime) / 1000}s)")
                    return@withContext Result.success(cachedCloudItems!!)
                }

                Log.d(TAG, "[SYNC] Step 1: Checking authentication...")
                if (!authManager.isAuthenticated()) {
                    Log.d(TAG, "[SYNC] User not authenticated, skipping cloud sync")
                    return@withContext Result.success(emptyList())
                }
                Log.d(TAG, "[SYNC] User is authenticated ✓")

                _syncStatus.value = SyncStatus.PENDING
                Log.d(TAG, "[SYNC] Step 2: Starting sync with cloud...")

                // 1. Obtener medios de la nube
                Log.d(TAG, "[SYNC] Step 3: Getting token...")
                val token = authManager.getToken()
                if (token == null) {
                    Log.d(TAG, "[SYNC] No auth token available, skipping cloud sync")
                    return@withContext Result.success(emptyList())
                }
                Log.d(TAG, "[SYNC] Token available ✓ (length: ${token.length})")

                Log.d(TAG, "[SYNC] Step 4: Calling API getMediaList...")
                val cloudResponse = apiService.getMediaList("Bearer $token")

                Log.d(TAG, "[SYNC] Step 5: Response received - isSuccessful=${cloudResponse.isSuccessful}, code=${cloudResponse.code()}")

                // Log raw response for debugging
                val rawResponse = cloudResponse.raw()
                Log.d(TAG, "[SYNC] Raw response headers: ${rawResponse.headers}")

                // Try to read the raw body (be careful with large responses)
                if (!cloudResponse.isSuccessful) {
                    _syncStatus.value = SyncStatus.ERROR
                    val errorBody = cloudResponse.errorBody()?.string()
                    Log.e(TAG, "[SYNC] Failed to fetch cloud media: ${cloudResponse.code()} - ${cloudResponse.message()}")
                    Log.e(TAG, "[SYNC] Error body: $errorBody")

                    // Si es 401 y el error indica que no hay refresh token, forzar logout
                    if (cloudResponse.code() == 401 && errorBody?.contains("NO_REFRESH_TOKEN") == true) {
                        Log.w(TAG, "[SYNC] NO_REFRESH_TOKEN detected - forcing logout")
                        authManager.logout()
                        return@withContext Result.failure(Exception("Session expired. Please login again."))
                    }

                    return@withContext Result.failure(Exception("Failed to fetch cloud media: ${cloudResponse.message()}"))
                }

                Log.d(TAG, "[SYNC] Step 6: Parsing response body...")
                val responseBody = cloudResponse.body()
                val cloudItems = responseBody?.items ?: emptyList()
                Log.d(TAG, "[SYNC] Response body type: ${responseBody?.javaClass?.simpleName ?: "null"}")
                Log.d(TAG, "[SYNC] Step 7: Found ${cloudItems.size} items in cloud (total_count: ${responseBody?.totalCount ?: 0})")

                if (responseBody != null && responseBody.filters != null) {
                    Log.d(TAG, "[SYNC] Filters applied - type: ${responseBody.filters.type}, sort_by: ${responseBody.filters.sortBy}")
                }

                // Convertir CloudMediaItem a MediaItem
                val cloudMediaItems = cloudItems.map { cloudItem ->
                    val mediaType = cloudItem.getMediaType()
                    MediaItem(
                        uri = cloudItem.getFileUrl(),
                        type = mediaType,
                        dateModified = cloudItem.lastModified,
                        relativePath = null,
                        duration = cloudItem.duration,
                        isCloudItem = true,
                        cloudId = cloudItem.id,
                        hasThumbnail = cloudItem.hasThumbnail,
                        thumbnailUri = cloudItem.getThumbnailUrl()
                    )
                }

                // Actualizar caché
                cachedCloudItems = cloudMediaItems
                lastSyncTime = now

                _syncStatus.value = SyncStatus.SYNCED
                _syncProgress.value = 1f

                Log.d(TAG, "[SYNC] Step 8: Sync completed successfully, returning ${cloudMediaItems.size} cloud items.")
                Result.success(cloudMediaItems)

            } catch (e: Exception) {
                Log.e(TAG, "[SYNC] ERROR: Sync failed with exception", e)
                Log.e(TAG, "[SYNC] Exception message: ${e.message}")
                Log.e(TAG, "[SYNC] Exception type: ${e.javaClass.simpleName}")
                _syncStatus.value = SyncStatus.ERROR
                Result.failure(e)
            }
        }
    }

    /**
     * Limpia el caché de sincronización para forzar una nueva carga
     */
    fun clearSyncCache() {
        cachedCloudItems = null
        lastSyncTime = 0L
        Log.d(TAG, "[SYNC] Cache cleared")
    }

    /**
     * Sube un archivo media a la nube
     */
    suspend fun uploadMedia(mediaItem: MediaItem, file: File): Result<CloudMediaItem> = withContext(Dispatchers.IO) {
        try {
            _syncStatus.value = SyncStatus.UPLOADING

            val token = authManager.getToken() ?: return@withContext Result.failure(Exception("No auth token"))

            // Crear el multipart body
            val requestBody = file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestBody)

            // Metadata en JSON (ajustar según tu API)
            val metadata = okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                """{"type":"${mediaItem.type}","dateModified":${mediaItem.dateModified}}"""
            )

            val response = apiService.uploadMedia("Bearer $token", filePart, metadata)

            if (response.isSuccessful) {
                val cloudItem = response.body()?.mediaItem
                if (cloudItem != null) {
                    Log.d(TAG, "Upload successful: ${file.name}")
                    _syncStatus.value = SyncStatus.SYNCED
                    Result.success(cloudItem)
                } else {
                    Result.failure(Exception("Cloud item not found in response"))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Upload failed: ${response.code()} - ${response.message()}")
                Log.e(TAG, "Error body: $errorBody")

                // Si es 401 y el error indica que no hay refresh token, forzar logout
                if (response.code() == 401 && errorBody?.contains("NO_REFRESH_TOKEN") == true) {
                    Log.w(TAG, "NO_REFRESH_TOKEN detected - forcing logout")
                    authManager.logout()
                    _syncStatus.value = SyncStatus.ERROR
                    return@withContext Result.failure(Exception("Session expired. Please login again."))
                }

                _syncStatus.value = SyncStatus.ERROR
                Result.failure(Exception("Upload failed: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error", e)
            _syncStatus.value = SyncStatus.ERROR
            Result.failure(e)
        }
    }

    /**
     * Descarga un archivo media de la nube
     */
    suspend fun downloadMedia(cloudMediaItem: CloudMediaItem): Result<File> = withContext(Dispatchers.IO) {
        try {
            _syncStatus.value = SyncStatus.DOWNLOADING

            val token = authManager.getToken() ?: return@withContext Result.failure(Exception("No auth token"))

            val response = apiService.downloadMedia("Bearer $token", cloudMediaItem.id)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    // Guardar el archivo en el almacenamiento local
                    val file = File(context.filesDir, cloudMediaItem.id)
                    file.outputStream().use { output ->
                        body.byteStream().copyTo(output)
                    }

                    Log.d(TAG, "Download successful: ${cloudMediaItem.id}")
                    _syncStatus.value = SyncStatus.SYNCED
                    Result.success(file)
                } else {
                    Result.failure(Exception("Response body is null"))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Download failed: ${response.code()} - ${response.message()}")
                Log.e(TAG, "Error body: $errorBody")

                // Si es 401 y el error indica que no hay refresh token, forzar logout
                if (response.code() == 401 && errorBody?.contains("NO_REFRESH_TOKEN") == true) {
                    Log.w(TAG, "NO_REFRESH_TOKEN detected - forcing logout")
                    authManager.logout()
                    _syncStatus.value = SyncStatus.ERROR
                    return@withContext Result.failure(Exception("Session expired. Please login again."))
                }

                _syncStatus.value = SyncStatus.ERROR
                Result.failure(Exception("Download failed: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            _syncStatus.value = SyncStatus.ERROR
            Result.failure(e)
        }
    }

    /**
     * Elimina un media de la nube
     */
    suspend fun deleteFromCloud(mediaId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getToken() ?: return@withContext Result.failure(Exception("No auth token"))

            val response = apiService.deleteMedia("Bearer $token", mediaId)

            if (response.isSuccessful) {
                Log.d(TAG, "Delete successful: $mediaId")
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Delete failed: ${response.code()} - ${response.message()}")
                Log.e(TAG, "Error body: $errorBody")

                // Si es 401 y el error indica que no hay refresh token, forzar logout
                if (response.code() == 401 && errorBody?.contains("NO_REFRESH_TOKEN") == true) {
                    Log.w(TAG, "NO_REFRESH_TOKEN detected - forcing logout")
                    authManager.logout()
                    return@withContext Result.failure(Exception("Session expired. Please login again."))
                }

                Result.failure(Exception("Delete failed: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete error", e)
            Result.failure(e)
        }
    }
}
