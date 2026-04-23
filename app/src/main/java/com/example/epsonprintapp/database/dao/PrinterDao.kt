package com.example.epsonprintapp.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.epsonprintapp.database.entities.NotificationEntity
import com.example.epsonprintapp.database.entities.PrintJobEntity
import com.example.epsonprintapp.database.entities.PrinterEntity
import com.example.epsonprintapp.database.entities.ScanJobEntity
import kotlinx.coroutines.flow.Flow

/**
 * ===================================================================
 * DAOs (Data Access Objects) - Interfaz de acceso a la base de datos
 * ===================================================================
 *
 * Room genera el código SQL automáticamente a partir de estas interfaces.
 * Las anotaciones @Query, @Insert, @Update, @Delete describen las operaciones.
 *
 * CONVENCIONES:
 * - Funciones suspend = se ejecutan en coroutine (sin bloquear UI thread)
 * - Flow<T> = actualización automática cuando cambian los datos
 * - LiveData<T> = observable para la UI
 */

// ===================================================================
// DAO: Impresoras
// ===================================================================

/**
 * PrinterDao - Operaciones CRUD para impresoras guardadas
 */
@Dao
interface PrinterDao {

    /**
     * Insertar nueva impresora
     * OnConflict.REPLACE: si ya existe (misma IP), la reemplaza
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrinter(printer: PrinterEntity): Long

    /**
     * Actualizar impresora existente
     */
    @Update
    suspend fun updatePrinter(printer: PrinterEntity)

    /**
     * Eliminar impresora
     */
    @Delete
    suspend fun deletePrinter(printer: PrinterEntity)

    /**
     * Obtener todas las impresoras (Flow = auto-actualizable)
     *
     * La UI se actualiza automáticamente cuando cambia la lista.
     * Útil para el RecyclerView de impresoras.
     */
    @Query("SELECT * FROM printers ORDER BY isDefault DESC, lastSeen DESC")
    fun getAllPrinters(): Flow<List<PrinterEntity>>

    /**
     * Obtener la impresora predeterminada
     *
     * @return Impresora marcada como default, o null si no hay ninguna
     */
    @Query("SELECT * FROM printers WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultPrinter(): PrinterEntity?

    /**
     * Obtener impresora por ID
     */
    @Query("SELECT * FROM printers WHERE id = :id")
    suspend fun getPrinterById(id: Long): PrinterEntity?

    /**
     * Obtener impresora por IP
     * Evita duplicados al redescubrir impresoras
     */
    @Query("SELECT * FROM printers WHERE ipAddress = :ipAddress LIMIT 1")
    suspend fun getPrinterByIp(ipAddress: String): PrinterEntity?

    /**
     * Marcar impresora como predeterminada
     * Primero limpia todas, luego marca la seleccionada
     */
    @Query("UPDATE printers SET isDefault = 0")
    suspend fun clearDefaultPrinter()

    @Query("UPDATE printers SET isDefault = 1 WHERE id = :id")
    suspend fun setDefaultPrinter(id: Long)

    /**
     * Actualizar estado online/offline
     */
    @Query("UPDATE printers SET isOnline = :isOnline, lastSeen = :timestamp WHERE id = :id")
    suspend fun updatePrinterOnlineStatus(id: Long, isOnline: Boolean, timestamp: Long = System.currentTimeMillis())

    /**
     * Contar impresoras registradas
     */
    @Query("SELECT COUNT(*) FROM printers")
    suspend fun getPrinterCount(): Int
}

// ===================================================================
// DAO: Trabajos de Impresión
// ===================================================================

/**
 * PrintJobDao - Historial y gestión de trabajos de impresión
 */
@Dao
interface PrintJobDao {

    @Insert
    suspend fun insertPrintJob(job: PrintJobEntity): Long

    @Update
    suspend fun updatePrintJob(job: PrintJobEntity)

    /**
     * Obtener todos los trabajos de impresión
     * Ordenados por más recientes primero
     */
    @Query("SELECT * FROM print_jobs ORDER BY createdAt DESC")
    fun getAllPrintJobs(): Flow<List<PrintJobEntity>>

    /**
     * Obtener trabajos de impresión con paginación
     * Para no cargar todos los registros en memoria
     *
     * @param limit Número máximo de registros
     * @param offset Desde qué registro empezar
     */
    @Query("SELECT * FROM print_jobs ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getPrintJobsPaged(limit: Int = 20, offset: Int = 0): List<PrintJobEntity>

    /**
     * Obtener estadísticas de impresión
     * Para el dashboard
     */
    @Query("SELECT COUNT(*) FROM print_jobs WHERE status = 'COMPLETED'")
    suspend fun getCompletedJobsCount(): Int

    @Query("SELECT COUNT(*) FROM print_jobs WHERE status = 'FAILED'")
    suspend fun getFailedJobsCount(): Int

    /**
     * Obtener trabajo activo (procesándose actualmente)
     */
    @Query("SELECT * FROM print_jobs WHERE status IN ('PENDING', 'PROCESSING') ORDER BY createdAt DESC LIMIT 1")
    fun getActiveJob(): Flow<PrintJobEntity?>

    /**
     * Actualizar estado de un trabajo específico
     */
    @Query("UPDATE print_jobs SET status = :status, errorMessage = :error WHERE id = :jobId")
    suspend fun updateJobStatus(jobId: Long, status: String, error: String? = null)

    /**
     * Marcar trabajo como completado
     */
    @Query("UPDATE print_jobs SET status = 'COMPLETED', completedAt = :timestamp WHERE id = :jobId")
    suspend fun markJobCompleted(jobId: Long, timestamp: Long = System.currentTimeMillis())

    /**
     * Eliminar registros más antiguos de X días (limpieza automática)
     */
    @Query("DELETE FROM print_jobs WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteOldJobs(cutoffTimestamp: Long)
}

// ===================================================================
// DAO: Trabajos de Escaneo
// ===================================================================

/**
 * ScanJobDao - Historial de escaneos
 */
@Dao
interface ScanJobDao {

    @Insert
    suspend fun insertScanJob(job: ScanJobEntity): Long

    @Update
    suspend fun updateScanJob(job: ScanJobEntity)

    @Delete
    suspend fun deleteScanJob(job: ScanJobEntity)

    /**
     * Todos los escaneos, más recientes primero
     */
    @Query("SELECT * FROM scan_jobs ORDER BY createdAt DESC")
    fun getAllScanJobs(): Flow<List<ScanJobEntity>>

    /**
     * Escaneos recientes (últimos 50)
     */
    @Query("SELECT * FROM scan_jobs ORDER BY createdAt DESC LIMIT 50")
    suspend fun getRecentScanJobs(): List<ScanJobEntity>

    @Query("UPDATE scan_jobs SET status = :status, errorMessage = :error WHERE id = :jobId")
    suspend fun updateScanJobStatus(jobId: Long, status: String, error: String? = null)

    @Query("UPDATE scan_jobs SET filePath = :filePath, fileSize = :fileSize, status = 'COMPLETED' WHERE id = :jobId")
    suspend fun markScanCompleted(jobId: Long, filePath: String, fileSize: Long)

    @Query("SELECT COUNT(*) FROM scan_jobs WHERE status = 'COMPLETED'")
    suspend fun getCompletedScansCount(): Int
}

// ===================================================================
// DAO: Notificaciones
// ===================================================================

/**
 * NotificationDao - Sistema persistente de notificaciones
 *
 * Las notificaciones se guardan en DB para que el usuario
 * pueda revisarlas aunque las haya descartado del sistema.
 */
@Dao
interface NotificationDao {

    @Insert
    suspend fun insertNotification(notification: NotificationEntity): Long

    @Update
    suspend fun updateNotification(notification: NotificationEntity)

    @Delete
    suspend fun deleteNotification(notification: NotificationEntity)

    /**
     * Todas las notificaciones, más recientes primero
     * Flow para actualización automática en la UI
     */
    @Query("SELECT * FROM notifications ORDER BY createdAt DESC")
    fun getAllNotifications(): Flow<List<NotificationEntity>>

    /**
     * Solo notificaciones no leídas
     * Para mostrar badge/contador en el ícono
     */
    @Query("SELECT * FROM notifications WHERE isRead = 0 ORDER BY createdAt DESC")
    fun getUnreadNotifications(): Flow<List<NotificationEntity>>

    /**
     * Contar notificaciones no leídas
     * Para el badge en el botón de notificaciones del dashboard
     */
    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    /**
     * Marcar notificación específica como leída
     */
    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)

    /**
     * Marcar TODAS las notificaciones como leídas
     * Acción "Marcar todas como leídas"
     */
    @Query("UPDATE notifications SET isRead = 1")
    suspend fun markAllAsRead()

    /**
     * Notificaciones por tipo (para filtrar)
     */
    @Query("SELECT * FROM notifications WHERE type = :type ORDER BY createdAt DESC")
    fun getNotificationsByType(type: String): Flow<List<NotificationEntity>>

    /**
     * Eliminar notificaciones antiguas (más de 30 días)
     */
    @Query("DELETE FROM notifications WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteOldNotifications(cutoffTimestamp: Long)

    /**
     * Eliminar todas las notificaciones
     */
    @Query("DELETE FROM notifications")
    suspend fun deleteAllNotifications()

    /**
     * Obtener las últimas N notificaciones para preview en dashboard
     */
    @Query("SELECT * FROM notifications ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentNotifications(limit: Int = 5): List<NotificationEntity>
}
