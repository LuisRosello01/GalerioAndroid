package com.example.galerio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.ImageLoader
import com.example.galerio.data.model.MediaType
import com.example.galerio.ui.components.ImageCard
import com.example.galerio.ui.components.VideoCard
import com.example.galerio.viewmodel.MediaViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Función para mostrar la lista de imágenes en una cuadrícula
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaList(
    modifier: Modifier,
    imageLoader: ImageLoader,
    onMediaClick: (String, MediaType) -> Unit
) {
    val viewModel: MediaViewModel = hiltViewModel()
    val mediaItems by viewModel.mediaItems.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()

    // Mostrar error en Snackbar
    LaunchedEffect(error) {
        error?.let { errorMessage ->
            snackbarHostState.showSnackbar(
                message = "Error: $errorMessage",
                actionLabel = "Reintentar"
            )
            viewModel.clearError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refreshMedia() },
            state = pullToRefreshState,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                isLoading && mediaItems.isEmpty() -> {
                    // Solo muestra el indicador centrado si es la carga inicial
                    Box(modifier = Modifier.fillMaxSize()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }

                mediaItems.isEmpty() && !isLoading -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "No media found\nPull down to refresh",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.align(Alignment.Center),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                else -> {
                    // Agrupamos por fecha
                    val formatter = remember { DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()) }
                    val groupedMediaItems = mediaItems.groupBy { mediaItem ->
                        // Fix: mediaItem.dateModified is already in milliseconds
                        val timestampInMillis = mediaItem.dateModified
                        Instant.ofEpochMilli(timestampInMillis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .format(formatter)
                    }

                    //Log.d("MediaList", "Grouped media items by date: $groupedMediaItems")

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        groupedMediaItems.forEach { (date, mediaForDate) ->
                            // Header de fecha
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = date,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }

                            items(
                                mediaForDate.sortedByDescending { it.dateModified },
                                key = { it.uri }
                            ) { mediaItem ->
                                when (mediaItem.type) {
                                    MediaType.Image -> ImageCard(
                                        mediaItem = mediaItem,
                                        context = context,
                                        imageLoader = imageLoader,
                                        onClick = { onMediaClick(mediaItem.uri, mediaItem.type) }
                                    )

                                    MediaType.Video -> VideoCard(
                                        mediaItem = mediaItem,
                                        context = context,
                                        imageLoader = imageLoader,
                                        onClick = { onMediaClick(mediaItem.uri, mediaItem.type) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Snackbar para mostrar errores
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
