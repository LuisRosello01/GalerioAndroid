package com.example.galerio.data.model

data class MediaItem(
    val uri: String,
    val type: MediaType,
    val dateTaken: Long? = null,    // Fecha de captura (EXIF)
    val dateModified: Long,         // Fecha de modificación del archivo
    val dateAdded: Long? = null,    // Fecha añadida al dispositivo
    val relativePath: String? = null,
    val duration: Long? = null, // Solo para videos
    val isCloudItem: Boolean = false, // Indica si el archivo está en la nube
    val cloudId: String? = null, // ID del archivo en la nube
    val hasThumbnail: Boolean = false, // Indica si el archivo tiene thumbnail
    val thumbnailUri: String? = null // URI del thumbnail (URL para cloud, URI local para archivos locales)
)
