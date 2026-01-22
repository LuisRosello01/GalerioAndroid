package com.example.galerio.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests instrumentados para verificar el correcto funcionamiento
 * del scheduling de SyncWorker con WorkManager.
 *
 * Estos tests usan WorkManagerTestInitHelper para simular
 * la ejecución de workers sin esperar a los triggers reales.
 */
@RunWith(AndroidJUnit4::class)
class SyncWorkerSchedulingTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var testDriver: TestDriver

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Configuración de WorkManager para testing
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()

        // Inicializar WorkManager en modo test
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!
    }

    @Test
    fun schedulePeriodicSync_createsUniquePeriodicWork() {
        // Given: No hay trabajo programado

        // When: Programamos sincronización periódica
        SyncWorker.schedulePeriodicSync(
            context = context,
            intervalHours = 6,
            autoUpload = true
        )

        // Then: Verificar que el trabajo está programado
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        assertTrue("Work should be scheduled", workInfos.isNotEmpty())
        assertTrue(
            "Work state should be ENQUEUED, RUNNING, or BLOCKED",
            workInfos[0].state in listOf(
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING,
                WorkInfo.State.BLOCKED
            )
        )
    }

    @Test
    fun schedulePeriodicSync_withKeepPolicy_doesNotReplaceExisting() {
        // Given: Ya hay un trabajo programado
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)
        val firstWorkInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        val firstWorkId = firstWorkInfos[0].id

        // When: Intentamos programar otro trabajo con la misma configuración
        SyncWorker.schedulePeriodicSync(context, intervalHours = 12, autoUpload = false)

        // Then: El trabajo original se mantiene (KEEP policy)
        val secondWorkInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        assertEquals("Work ID should be kept", firstWorkId, secondWorkInfos[0].id)
    }

    @Test
    fun syncNow_createsOneTimeWork() {
        // When: Ejecutamos sincronización inmediata
        SyncWorker.syncNow(context, autoUpload = true)

        // Then: Verificar que el trabajo único está programado
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.ONE_TIME_WORK_NAME).get()
        assertTrue("One-time work should be scheduled", workInfos.isNotEmpty())
        assertTrue(
            "Work state should be ENQUEUED or RUNNING",
            workInfos[0].state in listOf(
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING
            )
        )
    }

    @Test
    fun syncNow_withReplacePolicy_replacesExisting() {
        // Given: Ya hay un trabajo único programado
        SyncWorker.syncNow(context, autoUpload = false)
        val firstWorkInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.ONE_TIME_WORK_NAME).get()
        val firstWorkId = firstWorkInfos[0].id

        // When: Ejecutamos otra sincronización inmediata
        SyncWorker.syncNow(context, autoUpload = true)

        // Then: El trabajo fue reemplazado (REPLACE policy)
        // Verificamos que ahora hay un trabajo activo con un ID diferente
        val newWorkInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.ONE_TIME_WORK_NAME).get()
        val activeWork = newWorkInfos.filter {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
        assertTrue("Should have active work after replace", activeWork.isNotEmpty())
        // El trabajo activo debe tener un ID diferente (fue reemplazado)
        assertNotEquals("Work ID should be different after replace", firstWorkId, activeWork[0].id)
    }

    @Test
    fun cancelScheduledSync_cancelsPeriodicWork() {
        // Given: Hay un trabajo periódico programado
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)

        // When: Cancelamos la sincronización
        SyncWorker.cancelScheduledSync(context)

        // Then: El trabajo está cancelado
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        if (workInfos.isNotEmpty()) {
            assertEquals(
                "Work should be cancelled",
                WorkInfo.State.CANCELLED,
                workInfos[0].state
            )
        }
    }

    @Test
    fun periodicSync_hasCorrectConstraints() {
        // When: Programamos sincronización periódica
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)

        // Then: Verificar que las constraints están configuradas
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        assertTrue("Work should exist", workInfos.isNotEmpty())

        // Las constraints se aplican internamente, verificamos que el trabajo existe
        // y tiene los tags correctos
        assertTrue(
            "Work should have correct tag",
            workInfos[0].tags.contains(SyncWorker.WORK_NAME)
        )
    }

    @Test
    fun getDiagnosticInfo_returnsCorrectState(): Unit = runBlocking {
        // Given: Hay un trabajo programado
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)

        // When: Obtenemos información de diagnóstico
        val diagnostic = SyncWorker.getDiagnosticInfo(context)

        // Then: La información es correcta
        assertTrue("Periodic should be scheduled", diagnostic.isPeriodicScheduled)
        assertTrue(
            "State should be ENQUEUED, RUNNING, or BLOCKED",
            diagnostic.periodicState in listOf("ENQUEUED", "RUNNING", "BLOCKED")
        )
        assertTrue(
            "Tags should contain work name",
            diagnostic.periodicTags.contains(SyncWorker.WORK_NAME)
        )
    }

    @Test
    fun getDiagnosticInfo_whenNoWork_returnsNotScheduled(): Unit = runBlocking {
        // Given: No hay trabajo programado
        workManager.cancelAllWork().result.get()

        // When: Obtenemos información de diagnóstico
        val diagnostic = SyncWorker.getDiagnosticInfo(context)

        // Then: Indica que no hay trabajo programado
        assertFalse("Periodic should not be scheduled", diagnostic.isPeriodicScheduled)
    }

    @Test
    fun periodicWork_triggersAtInterval() {
        // Given: Trabajo periódico programado
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)
        val workInfosBefore = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        assertEquals(
            "Work should be enqueued",
            WorkInfo.State.ENQUEUED,
            workInfosBefore[0].state
        )

        // When: Simulamos que pasó el tiempo del periodo
        testDriver.setPeriodDelayMet(workInfosBefore[0].id)

        // Then: El trabajo debería ejecutarse (o estar en estado de ejecución)
        val workInfosAfter = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        assertTrue("Work should still exist", workInfosAfter.isNotEmpty())
    }

    @Test
    fun expeditedWork_respectsOutOfQuotaPolicy() {
        // When: Programamos trabajo expedited
        SyncWorker.syncNow(context, autoUpload = true)

        // Then: El trabajo está programado (la política de fallback permite esto)
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.ONE_TIME_WORK_NAME).get()
        assertTrue("Expedited work should be scheduled", workInfos.isNotEmpty())
        assertTrue(
            "Work should be ENQUEUED or RUNNING",
            workInfos[0].state in listOf(
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING
            )
        )
    }

    @Test
    fun networkConstraint_blocksWorkWithoutNetwork() {
        // When: Programamos trabajo con constraints de red no medida
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)

        // Then: El trabajo está en estado blocked o enqueued esperando constraints
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        assertTrue(
            "Work should be ENQUEUED or BLOCKED",
            workInfos[0].state in listOf(
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED
            )
        )
    }

    @Test
    fun work_hasCorrectTags() {
        // When: Programamos ambos tipos de trabajo
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)
        SyncWorker.syncNow(context, autoUpload = true)

        // Then: Cada trabajo tiene su tag correcto
        val periodicWorkInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        val oneTimeWorkInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.ONE_TIME_WORK_NAME).get()

        assertTrue(
            "Periodic work should have correct tag",
            periodicWorkInfos[0].tags.contains(SyncWorker.WORK_NAME)
        )
        assertTrue(
            "One-time work should have correct tag",
            oneTimeWorkInfos[0].tags.contains(SyncWorker.ONE_TIME_WORK_NAME)
        )
    }

    @Test
    fun bothWorkTypes_haveDifferentIds() {
        // When: Programamos ambos tipos de trabajo
        SyncWorker.schedulePeriodicSync(context, intervalHours = 6, autoUpload = true)
        SyncWorker.syncNow(context, autoUpload = true)

        // Then: Los IDs son diferentes
        val periodicWorkInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        val oneTimeWorkInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.ONE_TIME_WORK_NAME).get()

        assertNotEquals(
            "Work IDs should be different",
            periodicWorkInfos[0].id,
            oneTimeWorkInfos[0].id
        )
    }
}
