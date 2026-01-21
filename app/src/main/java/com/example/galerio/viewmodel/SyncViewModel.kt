package com.example.galerio.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.galerio.data.local.preferences.SyncSettingsManager
import com.example.galerio.data.model.MediaItem
import com.example.galerio.data.model.cloud.SyncStatus
import com.example.galerio.data.repository.CloudSyncRepository
import com.example.galerio.notification.SyncNotificationHelper
import com.example.galerio.worker.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    CANCELLED,
    ERROR
}

/**
 * ViewModel para gestionar sincronización con la nube
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncRepository: CloudSyncRepository,
    private val syncSettingsManager: SyncSettingsManager
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

    // Configuración de sincronización
    private val _syncSettings = MutableStateFlow(SyncSettingsManager.SyncSettings())
    val syncSettings: StateFlow<SyncSettingsManager.SyncSettings> = _syncSettings.asStateFlow()

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

                    // Mostrar notificaciones de progreso
                    val percent = (progress * 100).toInt()
                    when (newPhase) {
                        SyncPhase.CALCULATING_HASHES -> {
                            SyncNotificationHelper.showHashingProgressNotification(
                                context = context,
                                progressPercent = percent
                            )
                        }
                        SyncPhase.CHECKING_SERVER -> {
                            SyncNotificationHelper.showCheckingServerNotification(context)
                        }
                        else -> { /* Upload notifications are handled in uploadProgressInfo collector */ }
                    }
                }
            }
        }

        // Observar progreso detallado de subida
        viewModelScope.launch {
            syncRepository.uploadProgressInfo.collect { progressInfo ->
                val currentState = _batchSyncState.value
                if (currentState.isActive && progressInfo.totalCount > 0) {
                    _batchSyncState.value = currentState.copy(
                        currentUploadIndex = progressInfo.currentIndex,
                        totalToUpload = progressInfo.totalCount
                    )

                    // Mostrar notificación de progreso de subida
                    val percent = ((progressInfo.currentIndex.toFloat() / progressInfo.totalCount) * 100).toInt()
                    SyncNotificationHelper.showUploadProgressNotification(
                        context = context,
                        currentFile = progressInfo.currentIndex,
                        totalFiles = progressInfo.totalCount,
                        progressPercent = 50 + (percent / 2) // 50-100% para fase de uploads
                    )
                }
            }
        }

        // Observar configuración de sincronización
        viewModelScope.launch {
            syncSettingsManager.syncSettings.collect { settings ->
                _syncSettings.value = settings
                // Actualizar la programación de sincronización cuando cambie la configuración
                updateBackgroundSyncSchedule(settings)
            }
        }

        // Cargar el conteo de archivos sincronizados al iniciar
        viewModelScope.launch {
            loadSyncedCount()
        }
    }

    /**
     * Actualiza la programación de sincronización en background
     */
    private fun updateBackgroundSyncSchedule(settings: SyncSettingsManager.SyncSettings) {
        if (settings.autoSyncEnabled) {
            SyncWorker.schedulePeriodicSync(
                context = context,
                intervalHours = settings.syncIntervalHours,
                autoUpload = settings.autoUpload
            )
            Log.d(TAG, "Background sync scheduled every ${settings.syncIntervalHours} hours")
        } else {
            SyncWorker.cancelScheduledSync(context)
            Log.d(TAG, "Background sync cancelled")
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
                    Log.d(TAG, "Batch sync completed: ${result.alreadySynced.size} synced, ${result.needsUpload.size} pending, cancelled: ${result.wasCancelled}")

                    // Guardar URIs pendientes para reintento manual
                    pendingUploadUris = result.needsUpload

                    // Determinar la fase según si fue cancelado o completado
                    val phase = if (result.wasCancelled) SyncPhase.CANCELLED else SyncPhase.COMPLETED

                    _batchSyncState.value = BatchSyncState(
                        isActive = false,
                        currentPhase = phase,
                        alreadySyncedCount = result.alreadySynced.size,
                        pendingUploadCount = result.needsUpload.size - result.uploadedCount,
                        uploadedCount = result.uploadedCount,
                        failedCount = result.failedCount,
                        totalSyncedCount = syncRepository.getSyncedMediaCount()
                    )

                    // Resetear progreso
                    _syncProgress.value = 0f

                    // Mostrar notificación de completado o cancelado
                    if (result.wasCancelled) {
                        SyncNotificationHelper.showSyncCancelledNotification(context)
                    } else {
                        SyncNotificationHelper.showSyncCompleteNotification(
                            context = context,
                            uploadedCount = result.uploadedCount,
                            alreadySyncedCount = result.alreadySynced.size,
                            failedCount = result.failedCount
                        )
                    }

                    val message = buildSyncResultMessage(result)
                    _successMessage.value = message
                }
                .onFailure { exception ->
                    _error.value = exception.message ?: "Batch sync failed"
                    _batchSyncState.value = BatchSyncState(
                        isActive = false,
                        currentPhase = SyncPhase.ERROR
                    )

                    // Mostrar notificación de error
                    SyncNotificationHelper.showSyncErrorNotification(
                        context = context,
                        errorMessage = exception.message ?: "Error durante la sincronización"
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
     * Cancela la sincronización en curso
     */
    fun cancelSync() {
        Log.d(TAG, "Cancel sync requested")
        syncRepository.cancelSync()
        _batchSyncState.value = _batchSyncState.value.copy(
            currentPhase = SyncPhase.CANCELLED,
            isActive = false
        )
        _syncProgress.value = 0f
        _isSyncing.value = false
        _successMessage.value = "Sincronización cancelada"

        // Mostrar notificación de cancelado
        SyncNotificationHelper.showSyncCancelledNotification(context)
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
            SyncPhase.CANCELLED -> "Sincronización cancelada"
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

    // ============ CONFIGURACIÓN DE SINCRONIZACIÓN ============

    /**
     * Habilita o deshabilita la sincronización automática
     */
    fun setAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            syncSettingsManager.setAutoSyncEnabled(enabled)
        }
    }

    /**
     * Configura si solo sincronizar con WiFi
     */
    fun setWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            syncSettingsManager.setWifiOnly(wifiOnly)
        }
    }

    /**
     * Configura si subir automáticamente los archivos nuevos
     */
    fun setAutoUpload(autoUpload: Boolean) {
        viewModelScope.launch {
            syncSettingsManager.setAutoUpload(autoUpload)
        }
    }

    /**
     * Configura el intervalo de sincronización en horas
     */
    fun setSyncIntervalHours(hours: Long) {
        viewModelScope.launch {
            syncSettingsManager.setSyncIntervalHours(hours)
        }
    }

    /**
     * Actualiza el tiempo de última sincronización
     */
    fun updateLastSyncTime() {
        viewModelScope.launch {
            syncSettingsManager.updateLastSyncTime()
        }
    }

    /**
     * Ejecuta una sincronización inmediata en background
     */
    fun syncNowInBackground(autoUpload: Boolean = false) {
        SyncWorker.syncNow(context, autoUpload)
    }

    // Estado del diagnóstico del worker
    private val _workerDiagnostic = MutableStateFlow<SyncWorker.SyncWorkerDiagnostic?>(null)
    val workerDiagnostic: StateFlow<SyncWorker.SyncWorkerDiagnostic?> = _workerDiagnostic.asStateFlow()

    /**
     * Obtiene diagnóstico del Worker de sincronización para verificar si está funcionando
     */
    fun checkWorkerDiagnostic() {
        viewModelScope.launch {
            SyncWorker.logDiagnosticInfo(context)
            _workerDiagnostic.value = SyncWorker.getDiagnosticInfo(context)
        }
    }

    /**
     * Obtiene un texto legible del diagnóstico del worker
     */
    fun getWorkerDiagnosticText(): String {
        return _workerDiagnostic.value?.toReadableString()
            ?: "Diagnóstico no disponible. Llama a checkWorkerDiagnostic() primero."
    }
}