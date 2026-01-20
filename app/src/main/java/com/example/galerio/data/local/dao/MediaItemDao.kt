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

    /**
     * Obtiene los hashes cacheados para una lista de URIs
     * Retorna solo los items que tienen hash calculado
     */
    @Query("SELECT uri, hash FROM media_items WHERE uri IN (:uris) AND hash IS NOT NULL")
    suspend fun getCachedHashes(uris: List<String>): List<UriHashPair>

    /**
     * Actualiza el hash de un MediaItem
     */
    @Query("UPDATE media_items SET hash = :hash, hashCalculatedAt = :calculatedAt WHERE uri = :uri")
    suspend fun updateHash(uri: String, hash: String, calculatedAt: Long = System.currentTimeMillis())

    /**
     * Actualiza los hashes de múltiples MediaItems
     */
    @Transaction
    suspend fun updateHashes(hashMap: Map<String, String>) {
        val now = System.currentTimeMillis()
        hashMap.forEach { (uri, hash) ->
            updateHash(uri, hash, now)
        }
    }

    /**
     * Obtiene URIs que necesitan calcular hash (hash es null o el archivo fue modificado después del cálculo)
     */
    @Query("SELECT uri FROM media_items WHERE uri IN (:uris) AND (hash IS NULL OR hashCalculatedAt IS NULL OR dateModified > hashCalculatedAt)")
    suspend fun getUrisNeedingHashCalculation(uris: List<String>): List<String>

    /**
     * Obtiene todos los hashes existentes (para preservar durante refresh)
     */
    @Query("SELECT uri, hash, hashCalculatedAt FROM media_items WHERE hash IS NOT NULL")
    suspend fun getAllHashData(): List<HashData>

    /**
     * Elimina items que ya no existen en MediaStore
     * @param currentUris URIs que actualmente existen en MediaStore
     */
    @Query("DELETE FROM media_items WHERE uri NOT IN (:currentUris)")
    suspend fun deleteNotIn(currentUris: List<String>)

    /**
     * Actualiza un MediaItem preservando el hash si existe
     */
    @Query("""
        UPDATE media_items 
        SET type = :type, dateTaken = :dateTaken, dateModified = :dateModified, 
            dateAdded = :dateAdded, relativePath = :relativePath, duration = :duration, 
            cachedAt = :cachedAt
        WHERE uri = :uri
    """)
    suspend fun updatePreservingHash(
        uri: String,
        type: MediaType,
        dateTaken: Long?,
        dateModified: Long,
        dateAdded: Long?,
        relativePath: String?,
        duration: Long?,
        cachedAt: Long
    )

    /**
     * Inserta o actualiza MediaItems preservando hashes existentes
     * Esta función hace upsert: actualiza si existe (preservando hash), inserta si no existe
     */
    @Transaction
    suspend fun upsertAllPreservingHashes(mediaItems: List<MediaItemEntity>) {
        // Obtener hashes existentes antes de la operación
        val existingHashes = getAllHashData().associate { it.uri to Pair(it.hash, it.hashCalculatedAt) }

        mediaItems.forEach { item ->
            val existing = getMediaByUri(item.uri)
            if (existing != null) {
                // Actualiza preservando el hash existente
                updatePreservingHash(
                    uri = item.uri,
                    type = item.type,
                    dateTaken = item.dateTaken,
                    dateModified = item.dateModified,
                    dateAdded = item.dateAdded,
                    relativePath = item.relativePath,
                    duration = item.duration,
                    cachedAt = item.cachedAt
                )
            } else {
                // Es nuevo, verificar si tenemos hash de un registro anterior (edge case)
                val previousHash = existingHashes[item.uri]
                val itemToInsert = if (previousHash != null) {
                    item.copy(hash = previousHash.first, hashCalculatedAt = previousHash.second)
                } else {
                    item
                }
                insert(itemToInsert)
            }
        }
    }
}

/**
 * Clase auxiliar para consultas de hash
 */
data class UriHashPair(
    val uri: String,
    val hash: String
)

/**
 * Clase auxiliar para datos completos de hash
 */
data class HashData(
    val uri: String,
    val hash: String,
    val hashCalculatedAt: Long?
)

