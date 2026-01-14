package com.example.galerio.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.example.galerio.data.local.dao.MediaItemDao
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
    private val cloudSyncRepository: CloudSyncRepository
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

            // Manejar posibles errores
            localMediaResult.exceptionOrNull()?.let { Log.e(TAG, "Error loading local media", it) }
            cloudMediaResult.exceptionOrNull()?.let { Log.e(TAG, "Error loading cloud media", it) }

            // Combinar las listas
            val combinedList = mutableListOf<MediaItem>()
            combinedList.addAll(localMediaResult.getOrNull() ?: emptyList())
            combinedList.addAll(cloudMediaResult.getOrNull() ?: emptyList())

            // Ordenar por fecha y eliminar duplicados si es necesario (ej. por URI)
            val sortedList = combinedList.sortedByDescending { it.dateModified }
            val distinctList = sortedList.distinctBy { it.uri }


            Log.d(TAG, "Total media loaded: ${distinctList.size} (Local: ${localMediaResult.getOrNull()?.size ?: 0}, Cloud: ${cloudMediaResult.getOrNull()?.size ?: 0})")
            Result.success(distinctList)

        } catch (e: Exception) {
            Log.e(TAG, "Error getting all media", e)
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
            mediaItemDao?.insertAll(sortedList.toEntityList())

            Log.d(TAG, "Loaded ${sortedList.size} local items from MediaStore")
            return Result.success(sortedList)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading local media", e)
            return Result.failure(e)
        }
    }


    /**
     * Fuerza una recarga desde MediaStore (ignora caché)
     */
    suspend fun forceRefresh(): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Force refresh: clearing cache and reloading")

            // Limpiar caché
            mediaItemDao?.deleteAll()
            cloudSyncRepository.clearSyncCache()

            // Recargar todo
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
            MediaStore.Images.Media.DATE_MODIFIED
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
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateModified = cursor.getLong(dateModifiedColumn)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                mediaList.add(
                    MediaItem(
                        uri = contentUri.toString(),
                        type = MediaType.Image,
                        dateModified = dateModified * 1000
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
            MediaStore.Video.Media.DATE_MODIFIED
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
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateModified = cursor.getLong(dateModifiedColumn)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                mediaList.add(
                    MediaItem(
                        uri = contentUri.toString(),
                        type = MediaType.Video,
                        dateModified = dateModified * 1000
                    )
                )
            }
        }
        return mediaList
    }
}
