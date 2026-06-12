package com.example.epsonprintapp.data

import android.util.Log
import com.example.epsonprintapp.database.dao.PrinterDao
import com.example.epsonprintapp.database.entities.PrinterEntity
import com.example.epsonprintapp.network.PrinterDiscovery
import com.example.epsonprintapp.network.PrinterInfo
import com.example.epsonprintapp.printer.IppClient
import com.example.epsonprintapp.printer.PrinterStatus
import kotlinx.coroutines.flow.Flow

/**
 * PrinterRepository — ÚNICA fuente de verdad para la gestión de impresoras.
 *
 * Todos los ViewModels pasan por aquí; ninguno escribe en PrinterDao
 * directamente. Esto garantiza que reglas como "el puerto IPP nunca es 9100"
 * o "la primera impresora es la predeterminada" se apliquen en TODOS los
 * caminos (mDNS, TCP scan, IP manual), no solo en algunos.
 *
 * Las dependencias se inyectan desde AppContainer — sin Context: el
 * repositorio es testeable en JVM puro con fakes de DAO/discovery/IPP.
 *
 * Contrato de concurrencia: todas las funciones suspend son main-safe.
 * (Room y los clientes de red ya aíslan su I/O internamente.)
 */
class PrinterRepository(
    private val dao:       PrinterDao,
    private val discovery: PrinterDiscovery,
    private val ippClient: IppClient
) {

    companion object {
        private const val TAG = "PrinterRepository"

        /** IPP siempre es 631. 9100 es RAW/PDL y 0 es "desconocido". */
        fun normalizeIppPort(port: Int): Int =
            if (port == 9100 || port <= 0) 631 else port
    }

    // ── Observación ───────────────────────────────────────────────────────────

    /** Flow de todas las impresoras: predeterminada primero, luego por última vez vista. */
    fun getAllPrinters(): Flow<List<PrinterEntity>> = dao.getAllPrinters()

    // ── Lectura ───────────────────────────────────────────────────────────────

    suspend fun getDefaultPrinter(): PrinterEntity? = dao.getDefaultPrinter()

    suspend fun getPrinterById(id: Long): PrinterEntity? = dao.getPrinterById(id)

    suspend fun getAllPrintersOnce(): List<PrinterEntity> = dao.getAllPrintersOnce()

    // ── Escritura ─────────────────────────────────────────────────────────────

    /**
     * Guarda una impresora descubierta (mDNS, TCP o manual).
     * Si ya existe una con esa IP, la actualiza en lugar de duplicar.
     * La primera impresora registrada queda como predeterminada.
     *
     * @return El ID asignado por Room
     */
    suspend fun saveDiscoveredPrinter(info: PrinterInfo): Long {
        val existing = dao.getPrinterByIp(info.ipAddress)
        val isFirst  = dao.getPrinterCount() == 0

        val entity = PrinterEntity(
            id        = existing?.id ?: 0L,
            name      = info.displayName,
            ipAddress = info.ipAddress,
            ippPort   = normalizeIppPort(info.ippPort),
            ippPath   = info.ippPath,
            esclPath  = info.esclPath,
            model     = info.model,
            isDefault = isFirst || (existing?.isDefault == true),
            isOnline  = true,
            lastSeen  = System.currentTimeMillis()
        )

        val savedId = dao.insertPrinter(entity)
        Log.d(TAG, "Impresora guardada: ${info.displayName} @ ${info.ipAddress} (id=$savedId)")
        return savedId
    }

    /**
     * Agrega una impresora por IP ingresada manualmente.
     * Verifica conectividad antes de guardar.
     */
    suspend fun addByIp(
        ip:         String,
        customName: String? = null,
        port:       Int     = 631
    ): AddPrinterResult {
        val trimmedIp = ip.trim()
        try {
            // 1. Validar formato
            if (!discovery.isValidIpAddress(trimmedIp)) {
                return AddPrinterResult.InvalidIp
            }

            // 2. Si ya existe, actualizar en lugar de duplicar
            val existing = dao.getPrinterByIp(trimmedIp)
            if (existing != null) {
                Log.d(TAG, "IP $trimmedIp ya existe (id=${existing.id}), actualizando…")
                val updated = existing.copy(
                    name     = customName?.takeIf { it.isNotBlank() } ?: existing.name,
                    ippPort  = normalizeIppPort(port),
                    lastSeen = System.currentTimeMillis()
                )
                dao.updatePrinter(updated)
                return AddPrinterResult.AlreadyExists(updated)
            }

            // 3. Intentar conectar
            val info = discovery.connectByIp(trimmedIp, port)
                ?: return AddPrinterResult.Unreachable(trimmedIp)

            // 4. Guardar — sin roundtrip extra a la BD ni !!: ya tenemos la entidad
            val isFirst = dao.getPrinterCount() == 0
            val entity  = PrinterEntity(
                name      = customName?.takeIf { it.isNotBlank() } ?: info.displayName,
                ipAddress = trimmedIp,
                ippPort   = normalizeIppPort(port),
                ippPath   = info.ippPath,
                esclPath  = info.esclPath,
                model     = info.model,
                isDefault = isFirst,
                isOnline  = true,
                lastSeen  = System.currentTimeMillis()
            )
            val savedId = dao.insertPrinter(entity)
            Log.d(TAG, "Impresora manual guardada: ${entity.name} @ $trimmedIp (id=$savedId)")

            return AddPrinterResult.Success(entity.copy(id = savedId))

        } catch (e: Exception) {
            Log.e(TAG, "Error agregando impresora por IP $trimmedIp: ${e.message}")
            return AddPrinterResult.Error(e.message ?: "Error desconocido")
        }
    }

    /**
     * Establece una impresora como predeterminada.
     * Quita el flag de cualquier otra que lo tuviera.
     */
    suspend fun setDefault(printerId: Long) {
        dao.clearDefaultPrinter()
        dao.setDefaultPrinter(printerId)
        Log.d(TAG, "Predeterminada establecida: id=$printerId")
    }

    /**
     * Elimina una impresora del registro.
     * Si era la predeterminada, asigna la siguiente disponible como default.
     */
    suspend fun deletePrinter(printer: PrinterEntity) {
        val wasDefault = printer.isDefault
        dao.deletePrinter(printer)
        Log.d(TAG, "Impresora eliminada: ${printer.name} @ ${printer.ipAddress}")

        if (wasDefault) {
            dao.getAllPrintersOnce().firstOrNull()?.let { next ->
                dao.setDefaultPrinter(next.id)
                Log.d(TAG, "Nueva predeterminada asignada: ${next.name}")
            }
        }
    }

    /** Actualiza el nombre de una impresora. */
    suspend fun renamePrinter(printer: PrinterEntity, newName: String) {
        dao.updatePrinter(printer.copy(name = newName.trim()))
    }

    /** Persiste el path eSCL descubierto para próximas sesiones. */
    suspend fun updateEsclPath(printer: PrinterEntity, newPath: String) {
        dao.updatePrinter(printer.copy(esclPath = newPath))
    }

    /**
     * Marca como offline las impresoras cuyo prefijo de red no coincide con
     * la red actual (ej: impresora de la oficina cuando estás en casa).
     *
     * NO las borra: antes se eliminaban de la BD y el usuario perdía sus
     * impresoras guardadas (nombres, configuración) cada vez que cambiaba
     * de red WiFi. Volverán a marcarse online cuando se reencuentren.
     */
    suspend fun markStalePrintersOffline(currentPrefix: String) {
        dao.getAllPrintersOnce()
            .filter { it.ipAddress.substringBeforeLast(".") != currentPrefix && it.isOnline }
            .forEach { stale ->
                Log.d(TAG, "Fuera de la red actual → offline: ${stale.name} @ ${stale.ipAddress}")
                dao.updatePrinterOnlineStatus(stale.id, false)
            }
    }

    // ── Estado IPP ────────────────────────────────────────────────────────────

    /**
     * Consulta el estado actual de una impresora vía IPP.
     * Corrige el puerto si estaba mal guardado (9100 → 631), lo persiste,
     * y actualiza isOnline/lastSeen según el resultado.
     *
     * @return PrinterStatus o null si no responde
     */
    suspend fun checkPrinterStatus(printer: PrinterEntity): PrinterStatus? {
        return try {
            val safePort = normalizeIppPort(printer.ippPort)
            val ippUrl   = "http://${printer.ipAddress}:$safePort${printer.ippPath}"

            val status = ippClient.getPrinterStatus(ippUrl)
            dao.updatePrinterOnlineStatus(printer.id, status != null)

            // Persistir la corrección de puerto para próximas sesiones
            if (status != null && printer.ippPort != safePort) {
                dao.updatePrinter(printer.copy(ippPort = safePort))
            }
            status
        } catch (e: Exception) {
            Log.e(TAG, "Error comprobando estado de ${printer.name}: ${e.message}")
            dao.updatePrinterOnlineStatus(printer.id, false)
            null
        }
    }

    /**
     * Verifica el estado de todas las impresoras guardadas.
     *
     * @return Mapa de id → PrinterStatus? (null = no responde)
     */
    suspend fun checkAllPrinters(): Map<Long, PrinterStatus?> =
        dao.getAllPrintersOnce().associate { printer ->
            printer.id to checkPrinterStatus(printer)
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
