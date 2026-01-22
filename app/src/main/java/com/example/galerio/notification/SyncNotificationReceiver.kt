package com.example.galerio.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import com.example.galerio.data.repository.CloudSyncRepository
import com.example.galerio.worker.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * BroadcastReceiver para manejar acciones de notificación de sincronización
 * Permite cancelar la sincronización desde la notificación
 */
@AndroidEntryPoint
class SyncNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CANCEL_SYNC = "com.example.galerio.ACTION_CANCEL_SYNC"
        const val ACTION_DEBUG_SYNC_NOW = "com.example.galerio.DEBUG_SYNC_NOW"
        private const val TAG = "SyncNotificationReceiver"
    }

    @Inject
    lateinit var syncRepository: CloudSyncRepository

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")

        when (intent.action) {
            ACTION_CANCEL_SYNC -> {
                Log.d(TAG, "Cancelling sync from notification")

                // Cancelar la sincronización en el repositorio
                syncRepository.cancelSync()

                // Cancelar el Worker de sincronización (one-time y periodic running)
                val workManager = WorkManager.getInstance(context)
                workManager.cancelUniqueWork(SyncWorker.ONE_TIME_WORK_NAME)
                Log.d(TAG, "Cancelled one-time sync worker")

                // Mostrar notificación de cancelado
                SyncNotificationHelper.showSyncCancelledNotification(context)
            }
            ACTION_DEBUG_SYNC_NOW -> {
                Log.d(TAG, "Debug: Triggering background sync manually")
                val autoUpload = intent.getBooleanExtra("auto_upload", false)
                val wifiOnly = intent.getBooleanExtra("wifi_only", false)
                SyncWorker.syncNow(context, autoUpload = autoUpload, wifiOnly = wifiOnly)
            }
        }
    }
}
