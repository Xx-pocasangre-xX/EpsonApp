package com.example.epsonprintapp.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.epsonprintapp.EpsonPrintApp
import com.example.epsonprintapp.MainActivity
import com.example.epsonprintapp.R
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.database.entities.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AppNotificationManager — Gestiona notificaciones del sistema y su historial en BD.
 *
 * Instancia ÚNICA a nivel de aplicación (vive en AppContainer). El scope
 * propio dura lo mismo que el proceso: no es una fuga, es intencional —
 * el guardado del historial no debe cancelarse porque una pantalla se cierre.
 * SupervisorJob evita que el fallo de una inserción cancele las demás.
 */
class AppNotificationManager(
    private val context:  Context,
    private val database: AppDatabase
) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val NOTIF_ID_PRINTING = 1001
        const val NOTIF_ID_SCANNING = 1002
        const val NOTIF_ID_ERROR    = 1003
        const val NOTIF_ID_INK      = 1004
    }

    // ── Impresión ─────────────────────────────────────────────────────────────

    fun notifyPrintSuccess(fileName: String, jobId: Long = -1) {
        val title          = "✅ Impresión completada"
        val message        = "'$fileName' se imprimió correctamente"
        val recommendation = "Recoge tu documento de la impresora"
        showSystemNotification(NOTIF_ID_PRINTING, EpsonPrintApp.CHANNEL_PRINT, title, message)
        saveToDatabase("PRINT_SUCCESS", title, message, recommendation, "INFO", jobId)
    }

    fun notifyPrintError(errorReason: String, fileName: String, jobId: Long = -1) {
        val (friendly, recommendation) = interpretPrintError(errorReason)
        val title   = "❌ Error de impresión"
        val message = "'$fileName': $friendly"
        showSystemNotification(NOTIF_ID_ERROR, EpsonPrintApp.CHANNEL_ALERTS, title, message,
            NotificationCompat.PRIORITY_HIGH)
        saveToDatabase("PRINT_ERROR", title, message, recommendation, "ERROR", jobId)
    }

    // ── Escaneo ───────────────────────────────────────────────────────────────

    fun notifyScanSuccess(filePath: String, jobId: Long = -1) {
        val fileName       = filePath.substringAfterLast("/")
        val title          = "✅ Escaneo completado"
        val message        = "Guardado: $fileName"
        val recommendation = "Encuéntralo en la pantalla de escaneos recientes"
        showSystemNotification(NOTIF_ID_SCANNING, EpsonPrintApp.CHANNEL_SCAN, title, message)
        saveToDatabase("SCAN_SUCCESS", title, message, recommendation, "INFO", jobId)
    }

    fun notifyScanError(errorMessage: String, jobId: Long = -1) {
        val title                      = "❌ Error de escaneo"
        val (friendly, recommendation) = interpretScanError(errorMessage)
        showSystemNotification(NOTIF_ID_ERROR, EpsonPrintApp.CHANNEL_ALERTS, title, friendly,
            NotificationCompat.PRIORITY_HIGH)
        saveToDatabase("SCAN_ERROR", title, friendly, recommendation, "ERROR", jobId)
    }

    // ── Estado impresora ──────────────────────────────────────────────────────

    fun notifyInkLow(color: String, levelPercent: Int) {
        val colorName = mapOf(
            "cyan" to "Cian", "magenta" to "Magenta",
            "yellow" to "Amarillo", "black" to "Negro"
        )[color.lowercase()] ?: color
        val title          = "⚠️ Tinta $colorName baja"
        val message        = "Nivel: $levelPercent%"
        val recommendation = "Recarga la tinta $colorName pronto"
        showSystemNotification(NOTIF_ID_INK, EpsonPrintApp.CHANNEL_ALERTS, title, message)
        saveToDatabase("INK_LOW", title, message, recommendation, "WARNING")
    }

    fun notifyNoPaper() {
        val title          = "📄 Sin papel"
        val message        = "La impresora no tiene papel"
        val recommendation = "Agrega hojas en la bandeja de entrada"
        showSystemNotification(NOTIF_ID_ERROR, EpsonPrintApp.CHANNEL_ALERTS, title, message,
            NotificationCompat.PRIORITY_HIGH)
        saveToDatabase("NO_PAPER", title, message, recommendation, "WARNING")
    }

    fun notifyPrinterOffline() {
        val title          = "📡 Impresora desconectada"
        val message        = "No se puede conectar con la impresora"
        val recommendation = "Verifica que esté encendida y en la misma red WiFi"
        showSystemNotification(NOTIF_ID_ERROR, EpsonPrintApp.CHANNEL_ALERTS, title, message)
        saveToDatabase("PRINTER_OFFLINE", title, message, recommendation, "ERROR")
    }

    // ── Privados ──────────────────────────────────────────────────────────────

    private fun showSystemNotification(
        id:        Int,
        channelId: String,
        title:     String,
        message:   String,
        priority:  Int = NotificationCompat.PRIORITY_DEFAULT
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_print)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(id, notification)
    }

    private fun saveToDatabase(
        type:           String,
        title:          String,
        message:        String,
        recommendation: String? = null,
        severity:       String  = "INFO",
        relatedJobId:   Long?   = null
    ) {
        scope.launch {
            runCatching {
                database.notificationDao().insertNotification(
                    NotificationEntity(
                        type           = type,
                        title          = title,
                        message        = message,
                        recommendation = recommendation,
                        severity       = severity,
                        relatedJobId   = relatedJobId
                    )
                )
            }
        }
    }

    private fun interpretPrintError(reason: String): Pair<String, String> = when {
        reason.contains("media-empty")                                      -> Pair("Sin papel",              "Agrega papel en la bandeja")
        reason.contains("media-jam")                                        -> Pair("Atasco de papel",        "Retira el papel atascado cuidadosamente")
        reason.contains("toner-empty") || reason.contains("marker-supply-empty") -> Pair("Tinta agotada",   "Recarga la tinta")
        reason.contains("cover-open")                                       -> Pair("Cubierta abierta",       "Cierra todas las cubiertas")
        reason.contains("offline")                                          -> Pair("Impresora offline",      "Verifica WiFi y que esté encendida")
        reason.contains("document-format-not-supported")                    -> Pair("Formato no soportado",  "Convierte el archivo a PDF o JPEG")
        reason.contains("503") || reason.contains("service-unavailable")   -> Pair("Servicio no disponible","Espera un momento y reintenta")
        reason.contains("0x400") || reason.contains("bad-request")         -> Pair("Error en petición",      "El archivo puede estar dañado")
        else -> Pair("Error: $reason", "Reinicia la impresora e intenta de nuevo")
    }

    private fun interpretScanError(reason: String): Pair<String, String> = when {
        reason.contains("503") || reason.contains("busy") -> Pair("Escáner ocupado",        "Espera a que termine")
        reason.contains("404")                            -> Pair("Escaneo cancelado",       "Intenta de nuevo")
        reason.contains("timeout")                        -> Pair("Tiempo de espera agotado","Verifica la conexión WiFi")
        else -> Pair("Error: $reason", "Verifica la conexión e intenta de nuevo")
    }
}