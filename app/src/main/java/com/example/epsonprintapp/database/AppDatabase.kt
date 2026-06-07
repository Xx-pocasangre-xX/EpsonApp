package com.example.epsonprintapp.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.epsonprintapp.AppConstants
import com.example.epsonprintapp.database.dao.NotificationDao
import com.example.epsonprintapp.database.dao.PrintJobDao
import com.example.epsonprintapp.database.dao.PrinterDao
import com.example.epsonprintapp.database.dao.ScanJobDao
import com.example.epsonprintapp.database.entities.NotificationEntity
import com.example.epsonprintapp.database.entities.PrintJobEntity
import com.example.epsonprintapp.database.entities.PrinterEntity
import com.example.epsonprintapp.database.entities.ScanJobEntity
import java.util.Date

/**
 * AppDatabase — Base de datos principal Room.
 *
 * Cambios:
 * - exportSchema = true: genera /schemas/1.json para historial de versiones
 * - Sin fallbackToDestructiveMigration: los datos del usuario no se pierden nunca
 *   sin una migración explícita. Si se incrementa `version` sin migración,
 *   Room lanza excepción en lugar de borrar silenciosamente.
 */
@Database(
    entities = [
        PrinterEntity::class,
        PrintJobEntity::class,
        ScanJobEntity::class,
        NotificationEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun printerDao():      PrinterDao
    abstract fun printJobDao():     PrintJobDao
    abstract fun scanJobDao():      ScanJobDao
    abstract fun notificationDao(): NotificationDao

    class Converters {
        @TypeConverter fun fromTimestamp(value: Long?): Date? = value?.let { Date(it) }
        @TypeConverter fun dateToTimestamp(date: Date?): Long? = date?.time
    }

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    AppConstants.DB_NAME
                )
                    // Sin fallbackToDestructiveMigration — migrar explícitamente
                    // cuando cambie el schema. Ejemplo:
                    // .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }

        // Plantilla de migración para versiones futuras:
        // val MIGRATION_1_2 = object : Migration(1, 2) {
        //     override fun migrate(db: SupportSQLiteDatabase) {
        //         db.execSQL("ALTER TABLE printers ADD COLUMN protocol TEXT NOT NULL DEFAULT 'ipp'")
        //     }
        // }
    }
}