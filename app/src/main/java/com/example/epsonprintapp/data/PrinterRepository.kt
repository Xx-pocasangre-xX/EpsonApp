package com.example.epsonprintapp.data

import android.content.Context
import android.util.Log
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.database.entities.PrinterEntity
import com.example.epsonprintapp.network.PrinterDiscovery
import com.example.epsonprintapp.network.PrinterInfo
import com.example.epsonprintapp.printer.IppClient
import com.example.epsonprintapp.printer.PrinterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * PrinterRepository — Fuente de verdad para la gestión de impresoras.
 *
 * Centraliza toda la lógica de:
 * - Guardar/actualizar/eliminar impresoras en Room
 * - Convertir PrinterInfo → PrinterEntity
 * - Consultar estado IPP de impresoras
 * - Seleccionar impresora predeterminada
 * - Agregar impresoras manualmente por IP
 *
 * Uso desde los ViewModels:
 *   val repo = PrinterRepository(application)
 *   repo.getAllPrinters()         // Flow para el RecyclerView
 *   repo.saveDiscoveredPrinter()  // Persistir una encontrada
 *   repo.addByIp("192.168.1.50") // Agregar manualmente
 */
class PrinterRepository(private val context: Context) {

    companion object {
        private const val TAG = "PrinterRepository"
    }

    private val database  = AppDatabase.getInstance(context)
    private val dao       = database.printerDao()
    private val discovery = PrinterDiscovery(context)
    private val ippClient = IppClient(context)

    // ── Observación ───────────────────────────────────────────────────────────

    /** Flow de todas las impresoras ordenadas: predeterminada primero, luego por última vez vista. */
    fun getAllPrinters(): Flow<List<PrinterEntity>> = dao.getAllPrinters()

    // ── Lectura ───────────────────────────────────────────────────────────────

    suspend fun getDefaultPrinter(): PrinterEntity? = dao.getDefaultPrinter()

    suspend fun getPrinterById(id: Long): PrinterEntity? = dao.getPrinterById(id)

    suspend fun getPrinterCount(): Int = dao.getPrinterCount()

    // ── Escritura ─────────────────────────────────────────────────────────────

    /**
     * Guarda una impresora descubierta (mDNS o TCP).
     * Si ya existe una impresora con esa IP, la actualiza en lugar de duplicar.
     * Establece como predeterminada si es la primera impresora.
     *
     * @return El ID asignado por Room (o el existente si fue update)
     */
    suspend fun saveDiscoveredPrinter(info: PrinterInfo): Long = withContext(Dispatchers.IO) {
        val existing  = dao.getPrinterByIp(info.ipAddress)
        val isFirst   = dao.getPrinterCount() == 0

        val entity = PrinterEntity(
            id        = existing?.id ?: 0L,
            name      = info.displayName,
            ipAddress = info.ipAddress,
            ippPort   = info.ippPort,
            ippPath   = info.ippPath,
            esclPath  = info.esclPath,
            model     = info.model,
            isDefault = isFirst || (existing?.isDefault == true),
            isOnline  = true,
            lastSeen  = System.currentTimeMillis()
        )

        val savedId = dao.insertPrinter(entity)
        Log.d(TAG, "Impresora guardada: ${info.displayName} @ ${info.ipAddress} (id=$savedId)")
        savedId
    }

    /**
     * Agrega una impresora por IP ingresada manualmente.
     * Verifica conectividad antes de guardar.
     *
     * @param ip Dirección IP (ej: "192.168.1.50")
     * @param customName Nombre personalizado opcional
     * @param port Puerto IPP (por defecto 631)
     * @return Result con el PrinterEntity guardado, o error descriptivo
     */
    suspend fun addByIp(
        ip:         String,
        customName: String? = null,
        port:       Int     = 631
    ): AddPrinterResult = withContext(Dispatchers.IO) {
        try {
            // 1. Validar formato
            if (!discovery.isValidIpAddress(ip)) {
                return@withContext AddPrinterResult.InvalidIp
            }

            // 2. Verificar que no está duplicada
            val existing = dao.getPrinterByIp(ip.trim())
            if (existing != null) {
                Log.d(TAG, "IP $ip ya existe (id=${existing.id}), actualizando...")
                // Actualizar y retornar la existente
                val updated = existing.copy(
                    name     = customName ?: existing.name,
                    ippPort  = port,
                    lastSeen = System.currentTimeMillis()
                )
                dao.updatePrinter(updated)
                return@withContext AddPrinterResult.AlreadyExists(updated)
            }

            // 3. Intentar conectar
            val info = discovery.connectByIp(ip.trim(), port)
                ?: return@withContext AddPrinterResult.Unreachable(ip)

            // 4. Guardar
            val isFirst = dao.getPrinterCount() == 0
            val entity  = PrinterEntity(
                name      = customName ?: info.displayName,
                ipAddress = ip.trim(),
                ippPort   = port,
                ippPath   = info.ippPath,
                esclPath  = info.esclPath,
                model     = info.model,
                isDefault = isFirst,
                isOnline  = true,
                lastSeen  = System.currentTimeMillis()
            )

            val savedId = dao.insertPrinter(entity)
            val saved   = dao.getPrinterById(savedId)!!
            Log.d(TAG, "Impresora manual guardada: ${saved.name} @ $ip (id=$savedId)")

            AddPrinterResult.Success(saved)

        } catch (e: Exception) {
            Log.e(TAG, "Error agregando impresora por IP $ip: ${e.message}")
            AddPrinterResult.Error(e.message ?: "Error desconocido")
        }
    }

    /**
     * Establece una impresora como predeterminada.
     * Quita el flag de cualquier otra que lo tuviera.
     */
    suspend fun setDefault(printerId: Long) = withContext(Dispatchers.IO) {
        dao.clearDefaultPrinter()
        dao.setDefaultPrinter(printerId)
        Log.d(TAG, "Predeterminada establecida: id=$printerId")
    }

    /**
     * Elimina una impresora del registro.
     * Si era la predeterminada, asigna la siguiente disponible como default.
     */
    suspend fun deletePrinter(printer: PrinterEntity) = withContext(Dispatchers.IO) {
        val wasDefault = printer.isDefault
        dao.deletePrinter(printer)
        Log.d(TAG, "Impresora eliminada: ${printer.name} @ ${printer.ipAddress}")

        if (wasDefault) {
            // Asignar la primera impresora restante como predeterminada
            val remaining = dao.getAllPrintersOnce()
            remaining.firstOrNull()?.let { next ->
                dao.setDefaultPrinter(next.id)
                Log.d(TAG, "Nueva predeterminada asignada: ${next.name}")
            }
        }
    }

    /**
     * Actualiza el nombre de una impresora.
     */
    suspend fun renamePrinter(printer: PrinterEntity, newName: String) = withContext(Dispatchers.IO) {
        dao.updatePrinter(printer.copy(name = newName.trim()))
    }

    // ── Estado IPP ────────────────────────────────────────────────────────────

    /**
     * Consulta el estado actual de una impresora vía IPP.
     * Actualiza isOnline y lastSeen en Room según el resultado.
     *
     * @return PrinterStatus o null si no responde
     */
    suspend fun checkPrinterStatus(printer: PrinterEntity): PrinterStatus? =
        withContext(Dispatchers.IO) {
            try {
                val status = ippClient.getPrinterStatus(printer.ippUrl)
                val isOnline = status != null
                dao.updatePrinterOnlineStatus(printer.id, isOnline)
                status
            } catch (e: Exception) {
                Log.e(TAG, "Error comprobando estado de ${printer.name}: ${e.message}")
                dao.updatePrinterOnlineStatus(printer.id, false)
                null
            }
        }

    /**
     * Verifica el estado de todas las impresoras guardadas.
     * Útil para actualizar badges y estados en la lista.
     *
     * @return Mapa de id → PrinterStatus?
     */
    suspend fun checkAllPrinters(): Map<Long, PrinterStatus?> = withContext(Dispatchers.IO) {
        val printers = dao.getAllPrintersOnce()
        printers.associate { printer ->
            printer.id to runCatching { ippClient.getPrinterStatus(printer.ippUrl) }.getOrNull()
                .also { status ->
                    dao.updatePrinterOnlineStatus(printer.id, status != null)
                }
        }
    }
}

// ── Resultados de addByIp ─────────────────────────────────────────────────────

sealed class AddPrinterResult {
    data class Success(val printer: PrinterEntity)          : AddPrinterResult()
    data class AlreadyExists(val printer: PrinterEntity)    : AddPrinterResult()
    data object InvalidIp                                   : AddPrinterResult()
    data class Unreachable(val ip: String)                  : AddPrinterResult()
    data class Error(val message: String)                   : AddPrinterResult()
}