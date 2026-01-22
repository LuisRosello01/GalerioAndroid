package com.example.galerio.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.galerio.data.local.preferences.AuthManager
import com.example.galerio.data.model.cloud.User
import com.example.galerio.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel para gestionar autenticación
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val authManager: AuthManager
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    // Flag para indicar si ya se verificó el estado inicial de autenticación
    private val _isAuthCheckComplete = MutableStateFlow(false)
    val isAuthCheckComplete: StateFlow<Boolean> = _isAuthCheckComplete.asStateFlow()

    // Observar directamente el flujo de isLoggedIn del AuthManager
    // Usamos Eagerly para evitar reinicios del flujo que causen oscilaciones
    val isAuthenticated: StateFlow<Boolean> = authManager.isLoggedIn
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    init {
        // Cargar el usuario actual si está autenticado
        viewModelScope.launch {
            authManager.currentUser.collect { user ->
                _currentUser.value = user
            }
        }
        // Marcar que el check de auth está completo después de la primera emisión
        viewModelScope.launch {
            // Esperamos a que el DataStore emita el primer valor real
            authManager.isLoggedIn.first()
            _isAuthCheckComplete.value = true
            Log.d("AuthViewModel", "Auth check complete, isAuthenticated: ${isAuthenticated.value}")
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            authRepository.login(username, password)
                .onSuccess { user ->
                    // El estado de autenticación se actualizará automáticamente
                    // a través del flujo isLoggedIn del AuthManager
                    Log.d("AuthViewModel", "Login successful")
                }
                .onFailure { exception ->
                    _error.value = exception.message ?: "Login failed"
                    Log.e("AuthViewModel", "Login failed", exception)
                }

            _isLoading.value = false
        }
    }

    fun register(username: String, email: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            authRepository.register(username, email, password)
                .onSuccess { user ->
                    // El estado de autenticación se actualizará automáticamente
                    // a través del flujo isLoggedIn del AuthManager
                    Log.d("AuthViewModel", "Registration successful")
                }
                .onFailure { exception ->
                    _error.value = exception.message ?: "Registration failed"
                    Log.e("AuthViewModel", "Registration failed", exception)
                }

            _isLoading.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            // El estado de autenticación se actualizará automáticamente
            // a través del flujo isLoggedIn del AuthManager
            Log.d("AuthViewModel", "Logout successful")
        }
    }

    fun clearError() {
        _error.value = null
    }
}

