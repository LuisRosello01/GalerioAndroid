package com.example.galerio

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.galerio.notification.SyncNotificationHelper
import com.example.galerio.worker.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalerioApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var hiltWorkerFactory: HiltWorkerFactory

    companion object {
        private const val SYNC_PREFS = "sync_prefs"
        private const val KEY_PERIODIC_SYNC_SCHEDULED = "periodic_sync_scheduled"
        private const val KEY_SYNC_VERSION = "sync_version"
        private const val KEY_FIRST_SYNC_COMPLETED = "first_sync_completed"
        // Incrementar esta versión cuando cambien los parámetros de sincronización
        private const val CURRENT_SYNC_VERSION = 1
    }

    override fun newImageLoader(): ImageLoader = imageLoader

    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(hiltWorkerFactory)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        // Crear canales de notificación
        SyncNotificationHelper.createNotificationChannels(this)

        // Inicializar WorkManager con nuestra configuración personalizada
        androidx.work.WorkManager.getInstance(this)

        // Solo programar sincronización si no está ya programada o si cambió la versión
        schedulePeriodicSyncIfNeeded()
    }

    /**
     * Programa la sincronización periódica solo si es necesario.
     * Evita trabajo innecesario en cada inicio de la app.
     * Solo programa si el usuario ya completó la primera sincronización manual.
     */
    private fun schedulePeriodicSyncIfNeeded() {
        val prefs = getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        val isScheduled = prefs.getBoolean(KEY_PERIODIC_SYNC_SCHEDULED, false)
        val savedVersion = prefs.getInt(KEY_SYNC_VERSION, 0)
        val firstSyncCompleted = prefs.getBoolean(KEY_FIRST_SYNC_COMPLETED, false)

        // Solo programar si ya se completó la primera sincronización manual
        if (!firstSyncCompleted) {
            return
        }

        // Programar si no está programado o si la configuración cambió
        if (!isScheduled || savedVersion != CURRENT_SYNC_VERSION) {
            SyncWorker.schedulePeriodicSync(
                context = this,
                intervalHours = SyncWorker.DEFAULT_SYNC_INTERVAL_HOURS,
                autoUpload = true
            )

            prefs.edit()
                .putBoolean(KEY_PERIODIC_SYNC_SCHEDULED, true)
                .putInt(KEY_SYNC_VERSION, CURRENT_SYNC_VERSION)
                .apply()
        }
    }

    /**
     * Marca que la primera sincronización manual fue completada.
     * Esto habilita la sincronización periódica automática.
     */
    fun markFirstSyncCompleted() {
        val prefs = getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_FIRST_SYNC_COMPLETED, true)
            .apply()
        // Ahora programar la sincronización periódica
        schedulePeriodicSyncIfNeeded()
    }

    /**
     * Verifica si la primera sincronización manual fue completada.
     */
    fun isFirstSyncCompleted(): Boolean {
        val prefs = getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FIRST_SYNC_COMPLETED, false)
    }

    /**
     * Fuerza la reprogramación de la sincronización periódica.
     * Llamar cuando el usuario cambie la configuración de sincronización.
     */
    fun reschedulePeriodicSync(intervalHours: Long = SyncWorker.DEFAULT_SYNC_INTERVAL_HOURS, autoUpload: Boolean = true) {
        SyncWorker.schedulePeriodicSync(
            context = this,
            intervalHours = intervalHours,
            autoUpload = autoUpload
        )

        getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PERIODIC_SYNC_SCHEDULED, true)
            .apply()
    }
}
