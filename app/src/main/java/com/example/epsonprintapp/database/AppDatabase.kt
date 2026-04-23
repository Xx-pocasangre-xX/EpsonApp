package com.example.epsonprintapp.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
 * AppDatabase - Base de datos principal de la aplicación
 *
 * Room Database es la capa de abstracción sobre SQLite.
 * Esta clase es el punto de entrada a todos los DAOs.
 *
 * PARÁMETROS DE @Database:
 * - entities: Lista de todas las tablas (entidades)
 * - version: Versión del esquema (incrementar con cada cambio)
 * - exportSchema: Exportar JSON del esquema para control de versiones
 *
 * PATRÓN SINGLETON:
 * Se usa un singleton para evitar múltiples instancias del mismo DB,
 * lo cual causaría problemas de concurrencia y desperdicio de memoria.
 *
 * UBICACIÓN DEL ARCHIVO:
 * /data/data/com.example.epsonprintapp/databases/epsonprint.db
 * (Solo accesible con root o Android Debug Bridge)
 */
@Database(
    entities = [
        PrinterEntity::class,
        PrintJobEntity::class,
        ScanJobEntity::class,
        NotificationEntity::class
    ],
    version = 1,
    exportSchema = false  // En producción cambiar a true y configurar schemaLocation
)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {

    // ===== ABSTRACT DAOs =====
    // Room genera las implementaciones automáticamente

    /** DAO para operaciones con impresoras */
    abstract fun printerDao(): PrinterDao

    /** DAO para historial de trabajos de impresión */
    abstract fun printJobDao(): PrintJobDao

    /** DAO para historial de escaneos */
    abstract fun scanJobDao(): ScanJobDao

    /** DAO para sistema de notificaciones */
    abstract fun notificationDao(): NotificationDao

    // ===== TYPE CONVERTERS =====
    // Room solo soporta tipos primitivos. Para tipos complejos usamos converters.

    /**
     * Converters - Convierte tipos no soportados por SQLite
     *
     * Date → Long (timestamp en milisegundos)
     * Esto permite almacenar fechas como números en SQLite
     */
    class Converters {
        @TypeConverter
        fun fromTimestamp(value: Long?): Date? {
            return value?.let { Date(it) }
        }

        @TypeConverter
        fun dateToTimestamp(date: Date?): Long? {
            return date?.time
        }
    }

    companion object {
        /** Nombre del archivo de base de datos */
        private const val DATABASE_NAME = "epsonprint.db"

        // @Volatile asegura que los cambios a esta variable
        // sean inmediatamente visibles para todos los threads
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Obtener la instancia singleton de la base de datos
         *
         * Usa double-checked locking para thread-safety sin
         * synchronization innecesaria en cada llamada.
         *
         * @param context Application context
         * @return Instancia única de AppDatabase
         */
        fun getInstance(context: Context): AppDatabase {
            // Primera verificación (sin lock para performance)
            return INSTANCE ?: synchronized(this) {
                // Segunda verificación (con lock para thread-safety)
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        /**
         * Construir la base de datos
         *
         * Room.databaseBuilder crea o abre el archivo SQLite.
         *
         * fallbackToDestructiveMigration:
         * Si la versión del DB cambia y no hay Migration definida,
         * elimina y recrea la DB. En producción, definir migraciones!
         *
         * Ejemplo de migración manual:
         * val MIGRATION_1_2 = object : Migration(1, 2) {
         *     override fun migrate(database: SupportSQLiteDatabase) {
         *         database.execSQL("ALTER TABLE printers ADD COLUMN firmware TEXT")
         *     }
         * }
         */
        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()  // Solo para desarrollo
                // .addMigrations(MIGRATION_1_2)    // Para producción
                .build()
        }
    }
}
