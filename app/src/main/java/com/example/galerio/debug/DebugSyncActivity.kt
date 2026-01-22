package com.example.galerio.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.example.galerio.worker.SyncWorker

/**
 * Activity de debug para lanzar sincronización background manualmente.
 *
 * Uso desde ADB:
 * adb shell am start -n com.example.galerio/.debug.DebugSyncActivity
 *
 * Con auto-upload:
 * adb shell am start -n com.example.galerio/.debug.DebugSyncActivity --ez auto_upload true
 *
 * Sin restricción WiFi:
 * adb shell am start -n com.example.galerio/.debug.DebugSyncActivity --ez wifi_only false
 */
class DebugSyncActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DebugSyncActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val autoUpload = intent.getBooleanExtra("auto_upload", false)
        val wifiOnly = intent.getBooleanExtra("wifi_only", false)

        Log.d(TAG, "Triggering background sync: autoUpload=$autoUpload, wifiOnly=$wifiOnly")

        SyncWorker.syncNow(
            context = applicationContext,
            autoUpload = autoUpload,
            wifiOnly = wifiOnly
        )

        Log.d(TAG, "Background sync triggered successfully")

        // Cerrar la activity inmediatamente
        finish()
    }
}
