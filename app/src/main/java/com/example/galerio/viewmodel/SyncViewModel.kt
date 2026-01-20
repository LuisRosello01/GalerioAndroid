package com.example.galerio.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.galerio.data.model.MediaItem
import com.example.galerio.data.model.cloud.SyncStatus
import com.example.galerio.data.repository.CloudSyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado de la sincronización batch
 */
data class BatchSyncState(
    val isActive: Boolean = false,
    val alreadySyncedCount: Int = 0,
    val pendingUploadCount: Int = 0,
    val uploadedCount: Int = 0,
    val failedCount: Int = 0,
    val totalSyncedCount: Int = 0
)

/**
 * ViewModel para gestionar sincronización con la nube
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncRepository: CloudSyncRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SyncViewModel"
    }

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus.SYNCED)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _syncProgress = MutableStateFlow(0f)
    val syncProgress: StateFlow<Float> = _syncProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _batchSyncState = MutableStateFlow(BatchSyncState())
    val batchSyncState: StateFlow<BatchSyncState> = _batchSyncState.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    init {
        // Observar el estado de sincronización del repository
        viewModelScope.launch {
            syncRepository.syncStatus.collect { status ->
                _syncStatus.value = status
            }
        }

        viewModelScope.launch {
            syncRepository.syncProgress.collect { progress ->
                _syncProgress.value = progress
            }
        }

        // Cargar el conteo de archivos sincronizados al iniciar
        viewModelScope.launch {
            loadSyncedCount()
        }
    }

    /**
     * Sincronización simple (obtener lista de archivos de la nube)
     */
    fun startSync() {
        viewModelScope.launch {
            _isSyncing.value = true
            _error.value = null

            syncRepository.syncWithCloud()
                .onSuccess {
                    Log.d(TAG, "Sync completed successfully")
                }
                .onFailure { exception ->
                    _error.value = exception.message ?: "Sync failed"
                    Log.e(TAG, "Sync failed", exception)
                }

            _isSyncing.value = false
        }
    }

    /**
     * Sincronización batch con hashes - detecta qué archivos ya están en la nube
     * @param localMediaItems Lista de archivos locales a sincronizar
     * @param autoUpload Si es true, sube automáticamente los archivos pendientes
     */
    fun startBatchSync(localMediaItems: List<MediaItem>, autoUpload: Boolean = false) {
        viewModelScope.launch {
            _isSyncing.value = true
            _error.value = null
            _batchSyncState.value = BatchSyncState(isActive = true)

            Log.d(TAG, "Starting batch sync for ${localMediaItems.size} items, autoUpload=$autoUpload")

            syncRepository.syncBatch(localMediaItems, autoUpload)
                .onSuccess { result ->
                    Log.d(TAG, "Batch sync completed: ${result.alreadySynced.size} synced, ${result.needsUpload.size} pending")

                    _batchSyncState.value = BatchSyncState(
                        isActive = false,
                        alreadySyncedCount = result.alreadySynced.size,
                        pendingUploadCount = result.needsUpload.size,
                        uploadedCount = result.uploadedCount,
                        failedCount = result.failedCount,
                        totalSyncedCount = syncRepository.getSyncedMediaCount()
                    )

                    val message = buildSyncResultMessage(result)
                    _successMessage.value = message
                }
                .onFailure { exception ->
                    _error.value = exception.message ?: "Batch sync failed"
                    _batchSyncState.value = BatchSyncState(isActive = false)
                    Log.e(TAG, "Batch sync failed", exception)
                }

            _isSyncing.value = false
        }
    }

    /**
     * Construye un mensaje descriptivo del resultado de la sincronización
     */
    private fun buildSyncResultMessage(result: CloudSyncRepository.BatchSyncResult): String {
        val parts = mutableListOf<String>()

        if (result.alreadySynced.isNotEmpty()) {
            parts.add("${result.alreadySynced.size} archivos ya sincronizados")
        }

        if (result.uploadedCount > 0) {
            parts.add("${result.uploadedCount} archivos subidos")
        }

        if (result.failedCount > 0) {
            parts.add("${result.failedCount} fallidos")
        }

        if (result.needsUpload.isNotEmpty() && result.uploadedCount == 0) {
            parts.add("${result.needsUpload.size} archivos pendientes de subir")
        }

        return if (parts.isEmpty()) {
            "Sincronización completada"
        } else {
            parts.joinToString(", ")
        }
    }

    /**
     * Carga el conteo de archivos sincronizados
     */
    private suspend fun loadSyncedCount() {
        try {
            val count = syncRepository.getSyncedMediaCount()
            _batchSyncState.value = _batchSyncState.value.copy(totalSyncedCount = count)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading synced count", e)
        }
    }

    /**
     * Refresca el conteo de archivos sincronizados
     */
    fun refreshSyncedCount() {
        viewModelScope.launch {
            loadSyncedCount()
        }
    }

    /**
     * Verifica si un archivo específico está sincronizado
     */
    suspend fun isMediaSynced(uri: String): Boolean {
        return syncRepository.isMediaSynced(uri)
    }

    fun clearError() {
        _error.value = null
    }

    fun clearSuccessMessage() {
        _successMessage.value = null
    }
}

