package com.example.galerio.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.galerio.data.local.dao.SyncedMediaDao
import com.example.galerio.data.local.entity.SyncedMediaEntity
import com.example.galerio.data.model.MediaItem
import com.example.galerio.data.model.MediaType
import com.example.galerio.data.repository.CloudSyncRepository
import com.example.galerio.utils.HashUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.core.net.toUri

/**
 * ViewModel para manejar la subida individual de archivos
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncRepository: CloudSyncRepository,
    private val syncedMediaDao: SyncedMediaDao
) : ViewModel() {

    companion object {
        private const val TAG = "UploadViewModel"
    }

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _uploadSuccess = MutableStateFlow(false)
    val uploadSuccess: StateFlow<Boolean> = _uploadSuccess.asStateFlow()

    private val _uploadError = MutableStateFlow<String?>(null)
    val uploadError: StateFlow<String?> = _uploadError.asStateFlow()

    private val _uploadProgress = MutableStateFlow(0f)
    val uploadProgress: StateFlow<Float> = _uploadProgress.asStateFlow()

    /**
     * Sube un archivo individual a la nube
     * @param uriString URI del archivo a subir
     */
    fun uploadSingleMedia(uriString: String) {
        viewModelScope.launch {
            _isUploading.value = true
            _uploadError.value = null
            _uploadSuccess.value = false
            _uploadProgress.value = 0f

            try {
                Log.d(TAG, "Starting upload for: $uriString")

                // Verificar si ya está sincronizado
                if (syncedMediaDao.isSynced(uriString)) {
                    Log.d(TAG, "File already synced: $uriString")
                    _uploadSuccess.value = true
                    _isUploading.value = false
                    return@launch
                }

                // Determinar el tipo de media basándose en el MIME type
                val mediaType = withContext(Dispatchers.IO) {
                    val mimeType = context.contentResolver.getType(uriString.toUri())
                    when {
                        mimeType?.startsWith("video/") == true -> MediaType.Video
                        else -> MediaType.Image
                    }
                }

                // Crear MediaItem
                val mediaItem = MediaItem(
                    uri = uriString,
                    type = mediaType,
                    dateModified = System.currentTimeMillis()
                )

                _uploadProgress.value = 0.2f

                // Calcular hash del archivo
                val hash = withContext(Dispatchers.IO) {
                    try {
                        HashUtils.calculateSHA256(context.contentResolver, uriString.toUri())
                    } catch (e: Exception) {
                        Log.e(TAG, "Error calculating hash", e)
                        null
                    }
                }

                _uploadProgress.value = 0.4f

                // Crear archivo temporal y subir
                val tempFile = withContext(Dispatchers.IO) {
                    createTempFileFromUri(uriString, mediaType.name.lowercase())
                }

                if (tempFile == null) {
                    _uploadError.value = "No se pudo preparar el archivo para subir"
                    _isUploading.value = false
                    return@launch
                }

                _uploadProgress.value = 0.6f

                try {
                    val result = syncRepository.uploadMedia(mediaItem, tempFile)

                    if (result.isSuccess) {
                        val cloudItem = result.getOrNull()
                        if (cloudItem != null) {
                            // Guardar en Room
                            syncedMediaDao.insert(
                                SyncedMediaEntity(
                                    localUri = uriString,
                                    cloudId = cloudItem.id,
                                    hash = hash ?: "",
                                    syncedAt = System.currentTimeMillis()
                                )
                            )
                            Log.d(TAG, "Upload successful: $uriString -> ${cloudItem.id}")
                            _uploadSuccess.value = true
                            _uploadProgress.value = 1f
                        } else {
                            _uploadError.value = "Error: respuesta vacía del servidor"
                        }
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "Error desconocido"
                        Log.e(TAG, "Upload failed: $error")
                        _uploadError.value = error
                    }
                } finally {
                    // Limpiar archivo temporal
                    tempFile.delete()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Upload error", e)
                _uploadError.value = e.message ?: "Error al subir el archivo"
            } finally {
                _isUploading.value = false
            }
        }
    }

    /**
     * Crea un archivo temporal desde un URI
     */
    private fun createTempFileFromUri(uriString: String, mediaType: String): java.io.File? {
        return try {
            val uri = uriString.toUri()
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null

            // Obtener el MIME type real del archivo
            val mimeType = context.contentResolver.getType(uri)

            // Determinar la extensión correcta
            val extension = when {
                mimeType?.startsWith("image/jpeg") == true -> ".jpg"
                mimeType?.startsWith("image/png") == true -> ".png"
                mimeType?.startsWith("image/gif") == true -> ".gif"
                mimeType?.startsWith("image/webp") == true -> ".webp"
                mimeType?.startsWith("image/heic") == true -> ".heic"
                mimeType?.startsWith("image/heif") == true -> ".heif"
                mimeType?.startsWith("video/mp4") == true -> ".mp4"
                mimeType?.startsWith("video/3gpp") == true -> ".3gp"
                mimeType?.startsWith("video/webm") == true -> ".webm"
                mimeType?.startsWith("video/quicktime") == true -> ".mov"
                mimeType?.startsWith("video/x-matroska") == true -> ".mkv"
                mimeType?.startsWith("video/") == true -> ".mp4"
                mimeType?.startsWith("image/") == true -> ".jpg"
                mediaType == "video" -> ".mp4"
                else -> ".jpg"
            }

            // Intentar obtener el nombre original del archivo
            var originalName: String? = null
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        originalName = cursor.getString(nameIndex)
                    }
                }
            }

            // Crear nombre del archivo temporal
            val fileName = originalName?.substringBeforeLast(".")
                ?: "upload_${System.currentTimeMillis()}"

            val tempFile = java.io.File.createTempFile(fileName, extension, context.cacheDir)
            tempFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            inputStream.close()

            Log.d(TAG, "Created temp file: ${tempFile.name} (mimeType: $mimeType)")
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Error creating temp file from URI: $uriString", e)
            null
        }
    }

    /**
     * Limpia el estado después de mostrar resultado
     */
    fun clearState() {
        _uploadSuccess.value = false
        _uploadError.value = null
        _uploadProgress.value = 0f
    }
}
