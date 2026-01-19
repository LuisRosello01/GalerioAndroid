package com.example.galerio.viewmodel

import androidx.lifecycle.ViewModel
import com.example.galerio.data.local.preferences.AuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    private val authManager: AuthManager
) : ViewModel() {
    val authToken: Flow<String?> = authManager.authToken
}
