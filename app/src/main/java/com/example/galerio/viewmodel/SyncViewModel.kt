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
    val currentPhase: SyncPhase = SyncPhase.IDLE,
    val alreadySyncedCount: Int = 0,
    val pendingUploadCount: Int = 0,
    val uploadedCount: Int = 0,
    val failedCount: Int = 0,
    val totalSyncedCount: Int = 0,
    val currentUploadIndex: Int = 0,
    val totalToUpload: Int = 0
)

/**
 * Fases de la sincronización
 */
enum class SyncPhase {
    IDLE,
    CALCULATING_HASHES,
    CHECKING_SERVER,
    UPLOADING,
    COMPLETED,
    ERROR
}

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

    // Lista de items pendientes de subir (para reintento manual)
    private var pendingUploadItems: List<MediaItem> = emptyList()
    private var pendingUploadUris: List<String> = emptyList()

    init {
        // Observar el estado de sincronización del repository
        viewModelScope.launch {
            syncRepository.syncStatus.collect { status ->
                _syncStatus.value = status

                // Actualizar fase basándose en el estado
                val currentState = _batchSyncState.value
                if (currentState.isActive) {
                    val newPhase = when (status) {
                        SyncStatus.PENDING -> SyncPhase.CHECKING_SERVER
                        SyncStatus.UPLOADING -> SyncPhase.UPLOADING
                        SyncStatus.SYNCED -> if (currentState.currentPhase != SyncPhase.IDLE) SyncPhase.COMPLETED else SyncPhase.IDLE
                        SyncStatus.ERROR -> SyncPhase.ERROR
                        else -> currentState.currentPhase
                    }
                    _batchSyncState.value = currentState.copy(currentPhase = newPhase)
                }
            }
        }

        viewModelScope.launch {
            syncRepository.syncProgress.collect { progress ->
                _syncProgress.value = progress

                // Estimar fase basándose en el progreso
                val currentState = _batchSyncState.value
                if (currentState.isActive) {
                    val newPhase = when {
                        progress < 0.3f -> SyncPhase.CALCULATING_HASHES
                        progress < 0.5f -> SyncPhase.CHECKING_SERVER
                        progress < 1f -> SyncPhase.UPLOADING
                        else -> SyncPhase.COMPLETED
                    }
                    _batchSyncState.value = currentState.copy(currentPhase = newPhase)
                }
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
            _batchSyncState.value = BatchSyncState(
                isActive = true,
                currentPhase = SyncPhase.CALCULATING_HASHES
            )

            Log.d(TAG, "Starting batch sync for ${localMediaItems.size} items, autoUpload=$autoUpload")

            // Guardar items para posible reintento
            pendingUploadItems = localMediaItems

            syncRepository.syncBatch(localMediaItems, autoUpload)
                .onSuccess { result ->
                    Log.d(TAG, "Batch sync completed: ${result.alreadySynced.size} synced, ${result.needsUpload.size} pending")

                    // Guardar URIs pendientes para reintento manual
                    pendingUploadUris = result.needsUpload

                    _batchSyncState.value = BatchSyncState(
                        isActive = false,
                        currentPhase = SyncPhase.COMPLETED,
                        alreadySyncedCount = result.alreadySynced.size,
                        pendingUploadCount = result.needsUpload.size - result.uploadedCount,
                        uploadedCount = result.uploadedCount,
                        failedCount = result.failedCount,
                        totalSyncedCount = syncRepository.getSyncedMediaCount()
                    )

                    val message = buildSyncResultMessage(result)
                    _successMessage.value = message
                }
                .onFailure { exception ->
                    _error.value = exception.message ?: "Batch sync failed"
                    _batchSyncState.value = BatchSyncState(
                        isActive = false,
                        currentPhase = SyncPhase.ERROR
                    )
                    Log.e(TAG, "Batch sync failed", exception)
                }

            _isSyncing.value = false
        }
    }

    /**
     * Sincronización batch CON subida automática
     */
    fun startBatchSyncWithAutoUpload(localMediaItems: List<MediaItem>) {
        startBatchSync(localMediaItems, autoUpload = true)
    }

    /**
     * Reintenta subir los archivos que fallaron
     */
    fun retryFailedUploads() {
        if (pendingUploadUris.isEmpty() || pendingUploadItems.isEmpty()) {
            _error.value = "No hay archivos pendientes para reintentar"
            return
        }

        // Filtrar solo los items que están en la lista de pendientes
        val itemsToRetry = pendingUploadItems.filter { it.uri in pendingUploadUris }
        if (itemsToRetry.isNotEmpty()) {
            startBatchSync(itemsToRetry, autoUpload = true)
        }
    }

    /**
     * Obtiene la cantidad de archivos pendientes de subir
     */
    fun getPendingUploadCount(): Int = pendingUploadUris.size

    /**
     * Verifica si hay archivos pendientes de subir
     */
    fun hasPendingUploads(): Boolean = pendingUploadUris.isNotEmpty()

    /**
     * Construye un mensaje descriptivo del resultado de la sincronización
     */
    private fun buildSyncResultMessage(result: CloudSyncRepository.BatchSyncResult): String {
        val parts = mutableListOf<String>()

        if (result.alreadySynced.isNotEmpty()) {
            parts.add("${result.alreadySynced.size} ya sincronizados")
        }

        if (result.uploadedCount > 0) {
            parts.add("${result.uploadedCount} subidos")
        }

        if (result.failedCount > 0) {
            parts.add("${result.failedCount} fallidos")
        }

        val remainingPending = result.needsUpload.size - result.uploadedCount
        if (remainingPending > 0) {
            parts.add("$remainingPending pendientes")
        }

        return if (parts.isEmpty()) {
            "✓ Sincronización completada"
        } else {
            parts.joinToString(" • ")
        }
    }

    /**
     * Obtiene descripción de la fase actual
     */
    fun getCurrentPhaseDescription(): String {
        return when (_batchSyncState.value.currentPhase) {
            SyncPhase.IDLE -> "Listo para sincronizar"
            SyncPhase.CALCULATING_HASHES -> "Calculando hashes de archivos..."
            SyncPhase.CHECKING_SERVER -> "Verificando con el servidor..."
            SyncPhase.UPLOADING -> "Subiendo archivos..."
            SyncPhase.COMPLETED -> "Sincronización completada"
            SyncPhase.ERROR -> "Error en la sincronización"
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

    /**
     * Resetea el estado de sincronización
     */
    fun resetSyncState() {
        _batchSyncState.value = BatchSyncState()
        _error.value = null
        _successMessage.value = null
    }
}