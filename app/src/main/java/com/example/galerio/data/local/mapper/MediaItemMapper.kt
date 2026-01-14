package com.example.galerio.data.local.mapper

import com.example.galerio.data.local.entity.MediaItemEntity
import com.example.galerio.data.model.MediaItem

/**
 * Extensiones para mapear entre MediaItem y MediaItemEntity
 */

fun MediaItem.toEntity(): MediaItemEntity {
    return MediaItemEntity(
        uri = this.uri,
        type = this.type,
        dateModified = this.dateModified,
        relativePath = this.relativePath,
        duration = this.duration
    )
}

fun MediaItemEntity.toMediaItem(): MediaItem {
    return MediaItem(
        uri = this.uri,
        type = this.type,
        dateModified = this.dateModified,
        relativePath = this.relativePath,
        duration = this.duration
    )
}

fun List<MediaItem>.toEntityList(): List<MediaItemEntity> {
    return this.map { it.toEntity() }
}

fun List<MediaItemEntity>.toMediaItemList(): List<MediaItem> {
    return this.map { it.toMediaItem() }
}
