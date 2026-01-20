package com.example.galerio.data.local.dao

import androidx.room.*
import com.example.galerio.data.local.entity.SyncedMediaEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO para operaciones de base de datos con SyncedMedia
 * Mantiene el mapping entre archivos locales y sus IDs en la nube
 */
@Dao
interface SyncedMediaDao {

    /**
     * Inserta o actualiza un registro de sincronización
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(syncedMedia: SyncedMediaEntity)

    /**
     * Inserta o actualiza múltiples registros de sincronización
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(syncedMediaList: List<SyncedMediaEntity>)

    /**
     * Obtiene un registro por URI local
     */
    @Query("SELECT * FROM synced_media WHERE localUri = :uri")
    suspend fun getByUri(uri: String): SyncedMediaEntity?

    /**
     * Obtiene un registro por cloudId
     */
    @Query("SELECT * FROM synced_media WHERE cloudId = :cloudId")
    suspend fun getByCloudId(cloudId: String): SyncedMediaEntity?

    /**
     * Obtiene todos los registros de sincronización
     */
    @Query("SELECT * FROM synced_media ORDER BY syncedAt DESC")
    fun getAllSynced(): Flow<List<SyncedMediaEntity>>

    /**
     * Obtiene todos los registros como lista (no Flow)
     */
    @Query("SELECT * FROM synced_media")
    suspend fun getAllSyncedList(): List<SyncedMediaEntity>

    /**
     * Verifica si un archivo local ya está sincronizado
     */
    @Query("SELECT EXISTS(SELECT 1 FROM synced_media WHERE localUri = :uri)")
    suspend fun isSynced(uri: String): Boolean

    /**
     * Verifica si un hash ya existe en la nube
     */
    @Query("SELECT EXISTS(SELECT 1 FROM synced_media WHERE hash = :hash)")
    suspend fun isHashSynced(hash: String): Boolean

    /**
     * Obtiene el cloudId para un URI local
     */
    @Query("SELECT cloudId FROM synced_media WHERE localUri = :uri")
    suspend fun getCloudIdForUri(uri: String): String?

    /**
     * Elimina un registro de sincronización
     */
    @Delete
    suspend fun delete(syncedMedia: SyncedMediaEntity)

    /**
     * Elimina un registro por URI
     */
    @Query("DELETE FROM synced_media WHERE localUri = :uri")
    suspend fun deleteByUri(uri: String)

    /**
     * Elimina un registro por cloudId
     */
    @Query("DELETE FROM synced_media WHERE cloudId = :cloudId")
    suspend fun deleteByCloudId(cloudId: String)

    /**
     * Elimina todos los registros
     */
    @Query("DELETE FROM synced_media")
    suspend fun deleteAll()

    /**
     * Obtiene el conteo de archivos sincronizados
     */
    @Query("SELECT COUNT(*) FROM synced_media")
    suspend fun getCount(): Int

    /**
     * Obtiene todos los URIs locales sincronizados (para filtrado eficiente)
     */
    @Query("SELECT localUri FROM synced_media")
    suspend fun getAllSyncedUris(): List<String>

    /**
     * Obtiene múltiples registros por lista de URIs
     */
    @Query("SELECT * FROM synced_media WHERE localUri IN (:uris)")
    suspend fun getByUris(uris: List<String>): List<SyncedMediaEntity>
}
