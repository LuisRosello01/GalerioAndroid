package com.example.galerio.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.example.galerio.data.local.dao.MediaItemDao
import com.example.galerio.data.local.dao.SyncedMediaDao
import com.example.galerio.data.local.entity.SyncedMediaEntity
import com.example.galerio.data.local.preferences.AuthManager
import com.example.galerio.data.model.MediaItem
import com.example.galerio.data.model.cloud.CloudMediaItem
import com.example.galerio.data.model.cloud.SyncStatus
import com.example.galerio.data.remote.api.CloudApiService
import com.example.galerio.utils.HashUtils
import com.example.galerio.utils.LocationUtils
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
import okhttp3.RequestBody.Companion.toRequestBody

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

    // Flag para cancelar la sincronización en curso
    @Volatile
    private var isCancelled = false

    // Caché de última sincronización
    private var lastSyncTime = 0L
    private var cachedCloudItems: List<MediaItem>? = null

    // Estado de sincronización
    private val _syncStatus = MutableStateFlow(SyncStatus.SYNCED)
    val syncStatus: Flow<SyncStatus> = _syncStatus.asStateFlow()

    private val _syncProgress = MutableStateFlow(0f)
    val syncProgress: Flow<Float> = _syncProgress.asStateFlow()

    // Progreso detallado de subida (índice actual y total)
    data class UploadProgressInfo(
        val currentIndex: Int = 0,
        val totalCount: Int = 0
    )

    private val _uploadProgressInfo = MutableStateFlow(UploadProgressInfo())
    val uploadProgressInfo: Flow<UploadProgressInfo> = _uploadProgressInfo.asStateFlow()

    /**
     * Cancela la sincronización en curso
     */
    fun cancelSync() {
        isCancelled = true
        Log.d(TAG, "[SYNC] Cancellation requested")
    }

    /**
     * Verifica si la sincronización fue cancelada
     */
    fun isSyncCancelled(): Boolean = isCancelled

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
                        dateTaken = cloudItem.dateTaken,
                        dateModified = cloudItem.lastModified,
                        dateAdded = cloudItem.dateAdded,
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
     * @param mediaItem El MediaItem a subir
     * @param file El archivo a subir
     * @param hash Hash SHA-256 del archivo (opcional, se calcula si no se proporciona)
     * @param originalUri URI original del archivo para extraer GPS (opcional)
     */
    suspend fun uploadMedia(
        mediaItem: MediaItem,
        file: File,
        hash: String? = null,
        originalUri: Uri? = null
    ): Result<CloudMediaItem> = withContext(Dispatchers.IO) {
        try {
            _syncStatus.value = SyncStatus.UPLOADING

            val token = authManager.getToken() ?: return@withContext Result.failure(Exception("No auth token"))
            val user = authManager.currentUser.first() ?: return@withContext Result.failure(Exception("No user found"))

            // Calcular hash si no se proporcionó
            val fileHash = hash ?: HashUtils.calculateFileHash(file)
            Log.d(TAG, "File hash: $fileHash")

            // Extraer datos GPS del archivo original (si está disponible)
            val gpsLocation = originalUri?.let { uri ->
                try {
                    LocationUtils.extractGpsLocation(context.contentResolver, uri)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not extract GPS from $uri: ${e.message}")
                    null
                }
            }

            if (gpsLocation != null) {
                Log.d(TAG, "GPS data found: lat=${gpsLocation.latitude}, lon=${gpsLocation.longitude}")
            } else {
                Log.d(TAG, "No GPS data available for this file")
            }

            // Crear el multipart body con el MIME type correcto
            val mimeType = getMimeTypeFromFile(file, mediaItem.type.name.lowercase())
            Log.d(TAG, "Uploading file ${file.name} with MIME type: $mimeType")
            val requestBody = file.asRequestBody(mimeType.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestBody)

            // Metadata en JSON con hash y GPS incluidos
            val dateTakenJson = mediaItem.dateTaken?.toString() ?: "null"
            val dateAddedJson = mediaItem.dateAdded?.toString() ?: "null"

            // Construir JSON de metadata incluyendo GPS si está disponible
            val metadata = buildMetadataJson(
                type = mediaItem.type.name.lowercase(),
                dateTaken = dateTakenJson,
                dateModified = mediaItem.dateModified,
                dateAdded = dateAddedJson,
                hash = fileHash,
                gpsLocation = gpsLocation
            ).toRequestBody("application/json".toMediaTypeOrNull())

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

                // Eliminar el registro de sincronización local
                syncedMediaDao.deleteByCloudId(mediaId)
                Log.d(TAG, "Local sync record deleted for: $mediaId")

                // Limpiar caché para reflejar el cambio
                clearSyncCache()

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
        val failedCount: Int = 0,
        val wasCancelled: Boolean = false
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
        // Resetear flag de cancelación al inicio de una nueva sincronización
        isCancelled = false

        try {
            Log.d(TAG, "[BATCH_SYNC] Starting batch sync for ${localMediaItems.size} items")

            // Verificar autenticación
            if (!authManager.isAuthenticated()) {
                Log.d(TAG, "[BATCH_SYNC] User not authenticated")
                return@withContext Result.failure(Exception("User not authenticated"))
            }

            val token = authManager.getToken() ?: return@withContext Result.failure(Exception("No auth token"))

            _syncStatus.value = SyncStatus.PENDING

            // Verificar cancelación antes de continuar
            if (isCancelled) {
                Log.d(TAG, "[BATCH_SYNC] Cancelled before getting synced items")
                return@withContext Result.success(BatchSyncResult(
                    alreadySynced = emptyMap(),
                    needsUpload = emptyList(),
                    wasCancelled = true
                ))
            }

            // 0. Obtener URIs sincronizados localmente para referencia
            Log.d(TAG, "[BATCH_SYNC] Step 0: Getting locally synced items for reference...")
            val alreadySyncedUris = syncedMediaDao.getAllSyncedUris().toSet()
            val alreadySyncedCount = localMediaItems.count { it.uri in alreadySyncedUris }

            Log.d(TAG, "[BATCH_SYNC] Locally marked as synced: $alreadySyncedCount, total items: ${localMediaItems.size}")

            // IMPORTANTE: Verificamos TODOS los archivos con el servidor, no solo los nuevos
            // Esto permite detectar cuando un archivo fue eliminado del servidor
            val itemsToCheck = localMediaItems

            // 1. Obtener hashes cacheados y calcular solo los que faltan
            Log.d(TAG, "[BATCH_SYNC] Step 1: Getting cached hashes and calculating missing ones...")
            val contentResolver = context.contentResolver
            val uriStrings = itemsToCheck.map { it.uri }

            // 1a. Buscar hashes desde SyncedMediaEntity (archivos previamente sincronizados)
            val syncedMediaHashes = syncedMediaDao.getByUris(uriStrings)
                .associate { it.localUri to it.hash }
            Log.d(TAG, "[BATCH_SYNC] Found ${syncedMediaHashes.size} hashes from synced media")

            // 1b. Buscar hashes ya calculados en MediaItemDao
            val cachedHashPairs = mediaItemDao.getCachedHashes(uriStrings)
            val cachedHashes = cachedHashPairs.associate { it.uri to it.hash }
            Log.d(TAG, "[BATCH_SYNC] Found ${cachedHashes.size} cached hashes from media items")

            // 1c. Combinar hashes de ambas fuentes (syncedMedia tiene prioridad)
            val allCachedHashes = cachedHashes + syncedMediaHashes
            Log.d(TAG, "[BATCH_SYNC] Total cached hashes: ${allCachedHashes.size}")

            // 1d. Identificar URIs que necesitan calcular hash (no están en ninguna de las fuentes)
            val urisWithHash = allCachedHashes.keys
            val urisNeedingHash = uriStrings.filter { it !in urisWithHash }
            Log.d(TAG, "[BATCH_SYNC] Need to calculate hash for ${urisNeedingHash.size} items")

            // Verificar cancelación antes de calcular hashes (operación costosa)
            if (isCancelled) {
                Log.d(TAG, "[BATCH_SYNC] Cancelled before calculating hashes")
                _syncStatus.value = SyncStatus.PENDING
                return@withContext Result.success(BatchSyncResult(
                    alreadySynced = emptyMap(),
                    needsUpload = emptyList(),
                    wasCancelled = true
                ))
            }

            // 1e. Calcular hashes solo para los que faltan
            val newlyCalculatedHashes = if (urisNeedingHash.isNotEmpty()) {
                val urisToCalculate = urisNeedingHash.mapNotNull {
                    try {
                        it.toUri()
                    } catch (e: Exception) {
                        Log.w(TAG, "[BATCH_SYNC] Invalid URI: $it")
                        null
                    }
                }

                val calculated = HashUtils.calculateHashesForUris(
                    contentResolver = contentResolver,
                    uris = urisToCalculate,
                    onProgress = { progress ->
                        _syncProgress.value = progress * 0.4f // 0-40% para cálculo de hashes
                    },
                    isCancelled = { isCancelled } // Pasar función para verificar cancelación
                )

                // Verificar si fue cancelado durante el cálculo
                if (isCancelled) {
                    Log.d(TAG, "[BATCH_SYNC] Cancelled during hash calculation")
                    _syncStatus.value = SyncStatus.PENDING
                    return@withContext Result.success(BatchSyncResult(
                        alreadySynced = emptyMap(),
                        needsUpload = emptyList(),
                        wasCancelled = true
                    ))
                }

                // Guardar los nuevos hashes en la BD para futuras sincronizaciones
                if (calculated.isNotEmpty()) {
                    Log.d(TAG, "[BATCH_SYNC] Saving ${calculated.size} newly calculated hashes to cache")
                    mediaItemDao.updateHashes(calculated)
                }

                calculated
            } else {
                emptyMap()
            }
            Log.d(TAG, "[BATCH_SYNC] Calculated ${newlyCalculatedHashes.size} new hashes")

            // 1e. Combinar hashes cacheados + nuevos
            val hashesMap = allCachedHashes + newlyCalculatedHashes
            Log.d(TAG, "[BATCH_SYNC] Total hashes: ${hashesMap.size} (${allCachedHashes.size} cached + ${newlyCalculatedHashes.size} new)")

            _syncProgress.value = 0.45f // 45% - hashes listos

            if (hashesMap.isEmpty()) {
                Log.d(TAG, "[BATCH_SYNC] No valid hashes calculated")
                _syncStatus.value = SyncStatus.SYNCED
                _syncProgress.value = 1f
                return@withContext Result.success(BatchSyncResult(
                    alreadySynced = emptyMap(),
                    needsUpload = emptyList()
                ))
            }

            // Verificar cancelación antes de enviar al servidor
            if (isCancelled) {
                Log.d(TAG, "[BATCH_SYNC] Cancelled before sending hashes to server")
                _syncStatus.value = SyncStatus.PENDING
                return@withContext Result.success(BatchSyncResult(
                    alreadySynced = emptyMap(),
                    needsUpload = emptyList(),
                    wasCancelled = true
                ))
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
            val alreadySyncedFromServer = syncResponse?.alreadySynced ?: emptyMap()
            val needsUpload = syncResponse?.needsUpload ?: emptyList()

            Log.d(TAG, "[BATCH_SYNC] Server response - already_synced: ${alreadySyncedFromServer.size}, needs_upload: ${needsUpload.size}")

            if (syncResponse?.filters != null) {
                Log.d(TAG, "[BATCH_SYNC] Filters: requested=${syncResponse.filters.syncRequested}, matched=${syncResponse.filters.syncMatched}, pending=${syncResponse.filters.syncPending}")
            }

            // Verificar cancelación después de recibir respuesta del servidor
            if (isCancelled) {
                Log.d(TAG, "[BATCH_SYNC] Cancelled after server response")
                _syncStatus.value = SyncStatus.PENDING
                return@withContext Result.success(BatchSyncResult(
                    alreadySynced = alreadySyncedFromServer,
                    needsUpload = needsUpload,
                    wasCancelled = true
                ))
            }

            // 3. Limpiar registros de sincronización obsoletos
            // Si el servidor dice que un archivo necesita subirse, significa que ya no existe en el servidor
            // Por lo tanto, debemos eliminar el registro de sincronización local si existe
            if (needsUpload.isNotEmpty()) {
                Log.d(TAG, "[BATCH_SYNC] Step 3a: Cleaning obsolete sync records for ${needsUpload.size} items...")
                cleanObsoleteSyncRecords(needsUpload)
            }

            // 4. Guardar already_synced en Room
            Log.d(TAG, "[BATCH_SYNC] Step 4: Saving synced items to local database...")
            saveSyncedItems(alreadySyncedFromServer, hashesMap)

            _syncProgress.value = 0.95f // 95% completado

            // 5. Subir archivos pendientes si autoUpload está habilitado
            var uploadedCount = 0
            var failedCount = 0
            var wasCancelled = isCancelled // Verificar si ya está cancelado

            if (autoUpload && needsUpload.isNotEmpty() && !isCancelled) {
                Log.d(TAG, "[BATCH_SYNC] Step 5: Auto-uploading ${needsUpload.size} pending files...")
                val uploadResult = uploadPendingMedia(needsUpload, itemsToCheck, hashesMap)
                uploadedCount = uploadResult.uploadedCount
                failedCount = uploadResult.failedCount
                wasCancelled = uploadResult.wasCancelled || isCancelled
            }

            _syncStatus.value = if (wasCancelled || isCancelled) SyncStatus.PENDING else SyncStatus.SYNCED
            _syncProgress.value = 1f

            // Combinar items sincronizados localmente (por conteo) + items sincronizados desde servidor
            // alreadySyncedFromServer ya contiene los nuevos, alreadySyncedCount tiene los previos
            val totalSyncedCount = alreadySyncedCount + alreadySyncedFromServer.size

            val finalWasCancelled = wasCancelled || isCancelled
            Log.d(TAG, "[BATCH_SYNC] Sync completed - locally synced: $alreadySyncedCount, server synced: ${alreadySyncedFromServer.size}, total: $totalSyncedCount, uploaded: $uploadedCount, failed: $failedCount, cancelled: $finalWasCancelled")

            Result.success(BatchSyncResult(
                alreadySynced = alreadySyncedFromServer, // Solo reportamos los nuevos sincronizados en esta sesión
                needsUpload = needsUpload,
                uploadedCount = uploadedCount,
                failedCount = failedCount,
                wasCancelled = finalWasCancelled
            ))

        } catch (e: Exception) {
            Log.e(TAG, "[BATCH_SYNC] Error during batch sync", e)
            _syncStatus.value = SyncStatus.ERROR
            Result.failure(e)
        }
    }

    /**
     * Resultado de la subida de archivos pendientes
     */
    data class UploadBatchResult(
        val uploadedCount: Int,
        val failedCount: Int,
        val cancelledCount: Int,
        val wasCancelled: Boolean
    )

    /**
     * Sube los archivos pendientes a la nube con reintentos
     * @return UploadBatchResult con conteos de subidos, fallidos y cancelados
     */
    private suspend fun uploadPendingMedia(
        needsUpload: List<String>,
        localMediaItems: List<MediaItem>,
        hashesMap: Map<String, String>
    ): UploadBatchResult {
        var uploadedCount = 0
        var failedCount = 0
        var cancelledCount = 0
        val totalToUpload = needsUpload.size
        val failedItems = mutableListOf<UploadFailure>()

        _syncStatus.value = SyncStatus.UPLOADING

        // Inicializar progreso detallado de subida
        _uploadProgressInfo.value = UploadProgressInfo(
            currentIndex = 0,
            totalCount = totalToUpload
        )

        for ((index, uriString) in needsUpload.withIndex()) {
            // Verificar si se solicitó cancelación
            if (isCancelled) {
                cancelledCount = totalToUpload - index
                Log.d(TAG, "[UPLOAD] Cancelled. Remaining: $cancelledCount files")
                break
            }

            // Actualizar progreso detallado antes de subir
            _uploadProgressInfo.value = UploadProgressInfo(
                currentIndex = index + 1,
                totalCount = totalToUpload
            )

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

        // Resetear progreso detallado al finalizar
        _uploadProgressInfo.value = UploadProgressInfo()

        if (failedItems.isNotEmpty()) {
            Log.w(TAG, "[UPLOAD] Failed uploads summary: ${failedItems.size} items failed")
            failedItems.forEach { failure ->
                Log.w(TAG, "[UPLOAD] - ${failure.uri}: ${failure.error}")
            }
        }

        return UploadBatchResult(
            uploadedCount = uploadedCount,
            failedCount = failedCount,
            cancelledCount = cancelledCount,
            wasCancelled = isCancelled
        )
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
            uriString.toUri()
        } catch (e: Exception) {
            return UploadResult.Skipped("Invalid URI format")
        }

        var lastError: String = "Unknown error"
        val existingHash = hashesMap[uriString]

        repeat(maxRetries) { attempt ->
            val tempFile = createTempFileFromUri(uri, mediaItem.type.name.lowercase())

            if (tempFile == null) {
                lastError = "Could not create temp file"
                Log.w(TAG, "[UPLOAD] Attempt ${attempt + 1}/$maxRetries - Failed to create temp file for: $uriString")
                return@repeat
            }

            try {
                Log.d(TAG, "[UPLOAD] Attempt ${attempt + 1}/$maxRetries for: $uriString")

                // Pasar el hash existente y la URI original para extraer GPS
                val result = uploadMedia(mediaItem, tempFile, existingHash, uri)

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
     * Elimina los registros de sincronización para URIs que el servidor indica que necesitan subirse.
     * Esto ocurre cuando un archivo fue eliminado del servidor pero aún existe localmente como "sincronizado".
     */
    private suspend fun cleanObsoleteSyncRecords(needsUploadUris: List<String>) {
        var deletedCount = 0

        for (uri in needsUploadUris) {
            // Verificar si existe un registro de sincronización para esta URI
            val syncedMedia = syncedMediaDao.getByUri(uri)
            if (syncedMedia != null) {
                // El archivo está marcado como sincronizado localmente, pero el servidor dice que necesita subirse
                // Esto significa que fue eliminado del servidor, así que limpiamos el registro local
                Log.d(TAG, "[BATCH_SYNC] Removing obsolete sync record for: $uri (was cloudId: ${syncedMedia.cloudId})")
                syncedMediaDao.deleteByUri(uri)
                deletedCount++
            }
        }

        if (deletedCount > 0) {
            Log.d(TAG, "[BATCH_SYNC] Cleaned $deletedCount obsolete sync records")
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

    /**
     * Determina el tipo MIME correcto del archivo.
     * Intenta obtenerlo de la extensión del archivo,
     * y si no es posible, usa un tipo predeterminado basado en el tipo de media.
     */
    private fun getMimeTypeFromFile(file: File, mediaType: String): String {
        // 1. Intentar obtener de la extensión del archivo
        val extension = file.extension.lowercase()
        if (extension.isNotBlank()) {
            val mimeFromExtension = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            if (!mimeFromExtension.isNullOrBlank()) {
                return mimeFromExtension
            }
        }

        // 2. Usar tipo predeterminado basado en el tipo de media
        return when (mediaType) {
            "image" -> "image/jpeg"
            "video" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }

    /**
     * Construye el JSON de metadata para el upload, incluyendo datos GPS si están disponibles.
     */
    private fun buildMetadataJson(
        type: String,
        dateTaken: String,
        dateModified: Long,
        dateAdded: String,
        hash: String?,
        gpsLocation: LocationUtils.GpsLocation?
    ): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"type\":\"$type\",")
        sb.append("\"date_taken\":$dateTaken,")
        sb.append("\"date_modified\":$dateModified,")
        sb.append("\"date_added\":$dateAdded,")
        sb.append("\"hash\":\"$hash\"")

        // Agregar datos GPS si están disponibles
        if (gpsLocation != null) {
            sb.append(",\"latitude\":${gpsLocation.latitude}")
            sb.append(",\"longitude\":${gpsLocation.longitude}")

            gpsLocation.altitude?.let { altitude ->
                sb.append(",\"altitude\":$altitude")
            }

            gpsLocation.timestamp?.let { timestamp ->
                sb.append(",\"gps_timestamp\":\"$timestamp\"")
            }
        }

        sb.append("}")
        return sb.toString()
    }
}
