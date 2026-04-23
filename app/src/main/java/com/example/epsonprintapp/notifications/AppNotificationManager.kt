package com.example.epsonprintapp.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.epsonprintapp.EpsonPrintApp
import com.example.epsonprintapp.R
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.database.entities.NotificationEntity
import com.example.epsonprintapp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AppNotificationManager - Gestión centralizada de notificaciones
 *
 * DOBLE FUNCIÓN:
 * ==============
 * 1. Notificaciones del sistema Android (aparecen en la barra de estado)
 *    → Usando NotificationCompat.Builder
 *
 * 2. Notificaciones persistentes en la DB (historial en la app)
 *    → Guardando en Room Database
 *
 * Por qué dos sistemas?
 * - Las notificaciones del sistema son temporales (el usuario las descarta)
 * - Las de la DB permanecen hasta que el usuario las limpia manualmente
 * - Permite revisar qué pasó aunque hayas cerrado la app
 *
 * TIPOS DE NOTIFICACIÓN:
 * ======================
 * PRINT_SUCCESS → "Impresión completada correctamente"
 * PRINT_ERROR   → "Error al imprimir: [razón]"
 * SCAN_SUCCESS  → "Escaneo completado"
 * SCAN_ERROR    → "Error al escanear: [razón]"
 * INK_LOW       → "Tinta [color] baja ([%]%)"
 * NO_PAPER      → "Sin papel - Agrega papel a la bandeja"
 * PRINTER_OFFLINE → "Impresora desconectada"
 */
class AppNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val database = AppDatabase.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    // IDs únicos para notificaciones del sistema (para poder actualizarlas)
    companion object {
        const val NOTIF_ID_PRINTING = 1001
        const val NOTIF_ID_SCANNING = 1002
        const val NOTIF_ID_ERROR = 1003
        const val NOTIF_ID_INK = 1004
    }

    // =========================================================================
    // NOTIFICACIONES DE IMPRESIÓN
    // =========================================================================

    /**
     * Notificar que una impresión se completó exitosamente
     *
     * Muestra:
     * - Notificación del sistema con ícono de éxito
     * - Guarda en DB para historial
     *
     * @param fileName Nombre del archivo impreso
     * @param jobId ID del trabajo de impresión
     */
    fun notifyPrintSuccess(fileName: String, jobId: Long = -1) {
        val title = "✅ Impresión completada"
        val message = "'$fileName' se imprimió correctamente"
        val recommendation = "Recoge tu documento de la impresora"

        // Notificación del sistema
        showSystemNotification(
            id = NOTIF_ID_PRINTING,
            channelId = EpsonPrintApp.CHANNEL_PRINT,
            title = title,
            message = message
        )

        // Guardar en DB
        saveToDatabase(
            type = "PRINT_SUCCESS",
            title = title,
            message = message,
            recommendation = recommendation,
            severity = "INFO",
            relatedJobId = jobId
        )
    }

    /**
     * Notificar error de impresión con recomendación específica
     *
     * @param errorReason Razón del error (de IPP printer-state-reasons)
     * @param fileName Nombre del archivo que falló
     * @param jobId ID del trabajo
     */
    fun notifyPrintError(errorReason: String, fileName: String, jobId: Long = -1) {
        // Convertir código de error técnico a mensaje amigable
        val (friendlyMessage, recommendation) = interpretPrintError(errorReason)

        val title = "❌ Error de impresión"
        val fullMessage = "'$fileName': $friendlyMessage"

        showSystemNotification(
            id = NOTIF_ID_ERROR,
            channelId = EpsonPrintApp.CHANNEL_ALERTS,
            title = title,
            message = fullMessage,
            priority = NotificationCompat.PRIORITY_HIGH
        )

        saveToDatabase(
            type = "PRINT_ERROR",
            title = title,
            message = fullMessage,
            recommendation = recommendation,
            severity = "ERROR",
            relatedJobId = jobId
        )
    }

    // =========================================================================
    // NOTIFICACIONES DE ESCANEO
    // =========================================================================

    fun notifyScanSuccess(filePath: String, jobId: Long = -1) {
        val fileName = filePath.substringAfterLast("/")
        val title = "✅ Escaneo completado"
        val message = "Documento guardado: $fileName"
        val recommendation = "Puedes encontrarlo en la pantalla de escaneos recientes"

        showSystemNotification(
            id = NOTIF_ID_SCANNING,
            channelId = EpsonPrintApp.CHANNEL_SCAN,
            title = title,
            message = message
        )

        saveToDatabase(
            type = "SCAN_SUCCESS",
            title = title,
            message = message,
            recommendation = recommendation,
            severity = "INFO",
            relatedJobId = jobId
        )
    }

    fun notifyScanError(errorMessage: String, jobId: Long = -1) {
        val title = "❌ Error de escaneo"
        val (friendly, recommendation) = interpretScanError(errorMessage)

        showSystemNotification(
            id = NOTIF_ID_ERROR,
            channelId = EpsonPrintApp.CHANNEL_ALERTS,
            title = title,
            message = friendly,
            priority = NotificationCompat.PRIORITY_HIGH
        )

        saveToDatabase(
            type = "SCAN_ERROR",
            title = title,
            message = friendly,
            recommendation = recommendation,
            severity = "ERROR",
            relatedJobId = jobId
        )
    }

    // =========================================================================
    // NOTIFICACIONES DE ESTADO DE IMPRESORA
    // =========================================================================

    /**
     * Notificar tinta baja
     *
     * @param color Nombre del color (cyan, magenta, yellow, black)
     * @param levelPercent Porcentaje actual de tinta
     */
    fun notifyInkLow(color: String, levelPercent: Int) {
        val colorName = when (color.lowercase()) {
            "cyan" -> "Cian"
            "magenta" -> "Magenta"
            "yellow" -> "Amarillo"
            "black" -> "Negro"
            else -> color
        }

        val title = "⚠️ Tinta $colorName baja"
        val message = "Nivel de tinta $colorName: $levelPercent%"
        val recommendation = "Recarga la tinta $colorName pronto para evitar interrupciones"

        showSystemNotification(
            id = NOTIF_ID_INK,
            channelId = EpsonPrintApp.CHANNEL_ALERTS,
            title = title,
            message = message
        )

        saveToDatabase(
            type = "INK_LOW",
            title = title,
            message = message,
            recommendation = recommendation,
            severity = "WARNING"
        )
    }

    fun notifyNoPaper() {
        val title = "📄 Sin papel"
        val message = "La impresora no tiene papel"
        val recommendation = "Agrega hojas de papel en la bandeja de entrada y reinicia la impresión"

        showSystemNotification(
            id = NOTIF_ID_ERROR,
            channelId = EpsonPrintApp.CHANNEL_ALERTS,
            title = title,
            message = message,
            priority = NotificationCompat.PRIORITY_HIGH
        )

        saveToDatabase(
            type = "NO_PAPER",
            title = title,
            message = message,
            recommendation = recommendation,
            severity = "WARNING"
        )
    }

    fun notifyPrinterOffline() {
        val title = "📡 Impresora desconectada"
        val message = "No se puede conectar con la impresora"
        val recommendation = "Verifica que la impresora esté encendida y conectada al mismo WiFi"

        showSystemNotification(
            id = NOTIF_ID_ERROR,
            channelId = EpsonPrintApp.CHANNEL_ALERTS,
            title = title,
            message = message
        )

        saveToDatabase(
            type = "PRINTER_OFFLINE",
            title = title,
            message = message,
            recommendation = recommendation,
            severity = "ERROR"
        )
    }

    // =========================================================================
    // MÉTODOS PRIVADOS
    // =========================================================================

    /**
     * Mostrar notificación en el sistema Android
     *
     * Crea una notificación visible en la barra de estado.
     * Al tocarla, abre la app en MainActivity.
     */
    private fun showSystemNotification(
        id: Int,
        channelId: String,
        title: String,
        message: String,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT
    ) {
        // Intent para abrir la app al tocar la notificación
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_printer_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)  // Se descarta al tocar
            .build()

        notificationManager.notify(id, notification)
    }

    /**
     * Guardar notificación en la base de datos
     *
     * Se ejecuta en coroutine en el thread IO para no bloquear UI.
     */
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

    /**
     * Interpretar errores de impresión IPP a mensajes amigables
     *
     * Los códigos IPP printer-state-reasons son técnicos.
     * Los convertimos a mensajes que cualquier usuario entiende.
     *
     * @return Pair(mensajeAmigable, recomendación)
     */
    private fun interpretPrintError(reason: String): Pair<String, String> {
        return when {
            reason.contains("media-empty") ->
                Pair("Sin papel", "Agrega papel en la bandeja y reintenta la impresión")
            reason.contains("media-jam") ->
                Pair("Atasco de papel", "Abre la cubierta y retira el papel atascado cuidadosamente")
            reason.contains("toner-empty") || reason.contains("marker-supply-empty") ->
                Pair("Tinta agotada", "Recarga la tinta y reintenta la impresión")
            reason.contains("toner-low") ->
                Pair("Tinta baja", "Considera recargar la tinta pronto")
            reason.contains("cover-open") ->
                Pair("Cubierta abierta", "Cierra todas las cubiertas de la impresora")
            reason.contains("offline") ->
                Pair("Impresora offline", "Verifica que la impresora esté encendida y en la misma red WiFi")
            reason.contains("document-format-not-supported") ->
                Pair("Formato no soportado", "La impresora no puede imprimir este tipo de archivo")
            reason.contains("service-unavailable") || reason.contains("503") ->
                Pair("Servicio no disponible", "La impresora está ocupada. Espera un momento y reintenta")
            reason.contains("bad-request") || reason.contains("400") ->
                Pair("Error en la solicitud", "El documento puede estar corrupto. Intenta con otro archivo")
            else ->
                Pair("Error: $reason", "Reinicia la impresora y vuelve a intentarlo")
        }
    }

    /**
     * Interpretar errores de escaneo eSCL
     */
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
