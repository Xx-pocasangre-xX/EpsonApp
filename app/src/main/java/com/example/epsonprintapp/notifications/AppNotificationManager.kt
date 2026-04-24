package com.example.epsonprintapp.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.epsonprintapp.EpsonPrintApp
import com.example.epsonprintapp.MainActivity          // FIX: correct package
import com.example.epsonprintapp.R
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.database.entities.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val database = AppDatabase.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val NOTIF_ID_PRINTING = 1001
        const val NOTIF_ID_SCANNING = 1002
        const val NOTIF_ID_ERROR    = 1003
        const val NOTIF_ID_INK      = 1004
    }

    // ── Print ──────────────────────────────────────────────────────────────────

    fun notifyPrintSuccess(fileName: String, jobId: Long = -1) {
        val title = "✅ Impresión completada"
        val message = "'$fileName' se imprimió correctamente"
        val recommendation = "Recoge tu documento de la impresora"
        showSystemNotification(NOTIF_ID_PRINTING, EpsonPrintApp.CHANNEL_PRINT, title, message)
        saveToDatabase("PRINT_SUCCESS", title, message, recommendation, "INFO", jobId)
    }

    fun notifyPrintError(errorReason: String, fileName: String, jobId: Long = -1) {
        val (friendlyMessage, recommendation) = interpretPrintError(errorReason)
        val title = "❌ Error de impresión"
        val fullMessage = "'$fileName': $friendlyMessage"
        showSystemNotification(NOTIF_ID_ERROR, EpsonPrintApp.CHANNEL_ALERTS, title, fullMessage,
            NotificationCompat.PRIORITY_HIGH)
        saveToDatabase("PRINT_ERROR", title, fullMessage, recommendation, "ERROR", jobId)
    }

    // ── Scan ───────────────────────────────────────────────────────────────────

    fun notifyScanSuccess(filePath: String, jobId: Long = -1) {
        val fileName = filePath.substringAfterLast("/")
        val title = "✅ Escaneo completado"
        val message = "Documento guardado: $fileName"
        val recommendation = "Puedes encontrarlo en la pantalla de escaneos recientes"
        showSystemNotification(NOTIF_ID_SCANNING, EpsonPrintApp.CHANNEL_SCAN, title, message)
        saveToDatabase("SCAN_SUCCESS", title, message, recommendation, "INFO", jobId)
    }

    fun notifyScanError(errorMessage: String, jobId: Long = -1) {
        val title = "❌ Error de escaneo"
        val (friendly, recommendation) = interpretScanError(errorMessage)
        showSystemNotification(NOTIF_ID_ERROR, EpsonPrintApp.CHANNEL_ALERTS, title, friendly,
            NotificationCompat.PRIORITY_HIGH)
        saveToDatabase("SCAN_ERROR", title, friendly, recommendation, "ERROR", jobId)
    }

    // ── Printer state ──────────────────────────────────────────────────────────

    fun notifyInkLow(color: String, levelPercent: Int) {
        val colorName = when (color.lowercase()) {
            "cyan"    -> "Cian"
            "magenta" -> "Magenta"
            "yellow"  -> "Amarillo"
            "black"   -> "Negro"
            else      -> color
        }
        val title = "⚠️ Tinta $colorName baja"
        val message = "Nivel de tinta $colorName: $levelPercent%"
        val recommendation = "Recarga la tinta $colorName pronto para evitar interrupciones"
        showSystemNotification(NOTIF_ID_INK, EpsonPrintApp.CHANNEL_ALERTS, title, message)
        saveToDatabase("INK_LOW", title, message, recommendation, "WARNING")
    }

    fun notifyNoPaper() {
        val title = "📄 Sin papel"
        val message = "La impresora no tiene papel"
        val recommendation = "Agrega hojas de papel en la bandeja de entrada y reinicia la impresión"
        showSystemNotification(NOTIF_ID_ERROR, EpsonPrintApp.CHANNEL_ALERTS, title, message,
            NotificationCompat.PRIORITY_HIGH)
        saveToDatabase("NO_PAPER", title, message, recommendation, "WARNING")
    }

    fun notifyPrinterOffline() {
        val title = "📡 Impresora desconectada"
        val message = "No se puede conectar con la impresora"
        val recommendation = "Verifica que la impresora esté encendida y conectada al mismo WiFi"
        showSystemNotification(NOTIF_ID_ERROR, EpsonPrintApp.CHANNEL_ALERTS, title, message)
        saveToDatabase("PRINTER_OFFLINE", title, message, recommendation, "ERROR")
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun showSystemNotification(
        id: Int,
        channelId: String,
        title: String,
        message: String,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT
    ) {
        // FIX: build PendingIntent correctly (no apply block — use explicit flags directly)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_print)   // FIX: use ic_print (ic_printer_notification doesn't exist)
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
        type: String,
        title: String,
        message: String,
        recommendation: String? = null,
        severity: String = "INFO",
        relatedJobId: Long? = null
    ) {
        scope.launch {
            val notification = NotificationEntity(
                type = type,
                title = title,
                message = message,
                recommendation = recommendation,
                severity = severity,
                relatedJobId = relatedJobId
            )
            database.notificationDao().insertNotification(notification)
        }
    }

    private fun interpretPrintError(reason: String): Pair<String, String> {
        return when {
            reason.contains("media-empty")  -> Pair("Sin papel", "Agrega papel en la bandeja y reintenta la impresión")
            reason.contains("media-jam")    -> Pair("Atasco de papel", "Abre la cubierta y retira el papel atascado cuidadosamente")
            reason.contains("toner-empty") || reason.contains("marker-supply-empty") ->
                Pair("Tinta agotada", "Recarga la tinta y reintenta la impresión")
            reason.contains("toner-low")    -> Pair("Tinta baja", "Considera recargar la tinta pronto")
            reason.contains("cover-open")   -> Pair("Cubierta abierta", "Cierra todas las cubiertas de la impresora")
            reason.contains("offline")      -> Pair("Impresora offline", "Verifica que la impresora esté encendida y en la misma red WiFi")
            reason.contains("document-format-not-supported") ->
                Pair("Formato no soportado", "La impresora no puede imprimir este tipo de archivo")
            reason.contains("service-unavailable") || reason.contains("503") ->
                Pair("Servicio no disponible", "La impresora está ocupada. Espera un momento y reintenta")
            reason.contains("bad-request") || reason.contains("400") ->
                Pair("Error en la solicitud", "El documento puede estar corrupto. Intenta con otro archivo")
            else -> Pair("Error: $reason", "Reinicia la impresora y vuelve a intentarlo")
        }
    }

    private fun interpretScanError(reason: String): Pair<String, String> {
        return when {
            reason.contains("503") || reason.contains("busy") ->
                Pair("Escáner ocupado", "Espera a que el escáner termine la operación actual")
            reason.contains("404") ->
                Pair("Trabajo de escaneo no encontrado", "El escaneo fue cancelado. Intenta de nuevo")
            reason.contains("cover") ->
                Pair("Cubierta del escáner abierta", "Cierra la cubierta del escáner correctamente")
            reason.contains("paper") || reason.contains("feeder") ->
                Pair("Problema con el papel en el ADF", "Retira el papel del alimentador y vuelve a colocarlo")
            reason.contains("timeout") ->
                Pair("Tiempo de espera agotado", "El escáner tardó demasiado. Verifica la conexión WiFi")
            else ->
                Pair("Error al escanear: $reason", "Verifica la conexión a la impresora e intenta de nuevo")
        }
    }
}