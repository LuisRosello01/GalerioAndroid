package com.example.galerio.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.galerio.data.local.dao.MediaItemDao
import com.example.galerio.data.local.preferences.SyncSettingsManager
import com.example.galerio.data.repository.CloudSyncRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory personalizada para crear SyncWorker con inyección de dependencias.
 *
 * @deprecated Esta factory ya no es necesaria. Ahora usamos @HiltWorker con
 * @AssistedInject directamente en SyncWorker. HiltWorkerFactory se encarga
 * de la inyección automáticamente.
 *
 * Se mantiene temporalmente por compatibilidad pero se eliminará en futuras versiones.
 */
@Deprecated(
    message = "Usar @HiltWorker con HiltWorkerFactory. Esta clase será eliminada.",
    level = DeprecationLevel.WARNING
)
@Singleton
class SyncWorkerFactory @Inject constructor(
    private val syncRepository: CloudSyncRepository,
    private val mediaItemDao: MediaItemDao,
    private val syncSettingsManager: SyncSettingsManager
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            SyncWorker::class.java.name -> {
                SyncWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    syncRepository = syncRepository,
                    mediaItemDao = mediaItemDao,
                    syncSettingsManager = syncSettingsManager
                )
            }
            else -> null // Delegar a la factory por defecto para otros workers
        }
    }
}
