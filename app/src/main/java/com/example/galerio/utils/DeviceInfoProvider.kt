package com.example.galerio.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.example.galerio.data.model.cloud.DeviceInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proveedor de información del dispositivo para autenticación
 */
@Singleton
class DeviceInfoProvider @Inject constructor(
    private val context: Context
) {

    /**
     * Obtiene la información completa del dispositivo
     */
    @SuppressLint("HardwareIds")
    fun getDeviceInfo(): DeviceInfo {
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val deviceName = getDeviceName()
        val userAgent = getUserAgent()

        return DeviceInfo(
            deviceId = deviceId,
            deviceName = deviceName,
            deviceType = "mobile",
            userAgent = userAgent
        )
    }

    /**
     * Genera un nombre legible del dispositivo
     */
    private fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) {
            capitalize(model)
        } else {
            capitalize(manufacturer) + " " + model
        }
    }

    /**
     * Genera el User-Agent del dispositivo
     */
    private fun getUserAgent(): String {
        return "Galerio/${getAppVersion()} " +
                "(Android ${Build.VERSION.RELEASE}; " +
                "${Build.MANUFACTURER} ${Build.MODEL}; " +
                "API ${Build.VERSION.SDK_INT})"
    }

    /**
     * Obtiene la versión de la aplicación
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Capitaliza la primera letra de un string
     */
    private fun capitalize(str: String): String {
        return str.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

