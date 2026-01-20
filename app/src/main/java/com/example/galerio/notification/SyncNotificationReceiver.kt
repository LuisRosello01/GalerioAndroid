package com.example.galerio.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.galerio.data.repository.CloudSyncRepository
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
        private const val TAG = "SyncNotificationReceiver"
    }

    @Inject
    lateinit var syncRepository: CloudSyncRepository

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")

        when (intent.action) {
            ACTION_CANCEL_SYNC -> {
                Log.d(TAG, "Cancelling sync from notification")
                syncRepository.cancelSync()

                // Mostrar notificación de cancelado
                SyncNotificationHelper.showSyncCancelledNotification(context)
            }
        }
    }
}
