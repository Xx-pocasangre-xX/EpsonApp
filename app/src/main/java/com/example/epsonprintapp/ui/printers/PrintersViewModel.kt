package com.example.epsonprintapp.ui.printers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.epsonprintapp.AppConstants
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.database.entities.PrinterEntity
import com.example.epsonprintapp.network.PrinterDiscovery
import com.example.epsonprintapp.network.PrinterInfo
import com.example.epsonprintapp.printer.IppClient
import com.example.epsonprintapp.printer.PrinterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * PrintersViewModel — Gestiona la pantalla de lista de impresoras.
 *
 * ARQUITECTURA SIMPLIFICADA: usa AppDatabase y PrinterDiscovery directamente,
 * igual que DashboardViewModel, para evitar dependencias intermedias que
 * causen fallos de compilación en cadena.
 */
class PrintersViewModel(application: Application) : AndroidViewModel(application) {

    private val database  = AppDatabase.getInstance(application)
    private val dao       = database.printerDao()
    private val discovery = PrinterDiscovery(application)
    private val ippClient = IppClient(application)

    // ── LiveData pública ───────────────────────────────────────────────────────

    val savedPrinters: LiveData<List<PrinterEntity>> =
        dao.getAllPrinters().asLiveData()

    private val _isDiscovering = MutableLiveData(false)
    val isDiscovering: LiveData<Boolean> = _isDiscovering

    private val _isAddingByIp = MutableLiveData(false)
    val isAddingByIp: LiveData<Boolean> = _isAddingByIp

    private val _discoveryStatus = MutableLiveData<String?>(null)
    val discoveryStatus: LiveData<String?> = _discoveryStatus

    private val _networkInfo = MutableLiveData<NetworkInfo?>(null)
    val networkInfo: LiveData<NetworkInfo?> = _networkInfo

    private val _printerStatuses = MutableLiveData<Map<Long, PrinterStatus?>>(emptyMap())
    val printerStatuses: LiveData<Map<Long, PrinterStatus?>> = _printerStatuses

    private val _actionResult = MutableLiveData<ActionResult?>(null)
    val actionResult: LiveData<ActionResult?> = _actionResult

    private var discoveryJob: Job? = null

    init {
        refreshNetworkInfo()
        checkAllPrintersStatus()
    }

    // ── Red ───────────────────────────────────────────────────────────────────

    fun refreshNetworkInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip     = discovery.getDeviceIpAddress()
            val local  = discovery.isLocalNetworkAvailable()
            val any    = discovery.isNetworkAvailable()
            val prefix = discovery.getNetworkPrefix()
            _networkInfo.postValue(NetworkInfo(ip, local, any, prefix))
        }
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    fun startDiscovery() {
        if (_isDiscovering.value == true) return

        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            _isDiscovering.postValue(true)
            _discoveryStatus.postValue("🔍 Buscando impresoras por mDNS…")

            val seenIps    = mutableSetOf<String>()
            val accumulated = mutableListOf<PrinterInfo>()
            var mDnsCount  = 0

            // Fase 1: mDNS
            try {
                withTimeout(AppConstants.DISCOVERY_TIMEOUT_MS) {
                    discovery.discoverPrinters()
                        .catch { e -> android.util.Log.w("PrintersVM", "mDNS: ${e.message}") }
                        .collect { printer ->
                            if (seenIps.add(printer.ipAddress)) {
                                mDnsCount++
                                accumulated.add(printer)
                                _discoveryStatus.postValue(
                                    "✅ ${printer.displayName} (${printer.ipAddress})"
                                )
                                savePrinterToDb(printer)
                            }
                        }
                }
            } catch (e: TimeoutCancellationException) {
                android.util.Log.d("PrintersVM", "mDNS timeout — $mDnsCount encontradas")
            }

            // Fase 2: TCP si mDNS no encontró nada
            if (accumulated.isEmpty()) {
                _discoveryStatus.postValue("🔎 Escaneando red por TCP…")
                if (discovery.isLocalNetworkAvailable()) {
                    try {
                        discovery.scanNetworkForPrinters()
                            .catch { e -> android.util.Log.e("PrintersVM", "TCP: ${e.message}") }
                            .collect { printer ->
                                if (seenIps.add(printer.ipAddress)) {
                                    accumulated.add(printer)
                                    _discoveryStatus.postValue("✅ TCP: ${printer.displayName} (${printer.ipAddress})")
                                    savePrinterToDb(printer)
                                }
                            }
                    } catch (e: Exception) {
                        android.util.Log.e("PrintersVM", "TCP excepción: ${e.message}")
                    }
                }
            }

            // Resultado final
            val total = accumulated.size
            if (total > 0) {
                _discoveryStatus.postValue("✅ $total impresora(s) encontrada(s) y guardada(s)")
                _actionResult.postValue(ActionResult.Success("Se encontraron $total impresora(s)."))
                checkAllPrintersStatus()
            } else {
                val ip = discovery.getDeviceIpAddress()
                _discoveryStatus.postValue("❌ No se encontraron impresoras")
                _actionResult.postValue(
                    ActionResult.Warning(
                        "No se encontraron impresoras.\n" +
                                "• Verifica que esté encendida y en la misma red\n" +
                                "• IP del dispositivo: $ip\n" +
                                "• Prueba agregar la IP manualmente"
                    )
                )
            }

            delay(3000)
            _isDiscovering.postValue(false)
        }
    }

    private suspend fun savePrinterToDb(info: PrinterInfo) {
        val existing = dao.getPrinterByIp(info.ipAddress)
        val isFirst  = dao.getPrinterCount() == 0
        val entity   = PrinterEntity(
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
        dao.insertPrinter(entity)
    }

    // ── Agregar por IP manual ─────────────────────────────────────────────────

    fun addPrinterByIp(ip: String, customName: String? = null, port: Int = 631) {
        if (_isAddingByIp.value == true) return

        viewModelScope.launch(Dispatchers.IO) {
            _isAddingByIp.postValue(true)
            _discoveryStatus.postValue("🔗 Conectando a $ip:$port…")

            val trimmedIp = ip.trim()

            // Validar formato IP
            if (!discovery.isValidIpAddress(trimmedIp)) {
                _discoveryStatus.postValue("❌ IP inválida: $ip")
                _actionResult.postValue(
                    ActionResult.Error("Dirección IP inválida: '$ip'\nEjemplo: 192.168.1.50")
                )
                _isAddingByIp.postValue(false)
                return@launch
            }

            // Verificar si ya existe
            val existing = dao.getPrinterByIp(trimmedIp)
            if (existing != null) {
                _discoveryStatus.postValue("ℹ️ ${existing.name} ya estaba guardada")
                _actionResult.postValue(
                    ActionResult.Info("'${existing.name}' ya estaba en tu lista.")
                )
                _isAddingByIp.postValue(false)
                return@launch
            }

            // Intentar conectar
            val info = discovery.connectByIp(trimmedIp, port)
            if (info == null) {
                _discoveryStatus.postValue("❌ No se pudo conectar a $ip")
                _actionResult.postValue(
                    ActionResult.Error(
                        "No hay ninguna impresora en $ip:$port\n" +
                                "• Verifica que la IP sea correcta\n" +
                                "• Confirma que esté encendida y en la misma red"
                    )
                )
                _isAddingByIp.postValue(false)
                return@launch
            }

            // Guardar en DB
            val isFirst = dao.getPrinterCount() == 0
            val entity  = PrinterEntity(
                name      = customName?.trim()?.takeIf { it.isNotBlank() } ?: info.displayName,
                ipAddress = trimmedIp,
                ippPort   = port,
                ippPath   = info.ippPath,
                esclPath  = info.esclPath,
                model     = info.model,
                isDefault = isFirst,
                isOnline  = true,
                lastSeen  = System.currentTimeMillis()
            )
            val savedId = dao.insertPrinter(entity)
            val saved   = dao.getPrinterById(savedId)

            _discoveryStatus.postValue("✅ ${saved?.name ?: info.displayName} agregada")
            _actionResult.postValue(
                ActionResult.Success("Impresora '${saved?.name}' agregada y guardada.")
            )
            checkAllPrintersStatus()
            delay(2000)
            _isAddingByIp.postValue(false)
        }
    }

    // ── Gestión ───────────────────────────────────────────────────────────────

    fun setDefaultPrinter(printerId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearDefaultPrinter()
            dao.setDefaultPrinter(printerId)
            _actionResult.postValue(ActionResult.Success("Impresora predeterminada actualizada."))
        }
    }

    fun deletePrinter(printer: PrinterEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val wasDefault = printer.isDefault
            dao.deletePrinter(printer)
            if (wasDefault) {
                // Asignar la siguiente disponible como predeterminada
                dao.getAllPrintersOnce().firstOrNull()?.let { next ->
                    dao.setDefaultPrinter(next.id)
                }
            }
            _actionResult.postValue(ActionResult.Info("'${printer.name}' eliminada del registro."))
        }
    }

    fun renamePrinter(printer: PrinterEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.updatePrinter(printer.copy(name = newName.trim()))
        }
    }

    // ── Estado IPP ────────────────────────────────────────────────────────────

    fun checkAllPrintersStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val printers = dao.getAllPrintersOnce()
            val results  = mutableMapOf<Long, PrinterStatus?>()
            printers.forEach { printer ->
                val status = runCatching {
                    ippClient.getPrinterStatus(printer.ippUrl)
                }.getOrNull()
                results[printer.id] = status
                dao.updatePrinterOnlineStatus(printer.id, status != null)
            }
            _printerStatuses.postValue(results)
        }
    }

    fun clearActionResult() {
        _actionResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        discoveryJob?.cancel()
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

data class NetworkInfo(
    val deviceIp:      String,
    val hasLocalNet:   Boolean,
    val hasAnyNet:     Boolean,
    val networkPrefix: String?
)

sealed class ActionResult {
    data class Success(val message: String) : ActionResult()
    data class Info(val message: String)    : ActionResult()
    data class Warning(val message: String) : ActionResult()
    data class Error(val message: String)   : ActionResult()
}