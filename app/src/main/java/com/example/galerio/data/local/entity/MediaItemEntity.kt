package com.example.galerio.data.local.entity

import android.net.Uri
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
    val dateModified: Long,
    val relativePath: String?,
    val duration: Long?,
    val cachedAt: Long = System.currentTimeMillis() // Timestamp del caché
)

