package com.example.galerio

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.galerio.worker.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalerioApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun newImageLoader(): ImageLoader {
        return imageLoader
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Programar sincronización periódica en background (cada 6 horas con WiFi)
        SyncWorker.schedulePeriodicSync(
            context = this,
            intervalHours = SyncWorker.DEFAULT_SYNC_INTERVAL_HOURS,
            autoUpload = true
        )
    }
}
