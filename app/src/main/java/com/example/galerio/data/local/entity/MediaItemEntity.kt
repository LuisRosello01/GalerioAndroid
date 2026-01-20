package com.example.galerio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.example.galerio.data.local.converter.UriConverter
import com.example.galerio.data.model.MediaType

/**
 * Entidad de Room que representa un MediaItem en la base de datos local
 */
@Entity(tableName = "media_items")
@TypeConverters(UriConverter::class)
data class MediaItemEntity(
    @PrimaryKey
    val uri: String,
    val type: MediaType,
    val dateTaken: Long? = null,    // Fecha de captura (EXIF)
    val dateModified: Long,         // Fecha de modificación del archivo
    val dateAdded: Long? = null,    // Fecha añadida al dispositivo
    val relativePath: String?,
    val duration: Long?,
    val cachedAt: Long = System.currentTimeMillis(), // Timestamp del caché
    val hash: String? = null,       // SHA-256 hash para sincronización (cacheado)
    val hashCalculatedAt: Long? = null // Timestamp de cuándo se calculó el hash
)

