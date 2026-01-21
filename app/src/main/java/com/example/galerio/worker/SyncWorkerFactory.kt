package com.example.galerio.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.galerio.data.local.dao.MediaItemDao
import com.example.galerio.data.repository.CloudSyncRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory personalizada para crear SyncWorker con inyección de dependencias.
 *
 * Esta factory es necesaria porque @HiltWorker con KSP tiene problemas
 * para generar la AssistedFactory correctamente en algunas configuraciones.
 */
@Singleton
class SyncWorkerFactory @Inject constructor(
    private val syncRepository: CloudSyncRepository,
    private val mediaItemDao: MediaItemDao
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
                    mediaItemDao = mediaItemDao
                )
            }
            else -> null // Delegar a la factory por defecto para otros workers
        }
    }
}
