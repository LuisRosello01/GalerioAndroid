package com.example.galerio.ui

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.launch

import coil.ImageLoader

@Composable
fun FullScreenImage(
    imageUri: String?,
    imageLoader: ImageLoader? = null,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableStateOf(0.5f) } // Estado inicial de escala para el zoom-in/out
    var offset by remember { mutableStateOf(Offset.Zero) } // Para el desplazamiento
    val animatedScale by animateFloatAsState(targetValue = if (scale > 0.5f) scale else 1f) // Animación de escala
    var isLoading by remember { mutableStateOf(true) } // Estado de carga de la imagen

    val scope = rememberCoroutineScope() // Para controlar la animación
    val context = LocalContext.current

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
                //contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
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