package com.example.galerio.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.galerio.MainActivity
import com.example.galerio.R

/**
 * Helper para gestionar notificaciones de sincronización
 *
 * Tipos de notificaciones:
 * - Progreso de subida: Muestra "Subiendo X de Y archivos" con barra de progreso
 * - Sincronización completada: Resumen de archivos sincronizados
 * - Error de sincronización: Notifica errores con opción de reintentar
 * - Archivos pendientes: Recordatorio de archivos sin subir
 */
@SuppressLint("MissingPermission")
object SyncNotificationHelper {

    private const val CHANNEL_ID_SYNC_PROGRESS = "sync_progress"
    private const val CHANNEL_ID_SYNC_COMPLETE = "sync_complete"
    private const val CHANNEL_ID_SYNC_ERROR = "sync_error"

    const val NOTIFICATION_ID_SYNC_PROGRESS = 1001
    const val NOTIFICATION_ID_SYNC_COMPLETE = 1002
    const val NOTIFICATION_ID_SYNC_ERROR = 1003
    const val NOTIFICATION_ID_PENDING_FILES = 1004

    /**
     * Crea los canales de notificación necesarios (Android 8+)
     */
    fun createNotificationChannels(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        // Canal para progreso de sincronización (baja importancia, sin sonido)
        val progressChannel = NotificationChannel(
            CHANNEL_ID_SYNC_PROGRESS,
            "Progreso de sincronización",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Muestra el progreso de subida de archivos"
            setShowBadge(false)
        }

        // Canal para sincronización completada
        val completeChannel = NotificationChannel(
            CHANNEL_ID_SYNC_COMPLETE,
            "Sincronización completada",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifica cuando la sincronización finaliza"
        }

        // Canal para errores
        val errorChannel = NotificationChannel(
            CHANNEL_ID_SYNC_ERROR,
            "Errores de sincronización",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifica errores durante la sincronización"
        }

        notificationManager.createNotificationChannels(
            listOf(progressChannel, completeChannel, errorChannel)
        )
    }

    /**
     * Verifica si tenemos permiso para mostrar notificaciones
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Crea un PendingIntent para abrir la app
     */
    private fun createOpenAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Muestra notificación de progreso de subida
     * @param currentFile Archivo actual que se está subiendo (1-based)
     * @param totalFiles Total de archivos a subir
     * @param progressPercent Progreso total en porcentaje (0-100)
     */
    fun showUploadProgressNotification(
        context: Context,
        currentFile: Int,
        totalFiles: Int,
        progressPercent: Int
    ) {
        if (!hasNotificationPermission(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC_PROGRESS)
            .setSmallIcon(R.drawable.ic_cloud_upload)
            .setContentTitle("Subiendo archivos")
            .setContentText("Subiendo $currentFile de $totalFiles archivos")
            .setProgress(100, progressPercent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(createOpenAppIntent(context))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Subiendo $currentFile de $totalFiles archivos\nProgreso total: $progressPercent%")
            )
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SYNC_PROGRESS, notification)
    }

    /**
     * Muestra notificación de progreso de cálculo de hashes
     */
    fun showHashingProgressNotification(
        context: Context,
        progressPercent: Int
    ) {
        if (!hasNotificationPermission(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC_PROGRESS)
            .setSmallIcon(R.drawable.ic_cloud_sync)
            .setContentTitle("Preparando sincronización")
            .setContentText("Analizando archivos... $progressPercent%")
            .setProgress(100, progressPercent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(createOpenAppIntent(context))
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SYNC_PROGRESS, notification)
    }

    /**
     * Muestra notificación de verificación con servidor
     */
    fun showCheckingServerNotification(context: Context) {
        if (!hasNotificationPermission(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC_PROGRESS)
            .setSmallIcon(R.drawable.ic_cloud_sync)
            .setContentTitle("Sincronizando")
            .setContentText("Verificando archivos con el servidor...")
            .setProgress(0, 0, true) // Indeterminado
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(createOpenAppIntent(context))
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SYNC_PROGRESS, notification)
    }

    /**
     * Muestra notificación de sincronización completada
     * @param uploadedCount Archivos subidos exitosamente
     * @param alreadySyncedCount Archivos que ya estaban sincronizados
     * @param failedCount Archivos que fallaron
     */
    fun showSyncCompleteNotification(
        context: Context,
        uploadedCount: Int,
        alreadySyncedCount: Int,
        failedCount: Int
    ) {
        if (!hasNotificationPermission(context)) return

        // Cancelar la notificación de progreso
        cancelProgressNotification(context)

        val parts = mutableListOf<String>()
        if (uploadedCount > 0) parts.add("$uploadedCount subidos")
        if (alreadySyncedCount > 0) parts.add("$alreadySyncedCount ya sincronizados")
        if (failedCount > 0) parts.add("$failedCount fallidos")

        val summary = if (parts.isEmpty()) "Sincronización completada" else parts.joinToString(" • ")

        val icon = if (failedCount > 0) R.drawable.ic_cloud_error else R.drawable.ic_cloud_done

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC_COMPLETE)
            .setSmallIcon(icon)
            .setContentTitle(if (failedCount > 0) "Sincronización con errores" else "Sincronización completada")
            .setContentText(summary)
            .setAutoCancel(true)
            .setContentIntent(createOpenAppIntent(context))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(summary)
            )
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SYNC_COMPLETE, notification)
    }

    /**
     * Muestra notificación de error de sincronización
     * @param errorMessage Mensaje de error
     */
    fun showSyncErrorNotification(
        context: Context,
        errorMessage: String
    ) {
        if (!hasNotificationPermission(context)) return

        // Cancelar la notificación de progreso
        cancelProgressNotification(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC_ERROR)
            .setSmallIcon(R.drawable.ic_cloud_error)
            .setContentTitle("Error de sincronización")
            .setContentText(errorMessage)
            .setAutoCancel(true)
            .setContentIntent(createOpenAppIntent(context))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(errorMessage)
            )
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SYNC_ERROR, notification)
    }

    /**
     * Muestra notificación de archivos pendientes
     * @param pendingCount Número de archivos pendientes de subir
     */
    fun showPendingFilesNotification(
        context: Context,
        pendingCount: Int
    ) {
        if (!hasNotificationPermission(context)) return
        if (pendingCount <= 0) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SYNC_COMPLETE)
            .setSmallIcon(R.drawable.ic_cloud_upload)
            .setContentTitle("Archivos pendientes")
            .setContentText("Tienes $pendingCount archivos pendientes de subir")
            .setAutoCancel(true)
            .setContentIntent(createOpenAppIntent(context))
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_PENDING_FILES, notification)
    }

    /**
     * Cancela la notificación de progreso
     */
    fun cancelProgressNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_SYNC_PROGRESS)
    }

    /**
     * Cancela todas las notificaciones de sincronización
     */
    fun cancelAllNotifications(context: Context) {
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.cancel(NOTIFICATION_ID_SYNC_PROGRESS)
        notificationManager.cancel(NOTIFICATION_ID_SYNC_COMPLETE)
        notificationManager.cancel(NOTIFICATION_ID_SYNC_ERROR)
        notificationManager.cancel(NOTIFICATION_ID_PENDING_FILES)
    }
}
