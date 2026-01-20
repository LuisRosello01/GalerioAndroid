package com.example.galerio.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.galerio.data.local.dao.SyncedMediaDao
import com.example.galerio.data.model.MediaItem
import com.example.galerio.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val syncedMediaDao: SyncedMediaDao
) : ViewModel() {

    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems: StateFlow<List<MediaItem>> = _mediaItems.asStateFlow()

    // Set de URIs sincronizados (para verificar rápidamente si un item está sincronizado)
    private val _syncedUris = MutableStateFlow<Set<String>>(emptySet())
    val syncedUris: StateFlow<Set<String>> = _syncedUris.asStateFlow()

    // Lógica de agrupación movida al ViewModel para optimizar la UI
    // Usa dateTaken (fecha de captura) si está disponible, si no usa dateModified para consistencia con el servidor
    val groupedMediaItems: StateFlow<Map<String, List<MediaItem>>> = _mediaItems
        .map { items ->
            val formatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
            items.groupBy { mediaItem ->
                Instant.ofEpochMilli(mediaItem.dateTaken ?: mediaItem.dateModified)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .format(formatter)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadMedia()
        loadSyncedUris()
        observeSyncedMedia()
    }

    private fun loadMedia() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            repository.getDeviceMedia()
                .onSuccess { items ->
                    _mediaItems.value = items
                    Log.d("MediaViewModel", "Loaded ${items.size} media items")
                    // Actualizar URIs sincronizados
                    loadSyncedUris()
                }
                .onFailure { exception ->
                    Log.e("MediaViewModel", "Error loading media", exception)
                    _error.value = exception.message ?: "Unknown error"
                    _mediaItems.value = emptyList()
                }

            _isLoading.value = false
        }
    }

    private fun loadSyncedUris() {
        viewModelScope.launch {
            try {
                val syncedItems = syncedMediaDao.getAllSyncedList()
                _syncedUris.value = syncedItems.map { it.localUri }.toSet()
                Log.d("MediaViewModel", "Loaded ${syncedItems.size} synced URIs")
            } catch (e: Exception) {
                Log.e("MediaViewModel", "Error loading synced URIs", e)
            }
        }
    }

    private fun observeSyncedMedia() {
        viewModelScope.launch {
            syncedMediaDao.getAllSynced().collect { syncedItems ->
                _syncedUris.value = syncedItems.map { it.localUri }.toSet()
                Log.d("MediaViewModel", "Updated synced URIs: ${syncedItems.size} items")
            }
        }
    }

    /**
     * Verifica si un item está sincronizado
     */
    fun isItemSynced(uri: String): Boolean {
        return _syncedUris.value.contains(uri)
    }

    fun refreshMedia() {
        Log.d("MediaViewModel", "Refreshing media (force refresh from MediaStore)")
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            repository.forceRefresh()
                .onSuccess { items ->
                    _mediaItems.value = items
                    Log.d("MediaViewModel", "Refresh complete: ${items.size} items")
                    // Actualizar URIs sincronizados
                    loadSyncedUris()
                }
                .onFailure { exception ->
                    Log.e("MediaViewModel", "Error refreshing media", exception)
                    _error.value = exception.message ?: "Unknown error"
                }

            _isLoading.value = false
        }
    }

    /**
     * Fuerza la recarga de los estados de sincronización
     */
    fun refreshSyncStatus() {
        loadSyncedUris()
    }

    fun clearError() {
        _error.value = null
    }
}
