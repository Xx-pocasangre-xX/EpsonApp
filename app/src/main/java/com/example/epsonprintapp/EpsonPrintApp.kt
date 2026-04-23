package com.example.epsonprintapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.epsonprintapp.database.AppDatabase

/**
 * EpsonPrintApp - Clase Application principal
 *
 * Se ejecuta ANTES que cualquier Activity o Service.
 * Aquí inicializamos componentes globales:
 * - Base de datos Room (singleton)
 * - Canales de notificación (requerido en Android 8+)
 *
 * Por qué usar Application class:
 * - Garantiza un único objeto Database en toda la app
 * - Evita memory leaks al no usar Context de Activity
 * - Centraliza la inicialización de SDKs y librerías
 */
class EpsonPrintApp : Application() {

    companion object {
        // ID del canal de notificaciones para impresión
        const val CHANNEL_PRINT = "channel_print"
        // ID del canal para escaneo
        const val CHANNEL_SCAN = "channel_scan"
        // ID del canal para errores/alertas
        const val CHANNEL_ALERTS = "channel_alerts"
    }

    // Instancia singleton de la base de datos
    // 'by lazy' = se crea solo cuando se accede por primera vez
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Crear canales de notificación (Android 8.0+)
        createNotificationChannels()
    }

    /**
     * Crear canales de notificación
     *
     * Android 8.0 (API 26) introdujo canales de notificación.
     * Sin esto, las notificaciones NO se muestran en Android 8+.
     * Los usuarios pueden controlar canales individualmente en Settings.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Canal para notificaciones de impresión
            val printChannel = NotificationChannel(
                CHANNEL_PRINT,
                "Impresión",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificaciones sobre trabajos de impresión"
                enableVibration(true)
            }

            // Canal para notificaciones de escaneo
            val scanChannel = NotificationChannel(
                CHANNEL_SCAN,
                "Escaneo",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificaciones sobre trabajos de escaneo"
            }

            // Canal para alertas y errores (alta importancia = heads-up notification)
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Alertas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas de error y estado de impresora"
                enableVibration(true)
                enableLights(true)
            }

            // Registrar todos los canales
            notificationManager.createNotificationChannels(
                listOf(printChannel, scanChannel, alertChannel)
            )
        }
    }
}
