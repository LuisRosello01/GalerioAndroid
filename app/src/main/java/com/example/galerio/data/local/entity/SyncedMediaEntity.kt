package com.example.galerio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entidad de Room para almacenar el mapping entre archivos locales y la nube
 * Se usa para evitar re-sincronizar archivos que ya están en la nube
 */
@Entity(tableName = "synced_media")
data class SyncedMediaEntity(
    @PrimaryKey
    val localUri: String,

    val cloudId: String,

    val hash: String,

    val syncedAt: Long = System.currentTimeMillis()
)
