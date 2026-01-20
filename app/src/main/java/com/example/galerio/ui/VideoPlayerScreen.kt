package com.example.galerio.ui

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.galerio.viewmodel.MediaViewModel
import com.example.galerio.viewmodel.UploadViewModel
import com.example.galerio.viewmodel.VideoPlayerViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Suppress("UnsafeOptInUsageError")
@Composable
fun VideoPlayerScreen(
    videoUri: Uri,
    viewModel: VideoPlayerViewModel = hiltViewModel(),
    uploadViewModel: UploadViewModel = hiltViewModel(),
    mediaViewModel: MediaViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val context = LocalContext.current
    val authToken by viewModel.authToken.collectAsState(initial = null)
    val isCloudVideo = remember(videoUri) { videoUri.toString().startsWith("http") }
    val videoUriString = remember(videoUri) { videoUri.toString() }

    // Estados de subida
    val isUploading by uploadViewModel.isUploading.collectAsState()
    val uploadSuccess by uploadViewModel.uploadSuccess.collectAsState()
    val uploadError by uploadViewModel.uploadError.collectAsState()

    // Verificar si ya está sincronizado
    val syncedUris by mediaViewModel.syncedUris.collectAsState()
    val isSynced = remember(videoUriString, syncedUris) {
        syncedUris.contains(videoUriString)
    }

    // Es un archivo local (no de la nube)?
    val isLocalFile = remember(videoUri) {
        videoUri.toString().startsWith("content://")
    }

    // Mostrar mensaje de éxito temporalmente
    var showSuccessMessage by remember { mutableStateOf(false) }
    LaunchedEffect(uploadSuccess) {
        if (uploadSuccess) {
            showSuccessMessage = true
            mediaViewModel.refreshSyncStatus()
            delay(3000)
            showSuccessMessage = false
            uploadViewModel.clearState()
        }
    }

    val exoPlayer = remember(videoUri, authToken) {
        val builder = ExoPlayer.Builder(context)

        if (isCloudVideo) {
            // Si aún no tenemos el token, devolvemos un player básico sin preparar
            if (authToken == null) return@remember builder.build()

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("GalerioAndroid/1.0")
                .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $authToken"))
                .setAllowCrossProtocolRedirects(true)

            builder.setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        }

        builder.build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                // IMPORTANTE: Actualizar el player cuando la instancia cambie
                if (playerView.player != exoPlayer) {
                    playerView.player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Mostrar cargando si es cloud y aún no hay token
        if (isCloudVideo && authToken == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        // Botón de retroceso
        IconButton(
            onClick = onBackPress,
            modifier = Modifier.padding(16.dp).align(Alignment.TopStart)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
        }

        // Botón de subir a la nube (solo para archivos locales)
        if (isLocalFile) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                when {
                    isUploading -> {
                        // Indicador de carga
                        FloatingActionButton(
                            onClick = { },
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    isSynced || showSuccessMessage -> {
                        // Ya sincronizado
                        FloatingActionButton(
                            onClick = { },
                            containerColor = Color(0xFF4CAF50)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDone,
                                contentDescription = "Sincronizado",
                                tint = Color.White
                            )
                        }
                    }
                    else -> {
                        // Botón para subir
                        FloatingActionButton(
                            onClick = {
                                uploadViewModel.uploadSingleMedia(videoUriString)
                            },
                            containerColor = MaterialTheme.colorScheme.primary
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = "Subir a la nube",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Mensaje de error
        uploadError?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { uploadViewModel.clearState() }) {
                        Text("OK", color = Color.White)
                    }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Text("Error: $error")
            }
        }

        // Mensaje de éxito
        AnimatedVisibility(
            visible = showSuccessMessage,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            Snackbar(
                containerColor = Color(0xFF4CAF50),
                contentColor = Color.White
            ) {
                Text("✓ Video subido correctamente")
            }
        }
    }
}