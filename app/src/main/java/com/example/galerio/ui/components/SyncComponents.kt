package com.example.galerio.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.galerio.data.model.cloud.SyncStatus
import com.example.galerio.viewmodel.BatchSyncState

/**
 * Indicador de progreso de sincronización
 */
@Composable
fun SyncProgressIndicator(
    progress: Float,
    status: SyncStatus,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = status == SyncStatus.UPLOADING || status == SyncStatus.PENDING || status == SyncStatus.DOWNLOADING,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = when (status) {
                        SyncStatus.UPLOADING -> "Subiendo archivos..."
                        SyncStatus.DOWNLOADING -> "Descargando archivos..."
                        SyncStatus.PENDING -> "Preparando sincronización..."
                        else -> "Sincronizando..."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
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
