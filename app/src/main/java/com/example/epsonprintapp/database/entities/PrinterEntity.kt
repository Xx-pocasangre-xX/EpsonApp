package com.example.epsonprintapp.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

/**
 * ===================================================================
 * ENTIDADES DE BASE DE DATOS (Room ORM)
 * ===================================================================
 *
 * Room es la capa de abstracción de SQLite de Android.
 * Cada @Entity se convierte en una tabla SQL.
 * Los campos son columnas, @PrimaryKey es la clave primaria.
 *
 * Las anotaciones Room generan automáticamente el SQL en tiempo de compilación.
 */

// ===================================================================
// TABLA: printers (Impresoras guardadas)
// ===================================================================

/**
 * PrinterEntity - Almacena impresoras descubiertas y configuradas
 *
 * SQL equivalente:
 * CREATE TABLE printers (
 *   id INTEGER PRIMARY KEY AUTOINCREMENT,
 *   name TEXT NOT NULL,
 *   ipAddress TEXT NOT NULL,
 *   ippPort INTEGER NOT NULL DEFAULT 631,
 *   ippPath TEXT NOT NULL DEFAULT '/ipp/print',
 *   esclPath TEXT NOT NULL DEFAULT '/eSCL',
 *   model TEXT,
 *   isDefault INTEGER NOT NULL DEFAULT 0,
 *   lastSeen INTEGER NOT NULL,
 *   isOnline INTEGER NOT NULL DEFAULT 0
 * )
 */
@Entity(tableName = "printers")
data class PrinterEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Nombre descriptivo de la impresora (del servicio mDNS) */
    val name: String,

    /** Dirección IP en la red local */
    val ipAddress: String,

    /** Puerto IPP (generalmente 631) */
    val ippPort: Int = 631,

    /** Ruta del endpoint IPP */
    val ippPath: String = "/ipp/print",

    /** Ruta del endpoint eSCL para escaneo */
    val esclPath: String = "/eSCL",

    /** Modelo de impresora (ej: "EPSON L3560 Series") */
    val model: String? = null,

    /** Si es la impresora predeterminada */
    val isDefault: Boolean = false,

    /** Timestamp de la última vez que se detectó online */
    val lastSeen: Long = System.currentTimeMillis(),

    /** Si está actualmente online */
    val isOnline: Boolean = false
) {
    /** URL completa del endpoint IPP */
    val ippUrl: String get() = "http://$ipAddress:$ippPort$ippPath"

    /** URL base del endpoint eSCL */
    val esclUrl: String get() = "http://$ipAddress$esclPath"
}

// ===================================================================
// TABLA: print_jobs (Historial de trabajos de impresión)
// ===================================================================

/**
 * PrintJobEntity - Registra cada trabajo de impresión enviado
 *
 * Permite:
 * - Historial de lo impreso
 * - Estado de trabajos en curso
 * - Reimpresión de documentos recientes
 */
@Entity(tableName = "print_jobs")
data class PrintJobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** ID del trabajo asignado por la impresora (de la respuesta IPP) */
    val ippJobId: Int = -1,

    /** ID de la impresora usada (foreign key a printers.id) */
    val printerId: Long,

    /** Nombre del archivo impreso */
    val fileName: String,

    /** Tipo MIME del documento */
    val mimeType: String,

    /** Tamaño del archivo en bytes */
    val fileSize: Long = 0,

    /**
     * Estado del trabajo:
     * PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
     */
    val status: String = "PENDING",

    /** Número de copias */
    val copies: Int = 1,

    /** Modo de color: COLOR, MONOCHROME */
    val colorMode: String = "COLOR",

    /** Tamaño de papel: A4, LETTER, etc. */
    val paperSize: String = "A4",

    /** Si es doble cara */
    val isDuplex: Boolean = false,

    /** Mensaje de error si hubo fallo */
    val errorMessage: String? = null,

    /** Timestamp de creación */
    val createdAt: Long = System.currentTimeMillis(),

    /** Timestamp de finalización */
    val completedAt: Long? = null
)

// ===================================================================
// TABLA: scan_jobs (Historial de trabajos de escaneo)
// ===================================================================

/**
 * ScanJobEntity - Registra cada escaneo realizado
 *
 * Guarda referencia al archivo escaneado en el almacenamiento.
 */
@Entity(tableName = "scan_jobs")
data class ScanJobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** ID de la impresora/escáner usada */
    val printerId: Long,

    /** Ruta del archivo guardado */
    val filePath: String? = null,

    /** Nombre del archivo */
    val fileName: String = "scan_${System.currentTimeMillis()}",

    /** Tipo MIME del resultado */
    val mimeType: String = "image/jpeg",

    /** Resolución del escaneo en DPI */
    val resolution: Int = 300,

    /** Modo de color */
    val colorMode: String = "COLOR",

    /** Tamaño de papel */
    val paperSize: String = "A4",

    /** Estado del escaneo */
    val status: String = "PENDING",

    /** Tamaño del archivo en bytes */
    val fileSize: Long = 0,

    /** Mensaje de error si hubo fallo */
    val errorMessage: String? = null,

    /** Timestamp de creación */
    val createdAt: Long = System.currentTimeMillis()
)

// ===================================================================
// TABLA: notifications (Sistema de notificaciones persistentes)
// ===================================================================

/**
 * NotificationEntity - Almacena todas las notificaciones del sistema
 *
 * A diferencia de las notificaciones push de Android (que desaparecen),
 * estas se guardan en DB para tener un historial permanente.
 *
 * Tipos de notificación:
 * - PRINT_SUCCESS: Impresión completada
 * - PRINT_ERROR: Error de impresión
 * - SCAN_SUCCESS: Escaneo completado
 * - SCAN_ERROR: Error de escaneo
 * - PRINTER_OFFLINE: Impresora desconectada
 * - INK_LOW: Tinta baja
 * - NO_PAPER: Sin papel
 */
@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Tipo de notificación:
     * PRINT_SUCCESS, PRINT_ERROR, SCAN_SUCCESS, SCAN_ERROR,
     * PRINTER_OFFLINE, INK_LOW, NO_PAPER, INFO
     */
    val type: String,

    /** Título corto de la notificación */
    val title: String,

    /** Mensaje descriptivo */
    val message: String,

    /**
     * Recomendación de acción para el usuario
     * Ej: "Agrega papel en la bandeja" / "Recarga la tinta cyan"
     */
    val recommendation: String? = null,

    /** Si el usuario la ha leído */
    val isRead: Boolean = false,

    /** ID del trabajo relacionado (print o scan job ID) */
    val relatedJobId: Long? = null,

    /** Nivel de importancia: INFO, WARNING, ERROR */
    val severity: String = "INFO",

    /** Timestamp de creación */
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Icono según el tipo de notificación
     * Retorna el nombre del drawable resource
     */
    val iconName: String get() = when (type) {
        "PRINT_SUCCESS" -> "ic_print_success"
        "PRINT_ERROR" -> "ic_print_error"
        "SCAN_SUCCESS" -> "ic_scan_success"
        "SCAN_ERROR" -> "ic_scan_error"
        "PRINTER_OFFLINE" -> "ic_printer_offline"
        "INK_LOW" -> "ic_ink_low"
        "NO_PAPER" -> "ic_no_paper"
        else -> "ic_info"
    }

    /**
     * Color del indicador según severidad
     */
    val severityColor: String get() = when (severity) {
        "ERROR" -> "#F44336"   // Rojo
        "WARNING" -> "#FF9800" // Naranja
        else -> "#4CAF50"      // Verde
    }
}
