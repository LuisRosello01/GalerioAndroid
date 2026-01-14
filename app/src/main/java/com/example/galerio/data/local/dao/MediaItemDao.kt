package com.example.galerio.data.local.dao

import androidx.room.*
import com.example.galerio.data.local.entity.MediaItemEntity
import com.example.galerio.data.model.MediaType
import kotlinx.coroutines.flow.Flow

/**
 * DAO para operaciones de base de datos con MediaItems
 */
@Dao
interface MediaItemDao {

    /**
     * Inserta o actualiza un MediaItem
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mediaItem: MediaItemEntity)

    /**
     * Inserta o actualiza múltiples MediaItems
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mediaItems: List<MediaItemEntity>)

    /**
     * Obtiene todos los MediaItems ordenados por fecha descendente
     */
    @Query("SELECT * FROM media_items ORDER BY dateModified DESC")
    fun getAllMedia(): Flow<List<MediaItemEntity>>

    /**
     * Obtiene solo las imágenes
     */
    @Query("SELECT * FROM media_items WHERE type = 'Image' ORDER BY dateModified DESC")
    fun getAllImages(): Flow<List<MediaItemEntity>>

    /**
     * Obtiene solo los videos
     */
    @Query("SELECT * FROM media_items WHERE type = 'Video' ORDER BY dateModified DESC")
    fun getAllVideos(): Flow<List<MediaItemEntity>>

    /**
     * Obtiene un MediaItem por URI
     */
    @Query("SELECT * FROM media_items WHERE uri = :uri")
    suspend fun getMediaByUri(uri: String): MediaItemEntity?

    /**
     * Elimina un MediaItem
     */
    @Delete
    suspend fun delete(mediaItem: MediaItemEntity)

    /**
     * Elimina todos los MediaItems
     */
    @Query("DELETE FROM media_items")
    suspend fun deleteAll()

    /**
     * Elimina MediaItems cacheados hace más de X días
     */
    @Query("DELETE FROM media_items WHERE cachedAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    /**
     * Obtiene el conteo de items en caché
     */
    @Query("SELECT COUNT(*) FROM media_items")
    suspend fun getCount(): Int

    /**
     * Busca MediaItems por ruta
     */
    @Query("SELECT * FROM media_items WHERE relativePath LIKE '%' || :path || '%' ORDER BY dateModified DESC")
    fun searchByPath(path: String): Flow<List<MediaItemEntity>>
}

