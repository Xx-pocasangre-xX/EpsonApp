package com.example.epsonprintapp.ui.printers

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.epsonprintapp.AppConstants
import com.example.epsonprintapp.appContainer
import com.example.epsonprintapp.data.AddPrinterResult
import com.example.epsonprintapp.database.entities.PrinterEntity
import com.example.epsonprintapp.network.PrinterInfo
import com.example.epsonprintapp.printer.PrinterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * PrintersViewModel — Gestiona la pantalla de lista de impresoras.
 *
 * Toda la persistencia pasa por PrinterRepository (las mismas reglas de
 * normalización de puerto y predeterminada que el resto de la app).
 */
class PrintersViewModel(application: Application) : AndroidViewModel(application) {

    companion object { private const val TAG = "PrintersVM" }

    private val container  = application.appContainer
    private val repository = container.printerRepository
    private val discovery  = container.printerDiscovery

    // ── LiveData pública ───────────────────────────────────────────────────────

    val savedPrinters: LiveData<List<PrinterEntity>> =
        repository.getAllPrinters().asLiveData()

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

            val seenIps     = mutableSetOf<String>()
            val accumulated = mutableListOf<PrinterInfo>()
            var mDnsCount   = 0

            // Fase 1: mDNS
            try {
                withTimeout(AppConstants.DISCOVERY_TIMEOUT_MS) {
                    discovery.discoverPrinters()
                        .catch { e -> Log.w(TAG, "mDNS: ${e.message}") }
                        .collect { printer ->
                            if (seenIps.add(printer.ipAddress)) {
                                mDnsCount++
                                accumulated.add(printer)
                                _discoveryStatus.postValue(
                                    "✅ ${printer.displayName} (${printer.ipAddress})"
                                )
                                repository.saveDiscoveredPrinter(printer)
                            }
                        }
                }
            } catch (e: TimeoutCancellationException) {
                Log.d(TAG, "mDNS timeout — $mDnsCount encontradas")
            }

            // Fase 2: TCP si mDNS no encontró nada
            if (accumulated.isEmpty() && discovery.isLocalNetworkAvailable()) {
                _discoveryStatus.postValue("🔎 Escaneando red por TCP…")
                try {
                    discovery.scanNetworkForPrinters()
                        .catch { e -> Log.e(TAG, "TCP: ${e.message}") }
                        .collect { printer ->
                            if (seenIps.add(printer.ipAddress)) {
                                accumulated.add(printer)
                                _discoveryStatus.postValue("✅ TCP: ${printer.displayName} (${printer.ipAddress})")
                                repository.saveDiscoveredPrinter(printer)
                            }
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "TCP excepción: ${e.message}")
                }
            }

            // Resultado final — el spinner se apaga YA, sin delay artificial
            _isDiscovering.postValue(false)
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
        }
    }

    // ── Agregar por IP manual ─────────────────────────────────────────────────

    fun addPrinterByIp(ip: String, customName: String? = null, port: Int = 631) {
        if (_isAddingByIp.value == true) return

        viewModelScope.launch {
            _isAddingByIp.value = true
            _discoveryStatus.value = "🔗 Conectando a ${ip.trim()}:$port…"

            when (val result = repository.addByIp(ip, customName, port)) {
                is AddPrinterResult.Success -> {
                    _discoveryStatus.value = "✅ ${result.printer.name} agregada"
                    _actionResult.value =
                        ActionResult.Success("Impresora '${result.printer.name}' agregada y guardada.")
                    checkAllPrintersStatus()
                }
                is AddPrinterResult.AlreadyExists -> {
                    _discoveryStatus.value = "ℹ️ ${result.printer.name} ya estaba guardada"
                    _actionResult.value =
                        ActionResult.Info("'${result.printer.name}' ya estaba en tu lista (datos actualizados).")
                }
                AddPrinterResult.InvalidIp -> {
                    _discoveryStatus.value = "❌ IP inválida: $ip"
                    _actionResult.value =
                        ActionResult.Error("Dirección IP inválida: '$ip'\nEjemplo: 192.168.1.50")
                }
                is AddPrinterResult.Unreachable -> {
                    _discoveryStatus.value = "❌ No se pudo conectar a ${result.ip}"
                    _actionResult.value = ActionResult.Error(
                        "No hay ninguna impresora en ${result.ip}:$port\n" +
                                "• Verifica que la IP sea correcta\n" +
                                "• Confirma que esté encendida y en la misma red"
                    )
                }
                is AddPrinterResult.Error -> {
                    _discoveryStatus.value = "❌ Error: ${result.message}"
                    _actionResult.value = ActionResult.Error(result.message)
                }
            }
            _isAddingByIp.value = false
        }
    }

    // ── Gestión ───────────────────────────────────────────────────────────────

    fun setDefaultPrinter(printerId: Long) {
        viewModelScope.launch {
            repository.setDefault(printerId)
            _actionResult.value = ActionResult.Success("Impresora predeterminada actualizada.")
        }
    }

    fun deletePrinter(printer: PrinterEntity) {
        viewModelScope.launch {
            repository.deletePrinter(printer)
            _actionResult.value = ActionResult.Info("'${printer.name}' eliminada del registro.")
        }
    }

    fun renamePrinter(printer: PrinterEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            repository.renamePrinter(printer, newName)
        }
    }

    // ── Estado IPP ────────────────────────────────────────────────────────────

    fun checkAllPrintersStatus() {
        viewModelScope.launch {
            _printerStatuses.value = repository.checkAllPrinters()
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
