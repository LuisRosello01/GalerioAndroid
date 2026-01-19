package com.example.galerio.ui

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
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

@Composable
fun VideoPlayerScreen(
    videoUri: Uri,
    viewModel: VideoPlayerViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val context = LocalContext.current
    val authToken by viewModel.authToken.collectAsState(initial = null)
    val isCloudVideo = remember(videoUri) { videoUri.toString().startsWith("http") }

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
    }

}