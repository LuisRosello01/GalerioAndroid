package com.example.galerio.permissions

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RequestMediaPermissions(
    onPermissionGranted: @Composable () -> Unit,
    onPermissionsJustGranted: () -> Unit = {}
) {
    // Estado para permitir continuar con acceso limitado
    var continueWithLimitedAccess by remember { mutableStateOf(false) }

    // En Android 14+ (UPSIDE_DOWN_CAKE), necesitamos manejar READ_MEDIA_VISUAL_USER_SELECTED
    // para detectar si el usuario seleccionó acceso parcial vs completo
    // ACCESS_MEDIA_LOCATION se incluye en Android 10+ para poder leer datos GPS de las fotos
    val permissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            android.Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        else -> listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val permissionsState = rememberMultiplePermissionsState(permissions)

    // Trackear si ya teníamos permisos antes de esta composición
    var hadPermissionsBefore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        permissionsState.launchMultiplePermissionRequest()
    }

    // En Android 14+, verificar si tenemos acceso completo o parcial
    val hasFullAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // Acceso completo = READ_MEDIA_IMAGES o READ_MEDIA_VIDEO concedidos
        // Acceso parcial = solo READ_MEDIA_VISUAL_USER_SELECTED concedido
        permissionsState.permissions.any {
            (it.permission == android.Manifest.permission.READ_MEDIA_IMAGES ||
             it.permission == android.Manifest.permission.READ_MEDIA_VIDEO) &&
            it.status.isGranted
        }
    } else {
        permissionsState.allPermissionsGranted
    }

    val hasPartialAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        !hasFullAccess && permissionsState.permissions.any {
            it.permission == android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED &&
            it.status.isGranted
        }
    } else {
        false
    }

    val hasAnyAccess = hasFullAccess || hasPartialAccess

    // Detectar cuando los permisos cambian de NO concedidos a concedidos
    LaunchedEffect(hasAnyAccess) {
        if (hasAnyAccess && !hadPermissionsBefore) {
            // Los permisos acaban de ser otorgados, notificar para refrescar
            onPermissionsJustGranted()
            hadPermissionsBefore = true
        }
    }

    when {
        hasFullAccess -> {
            // Acceso completo a todos los medios
            onPermissionGranted()
        }
        hasPartialAccess && continueWithLimitedAccess -> {
            // Usuario eligió continuar con acceso limitado
            onPermissionGranted()
        }
        hasPartialAccess -> {
            // Acceso parcial: el usuario solo seleccionó algunas fotos/videos
            // Mostramos opciones para dar acceso completo o continuar
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Solo tienes acceso a algunas fotos y videos.")
                Text("Para ver toda tu galería, otorga acceso completo.")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                    Text("Dar acceso a todas las fotos")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { continueWithLimitedAccess = true }) {
                    Text("Continuar con acceso limitado")
                }
            }
        }
        permissionsState.shouldShowRationale -> {
            // Si el usuario ha denegado el permiso anteriormente, mostrar un mensaje
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Necesitamos acceso a tus archivos multimedia para mostrar la galería.")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                    Text("Solicitar Permiso")
                }
            }
        }
        else -> {
            // Si los permisos no son concedidos ni se debe mostrar una justificación, mostrar mensaje
            Text("Se requieren permisos para acceder a los archivos multimedia.")
        }
    }
}

/**
 * Solicita el permiso de notificaciones en Android 13+ (Tiramisu)
 * En versiones anteriores, las notificaciones están habilitadas por defecto.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RequestNotificationPermission() {
    // Solo necesitamos pedir permiso en Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notificationPermissionState = rememberMultiplePermissionsState(
            listOf(android.Manifest.permission.POST_NOTIFICATIONS)
        )

        LaunchedEffect(Unit) {
            if (!notificationPermissionState.allPermissionsGranted) {
                notificationPermissionState.launchMultiplePermissionRequest()
            }
        }
    }
}
