package com.example.galerio.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.galerio.R
import com.example.galerio.data.local.dao.MediaItemDao
import com.example.galerio.data.local.mapper.toMediaItem
import com.example.galerio.data.local.preferences.SyncSettingsManager
import com.example.galerio.data.repository.CloudSyncRepository
import com.example.galerio.notification.SyncNotificationHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

/**
 * Worker para ejecutar sincronización periódica en background
 * Se ejecuta automáticamente cada X horas (configurable)
 *
 * Características:
 * - Sincronización automática periódica (por defecto cada 6 horas)
 * - Solo se ejecuta con conexión WiFi o red no medida
 * - Reporta progreso para notificaciones
 * - Reintentos automáticos con backoff exponencial
 *
 * Usa @HiltWorker con @AssistedInject para inyección de dependencias.
 * Context y WorkerParameters son inyectados por WorkManager (Assisted).
 * Otras dependencias son inyectadas por Hilt.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: CloudSyncRepository,
    private val mediaItemDao: MediaItemDao,
    private val syncSettingsManager: SyncSettingsManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "SyncWorker"
        const val WORK_NAME = "cloud_sync_work"
        const val ONE_TIME_WORK_NAME = "cloud_sync_one_time"

        // Keys para progress data
        const val KEY_PROGRESS = "sync_progress"
        const val KEY_STATUS = "sync_status"
        const val KEY_UPLOADED = "uploaded_count"
        const val KEY_FAILED = "failed_count"
        const val KEY_TOTAL = "total_count"

        // Keys para input data
        const val KEY_AUTO_UPLOAD = "auto_upload"

        // Configuración por defecto
        const val DEFAULT_SYNC_INTERVAL_HOURS = 6L

        /**
         * Programa la sincronización periódica en background
         */
        fun schedulePeriodicSync(
            context: Context,
            intervalHours: Long = DEFAULT_SYNC_INTERVAL_HOURS,
            autoUpload: Boolean = true,
            wifiOnly: Boolean = true
        ) {
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true) // Evitar sincronizar si hay poco almacenamiento
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalHours, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES // Backoff conservador para operaciones de red
                )
                .setInputData(workDataOf(KEY_AUTO_UPLOAD to autoUpload))
                .addTag(WORK_NAME)
                .build()

            // Usar REPLACE para que se actualice cuando cambien las configuraciones
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                syncRequest
            )

            Log.d(TAG, "Scheduled periodic sync every $intervalHours hours (wifiOnly=$wifiOnly, autoUpload=$autoUpload)")
        }

        /**
         * Ejecuta una sincronización inmediata
         */
        fun syncNow(context: Context, autoUpload: Boolean = false, wifiOnly: Boolean = true) {
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_AUTO_UPLOAD to autoUpload))
                // Usar setExpedited para Android 12+ con política de fallback
                // Si el sistema no puede ejecutar inmediatamente, se ejecutará como trabajo normal
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES // Backoff más conservador para operaciones de red
                )
                .addTag(ONE_TIME_WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )

            Log.d(TAG, "Enqueued one-time sync (autoUpload=$autoUpload, wifiOnly=$wifiOnly)")
        }

        /**
         * Cancela toda sincronización programada
         */
        fun cancelScheduledSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled scheduled sync")
        }

        /**
         * Obtiene el estado actual del trabajo
         */
        suspend fun getWorkState(context: Context): WorkInfo.State? = withContext(Dispatchers.IO) {
            try {
                val workInfos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(WORK_NAME)
                    .get()
                workInfos.firstOrNull()?.state
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Obtiene información de diagnóstico completa sobre el estado del Worker
         * Útil para depurar si la sincronización en background está funcionando
         */
        suspend fun getDiagnosticInfo(context: Context): SyncWorkerDiagnostic = withContext(Dispatchers.IO) {
            try {
                val workManager = WorkManager.getInstance(context)

                // Obtener info del trabajo periódico
                val periodicWorkInfos = workManager
                    .getWorkInfosForUniqueWork(WORK_NAME)
                    .get()
                val periodicInfo = periodicWorkInfos.firstOrNull()

                // Obtener info del trabajo único (sync now)
                val oneTimeWorkInfos = workManager
                    .getWorkInfosForUniqueWork(ONE_TIME_WORK_NAME)
                    .get()
                val oneTimeInfo = oneTimeWorkInfos.firstOrNull()

                SyncWorkerDiagnostic(
                    isPeriodicScheduled = periodicInfo != null,
                    periodicState = periodicInfo?.state?.name ?: "NOT_SCHEDULED",
                    periodicRunAttemptCount = periodicInfo?.runAttemptCount ?: 0,
                    periodicNextScheduleTime = periodicInfo?.nextScheduleTimeMillis ?: 0L,
                    periodicTags = periodicInfo?.tags?.toList() ?: emptyList(),
                    isOneTimeScheduled = oneTimeInfo != null && oneTimeInfo.state != WorkInfo.State.SUCCEEDED && oneTimeInfo.state != WorkInfo.State.CANCELLED,
                    oneTimeState = oneTimeInfo?.state?.name ?: "NOT_SCHEDULED",
                    oneTimeRunAttemptCount = oneTimeInfo?.runAttemptCount ?: 0,
                    lastProgress = periodicInfo?.progress?.getInt(KEY_PROGRESS, -1) ?: -1,
                    lastStatus = periodicInfo?.progress?.getString(KEY_STATUS) ?: "unknown"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error getting diagnostic info", e)
                SyncWorkerDiagnostic(
                    isPeriodicScheduled = false,
                    periodicState = "ERROR: ${e.message}",
                    periodicRunAttemptCount = 0,
                    periodicNextScheduleTime = 0L,
                    periodicTags = emptyList(),
                    isOneTimeScheduled = false,
                    oneTimeState = "ERROR",
                    oneTimeRunAttemptCount = 0,
                    lastProgress = -1,
                    lastStatus = "error"
                )
            }
        }

        /**
         * Log de diagnóstico completo - llama esto para verificar el estado en Logcat
         */
        suspend fun logDiagnosticInfo(context: Context) {
            val info = getDiagnosticInfo(context)
            Log.d(TAG, "========== SYNC WORKER DIAGNOSTIC ==========")
            Log.d(TAG, "Periodic Work:")
            Log.d(TAG, "  - Scheduled: ${info.isPeriodicScheduled}")
            Log.d(TAG, "  - State: ${info.periodicState}")
            Log.d(TAG, "  - Run Attempts: ${info.periodicRunAttemptCount}")
            Log.d(TAG, "  - Next Run: ${if (info.periodicNextScheduleTime > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(info.periodicNextScheduleTime)) else "N/A"}")
            Log.d(TAG, "  - Tags: ${info.periodicTags}")
            Log.d(TAG, "One-Time Work:")
            Log.d(TAG, "  - Scheduled: ${info.isOneTimeScheduled}")
            Log.d(TAG, "  - State: ${info.oneTimeState}")
            Log.d(TAG, "  - Run Attempts: ${info.oneTimeRunAttemptCount}")
            Log.d(TAG, "Last Progress: ${info.lastProgress}% - Status: ${info.lastStatus}")
            Log.d(TAG, "=============================================")
        }
    }

    /**
     * Clase de datos con información de diagnóstico del Worker
     */
    data class SyncWorkerDiagnostic(
        val isPeriodicScheduled: Boolean,
        val periodicState: String,
        val periodicRunAttemptCount: Int,
        val periodicNextScheduleTime: Long,
        val periodicTags: List<String>,
        val isOneTimeScheduled: Boolean,
        val oneTimeState: String,
        val oneTimeRunAttemptCount: Int,
        val lastProgress: Int,
        val lastStatus: String
    ) {
        fun toReadableString(): String {
            val nextRunFormatted = if (periodicNextScheduleTime > 0) {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(periodicNextScheduleTime))
            } else "No programado"

            return """
                |=== Estado del Worker de Sincronización ===
                |
                |📅 Trabajo Periódico:
                |   • Programado: ${if (isPeriodicScheduled) "✅ Sí" else "❌ No"}
                |   • Estado: $periodicState
                |   • Intentos: $periodicRunAttemptCount
                |   • Próxima ejecución: $nextRunFormatted
                |
                |⚡ Trabajo Único (Sync Now):
                |   • Activo: ${if (isOneTimeScheduled) "✅ Sí" else "❌ No"}
                |   • Estado: $oneTimeState
                |   • Intentos: $oneTimeRunAttemptCount
                |
                |📊 Último Progreso: ${if (lastProgress >= 0) "$lastProgress%" else "Sin datos"}
                |📋 Último Estado: $lastStatus
            """.trimMargin()
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting background sync...")

        // Leer configuración del usuario en tiempo de ejecución
        // Esto asegura que respetamos la configuración actual, no la del momento de programación
        val userSettings = syncSettingsManager.syncSettings.first()

        // El valor de autoUpload viene de la configuración del usuario, no del input data
        // Solo subimos automáticamente si el usuario lo tiene habilitado en configuración
        val autoUpload = userSettings.autoUpload

        Log.d(TAG, "User settings - autoUpload: $autoUpload, wifiOnly: ${userSettings.wifiOnly}, autoSyncEnabled: ${userSettings.autoSyncEnabled}")

        // Si el usuario deshabilitó la sincronización automática, salir sin hacer nada
        if (!userSettings.autoSyncEnabled) {
            Log.d(TAG, "Auto sync is disabled by user, skipping")
            return Result.success(workDataOf(
                KEY_STATUS to "skipped",
                KEY_TOTAL to 0
            ))
        }

        return try {
            // Iniciar como servicio en primer plano para mostrar notificaciones
            setForeground(createForegroundInfo("Preparando sincronización..."))

            // Reportar progreso inicial
            setProgress(workDataOf(
                KEY_PROGRESS to 0,
                KEY_STATUS to "starting"
            ))

            // Obtener items locales desde la base de datos
            val localEntities = mediaItemDao.getAllMedia().first()
            val localItems = localEntities.map { it.toMediaItem() }
                .filter { !it.isCloudItem }

            Log.d(TAG, "Found ${localItems.size} local items to sync")

            // Verificar cancelación cooperativa antes de continuar
            if (isStopped) {
                Log.d(TAG, "Worker stopped before sync started")
                SyncNotificationHelper.cancelProgressNotification(applicationContext)
                return Result.success(workDataOf(
                    KEY_STATUS to "stopped",
                    KEY_TOTAL to 0
                ))
            }

            if (localItems.isEmpty()) {
                Log.d(TAG, "No local items to sync")
                SyncNotificationHelper.cancelProgressNotification(applicationContext)
                return Result.success(workDataOf(
                    KEY_STATUS to "completed",
                    KEY_TOTAL to 0
                ))
            }

            // Observar progreso detallado en una coroutine separada
            coroutineScope {
                // Job para observar progreso de subida
                val progressJob = launch {
                    syncRepository.uploadProgressInfo.collect { progressInfo ->
                        if (progressInfo.totalCount > 0) {
                            val percent = ((progressInfo.currentIndex.toFloat() / progressInfo.totalCount) * 100).toInt()
                            SyncNotificationHelper.showUploadProgressNotification(
                                context = applicationContext,
                                currentFile = progressInfo.currentIndex,
                                totalFiles = progressInfo.totalCount,
                                progressPercent = 50 + (percent / 2) // 50-100%
                            )
                            setProgress(workDataOf(
                                KEY_PROGRESS to (50 + percent / 2),
                                KEY_STATUS to "uploading",
                                KEY_UPLOADED to progressInfo.currentIndex,
                                KEY_TOTAL to progressInfo.totalCount
                            ))
                        }
                    }
                }

                // Job para observar progreso general
                val syncProgressJob = launch {
                    syncRepository.syncProgress.collect { progress ->
                        val percent = (progress * 100).toInt()
                        when {
                            progress < 0.4f -> {
                                SyncNotificationHelper.showHashingProgressNotification(
                                    context = applicationContext,
                                    progressPercent = percent
                                )
                                // Actualizar progreso del Worker para la UI
                                setProgress(workDataOf(
                                    KEY_PROGRESS to percent,
                                    KEY_STATUS to "starting"
                                ))
                            }
                            progress < 0.5f -> {
                                SyncNotificationHelper.showCheckingServerNotification(applicationContext)
                                // Actualizar progreso del Worker para la UI
                                setProgress(workDataOf(
                                    KEY_PROGRESS to percent,
                                    KEY_STATUS to "syncing"
                                ))
                            }
                        }
                    }
                }

                // Ejecutar sincronización batch
                setProgress(workDataOf(
                    KEY_PROGRESS to 10,
                    KEY_STATUS to "syncing"
                ))

                val result = syncRepository.syncBatch(localItems, autoUpload)

                // Cancelar jobs de progreso
                progressJob.cancel()
                syncProgressJob.cancel()

                if (result.isSuccess) {
                    val syncResult = result.getOrNull()
                    Log.d(TAG, "Background sync completed successfully")
                    Log.d(TAG, "Already synced: ${syncResult?.alreadySynced?.size ?: 0}")
                    Log.d(TAG, "Needs upload: ${syncResult?.needsUpload?.size ?: 0}")
                    Log.d(TAG, "Uploaded: ${syncResult?.uploadedCount ?: 0}")
                    Log.d(TAG, "Failed: ${syncResult?.failedCount ?: 0}")
                    Log.d(TAG, "Cancelled: ${syncResult?.wasCancelled ?: false}")

                    // Si fue cancelado, mostrar notificación de cancelado
                    if (syncResult?.wasCancelled == true) {
                        SyncNotificationHelper.showSyncCancelledNotification(applicationContext)
                        return@coroutineScope Result.success(workDataOf(
                            KEY_STATUS to "cancelled",
                            KEY_UPLOADED to (syncResult.uploadedCount),
                            KEY_FAILED to (syncResult.failedCount),
                            KEY_TOTAL to (syncResult.alreadySynced.size)
                        ))
                    }

                    // Mostrar notificación de completado
                    SyncNotificationHelper.showSyncCompleteNotification(
                        context = applicationContext,
                        uploadedCount = syncResult?.uploadedCount ?: 0,
                        alreadySyncedCount = syncResult?.alreadySynced?.size ?: 0,
                        failedCount = syncResult?.failedCount ?: 0
                    )

                    // Si hay archivos pendientes, notificar
                    val pendingCount = (syncResult?.needsUpload?.size ?: 0) - (syncResult?.uploadedCount ?: 0)
                    if (pendingCount > 0 && !autoUpload) {
                        SyncNotificationHelper.showPendingFilesNotification(
                            context = applicationContext,
                            pendingCount = pendingCount
                        )
                    }

                    return@coroutineScope Result.success(workDataOf(
                        KEY_STATUS to "completed",
                        KEY_UPLOADED to (syncResult?.uploadedCount ?: 0),
                        KEY_FAILED to (syncResult?.failedCount ?: 0),
                        KEY_TOTAL to (syncResult?.alreadySynced?.size ?: 0)
                    ))
                } else {
                    val errorMessage = result.exceptionOrNull()?.message ?: "Error desconocido"
                    Log.e(TAG, "Background sync failed: $errorMessage")

                    // Mostrar notificación de error
                    SyncNotificationHelper.showSyncErrorNotification(
                        context = applicationContext,
                        errorMessage = errorMessage
                    )

                    return@coroutineScope Result.retry()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Worker fue cancelado - limpiar y re-lanzar para cancelación cooperativa
            Log.w(TAG, "Sync worker cancelled: ${e.message}")
            SyncNotificationHelper.showSyncCancelledNotification(applicationContext)
            // IMPORTANTE: Re-lanzar CancellationException para mantener la cancelación cooperativa
            // de coroutines. No hacer esto puede causar memory leaks.
            throw e
        } catch (e: IllegalStateException) {
            // Puede ocurrir si setForeground falla (ej: app en background en Android 12+)
            Log.e(TAG, "Foreground error: ${e.message}")
            // Intentar sin foreground service
            try {
                val localEntities = mediaItemDao.getAllMedia().first()
                val localItems = localEntities.map { it.toMediaItem() }.filter { !it.isCloudItem }

                if (localItems.isEmpty()) {
                    Result.success(workDataOf(KEY_STATUS to "completed", KEY_TOTAL to 0))
                } else {
                    // Leer configuración del usuario
                    val fallbackSettings = syncSettingsManager.syncSettings.first()
                    val result = syncRepository.syncBatch(localItems, fallbackSettings.autoUpload)
                    if (result.isSuccess) {
                        Result.success(workDataOf(KEY_STATUS to "completed"))
                    } else {
                        Result.retry()
                    }
                }
            } catch (innerE: Exception) {
                Log.e(TAG, "Fallback sync also failed", innerE)
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background sync error: ${e.message}", e)
            SyncNotificationHelper.showSyncErrorNotification(
                context = applicationContext,
                errorMessage = e.message ?: "Error durante la sincronización"
            )
            Result.retry()
        }
    }

    /**
     * Crea la información de servicio en primer plano para la notificación
     */
    private fun createForegroundInfo(contentText: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, "sync_progress")
            .setSmallIcon(R.drawable.ic_cloud_sync)
            .setContentTitle("Sincronizando Galerio")
            .setContentText(contentText)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                SyncNotificationHelper.NOTIFICATION_ID_SYNC_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(
                SyncNotificationHelper.NOTIFICATION_ID_SYNC_PROGRESS,
                notification
            )
        }
    }
}
