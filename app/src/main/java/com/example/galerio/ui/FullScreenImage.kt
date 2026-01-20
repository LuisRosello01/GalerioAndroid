package com.example.galerio.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import coil.ImageLoader
import com.example.galerio.viewmodel.MediaViewModel
import com.example.galerio.viewmodel.UploadViewModel

@Composable
fun FullScreenImage(
    imageUri: String?,
    imageLoader: ImageLoader? = null,
    onDismiss: () -> Unit,
    uploadViewModel: UploadViewModel = hiltViewModel(),
    mediaViewModel: MediaViewModel = hiltViewModel()
) {
    var scale by remember { mutableStateOf(0.5f) } // Estado inicial de escala para el zoom-in/out
    var offset by remember { mutableStateOf(Offset.Zero) } // Para el desplazamiento
    val animatedScale by animateFloatAsState(targetValue = if (scale > 0.5f) scale else 1f) // Animación de escala
    var isLoading by remember { mutableStateOf(true) } // Estado de carga de la imagen
    var showControls by remember { mutableStateOf(true) } // Mostrar/ocultar controles

    val scope = rememberCoroutineScope() // Para controlar la animación
    val context = LocalContext.current

    // Estados de subida
    val isUploading by uploadViewModel.isUploading.collectAsState()
    val uploadSuccess by uploadViewModel.uploadSuccess.collectAsState()
    val uploadError by uploadViewModel.uploadError.collectAsState()

    // Verificar si ya está sincronizado
    val syncedUris by mediaViewModel.syncedUris.collectAsState()
    val isSynced = remember(imageUri, syncedUris) {
        imageUri?.let { syncedUris.contains(it) } ?: false
    }

    // Es un archivo local (no de la nube)?
    val isLocalFile = remember(imageUri) {
        imageUri?.startsWith("content://") == true
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

    val imageRequest = remember(imageUri) {
        ImageRequest.Builder(context)
            .data(imageUri)
            .crossfade(true)
            .listener(
                onError = { _, _ -> isLoading = false },
                onSuccess = { _, _ -> isLoading = false }
            )
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = calculateAlpha(offset)))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        // Cierra la imagen si se ha arrastrado lo suficiente
                        if (offset.x > 300 || offset.x < -300 || offset.y > 300 || offset.y < -300) {
                            scope.launch {
                                onDismiss() // Cierra al arrastrar más de 300 píxeles
                            }
                        } else {
                            offset = Offset.Zero // Restablecer el desplazamiento
                        }
                    },
                    onDrag = { _, dragAmount ->
                        offset += dragAmount
                    }
                )
            }
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }

        Card(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = animatedScale,
                    scaleY = animatedScale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            shape = RoundedCornerShape(0.dp),
            colors = CardDefaults.cardColors(Color.Transparent)
        ) {
            val painter = if (imageLoader != null) {
                rememberAsyncImagePainter(model = imageRequest, imageLoader = imageLoader)
            } else {
                rememberAsyncImagePainter(model = imageRequest)
            }

            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Controles superiores (botón de volver)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Volver",
                    tint = Color.White
                )
            }
        }

        // Botón de subir a la nube (solo para archivos locales)
        if (isLocalFile) {
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
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
                                imageUri?.let { uri ->
                                    uploadViewModel.uploadSingleMedia(uri)
                                }
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
                Text("✓ Imagen subida correctamente")
            }
        }
    }

    // Lanzar la animación de zoom-in al entrar en la pantalla
    LaunchedEffect(Unit) {
        scale = 1f
    }
}
// Función para calcular la transparencia en función del desplazamiento
fun calculateAlpha(offset: Offset): Float {
    // Calcula la distancia desde el origen
    val distance = offset.getDistance()

    // Normaliza la distancia para que la transparencia varíe de 0.3 a 1.0 (por ejemplo)
    // Suponiendo que 300 píxeles es el máximo que deseas arrastrar
    val maxDistance = 300f
    return 1f - (distance / maxDistance).coerceIn(0f, 1f) // El mínimo alpha será 0.3
}