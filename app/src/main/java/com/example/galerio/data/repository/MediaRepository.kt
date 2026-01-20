package com.example.galerio.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.example.galerio.data.local.dao.MediaItemDao
import com.example.galerio.data.local.dao.SyncedMediaDao
import com.example.galerio.data.local.mapper.toEntityList
import com.example.galerio.data.local.mapper.toMediaItemList
import com.example.galerio.data.model.MediaItem
import com.example.galerio.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

open class MediaRepository(
    private val context: Context?,
    private val mediaItemDao: MediaItemDao?,
    private val cloudSyncRepository: CloudSyncRepository,
    private val syncedMediaDao: SyncedMediaDao? = null
) {

    companion object {
        private const val TAG = "MediaRepository"
        private const val CACHE_VALIDITY_HOURS = 24 // Caché válido por 24 horas
    }

    /**
     * Obtiene todos los medios del dispositivo con estrategia cache-first
     * 1. Intenta cargar desde caché
     * 2. Si el caché está vacío o desactualizado, carga desde MediaStore
     * 3. Guarda en caché para próximas cargas
     */
    open suspend fun getDeviceMedia(): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            // Cargar medios locales y de la nube en paralelo
            val localMediaDeferred = async { loadLocalMedia() }
            val cloudMediaDeferred = async { cloudSyncRepository.syncWithCloud() }

            val localMediaResult = localMediaDeferred.await()
            val cloudMediaResult = cloudMediaDeferred.await()

            // Si falla la carga local, considerar un error crítico (depende de la política de la app)
            if (localMediaResult.isFailure) {
                val exception = localMediaResult.exceptionOrNull() ?: Exception("Unknown error loading local media")
                Log.e(TAG, "Critical failure loading local media", exception)
                return@withContext Result.failure(exception)
            }

            // Manejar error de nube (no crítico, podemos mostrar lo local)
            cloudMediaResult.exceptionOrNull()?.let {
                Log.e(TAG, "Error loading cloud media (non-critical)", it)
            }

            val localMedia = localMediaResult.getOrNull() ?: emptyList()
            val cloudMedia = cloudMediaResult.getOrNull() ?: emptyList()

            // Obtener los cloudIds de archivos que ya están sincronizados localmente
            // para filtrar duplicados (evitar mostrar local + cloud del mismo archivo)
            val syncedCloudIds = syncedMediaDao?.getAllSyncedList()
                ?.map { it.cloudId }
                ?.toSet() ?: emptySet()

            // Filtrar los elementos de la nube que ya tienen versión local sincronizada
            val filteredCloudMedia = cloudMedia.filter { cloudItem ->
                val cloudId = cloudItem.cloudId
                val isAlreadySyncedLocally = cloudId != null && syncedCloudIds.contains(cloudId)
                if (isAlreadySyncedLocally) {
                    Log.d(TAG, "Filtering out cloud item (already synced locally): $cloudId")
                }
                !isAlreadySyncedLocally
            }

            // Combinar las listas
            val combinedList = mutableListOf<MediaItem>()
            combinedList.addAll(localMedia)
            combinedList.addAll(filteredCloudMedia)

            // Ordenar por fecha y eliminar duplicados si es necesario (ej. por URI)
            val sortedList = combinedList.sortedByDescending { it.dateModified }
            val distinctList = sortedList.distinctBy { it.uri }


            Log.d(TAG, "Total media loaded: ${distinctList.size} (Local: ${localMedia.size}, Cloud: ${cloudMedia.size}, Filtered cloud: ${filteredCloudMedia.size})")
            Result.success(distinctList)

        } catch (e: Exception) {
            Log.e(TAG, "Error getting all media", e)
            Result.failure(e)
        }
    }

    /**
     * Obtiene solo las imágenes del dispositivo
     */
    open suspend fun getImages(): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            val images = context?.let { loadImages(it) } ?: emptyList()
            Result.success(images)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting images", e)
            Result.failure(e)
        }
    }

    /**
     * Obtiene solo los videos del dispositivo
     */
    open suspend fun getVideos(): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            val videos = context?.let { loadVideos(it) } ?: emptyList()
            Result.success(videos)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting videos", e)
            Result.failure(e)
        }
    }

    /**
     * Carga los medios locales desde la base de datos (caché) o MediaStore.
     */
    private suspend fun loadLocalMedia(): Result<List<MediaItem>> {
        try {
            val cachedItems = mediaItemDao?.getAllMedia()?.first()

            if (cachedItems != null && cachedItems.isNotEmpty()) {
                Log.d(TAG, "Loading ${cachedItems.size} items from cache")
                return Result.success(cachedItems.toMediaItemList())
            }

            Log.d(TAG, "Cache empty, loading from MediaStore")
            val mediaList = mutableListOf<MediaItem>()
            context?.let {
                mediaList.addAll(loadImages(it))
                mediaList.addAll(loadVideos(it))
            }

            val sortedList = mediaList.sortedByDescending { it.dateModified }
            // Usar upsert que preserva hashes (por si acaso hay alguno de una sesión anterior)
            mediaItemDao?.upsertAllPreservingHashes(sortedList.toEntityList())

            Log.d(TAG, "Loaded ${sortedList.size} local items from MediaStore")
            return Result.success(sortedList)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading local media", e)
            return Result.failure(e)
        }
    }


    /**
     * Fuerza una recarga desde MediaStore (preservando hashes cacheados)
     */
    open suspend fun forceRefresh(): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Force refresh: reloading from MediaStore (preserving hashes)")

            // NO eliminamos el caché para preservar los hashes
            // Solo limpiamos el caché de sincronización de nube
            cloudSyncRepository.clearSyncCache()

            // Recargar desde MediaStore
            val mediaList = mutableListOf<MediaItem>()
            context?.let {
                mediaList.addAll(loadImages(it))
                mediaList.addAll(loadVideos(it))
            }

            val sortedList = mediaList.sortedByDescending { it.dateModified }

            // Usar upsert que preserva los hashes existentes
            mediaItemDao?.upsertAllPreservingHashes(sortedList.toEntityList())

            // Eliminar items que ya no existen en MediaStore
            val currentUris = sortedList.map { it.uri }
            if (currentUris.isNotEmpty()) {
                mediaItemDao?.deleteNotIn(currentUris)
            }

            Log.d(TAG, "Force refresh completed: ${sortedList.size} items (hashes preserved)")

            // Cargar los datos combinados (local + nube)
            getDeviceMedia()
        } catch (e: Exception) {
            Log.e(TAG, "Error during force refresh", e)
            Result.failure(e)
        }
    }


    /**
     * Carga las imágenes desde MediaStore
     */
    private fun loadImages(context: Context): List<MediaItem> {
        val mediaList = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateTakenColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val dateAddedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateTaken = if (dateTakenColumn >= 0) cursor.getLong(dateTakenColumn).takeIf { it > 0 } else null
                val dateModified = cursor.getLong(dateModifiedColumn)
                val dateAdded = if (dateAddedColumn >= 0) cursor.getLong(dateAddedColumn).takeIf { it > 0 } else null
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                mediaList.add(
                    MediaItem(
                        uri = contentUri.toString(),
                        type = MediaType.Image,
                        dateTaken = dateTaken,
                        dateModified = dateModified * 1000,
                        dateAdded = dateAdded?.let { it * 1000 }
                    )
                )
            }
        }
        return mediaList
    }

    /**
     * Carga los videos desde MediaStore
     */
    private fun loadVideos(context: Context): List<MediaItem> {
        val mediaList = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateTakenColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val dateAddedColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateTaken = if (dateTakenColumn >= 0) cursor.getLong(dateTakenColumn).takeIf { it > 0 } else null
                val dateModified = cursor.getLong(dateModifiedColumn)
                val dateAdded = if (dateAddedColumn >= 0) cursor.getLong(dateAddedColumn).takeIf { it > 0 } else null
                val duration = cursor.getLong(durationColumn)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                mediaList.add(
                    MediaItem(
                        uri = contentUri.toString(),
                        type = MediaType.Video,
                        dateTaken = dateTaken,
                        dateModified = dateModified * 1000,
                        dateAdded = dateAdded?.let { it * 1000 },
                        duration = duration
                    )
                )
            }
        }
        return mediaList
    }
}
