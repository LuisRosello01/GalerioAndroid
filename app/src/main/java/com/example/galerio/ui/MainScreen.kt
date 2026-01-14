package com.example.galerio.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import coil.ImageLoader
import com.example.galerio.data.model.MediaType
import com.example.galerio.permissions.RequestMediaPermissions
import com.example.galerio.viewmodel.AuthViewModel

// Función para mostrar la pantalla principal
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onVideoClick: (String) -> Unit,
    imageLoader: ImageLoader,
    authViewModel: AuthViewModel = hiltViewModel(),
    onLogout: () -> Unit
) {
    var selectedMediaUri by remember { mutableStateOf<String?>(null) } // Estado para la selección de medios
    var isVideoSelected by remember { mutableStateOf(false) } // Estado para saber si se seleccionó un video

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior() // Manejo de desplazamiento para la barra de aplicación

    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()

    LaunchedEffect(isAuthenticated) {
        if (!isAuthenticated) {
            onLogout()
        }
    }

    Scaffold(
        topBar = {
            MyAppBar(
                scrollBehavior = scrollBehavior,
                onLogoutClick = { authViewModel.logout() }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { paddingValues ->
        RequestMediaPermissions {
            // Lista de medios (fondo)
            MediaList(
                modifier = Modifier.padding(paddingValues),
                imageLoader = imageLoader
            ) { uri, mediaType ->
                selectedMediaUri = uri
                isVideoSelected = mediaType == MediaType.Video
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
fun MyAppBar(scrollBehavior: TopAppBarScrollBehavior, onLogoutClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("Galerio") },
        actions = {
            Box {
                IconButton(onClick = { showMenu = !showMenu }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
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