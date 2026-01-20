package com.example.galerio.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.galerio.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.core.net.toUri

@Composable
fun VideoCard(
    mediaItem: MediaItem,
    context: Context,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    isSynced: Boolean = false // Indica si el archivo local está sincronizado con la nube
) {
    val coroutineScope = rememberCoroutineScope()

    var videoThumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var remoteThumbnailFailed by remember(mediaItem.thumbnailUri) { mutableStateOf(false) }

    val useRemoteThumbnail = mediaItem.thumbnailUri?.isNotEmpty() == true && !remoteThumbnailFailed
    val isLocalVideo = remember(mediaItem.uri) { mediaItem.uri.startsWith("content://") }

    // Determinar el estado de sincronización
    val showSyncIndicator = !mediaItem.isCloudItem // Solo mostrar en items locales
    val isLocalSynced = isSynced || mediaItem.cloudId != null

    LaunchedEffect(mediaItem.uri, useRemoteThumbnail) {
        if (!useRemoteThumbnail && isLocalVideo) {
            coroutineScope.launch {
                videoThumbnail = loadVideoThumbnail(mediaItem.uri.toUri(), context)
            }
        }
    }

    Card(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box {
            when {
                useRemoteThumbnail -> {
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = ImageRequest.Builder(context)
                                .data(mediaItem.thumbnailUri)
                                .size(256)
                                .crossfade(true)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .listener(
                                    onError = { _, res ->
                                        android.util.Log.e("VideoCard", "Error loading thumb ${mediaItem.thumbnailUri}: ${res.throwable.message}")
                                        remoteThumbnailFailed = true
                                    }
                                )
                                .build(),
                            imageLoader = imageLoader
                        ),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                videoThumbnail != null -> {
                    Image(
                        bitmap = videoThumbnail!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Gray)
                    )
                }
            }

            // Indicador de sincronización (esquina superior derecha)
            if (showSyncIndicator) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .background(
                            color = if (isLocalSynced) Color(0xFF4CAF50).copy(alpha = 0.85f)
                                    else Color(0xFF757575).copy(alpha = 0.85f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isLocalSynced) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = if (isLocalSynced) "Sincronizado" else "No sincronizado",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Duración del video (esquina inferior derecha)
            mediaItem.duration?.let { duration ->
                Text(
                    text = formatDuration(duration),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 8.dp, end = 8.dp)
                        .background(
                            Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // Icono de play (centro)
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
                tint = Color.White.copy(alpha = 0.87f)
            )
        }
    }
}

suspend fun loadVideoThumbnail(videoUri: Uri, context: Context): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(videoUri, Size(200, 200), null)
            } else {
                context.contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
                    ThumbnailUtils.createVideoThumbnail(
                        pfd.fileDescriptor.toString(),
                        MediaStore.Video.Thumbnails.MINI_KIND
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}