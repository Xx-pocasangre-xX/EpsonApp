package com.example.epsonprintapp.database.dao

import androidx.room.*
import com.example.epsonprintapp.database.entities.NotificationEntity
import com.example.epsonprintapp.database.entities.PrintJobEntity
import com.example.epsonprintapp.database.entities.PrinterEntity
import com.example.epsonprintapp.database.entities.ScanJobEntity
import kotlinx.coroutines.flow.Flow

// ── PrinterDao ─────────────────────────────────────────────────────────────────

@Dao
interface PrinterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrinter(printer: PrinterEntity): Long

    @Update
    suspend fun updatePrinter(printer: PrinterEntity)

    @Delete
    suspend fun deletePrinter(printer: PrinterEntity)

    @Query("SELECT * FROM printers ORDER BY isDefault DESC, lastSeen DESC")
    fun getAllPrinters(): Flow<List<PrinterEntity>>

    @Query("SELECT * FROM printers WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultPrinter(): PrinterEntity?

    @Query("SELECT * FROM printers WHERE id = :id")
    suspend fun getPrinterById(id: Long): PrinterEntity?

    @Query("SELECT * FROM printers WHERE ipAddress = :ipAddress LIMIT 1")
    suspend fun getPrinterByIp(ipAddress: String): PrinterEntity?

    @Query("UPDATE printers SET isDefault = 0")
    suspend fun clearDefaultPrinter()

    @Query("UPDATE printers SET isDefault = 1 WHERE id = :id")
    suspend fun setDefaultPrinter(id: Long)

    @Query("UPDATE printers SET isOnline = :isOnline, lastSeen = :timestamp WHERE id = :id")
    suspend fun updatePrinterOnlineStatus(id: Long, isOnline: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM printers")
    suspend fun getPrinterCount(): Int
}

// ── PrintJobDao ────────────────────────────────────────────────────────────────

@Dao
interface PrintJobDao {

    @Insert
    suspend fun insertPrintJob(job: PrintJobEntity): Long

    @Update
    suspend fun updatePrintJob(job: PrintJobEntity)

    @Query("SELECT * FROM print_jobs ORDER BY createdAt DESC")
    fun getAllPrintJobs(): Flow<List<PrintJobEntity>>

    @Query("SELECT * FROM print_jobs ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getPrintJobsPaged(limit: Int = 20, offset: Int = 0): List<PrintJobEntity>

    @Query("SELECT COUNT(*) FROM print_jobs WHERE status = 'COMPLETED'")
    suspend fun getCompletedJobsCount(): Int

    @Query("SELECT COUNT(*) FROM print_jobs WHERE status = 'FAILED'")
    suspend fun getFailedJobsCount(): Int

    @Query("SELECT * FROM print_jobs WHERE status IN ('PENDING', 'PROCESSING') ORDER BY createdAt DESC LIMIT 1")
    fun getActiveJob(): Flow<PrintJobEntity?>

    @Query("UPDATE print_jobs SET status = :status, errorMessage = :error WHERE id = :jobId")
    suspend fun updateJobStatus(jobId: Long, status: String, error: String? = null)

    @Query("UPDATE print_jobs SET status = 'COMPLETED', completedAt = :timestamp WHERE id = :jobId")
    suspend fun markJobCompleted(jobId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM print_jobs WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteOldJobs(cutoffTimestamp: Long)
}

// ── ScanJobDao ─────────────────────────────────────────────────────────────────

@Dao
interface ScanJobDao {

    @Insert
    suspend fun insertScanJob(job: ScanJobEntity): Long

    @Update
    suspend fun updateScanJob(job: ScanJobEntity)

    @Delete
    suspend fun deleteScanJob(job: ScanJobEntity)

    @Query("SELECT * FROM scan_jobs ORDER BY createdAt DESC")
    fun getAllScanJobs(): Flow<List<ScanJobEntity>>

    @Query("SELECT * FROM scan_jobs ORDER BY createdAt DESC LIMIT 50")
    suspend fun getRecentScanJobs(): List<ScanJobEntity>

    @Query("UPDATE scan_jobs SET status = :status, errorMessage = :error WHERE id = :jobId")
    suspend fun updateScanJobStatus(jobId: Long, status: String, error: String? = null)

    @Query("UPDATE scan_jobs SET filePath = :filePath, fileSize = :fileSize, status = 'COMPLETED' WHERE id = :jobId")
    suspend fun markScanCompleted(jobId: Long, filePath: String, fileSize: Long)

    @Query("SELECT COUNT(*) FROM scan_jobs WHERE status = 'COMPLETED'")
    suspend fun getCompletedScansCount(): Int
}

// ── NotificationDao ────────────────────────────────────────────────────────────

@Dao
interface NotificationDao {

    @Insert
    suspend fun insertNotification(notification: NotificationEntity): Long

    @Update
    suspend fun updateNotification(notification: NotificationEntity)

    @Delete
    suspend fun deleteNotification(notification: NotificationEntity)

    // FIX: add deleteById used by NotificationsViewModel
    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM notifications ORDER BY createdAt DESC")
    fun getAllNotifications(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE isRead = 0 ORDER BY createdAt DESC")
    fun getUnreadNotifications(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)

    @Query("UPDATE notifications SET isRead = 1")
    suspend fun markAllAsRead()

    @Query("SELECT * FROM notifications WHERE type = :type ORDER BY createdAt DESC")
    fun getNotificationsByType(type: String): Flow<List<NotificationEntity>>

    @Query("DELETE FROM notifications WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteOldNotifications(cutoffTimestamp: Long)

    // FIX: rename deleteAllNotifications → clearAll so ViewModel compiles
    @Query("DELETE FROM notifications")
    suspend fun clearAll()

    @Query("SELECT * FROM notifications ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentNotifications(limit: Int = 5): List<NotificationEntity>
}