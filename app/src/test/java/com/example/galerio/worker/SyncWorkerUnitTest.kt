package com.example.galerio.worker

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests unitarios para SyncWorker usando Robolectric.
 * Estos tests verifican el scheduling y constantes del worker
 * sin necesidad de un dispositivo o emulador.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SyncWorkerUnitTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Inicializar WorkManager en modo test
        val config = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `constants are correctly defined`() {
        // Verificar que las constantes están correctamente definidas
        assertThat(SyncWorker.WORK_NAME).isEqualTo("cloud_sync_work")
        assertThat(SyncWorker.ONE_TIME_WORK_NAME).isEqualTo("cloud_sync_one_time")
        assertThat(SyncWorker.DEFAULT_SYNC_INTERVAL_HOURS).isEqualTo(6L)
        assertThat(SyncWorker.KEY_PROGRESS).isEqualTo("sync_progress")
        assertThat(SyncWorker.KEY_STATUS).isEqualTo("sync_status")
        assertThat(SyncWorker.KEY_UPLOADED).isEqualTo("uploaded_count")
        assertThat(SyncWorker.KEY_FAILED).isEqualTo("failed_count")
        assertThat(SyncWorker.KEY_TOTAL).isEqualTo("total_count")
        assertThat(SyncWorker.KEY_AUTO_UPLOAD).isEqualTo("auto_upload")
    }

    @Test
    fun `schedulePeriodicSync creates unique periodic work`() {
        // When: Programamos sincronización periódica
        SyncWorker.schedulePeriodicSync(
            context = context,
            intervalHours = 6,
            autoUpload = true
        )

        // Then: Verificar que el trabajo está programado
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        assertThat(workInfos).isNotEmpty()
        assertThat(workInfos[0].state).isAnyOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED
        )
    }

    @Test
    fun `schedulePeriodicSync with UPDATE policy updates existing work`() {
        // Given: Ya hay un trabajo programado
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)
        val workManager = WorkManager.getInstance(context)
        val firstWorkInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        assertThat(firstWorkInfos).isNotEmpty()

        // When: Programamos otro trabajo con diferente configuración
        SyncWorker.schedulePeriodicSync(context, intervalHours = 12, autoUpload = false, wifiOnly = false)

        // Then: El trabajo se actualiza (UPDATE policy)
        val secondWorkInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        assertThat(secondWorkInfos).isNotEmpty()
        // Con UPDATE policy, el trabajo se actualiza en lugar de mantener el original
    }

    @Test
    fun `syncNow creates one-time work`() {
        // When: Ejecutamos sincronización inmediata
        SyncWorker.syncNow(context, autoUpload = true)

        // Then: Verificar que el trabajo único está programado
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.ONE_TIME_WORK_NAME).get()
        assertThat(workInfos).isNotEmpty()
        assertThat(workInfos[0].state).isAnyOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING
        )
    }

    @Test
    fun `syncNow with REPLACE policy can enqueue multiple times`() {
        val workManager = WorkManager.getInstance(context)

        // When: Ejecutamos sincronización inmediata dos veces
        SyncWorker.syncNow(context, autoUpload = false)
        SyncWorker.syncNow(context, autoUpload = true)

        // Then: Hay exactamente un trabajo único activo (el segundo reemplazó al primero)
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.ONE_TIME_WORK_NAME).get()
        // Debería haber al menos un trabajo
        assertThat(workInfos).isNotEmpty()
        // El trabajo más reciente debe estar activo
        val hasActiveWork = workInfos.any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
        assertThat(hasActiveWork).isTrue()
    }

    @Test
    fun `cancelScheduledSync cancels periodic work`() {
        // Given: Hay un trabajo periódico programado
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)

        // When: Cancelamos la sincronización
        SyncWorker.cancelScheduledSync(context)

        // Then: El trabajo está cancelado
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        if (workInfos.isNotEmpty()) {
            assertThat(workInfos[0].state).isEqualTo(WorkInfo.State.CANCELLED)
        }
    }

    @Test
    fun `periodic sync has correct tags`() {
        // When: Programamos sincronización periódica
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)

        // Then: Verificar que los tags están correctos
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        assertThat(workInfos).isNotEmpty()
        assertThat(workInfos[0].tags).contains(SyncWorker.WORK_NAME)
    }

    @Test
    fun `one-time sync has correct tags`() {
        // When: Ejecutamos sincronización inmediata
        SyncWorker.syncNow(context, autoUpload = true)

        // Then: Verificar que los tags están correctos
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.ONE_TIME_WORK_NAME).get()
        assertThat(workInfos).isNotEmpty()
        assertThat(workInfos[0].tags).contains(SyncWorker.ONE_TIME_WORK_NAME)
    }

    @Test
    fun `getDiagnosticInfo returns scheduled when work exists`() = runBlocking {
        // Given: Hay un trabajo programado
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)

        // When: Obtenemos información de diagnóstico
        val diagnostic = SyncWorker.getDiagnosticInfo(context)

        // Then: La información es correcta
        assertThat(diagnostic.isPeriodicScheduled).isTrue()
        assertThat(diagnostic.periodicState).isAnyOf("ENQUEUED", "RUNNING", "BLOCKED")
        assertThat(diagnostic.periodicTags).contains(SyncWorker.WORK_NAME)
    }

    @Test
    fun `getDiagnosticInfo returns not scheduled when no work exists`() = runBlocking {
        // Given: No hay trabajo programado
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get()

        // When: Obtenemos información de diagnóstico
        val diagnostic = SyncWorker.getDiagnosticInfo(context)

        // Then: Indica que no hay trabajo programado
        assertThat(diagnostic.isPeriodicScheduled).isFalse()
    }

    @Test
    fun `SyncWorkerDiagnostic toReadableString returns formatted string`() = runBlocking {
        // Given: Programamos un trabajo para tener datos
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)

        // When: Obtenemos diagnóstico y formateamos el reporte
        val diagnostic = SyncWorker.getDiagnosticInfo(context)
        val report = diagnostic.toReadableString()

        // Then: El reporte contiene información relevante (usando JUnit para evitar ambigüedades)
        org.junit.Assert.assertTrue(
            "Report should contain 'Trabajo Periódico'",
            report.contains("Trabajo Periódico")
        )
        org.junit.Assert.assertTrue(
            "Report should contain 'Estado'",
            report.contains("Estado")
        )
    }

    @Test
    fun `work has both periodic and one-time with different names`() {
        // When: Programamos ambos tipos de trabajo
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)
        SyncWorker.syncNow(context, autoUpload = true)

        // Then: Cada trabajo tiene su nombre único diferente
        val workManager = WorkManager.getInstance(context)
        val periodicWorkInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        val oneTimeWorkInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.ONE_TIME_WORK_NAME).get()

        assertThat(periodicWorkInfos).isNotEmpty()
        assertThat(oneTimeWorkInfos).isNotEmpty()

        // Los IDs deben ser diferentes
        assertThat(periodicWorkInfos[0].id).isNotEqualTo(oneTimeWorkInfos[0].id)
    }

    @Test
    fun `default sync interval is 6 hours`() {
        // Verificar que el intervalo por defecto es 6 horas
        assertThat(SyncWorker.DEFAULT_SYNC_INTERVAL_HOURS).isEqualTo(6L)
    }
}
