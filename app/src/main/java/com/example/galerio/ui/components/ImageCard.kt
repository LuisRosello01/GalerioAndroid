package com.example.galerio.ui.components

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.galerio.data.model.MediaItem

@Composable
fun ImageCard(
    mediaItem: MediaItem,
    context: Context,
    imageLoader: ImageLoader,
    onClick: () -> Unit
) {
    var hasError by remember { mutableStateOf(false) }
    var remoteThumbnailFailed by remember(mediaItem.thumbnailUri) { mutableStateOf(false) }

    val useRemoteThumbnail = mediaItem.thumbnailUri?.isNotEmpty() == true && !remoteThumbnailFailed
    val imageUri = if (useRemoteThumbnail) mediaItem.thumbnailUri else mediaItem.uri

    Card(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        if (hasError) {
            // Mostrar un marcador de error o una imagen de error
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Error al cargar la imagen",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } else {
            Image(
                painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(context)
                        .data(imageUri)
                        .size(256) // Ajusta el tamaño para mejorar el rendimiento
                        .crossfade(true)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .listener(
                            onError = { request, result ->
                                android.util.Log.e("ImageCard", "Error loading image $imageUri: ${result.throwable.message}", result.throwable)
                                hasError = true
                                if (useRemoteThumbnail) remoteThumbnailFailed = true
                            },
                            onSuccess = { _, _ -> hasError = false }
                        )
                        .build(),
                    imageLoader = imageLoader
                ),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

// Versión legacy que mantiene compatibilidad con código existente
@Composable
fun ImageCard(imageUri: String, context: Context, onClick: () -> Unit) {
    var hasError by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        if (hasError) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Error al cargar la imagen",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } else {
            Image(
                painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(context)
                        .data(imageUri)
                        .size(256)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .listener(
                            onError = { _, _ -> hasError = true },
                            onSuccess = { _, _ -> hasError = false }
                        )
                        .build()
                ),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}