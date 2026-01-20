package com.example.galerio.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_settings")

/**
 * Manager para las preferencias de sincronización
 */
@Singleton
class SyncSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val KEY_AUTO_UPLOAD = booleanPreferencesKey("auto_upload")
        private val KEY_SYNC_INTERVAL_HOURS = longPreferencesKey("sync_interval_hours")
        private val KEY_LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        private val KEY_SHOW_NOTIFICATIONS = booleanPreferencesKey("show_notifications")

        const val DEFAULT_SYNC_INTERVAL_HOURS = 6L
    }

    /**
     * Configuración completa de sincronización
     */
    data class SyncSettings(
        val autoSyncEnabled: Boolean = true,
        val wifiOnly: Boolean = true,
        val autoUpload: Boolean = true,
        val syncIntervalHours: Long = DEFAULT_SYNC_INTERVAL_HOURS,
        val lastSyncTime: Long = 0L,
        val showNotifications: Boolean = true
    )

    /**
     * Flow de configuración de sincronización
     */
    val syncSettings: Flow<SyncSettings> = context.syncSettingsDataStore.data.map { preferences ->
        SyncSettings(
            autoSyncEnabled = preferences[KEY_AUTO_SYNC_ENABLED] ?: true,
            wifiOnly = preferences[KEY_WIFI_ONLY] ?: true,
            autoUpload = preferences[KEY_AUTO_UPLOAD] ?: true,
            syncIntervalHours = preferences[KEY_SYNC_INTERVAL_HOURS] ?: DEFAULT_SYNC_INTERVAL_HOURS,
            lastSyncTime = preferences[KEY_LAST_SYNC_TIME] ?: 0L,
            showNotifications = preferences[KEY_SHOW_NOTIFICATIONS] ?: true
        )
    }

    /**
     * Habilita o deshabilita la sincronización automática
     */
    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        context.syncSettingsDataStore.edit { preferences ->
            preferences[KEY_AUTO_SYNC_ENABLED] = enabled
        }
    }

    /**
     * Configura si solo sincronizar con WiFi
     */
    suspend fun setWifiOnly(wifiOnly: Boolean) {
        context.syncSettingsDataStore.edit { preferences ->
            preferences[KEY_WIFI_ONLY] = wifiOnly
        }
    }

    /**
     * Configura si subir automáticamente los archivos nuevos
     */
    suspend fun setAutoUpload(autoUpload: Boolean) {
        context.syncSettingsDataStore.edit { preferences ->
            preferences[KEY_AUTO_UPLOAD] = autoUpload
        }
    }

    /**
     * Configura el intervalo de sincronización en horas
     */
    suspend fun setSyncIntervalHours(hours: Long) {
        context.syncSettingsDataStore.edit { preferences ->
            preferences[KEY_SYNC_INTERVAL_HOURS] = hours
        }
    }

    /**
     * Actualiza el tiempo de última sincronización
     */
    suspend fun updateLastSyncTime() {
        context.syncSettingsDataStore.edit { preferences ->
            preferences[KEY_LAST_SYNC_TIME] = System.currentTimeMillis()
        }
    }

    /**
     * Configura si mostrar notificaciones de sincronización
     */
    suspend fun setShowNotifications(show: Boolean) {
        context.syncSettingsDataStore.edit { preferences ->
            preferences[KEY_SHOW_NOTIFICATIONS] = show
        }
    }

    /**
     * Guarda todas las configuraciones a la vez
     */
    suspend fun updateSettings(settings: SyncSettings) {
        context.syncSettingsDataStore.edit { preferences ->
            preferences[KEY_AUTO_SYNC_ENABLED] = settings.autoSyncEnabled
            preferences[KEY_WIFI_ONLY] = settings.wifiOnly
            preferences[KEY_AUTO_UPLOAD] = settings.autoUpload
            preferences[KEY_SYNC_INTERVAL_HOURS] = settings.syncIntervalHours
            preferences[KEY_SHOW_NOTIFICATIONS] = settings.showNotifications
        }
    }
}
