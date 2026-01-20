package com.example.galerio.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.galerio.data.local.dao.MediaItemDao
import com.example.galerio.data.local.mapper.toMediaItem
import com.example.galerio.data.repository.CloudSyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
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
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: CloudSyncRepository,
    private val mediaItemDao: MediaItemDao
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
            autoUpload: Boolean = true
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // Solo WiFi
                .setRequiresBatteryNotLow(true)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalHours, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .setInputData(workDataOf(KEY_AUTO_UPLOAD to autoUpload))
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )

            Log.d(TAG, "Scheduled periodic sync every $intervalHours hours")
        }

        /**
         * Ejecuta una sincronización inmediata
         */
        fun syncNow(context: Context, autoUpload: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_AUTO_UPLOAD to autoUpload))
                .addTag(ONE_TIME_WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )

            Log.d(TAG, "Enqueued one-time sync (autoUpload=$autoUpload)")
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
        suspend fun getWorkState(context: Context): WorkInfo.State? {
            return try {
                val workInfos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(WORK_NAME)
                    .get()
                workInfos.firstOrNull()?.state
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting background sync...")
        val autoUpload = inputData.getBoolean(KEY_AUTO_UPLOAD, false)

        return try {
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

            if (localItems.isEmpty()) {
                Log.d(TAG, "No local items to sync")
                return Result.success(workDataOf(
                    KEY_STATUS to "completed",
                    KEY_TOTAL to 0
                ))
            }

            // Ejecutar sincronización batch
            setProgress(workDataOf(
                KEY_PROGRESS to 10,
                KEY_STATUS to "syncing"
            ))

            val result = syncRepository.syncBatch(localItems, autoUpload)

            if (result.isSuccess) {
                val syncResult = result.getOrNull()
                Log.d(TAG, "Background sync completed successfully")
                Log.d(TAG, "Already synced: ${syncResult?.alreadySynced?.size ?: 0}")
                Log.d(TAG, "Needs upload: ${syncResult?.needsUpload?.size ?: 0}")
                Log.d(TAG, "Uploaded: ${syncResult?.uploadedCount ?: 0}")
                Log.d(TAG, "Failed: ${syncResult?.failedCount ?: 0}")

                Result.success(workDataOf(
                    KEY_STATUS to "completed",
                    KEY_UPLOADED to (syncResult?.uploadedCount ?: 0),
                    KEY_FAILED to (syncResult?.failedCount ?: 0),
                    KEY_TOTAL to (syncResult?.alreadySynced?.size ?: 0)
                ))
            } else {
                Log.e(TAG, "Background sync failed: ${result.exceptionOrNull()?.message}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background sync error", e)
            Result.retry()
        }
    }
}

