package com.example.galerio.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.galerio.data.model.cloud.SyncStatus
import com.example.galerio.data.repository.CloudSyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel para gestionar sincronización con la nube
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncRepository: CloudSyncRepository
) : ViewModel() {

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus.SYNCED)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _syncProgress = MutableStateFlow(0f)
    val syncProgress: StateFlow<Float> = _syncProgress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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
    }

    fun startSync() {
        viewModelScope.launch {
            _isSyncing.value = true
            _error.value = null

            syncRepository.syncWithCloud()
                .onSuccess {
                    Log.d("SyncViewModel", "Sync completed successfully")
                }
                .onFailure { exception ->
                    _error.value = exception.message ?: "Sync failed"
                    Log.e("SyncViewModel", "Sync failed", exception)
                }

            _isSyncing.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }
}

