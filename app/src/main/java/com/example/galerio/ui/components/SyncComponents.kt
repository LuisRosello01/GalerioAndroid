package com.example.galerio.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.galerio.data.model.cloud.SyncStatus
import com.example.galerio.viewmodel.BatchSyncState
import com.example.galerio.viewmodel.SyncPhase

/**
 * Indicador de progreso de sincronización mejorado con fases
 * Muestra una barra de progreso total para todos los archivos pendientes
 */
@Composable
fun SyncProgressIndicator(
    progress: Float,
    status: SyncStatus,
    phase: SyncPhase = SyncPhase.IDLE,
    batchSyncState: BatchSyncState = BatchSyncState(),
    onCancelClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Solo mostrar en fases con progreso visible: cálculo de hashes y subida
    // NO mostrar durante CHECKING_SERVER (es muy rápido y no tiene barra de progreso útil)
    val isActivePhase = phase == SyncPhase.CALCULATING_HASHES ||
            phase == SyncPhase.UPLOADING

    val isActiveStatus = status == SyncStatus.UPLOADING ||
            status == SyncStatus.PENDING ||
            status == SyncStatus.DOWNLOADING

    // No mostrar si está en estado terminal (CANCELLED, ERROR, COMPLETED, IDLE)
    val isTerminalPhase = phase == SyncPhase.CANCELLED ||
            phase == SyncPhase.ERROR ||
            phase == SyncPhase.COMPLETED ||
            phase == SyncPhase.IDLE

    // También mostrar si hay sincronización en background activa (y no está en estado terminal ni en CHECKING_SERVER)
    val isBackgroundActive = batchSyncState.isBackgroundSync &&
            batchSyncState.isActive &&
            phase != SyncPhase.CHECKING_SERVER

    val showIndicator = ((isActivePhase && isActiveStatus) || isBackgroundActive) && !isTerminalPhase

    AnimatedVisibility(
        visible = showIndicator,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            colors = CardDefaults.cardColors(
                containerColor = if (batchSyncState.isBackgroundSync)
                    MaterialTheme.colorScheme.tertiaryContainer
                else
                    MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                        color = if (batchSyncState.isBackgroundSync)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (batchSyncState.isBackgroundSync)
                                "Sincronización en segundo plano"
                            else
                                getPhaseTitle(phase, status),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (batchSyncState.isBackgroundSync)
                                MaterialTheme.colorScheme.onTertiaryContainer
                            else
                                MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = if (batchSyncState.isBackgroundSync)
                                getBackgroundSyncDescription(phase, batchSyncState)
                            else
                                getPhaseDescription(phase, progress, batchSyncState),
                            style = MaterialTheme.typography.bodySmall,
                            color = (if (batchSyncState.isBackgroundSync)
                                MaterialTheme.colorScheme.onTertiaryContainer
                            else
                                MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = 0.7f)
                        )
                    }
                    // Botón de cancelar (no mostrar para sincronización en background)
                    if (onCancelClick != null && !batchSyncState.isBackgroundSync &&
                        (phase == SyncPhase.UPLOADING || phase == SyncPhase.CALCULATING_HASHES)) {
                        IconButton(
                            onClick = onCancelClick,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancelar",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Barra de progreso total para todos los archivos
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    trackColor = (if (batchSyncState.isBackgroundSync)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.primary).copy(alpha = 0.2f)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Mostrar progreso detallado con archivos totales
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Contador de archivos si está en fase de subida
                    if (phase == SyncPhase.UPLOADING && batchSyncState.totalToUpload > 0) {
                        Text(
                            text = "${batchSyncState.currentUploadIndex} de ${batchSyncState.totalToUpload} archivos",
                            style = MaterialTheme.typography.labelSmall,
                            color = (if (batchSyncState.isBackgroundSync)
                                MaterialTheme.colorScheme.onTertiaryContainer
                            else
                                MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = 0.7f)
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = (if (batchSyncState.isBackgroundSync)
                            MaterialTheme.colorScheme.onTertiaryContainer
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/**
 * Descripción del progreso de sincronización en background
 */
private fun getBackgroundSyncDescription(phase: SyncPhase, batchSyncState: BatchSyncState): String {
    return when (phase) {
        SyncPhase.CALCULATING_HASHES -> "Analizando archivos locales..."
        SyncPhase.CHECKING_SERVER -> "Verificando archivos en el servidor..."
        SyncPhase.UPLOADING -> {
            if (batchSyncState.totalToUpload > 0) {
                "Subiendo ${batchSyncState.currentUploadIndex} de ${batchSyncState.totalToUpload} archivos..."
            } else {
                "Subiendo archivos..."
            }
        }
        SyncPhase.COMPLETED -> "Sincronización completada"
        SyncPhase.ERROR -> "Error en la sincronización"
        SyncPhase.CANCELLED -> "Sincronización cancelada"
        else -> "Sincronizando..."
    }
}

private fun getPhaseTitle(phase: SyncPhase, status: SyncStatus): String {
    return when (phase) {
        SyncPhase.CALCULATING_HASHES -> "Analizando archivos"
        SyncPhase.CHECKING_SERVER -> "Verificando con servidor"
        SyncPhase.UPLOADING -> "Subiendo archivos"
        SyncPhase.COMPLETED -> "Completado"
        SyncPhase.CANCELLED -> "Cancelado"
        SyncPhase.ERROR -> "Error"
        else -> when (status) {
            SyncStatus.UPLOADING -> "Subiendo archivos"
            SyncStatus.DOWNLOADING -> "Descargando archivos"
            SyncStatus.PENDING -> "Preparando"
            else -> "Sincronizando"
        }
    }
}

private fun getPhaseDescription(phase: SyncPhase, progress: Float, batchSyncState: BatchSyncState = BatchSyncState()): String {
    return when (phase) {
        SyncPhase.CALCULATING_HASHES -> "Calculando hashes de los archivos locales..."
        SyncPhase.CHECKING_SERVER -> "Comparando con archivos en la nube..."
        SyncPhase.UPLOADING -> {
            if (batchSyncState.totalToUpload > 0) {
                "Subiendo ${batchSyncState.currentUploadIndex} de ${batchSyncState.totalToUpload} archivos..."
            } else {
                "Subiendo archivos nuevos al servidor..."
            }
        }
        SyncPhase.COMPLETED -> "Sincronización finalizada"
        SyncPhase.CANCELLED -> "La sincronización fue cancelada"
        SyncPhase.ERROR -> "Se produjo un error durante la sincronización"
        else -> "Progreso: ${(progress * 100).toInt()}%"
    }
}

/**
 * Botón de sincronización batch
 */
@Composable
fun SyncButton(
    isSyncing: Boolean,
    batchSyncState: BatchSyncState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = !isSyncing,
        modifier = modifier
    ) {
        if (isSyncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sincronizando...")
        } else {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sincronizar con la nube")
        }
    }
}

/**
 * Card que muestra el estado de sincronización
 */
@Composable
fun SyncStatusCard(
    batchSyncState: BatchSyncState,
    syncStatus: SyncStatus,
    onSyncClick: () -> Unit,
    isSyncing: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (syncStatus) {
                SyncStatus.SYNCED -> MaterialTheme.colorScheme.primaryContainer
                SyncStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = when (syncStatus) {
                        SyncStatus.SYNCED -> Icons.Default.Check
                        SyncStatus.ERROR -> Icons.Default.Close
                        else -> Icons.Default.Refresh
                    },
                    contentDescription = null,
                    tint = when (syncStatus) {
                        SyncStatus.SYNCED -> MaterialTheme.colorScheme.primary
                        SyncStatus.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (syncStatus) {
                            SyncStatus.SYNCED -> "Sincronizado"
                            SyncStatus.ERROR -> "Error de sincronización"
                            SyncStatus.UPLOADING -> "Subiendo..."
                            SyncStatus.DOWNLOADING -> "Descargando..."
                            SyncStatus.PENDING -> "Pendiente"
                            SyncStatus.CONFLICT -> "Conflicto"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (batchSyncState.totalSyncedCount > 0) {
                        Text(
                            text = "${batchSyncState.totalSyncedCount} archivos sincronizados",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onSyncClick,
                    enabled = !isSyncing
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sincronizar"
                        )
                    }
                }
            }

            // Mostrar estadísticas detalladas si hay datos
            if (batchSyncState.alreadySyncedCount > 0 || batchSyncState.pendingUploadCount > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SyncStatItem(
                        label = "Sincronizados",
                        value = batchSyncState.alreadySyncedCount.toString()
                    )
                    SyncStatItem(
                        label = "Pendientes",
                        value = batchSyncState.pendingUploadCount.toString()
                    )
                    if (batchSyncState.uploadedCount > 0) {
                        SyncStatItem(
                            label = "Subidos",
                            value = batchSyncState.uploadedCount.toString()
                        )
                    }
                    if (batchSyncState.failedCount > 0) {
                        SyncStatItem(
                            label = "Fallidos",
                            value = batchSyncState.failedCount.toString(),
                            isError = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStatItem(
    label: String,
    value: String,
    isError: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Snackbar para mostrar mensajes de sincronización
 */
@Composable
fun SyncSnackbar(
    message: String?,
    isError: Boolean = false,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        message?.let {
            Snackbar(
                modifier = Modifier.padding(16.dp),
                containerColor = if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                action = {
                    TextButton(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            ) {
                Text(it)
            }
        }
    }
}

/**
 * Banner que muestra archivos pendientes de subir
 */
@Composable
fun PendingUploadsBanner(
    pendingCount: Int,
    onUploadClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = pendingCount > 0,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$pendingCount archivos pendientes",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = "Toca para subir a la nube",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                    )
                }
                TextButton(onClick = onUploadClick) {
                    Text("Subir")
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Card con resultado de sincronización
 */
@Composable
fun SyncResultCard(
    batchSyncState: BatchSyncState,
    onRetryClick: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasResults = batchSyncState.alreadySyncedCount > 0 ||
            batchSyncState.uploadedCount > 0 ||
            batchSyncState.failedCount > 0 ||
            batchSyncState.pendingUploadCount > 0

    val isTerminalPhase = batchSyncState.currentPhase == SyncPhase.COMPLETED ||
            batchSyncState.currentPhase == SyncPhase.CANCELLED ||
            batchSyncState.currentPhase == SyncPhase.ERROR

    val isCancelled = batchSyncState.currentPhase == SyncPhase.CANCELLED
    val isError = batchSyncState.currentPhase == SyncPhase.ERROR || batchSyncState.failedCount > 0

    AnimatedVisibility(
        visible = (hasResults || isCancelled) && isTerminalPhase,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isCancelled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = when {
                            isCancelled -> Icons.Default.Close
                            isError -> Icons.Default.Warning
                            else -> Icons.Default.Check
                        },
                        contentDescription = null,
                        tint = when {
                            isCancelled -> MaterialTheme.colorScheme.onSurfaceVariant
                            isError -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = when {
                            isCancelled -> "Sincronización cancelada"
                            isError -> "Sincronización con errores"
                            else -> "Sincronización completada"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (batchSyncState.alreadySyncedCount > 0) {
                        SyncStatItem(
                            label = "Ya sincronizados",
                            value = batchSyncState.alreadySyncedCount.toString()
                        )
                    }
                    if (batchSyncState.uploadedCount > 0) {
                        SyncStatItem(
                            label = "Subidos",
                            value = batchSyncState.uploadedCount.toString()
                        )
                    }
                    if (batchSyncState.pendingUploadCount > 0) {
                        SyncStatItem(
                            label = "Pendientes",
                            value = batchSyncState.pendingUploadCount.toString()
                        )
                    }
                    if (batchSyncState.failedCount > 0) {
                        SyncStatItem(
                            label = "Fallidos",
                            value = batchSyncState.failedCount.toString(),
                            isError = true
                        )
                    }
                }

                // Botón de reintento si hay fallos
                if (batchSyncState.failedCount > 0 && onRetryClick != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRetryClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reintentar ${batchSyncState.failedCount} archivos")
                    }
                }
            }
        }
    }
}

/**
 * Diálogo de configuración de sincronización
 */
@Composable
fun SyncSettingsDialog(
    isAutoSyncEnabled: Boolean,
    isWifiOnly: Boolean,
    isAutoUpload: Boolean,
    syncIntervalHours: Long,
    onAutoSyncChanged: (Boolean) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onAutoUploadChanged: (Boolean) -> Unit,
    onIntervalChanged: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configuración de sincronización") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Auto-sync toggle
                SyncSettingSwitch(
                    title = "Sincronización automática",
                    description = "Sincronizar automáticamente en segundo plano",
                    checked = isAutoSyncEnabled,
                    onCheckedChange = onAutoSyncChanged
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // WiFi only toggle
                SyncSettingSwitch(
                    title = "Solo con WiFi",
                    description = "Sincronizar solo cuando esté conectado a WiFi",
                    checked = isWifiOnly,
                    onCheckedChange = onWifiOnlyChanged,
                    enabled = isAutoSyncEnabled
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Auto-upload toggle
                SyncSettingSwitch(
                    title = "Subida automática",
                    description = "Subir nuevos archivos automáticamente",
                    checked = isAutoUpload,
                    onCheckedChange = onAutoUploadChanged,
                    enabled = isAutoSyncEnabled
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Sync interval selector
                Text(
                    text = "Intervalo de sincronización",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isAutoSyncEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SyncIntervalSelector(
                    selectedHours = syncIntervalHours,
                    onIntervalSelected = onIntervalChanged,
                    enabled = isAutoSyncEnabled
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Aceptar")
            }
        },
        modifier = modifier
    )
}

@Composable
private fun SyncSettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                }
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncIntervalSelector(
    selectedHours: Long,
    onIntervalSelected: (Long) -> Unit,
    enabled: Boolean = true
) {
    val intervals = listOf(
        1L to "1 hora",
        3L to "3 horas",
        6L to "6 horas",
        12L to "12 horas",
        24L to "24 horas"
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        intervals.forEach { (hours, label) ->
            FilterChip(
                selected = selectedHours == hours,
                onClick = { onIntervalSelected(hours) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                enabled = enabled
            )
        }
    }
}

/**
 * Indicador compacto de estado de sincronización para la barra superior
 */
@Composable
fun SyncStatusBadge(
    pendingCount: Int,
    isSyncing: Boolean,
    lastSyncTime: Long,
    modifier: Modifier = Modifier
) {
    val timeSinceSync = if (lastSyncTime > 0) {
        val diff = System.currentTimeMillis() - lastSyncTime
        when {
            diff < 60_000 -> "Hace un momento"
            diff < 3600_000 -> "Hace ${diff / 60_000} min"
            diff < 86400_000 -> "Hace ${diff / 3600_000} h"
            else -> "Hace ${diff / 86400_000} días"
        }
    } else {
        "Nunca"
    }

    Row(
        modifier = modifier
            .background(
                color = when {
                    isSyncing -> MaterialTheme.colorScheme.primaryContainer
                    pendingCount > 0 -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isSyncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp
            )
            Text(
                text = "Sincronizando...",
                style = MaterialTheme.typography.labelSmall
            )
        } else if (pendingCount > 0) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = "$pendingCount pendientes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        } else {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = timeSinceSync,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

