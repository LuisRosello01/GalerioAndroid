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
 */
@Composable
fun SyncProgressIndicator(
    progress: Float,
    status: SyncStatus,
    phase: SyncPhase = SyncPhase.IDLE,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = status == SyncStatus.UPLOADING || status == SyncStatus.PENDING || status == SyncStatus.DOWNLOADING,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = getPhaseTitle(phase, status),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = getPhaseDescription(phase, progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun getPhaseTitle(phase: SyncPhase, status: SyncStatus): String {
    return when (phase) {
        SyncPhase.CALCULATING_HASHES -> "Analizando archivos"
        SyncPhase.CHECKING_SERVER -> "Verificando con servidor"
        SyncPhase.UPLOADING -> "Subiendo archivos"
        SyncPhase.COMPLETED -> "Completado"
        SyncPhase.ERROR -> "Error"
        else -> when (status) {
            SyncStatus.UPLOADING -> "Subiendo archivos"
            SyncStatus.DOWNLOADING -> "Descargando archivos"
            SyncStatus.PENDING -> "Preparando"
            else -> "Sincronizando"
        }
    }
}

private fun getPhaseDescription(phase: SyncPhase, progress: Float): String {
    return when (phase) {
        SyncPhase.CALCULATING_HASHES -> "Calculando hashes de los archivos locales..."
        SyncPhase.CHECKING_SERVER -> "Comparando con archivos en la nube..."
        SyncPhase.UPLOADING -> "Subiendo archivos nuevos al servidor..."
        SyncPhase.COMPLETED -> "Sincronización finalizada"
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

    AnimatedVisibility(
        visible = hasResults && batchSyncState.currentPhase == SyncPhase.COMPLETED,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (batchSyncState.failedCount > 0) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
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
                        imageVector = if (batchSyncState.failedCount > 0) {
                            Icons.Default.Warning
                        } else {
                            Icons.Default.Check
                        },
                        contentDescription = null,
                        tint = if (batchSyncState.failedCount > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (batchSyncState.failedCount > 0) {
                            "Sincronización con errores"
                        } else {
                            "Sincronización completada"
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
