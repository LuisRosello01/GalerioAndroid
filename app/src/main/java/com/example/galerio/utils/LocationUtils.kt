package com.example.galerio.utils

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * Utilidad para extraer información de ubicación GPS de archivos multimedia.
 *
 * Usa ACCESS_MEDIA_LOCATION + MediaStore.setRequireOriginal para acceder
 * a los datos GPS originales en Android 10+.
 */
object LocationUtils {

    private const val TAG = "LocationUtils"

    /**
     * Datos de ubicación GPS extraídos del EXIF
     */
    data class GpsLocation(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double? = null,
        val timestamp: String? = null // GPS timestamp en formato EXIF
    ) {
        /**
         * Convierte a un mapa para incluir en JSON de metadatos
         */
        fun toMetadataMap(): Map<String, Any?> = mapOf(
            "latitude" to latitude,
            "longitude" to longitude,
            "altitude" to altitude,
            "gps_timestamp" to timestamp
        )
    }

    /**
     * Extrae la ubicación GPS de un archivo multimedia.
     *
     * En Android 10+ usa MediaStore.setRequireOriginal para acceder a los datos GPS originales.
     * Requiere el permiso ACCESS_MEDIA_LOCATION.
     *
     * @param contentResolver ContentResolver para acceder a los archivos
     * @param uri URI del archivo multimedia (content:// URI)
     * @return GpsLocation si se encontraron datos GPS válidos, null si no
     */
    fun extractGpsLocation(contentResolver: ContentResolver, uri: Uri): GpsLocation? {
        return try {
            // En Android 10+, usar setRequireOriginal para obtener los datos GPS
            val originalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.setRequireOriginal(uri)
            } else {
                uri
            }

            contentResolver.openInputStream(originalUri)?.use { inputStream ->
                extractGpsFromInputStream(inputStream)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException: Missing ACCESS_MEDIA_LOCATION permission for $uri", e)
            // Intentar sin setRequireOriginal (puede no tener datos GPS)
            tryExtractWithoutOriginal(contentResolver, uri)
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "setRequireOriginal not supported for $uri, trying without", e)
            tryExtractWithoutOriginal(contentResolver, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting GPS from $uri", e)
            null
        }
    }

    /**
     * Extrae GPS de un archivo File
     */
    fun extractGpsLocation(filePath: String): GpsLocation? {
        return try {
            val exif = ExifInterface(filePath)
            extractGpsFromExif(exif)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting GPS from file $filePath", e)
            null
        }
    }

    /**
     * Intenta extraer GPS sin usar setRequireOriginal
     */
    private fun tryExtractWithoutOriginal(contentResolver: ContentResolver, uri: Uri): GpsLocation? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                extractGpsFromInputStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting GPS without original from $uri", e)
            null
        }
    }

    /**
     * Extrae datos GPS de un InputStream usando ExifInterface
     */
    private fun extractGpsFromInputStream(inputStream: InputStream): GpsLocation? {
        return try {
            val exif = ExifInterface(inputStream)
            extractGpsFromExif(exif)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating ExifInterface from stream", e)
            null
        }
    }

    /**
     * Extrae datos GPS de un ExifInterface
     */
    @Suppress("DEPRECATION")
    private fun extractGpsFromExif(exif: ExifInterface): GpsLocation? {
        // Obtener latitud y longitud
        val latLong = FloatArray(2)
        val hasLatLong = exif.getLatLong(latLong)

        if (!hasLatLong) {
            Log.d(TAG, "No GPS coordinates found in EXIF")
            return null
        }

        val latitude = latLong[0].toDouble()
        val longitude = latLong[1].toDouble()

        // Obtener altitud (opcional)
        val altitude = exif.getAltitude(Double.NaN).takeIf { !it.isNaN() }

        // Obtener timestamp GPS (opcional)
        val gpsTimestamp = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)?.let { date ->
            val time = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP)
            if (time != null) "$date $time" else date
        }

        Log.d(TAG, "GPS found: lat=$latitude, lon=$longitude, alt=$altitude, timestamp=$gpsTimestamp")

        return GpsLocation(
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            timestamp = gpsTimestamp
        )
    }

    /**
     * Extrae todos los metadatos EXIF relevantes (no solo GPS)
     */
    fun extractExifMetadata(contentResolver: ContentResolver, uri: Uri): Map<String, Any?>? {
        return try {
            val originalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    MediaStore.setRequireOriginal(uri)
                } catch (e: Exception) {
                    uri
                }
            } else {
                uri
            }

            contentResolver.openInputStream(originalUri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                extractAllMetadataFromExif(exif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting EXIF metadata from $uri", e)
            null
        }
    }

    /**
     * Extrae todos los metadatos relevantes del EXIF
     */
    @Suppress("DEPRECATION")
    private fun extractAllMetadataFromExif(exif: ExifInterface): Map<String, Any?> {
        val metadata = mutableMapOf<String, Any?>()

        // GPS
        val latLong = FloatArray(2)
        if (exif.getLatLong(latLong)) {
            metadata["latitude"] = latLong[0].toDouble()
            metadata["longitude"] = latLong[1].toDouble()

            val altitude = exif.getAltitude(Double.NaN)
            if (!altitude.isNaN()) {
                metadata["altitude"] = altitude
            }
        }

        // Fecha y hora
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
            metadata["datetime_original"] = it
        }
        exif.getAttribute(ExifInterface.TAG_DATETIME)?.let {
            metadata["datetime"] = it
        }

        // Información de la cámara
        exif.getAttribute(ExifInterface.TAG_MAKE)?.let {
            metadata["camera_make"] = it
        }
        exif.getAttribute(ExifInterface.TAG_MODEL)?.let {
            metadata["camera_model"] = it
        }

        // Orientación
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        )
        if (orientation != ExifInterface.ORIENTATION_UNDEFINED) {
            metadata["orientation"] = orientation
        }

        // Dimensiones
        val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
        val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        if (width > 0 && height > 0) {
            metadata["width"] = width
            metadata["height"] = height
        }

        Log.d(TAG, "Extracted ${metadata.size} EXIF fields")
        return metadata
    }
}
