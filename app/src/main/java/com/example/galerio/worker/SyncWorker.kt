package com.example.galerio.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.galerio.data.repository.CloudSyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Worker para ejecutar sincronización periódica en background
 * Se ejecuta automáticamente cada X horas (configurable)
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: CloudSyncRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "SyncWorker"
        const val WORK_NAME = "cloud_sync_work"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting background sync...")

        return try {
            val result = syncRepository.syncWithCloud()

            if (result.isSuccess) {
                Log.d(TAG, "Background sync completed successfully")
                Result.success()
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

