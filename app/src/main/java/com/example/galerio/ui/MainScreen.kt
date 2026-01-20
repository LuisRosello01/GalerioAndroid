package com.example.galerio.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import coil.ImageLoader
import com.example.galerio.data.model.MediaType
import com.example.galerio.permissions.RequestMediaPermissions
import com.example.galerio.ui.components.SyncProgressIndicator
import com.example.galerio.viewmodel.AuthViewModel
import com.example.galerio.viewmodel.MediaViewModel
import com.example.galerio.viewmodel.SyncViewModel

// Función para mostrar la pantalla principal
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onVideoClick: (String) -> Unit,
    imageLoader: ImageLoader,
    authViewModel: AuthViewModel = hiltViewModel(),
    mediaViewModel: MediaViewModel = hiltViewModel(),
    syncViewModel: SyncViewModel = hiltViewModel(),
    onLogout: () -> Unit
) {
    var selectedMediaUri by remember { mutableStateOf<String?>(null) } // Estado para la selección de medios
    var isVideoSelected by remember { mutableStateOf(false) } // Estado para saber si se seleccionó un video

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior() // Manejo de desplazamiento para la barra de aplicación

    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()

    // Estados de sincronización
    val isSyncing by syncViewModel.isSyncing.collectAsState()
    val syncStatus by syncViewModel.syncStatus.collectAsState()
    val syncProgress by syncViewModel.syncProgress.collectAsState()
    val batchSyncState by syncViewModel.batchSyncState.collectAsState()
    val syncError by syncViewModel.error.collectAsState()
    val successMessage by syncViewModel.successMessage.collectAsState()

    // Lista de medios para sincronización
    val mediaItems by mediaViewModel.mediaItems.collectAsState()

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // Mostrar mensajes de sincronización
    LaunchedEffect(syncError, successMessage) {
        syncError?.let { error ->
            snackbarHostState.showSnackbar(
                message = "Error: $error",
                duration = SnackbarDuration.Long
            )
            syncViewModel.clearError()
        }
        successMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            syncViewModel.clearSuccessMessage()
        }
    }

    // El manejo de navegación por estado de autenticación se realiza en MainActivity
    // Evitamos duplicar la lógica aquí para prevenir bucles de redirección
    // al inicializarse el ViewModel con valor false por defecto.

    Scaffold(
        topBar = {
            MyAppBar(
                scrollBehavior = scrollBehavior,
                isSyncing = isSyncing,
                onSyncClick = {
                    // Filtrar solo los items locales (no de la nube) para sincronización
                    val localItems = mediaItems.filter { !it.isCloudItem }
                    syncViewModel.startBatchSync(localItems, autoUpload = false)
                },
                onLogoutClick = { authViewModel.logout() }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { paddingValues ->
        RequestMediaPermissions {
            Column(modifier = Modifier.padding(paddingValues)) {
                // Indicador de progreso de sincronización
                SyncProgressIndicator(
                    progress = syncProgress,
                    status = syncStatus,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // Lista de medios (fondo)
                MediaList(
                    modifier = Modifier.weight(1f),
                    imageLoader = imageLoader
                ) { uri, mediaType ->
                    selectedMediaUri = uri
                    isVideoSelected = mediaType == MediaType.Video
                }
            }

            // Si hay una imagen o video seleccionado, lo mostramos superpuesto
            selectedMediaUri?.let { uri ->
                Box(modifier = Modifier.zIndex(1f)) {
                    if (isVideoSelected) {
                        VideoPlayerScreen(videoUri = Uri.parse(uri)) {
                            selectedMediaUri = null // Cerrar el video y volver a la lista
                        }
                    } else {
                        FullScreenImage(
                            imageUri = uri,
                            imageLoader = imageLoader
                        ) {
                            selectedMediaUri = null // Cerrar la imagen y volver a la lista
                        }
                    }
                }
            }
        }
    }
}

// Función para mostrar la barra de aplicación
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyAppBar(
    scrollBehavior: TopAppBarScrollBehavior,
    isSyncing: Boolean = false,
    onSyncClick: () -> Unit = {},
    onLogoutClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("Galerio") },
        actions = {
            // Botón de sincronización
            IconButton(
                onClick = onSyncClick,
                enabled = !isSyncing
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(8.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Filled.CloudSync, contentDescription = "Sincronizar")
                }
            }

            Box {
                IconButton(onClick = { showMenu = !showMenu }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Sincronizar con nube") },
                        leadingIcon = {
                            Icon(Icons.Filled.CloudSync, contentDescription = null)
                        },
                        onClick = {
                            onSyncClick()
                            showMenu = false
                        },
                        enabled = !isSyncing
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Cerrar Sesión") },
                        onClick = {
                            onLogoutClick()
                            showMenu = false
                        }
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}