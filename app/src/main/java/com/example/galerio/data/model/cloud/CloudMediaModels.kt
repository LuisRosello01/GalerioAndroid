package com.example.galerio.data.model.cloud

import com.example.galerio.data.model.MediaType
import com.google.gson.annotations.SerializedName

/**
 * MediaItem en la nube (respuesta del servidor)
 */
data class CloudMediaItem(
    val id: String,

    // Información básica del archivo
    @SerializedName("original_name")
    val originalName: String,
    @SerializedName("stored_name")
    val storedName: String,
    val type: String, // Cambiado a String porque el servidor devuelve "image" o "video"
    val size: Long,
    val path: String,

    // Timestamps
    @SerializedName("last_modified")
    val lastModified: Long, // timestamp en milisegundos
    @SerializedName("uploaded_at")
    val uploadedAt: Long?, // timestamp en milisegundos

    // Thumbnail
    @SerializedName("has_thumbnail")
    val hasThumbnail: Boolean = false,

    // Contenido del usuario
    val description: String? = null,
    val tags: List<String>? = null,

    // Favoritos
    @SerializedName("is_favorite")
    val isFavorite: Boolean = false,

    // GPS (simplificado para la app)
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,

    // Estado de procesamiento
    @SerializedName("processing_status")
    val processingStatus: String? = "completed", // pending, processing, completed, failed

    // Papelera
    @SerializedName("is_deleted")
    val isDeleted: Boolean = false,

    // Metadatos adicionales (opcional, para videos principalmente)
    val duration: Long? = null, // duración en milisegundos (para videos)
    val width: Int? = null,
    val height: Int? = null
) {
    /**
     * Construye la URL completa para acceder al archivo
     * El servidor devuelve archivos en /media/download/access_file/<file_id>
     */
    fun getFileUrl(baseUrl: String = "https://galerio.es"): String {
        return "$baseUrl/media/download/access_file/$id"
    }

    /**
     * Construye la URL completa para acceder al thumbnail del archivo
     * El servidor devuelve thumbnails en /media/download/access_thumbnail/<file_id>
     */
    fun getThumbnailUrl(baseUrl: String = "https://galerio.es"): String {
        return "$baseUrl/media/download/access_thumbnail/$id"
    }

    /**
     * Convierte el tipo de string a MediaType enum
     */
    fun getMediaType(): MediaType {
        return when (type.lowercase()) {
            "image" -> MediaType.Image
            "video" -> MediaType.Video
            else -> MediaType.Image
        }
    }
}

/**
 * Request para subir un media a la nube
 */
data class UploadMediaRequest(
    @SerializedName("original_name")
    val originalName: String,
    val type: String, // "image" o "video"
    @SerializedName("last_modified")
    val lastModified: Long,
    val size: Long,
    val description: String? = null,
    val tags: List<String>? = null
)

/**
 * Response de subida de media
 */
data class UploadMediaResponse(
    val message: String?,
    @SerializedName("media_item")
    val mediaItem: CloudMediaItem?
)

/**
 * Estado de sincronización
 */
enum class SyncStatus {
    SYNCED,        // Sincronizado
    PENDING,       // Pendiente de subir
    UPLOADING,     // Subiendo
    DOWNLOADING,   // Descargando
    CONFLICT,      // Conflicto (versión local y nube diferentes)
    ERROR          // Error en la sincronización
}

/**
 * Response de listado de medios en la nube
 * Estructura de respuesta:
 * {
 *   "items": [...],
 *   "total_count": 2,
 *   "filters": {...}
 * }
 */
data class CloudMediaListResponse(
    @SerializedName("items")
    val items: List<CloudMediaItem>,

    @SerializedName("total_count")
    val totalCount: Int = 0,

    @SerializedName("filters")
    val filters: MediaFilters? = null
)

/**
 * Filtros aplicados en la respuesta de medios
 */
data class MediaFilters(
    @SerializedName("type")
    val type: String? = null,

    @SerializedName("include_deleted")
    val includeDeleted: Boolean? = null,

    @SerializedName("sort_by")
    val sortBy: String? = null
)
