package com.example.galerio

import android.app.Application
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.galerio.notification.SyncNotificationHelper
import com.example.galerio.worker.SyncWorker
import com.example.galerio.worker.SyncWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalerioApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var syncWorkerFactory: SyncWorkerFactory

    override fun newImageLoader(): ImageLoader = imageLoader

    override val workManagerConfiguration: Configuration by lazy {
        val delegatingWorkerFactory = DelegatingWorkerFactory()
        delegatingWorkerFactory.addFactory(syncWorkerFactory)

        Configuration.Builder()
            .setWorkerFactory(delegatingWorkerFactory)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        // Crear canales de notificación
        SyncNotificationHelper.createNotificationChannels(this)

        // Inicializar WorkManager con nuestra configuración personalizada
        androidx.work.WorkManager.getInstance(this)

        // Programar sincronización periódica en background (cada 6 horas con WiFi)
        SyncWorker.schedulePeriodicSync(
            context = this,
            intervalHours = SyncWorker.DEFAULT_SYNC_INTERVAL_HOURS,
            autoUpload = true
        )
    }
}
