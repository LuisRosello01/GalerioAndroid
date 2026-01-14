package com.example.galerio.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.galerio.data.model.MediaItem
import com.example.galerio.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {

    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems: StateFlow<List<MediaItem>> = _mediaItems.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadMedia()
    }

    private fun loadMedia() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            repository.getDeviceMedia()
                .onSuccess { items ->
                    _mediaItems.value = items
                    Log.d("MediaViewModel", "Loaded ${items.size} media items")
                }
                .onFailure { exception ->
                    Log.e("MediaViewModel", "Error loading media", exception)
                    _error.value = exception.message ?: "Unknown error"
                    _mediaItems.value = emptyList()
                }

            _isLoading.value = false
        }
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
                }
                .onFailure { exception ->
                    Log.e("MediaViewModel", "Error refreshing media", exception)
                    _error.value = exception.message ?: "Unknown error"
                }

            _isLoading.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }
}
