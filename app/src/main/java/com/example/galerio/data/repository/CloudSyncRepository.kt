package com.example.galerio.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.galerio.data.local.dao.MediaItemDao
import com.example.galerio.data.local.dao.SyncedMediaDao
import com.example.galerio.data.local.entity.SyncedMediaEntity
import com.example.galerio.data.local.preferences.AuthManager
import com.example.galerio.data.model.MediaItem
import com.example.galerio.data.model.cloud.CloudMediaItem
import com.example.galerio.data.model.cloud.SyncStatus
import com.example.galerio.data.remote.api.CloudApiService
import com.example.galerio.utils.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri

/**
 * Repository para sincronización bidireccional con la nube
 */
@Singleton
class CloudSyncRepository @Inject constructor(
    private val context: Context,
    private val apiService: CloudApiService,
    private val authManager: AuthManager,
    private val mediaItemDao: MediaItemDao,
    private val syncedMediaDao: SyncedMediaDao
) {

    companion object {
        private const val TAG = "CloudSyncRepository"
        private const val CACHE_DURATION_MS = 30_000L // 30 segundos de caché
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L
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

                    if (!cloudItem.hasThumbnail) {
                         Log.d(TAG, "Item ${cloudItem.id} reports hasThumbnail=false")
                    }
                    MediaItem(
                        uri = cloudItem.getFileUrl(CloudApiService.BASE_URL),
                        type = mediaType,
                        dateModified = cloudItem.lastModified,
                        relativePath = null,
                        duration = cloudItem.duration,
                        isCloudItem = true,
                        cloudId = cloudItem.id,
                        hasThumbnail = cloudItem.hasThumbnail,
                        thumbnailUri = cloudItem.getThumbnailUrl(CloudApiService.BASE_URL)
                    )
                }.sortedByDescending { it.dateModified }

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
            val user = authManager.currentUser.first() ?: return@withContext Result.failure(Exception("No user found"))

            // Crear el multipart body
            val requestBody = file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestBody)

            // Metadata en JSON (ajustar según tu API)
            val metadata = okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                """{"type":"${mediaItem.type.name.lowercase()}","dateModified":${mediaItem.dateModified}}"""
            )

            val response = apiService.uploadMedia("Bearer $token", user.id, filePart, metadata)

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

    // ============ SINCRONIZACIÓN BATCH ============

    /**
     * Resultado de la sincronización batch
     */
    data class BatchSyncResult(
        val alreadySynced: Map<String, String>, // uri -> cloudId
        val needsUpload: List<String>,          // uris que necesitan subirse
        val uploadedCount: Int = 0,
        val failedCount: Int = 0
    )

    /**
     * Sincroniza archivos locales con la nube usando hashes
     * @param localMediaItems Lista de MediaItems locales a sincronizar
     * @param autoUpload Si es true, sube automáticamente los archivos pendientes
     * @return BatchSyncResult con el resultado de la sincronización
     */
    suspend fun syncBatch(
        localMediaItems: List<MediaItem>,
        autoUpload: Boolean = false
    ): Result<BatchSyncResult> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[BATCH_SYNC] Starting batch sync for ${localMediaItems.size} items")

            // Verificar autenticación
            if (!authManager.isAuthenticated()) {
                Log.d(TAG, "[BATCH_SYNC] User not authenticated")
                return@withContext Result.failure(Exception("User not authenticated"))
            }

            val token = authManager.getToken() ?: return@withContext Result.failure(Exception("No auth token"))

            _syncStatus.value = SyncStatus.PENDING

            // 1. Calcular hashes de los archivos locales
            Log.d(TAG, "[BATCH_SYNC] Step 1: Calculating hashes...")
            val contentResolver = context.contentResolver
            val uris = localMediaItems.mapNotNull {
                try {
                    it.uri.toUri()
                } catch (e: Exception) {
                    Log.w(TAG, "[BATCH_SYNC] Invalid URI: ${it.uri}")
                    null
                }
            }

            val hashesMap = HashUtils.calculateHashesForUris(
                contentResolver = contentResolver,
                uris = uris,
                onProgress = { progress ->
                    _syncProgress.value = progress * 0.3f // 0-30% para cálculo de hashes
                }
            )
            Log.d(TAG, "[BATCH_SYNC] Calculated ${hashesMap.size} hashes")

            if (hashesMap.isEmpty()) {
                Log.d(TAG, "[BATCH_SYNC] No valid hashes calculated")
                return@withContext Result.success(BatchSyncResult(emptyMap(), emptyList()))
            }

            // 2. Enviar hashes al servidor
            Log.d(TAG, "[BATCH_SYNC] Step 2: Sending hashes to server...")
            val response = apiService.syncMedia("Bearer $token", hashesMap)

            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "[BATCH_SYNC] Server error: ${response.code()} - $errorBody")

                // Si es 401, manejar logout
                if (response.code() == 401 && errorBody?.contains("NO_REFRESH_TOKEN") == true) {
                    authManager.logout()
                    return@withContext Result.failure(Exception("Session expired. Please login again."))
                }

                _syncStatus.value = SyncStatus.ERROR
                return@withContext Result.failure(Exception("Sync failed: ${response.message()}"))
            }

            val syncResponse = response.body()
            val alreadySynced = syncResponse?.alreadySynced ?: emptyMap()
            val needsUpload = syncResponse?.needsUpload ?: emptyList()

            Log.d(TAG, "[BATCH_SYNC] Server response - already_synced: ${alreadySynced.size}, needs_upload: ${needsUpload.size}")

            if (syncResponse?.filters != null) {
                Log.d(TAG, "[BATCH_SYNC] Filters: requested=${syncResponse.filters.syncRequested}, matched=${syncResponse.filters.syncMatched}, pending=${syncResponse.filters.syncPending}")
            }

            // 3. Guardar already_synced en Room
            Log.d(TAG, "[BATCH_SYNC] Step 3: Saving synced items to local database...")
            saveSyncedItems(alreadySynced, hashesMap)

            _syncProgress.value = 0.5f // 50% completado

            // 4. Subir archivos pendientes si autoUpload está habilitado
            var uploadedCount = 0
            var failedCount = 0

            if (autoUpload && needsUpload.isNotEmpty()) {
                Log.d(TAG, "[BATCH_SYNC] Step 4: Auto-uploading ${needsUpload.size} pending files...")
                val uploadResult = uploadPendingMedia(needsUpload, localMediaItems, hashesMap)
                uploadedCount = uploadResult.first
                failedCount = uploadResult.second
            }

            _syncStatus.value = SyncStatus.SYNCED
            _syncProgress.value = 1f

            Log.d(TAG, "[BATCH_SYNC] Sync completed - synced: ${alreadySynced.size}, uploaded: $uploadedCount, failed: $failedCount")

            Result.success(BatchSyncResult(
                alreadySynced = alreadySynced,
                needsUpload = needsUpload,
                uploadedCount = uploadedCount,
                failedCount = failedCount
            ))

        } catch (e: Exception) {
            Log.e(TAG, "[BATCH_SYNC] Error during batch sync", e)
            _syncStatus.value = SyncStatus.ERROR
            Result.failure(e)
        }
    }

    /**
     * Sube los archivos pendientes a la nube con reintentos
     * @return Par (uploadedCount, failedCount)
     */
    private suspend fun uploadPendingMedia(
        needsUpload: List<String>,
        localMediaItems: List<MediaItem>,
        hashesMap: Map<String, String>
    ): Pair<Int, Int> {
        var uploadedCount = 0
        var failedCount = 0
        val totalToUpload = needsUpload.size
        val failedItems = mutableListOf<UploadFailure>()

        _syncStatus.value = SyncStatus.UPLOADING

        needsUpload.forEachIndexed { index, uriString ->
            val result = uploadSingleMediaWithRetry(
                uriString = uriString,
                localMediaItems = localMediaItems,
                hashesMap = hashesMap,
                maxRetries = MAX_RETRY_ATTEMPTS
            )

            when (result) {
                is UploadResult.Success -> uploadedCount++
                is UploadResult.Failure -> {
                    failedCount++
                    failedItems.add(UploadFailure(uriString, result.error))
                }
                is UploadResult.Skipped -> {
                    Log.d(TAG, "[UPLOAD] Skipped: $uriString - ${result.reason}")
                }
            }

            // Actualizar progreso (50-100% para uploads)
            val uploadProgress = (index + 1).toFloat() / totalToUpload
            _syncProgress.value = 0.5f + (uploadProgress * 0.5f)
        }

        if (failedItems.isNotEmpty()) {
            Log.w(TAG, "[UPLOAD] Failed uploads summary: ${failedItems.size} items failed")
            failedItems.forEach { failure ->
                Log.w(TAG, "[UPLOAD] - ${failure.uri}: ${failure.error}")
            }
        }

        return Pair(uploadedCount, failedCount)
    }

    /**
     * Resultado de un intento de subida
     */
    private sealed class UploadResult {
        data class Success(val cloudId: String) : UploadResult()
        data class Failure(val error: String) : UploadResult()
        data class Skipped(val reason: String) : UploadResult()
    }

    /**
     * Información de un fallo de subida
     */
    private data class UploadFailure(val uri: String, val error: String)

    /**
     * Sube un archivo con reintentos
     */
    private suspend fun uploadSingleMediaWithRetry(
        uriString: String,
        localMediaItems: List<MediaItem>,
        hashesMap: Map<String, String>,
        maxRetries: Int
    ): UploadResult {
        val mediaItem = localMediaItems.find { it.uri == uriString }
            ?: return UploadResult.Skipped("Media item not found for URI")

        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            return UploadResult.Skipped("Invalid URI format")
        }

        var lastError: String = "Unknown error"

        repeat(maxRetries) { attempt ->
            val tempFile = createTempFileFromUri(uri, mediaItem.type.name.lowercase())

            if (tempFile == null) {
                lastError = "Could not create temp file"
                Log.w(TAG, "[UPLOAD] Attempt ${attempt + 1}/$maxRetries - Failed to create temp file for: $uriString")
                return@repeat
            }

            try {
                Log.d(TAG, "[UPLOAD] Attempt ${attempt + 1}/$maxRetries for: $uriString")

                val result = uploadMedia(mediaItem, tempFile)

                if (result.isSuccess) {
                    val cloudItem = result.getOrNull()
                    if (cloudItem != null) {
                        // Guardar en Room el nuevo mapping
                        val hash = hashesMap[uriString] ?: ""
                        syncedMediaDao.insert(SyncedMediaEntity(
                            localUri = uriString,
                            cloudId = cloudItem.id,
                            hash = hash,
                            syncedAt = System.currentTimeMillis()
                        ))
                        Log.d(TAG, "[UPLOAD] Success on attempt ${attempt + 1}: $uriString -> ${cloudItem.id}")
                        return UploadResult.Success(cloudItem.id)
                    }
                }

                lastError = result.exceptionOrNull()?.message ?: "Upload returned null"
                Log.w(TAG, "[UPLOAD] Attempt ${attempt + 1}/$maxRetries failed: $lastError")

                // Esperar antes de reintentar (backoff exponencial)
                if (attempt < maxRetries - 1) {
                    val delayMs = RETRY_DELAY_MS * (attempt + 1)
                    Log.d(TAG, "[UPLOAD] Waiting ${delayMs}ms before retry...")
                    kotlinx.coroutines.delay(delayMs)
                }

            } catch (e: Exception) {
                lastError = e.message ?: "Exception during upload"
                Log.e(TAG, "[UPLOAD] Exception on attempt ${attempt + 1}/$maxRetries", e)

                if (attempt < maxRetries - 1) {
                    val delayMs = RETRY_DELAY_MS * (attempt + 1)
                    kotlinx.coroutines.delay(delayMs)
                }
            } finally {
                // Limpiar archivo temporal
                tempFile.delete()
            }
        }

        return UploadResult.Failure(lastError)
    }

    /**
     * Guarda los items ya sincronizados en Room
     */
    private suspend fun saveSyncedItems(
        alreadySynced: Map<String, String>,
        hashesMap: Map<String, String>
    ) {
        val syncedEntities = alreadySynced.mapNotNull { (uri, cloudId) ->
            val hash = hashesMap[uri]
            if (hash != null) {
                SyncedMediaEntity(
                    localUri = uri,
                    cloudId = cloudId,
                    hash = hash,
                    syncedAt = System.currentTimeMillis()
                )
            } else {
                null
            }
        }

        if (syncedEntities.isNotEmpty()) {
            syncedMediaDao.insertAll(syncedEntities)
            Log.d(TAG, "[BATCH_SYNC] Saved ${syncedEntities.size} synced items to database")
        }
    }

    /**
     * Crea un archivo temporal desde un URI de contenido con la extensión correcta
     */
    private fun createTempFileFromUri(uri: Uri, mediaType: String): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null

            // Obtener el MIME type real del archivo
            val mimeType = context.contentResolver.getType(uri)

            // Determinar la extensión correcta
            val extension = when {
                mimeType?.startsWith("image/jpeg") == true -> ".jpg"
                mimeType?.startsWith("image/png") == true -> ".png"
                mimeType?.startsWith("image/gif") == true -> ".gif"
                mimeType?.startsWith("image/webp") == true -> ".webp"
                mimeType?.startsWith("image/heic") == true -> ".heic"
                mimeType?.startsWith("image/heif") == true -> ".heif"
                mimeType?.startsWith("video/mp4") == true -> ".mp4"
                mimeType?.startsWith("video/3gpp") == true -> ".3gp"
                mimeType?.startsWith("video/webm") == true -> ".webm"
                mimeType?.startsWith("video/quicktime") == true -> ".mov"
                mimeType?.startsWith("video/x-matroska") == true -> ".mkv"
                mimeType?.startsWith("video/") == true -> ".mp4" // Default para videos
                mimeType?.startsWith("image/") == true -> ".jpg" // Default para imágenes
                mediaType == "video" -> ".mp4"
                else -> ".jpg"
            }

            // Intentar obtener el nombre original del archivo
            var originalName: String? = null
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        originalName = cursor.getString(nameIndex)
                    }
                }
            }

            // Crear nombre del archivo temporal
            val fileName = originalName?.substringBeforeLast(".")
                ?: "upload_${System.currentTimeMillis()}"

            val tempFile = File.createTempFile(fileName, extension, context.cacheDir)
            tempFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            inputStream.close()

            Log.d(TAG, "[UPLOAD] Created temp file: ${tempFile.name} (mimeType: $mimeType)")
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Error creating temp file from URI: $uri", e)
            null
        }
    }

    /**
     * Verifica si un archivo local ya está sincronizado
     */
    suspend fun isMediaSynced(uri: String): Boolean {
        return syncedMediaDao.isSynced(uri)
    }

    /**
     * Obtiene el cloudId de un archivo local sincronizado
     */
    suspend fun getCloudIdForLocalUri(uri: String): String? {
        return syncedMediaDao.getCloudIdForUri(uri)
    }

    /**
     * Obtiene todos los archivos sincronizados
     */
    fun getAllSyncedMedia(): Flow<List<SyncedMediaEntity>> {
        return syncedMediaDao.getAllSynced()
    }

    /**
     * Obtiene el conteo de archivos sincronizados
     */
    suspend fun getSyncedMediaCount(): Int {
        return syncedMediaDao.getCount()
    }
}
