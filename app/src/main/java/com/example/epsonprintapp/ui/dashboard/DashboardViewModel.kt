package com.example.epsonprintapp.ui.dashboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.epsonprintapp.AppConstants
import com.example.epsonprintapp.appContainer
import com.example.epsonprintapp.database.entities.PrinterEntity
import com.example.epsonprintapp.network.PrinterInfo
import com.example.epsonprintapp.printer.InkLevels
import com.example.epsonprintapp.printer.PrinterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * DashboardViewModel
 *
 * - SIN IP hardcodeada — todo viene de mDNS o TCP scan
 * - Toda la persistencia pasa por PrinterRepository (única fuente de verdad)
 * - Las impresoras de otras redes se marcan offline, NUNCA se borran
 * - Las dependencias vienen de AppContainer (compartidas con el resto de la app)
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val container           = application.appContainer
    private val repository          = container.printerRepository
    private val database            = container.database
    private val printerDiscovery    = container.printerDiscovery
    private val notificationManager = container.notificationManager

    // ── LiveData ───────────────────────────────────────────────────────────────

    private val _isLoading          = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _printerStatus      = MutableLiveData<PrinterStatus?>(null)
    val printerStatus: LiveData<PrinterStatus?> = _printerStatus

    private val _currentPrinter     = MutableLiveData<PrinterEntity?>(null)
    val currentPrinter: LiveData<PrinterEntity?> = _currentPrinter

    private val _isNetworkAvailable = MutableLiveData(false)
    val isNetworkAvailable: LiveData<Boolean> = _isNetworkAvailable
    val isWifiConnected: LiveData<Boolean>    = _isNetworkAvailable

    private val _isDiscovering      = MutableLiveData(false)
    val isDiscovering: LiveData<Boolean> = _isDiscovering

    private val _discoveredPrinters = MutableLiveData<List<PrinterInfo>>(emptyList())
    val discoveredPrinters: LiveData<List<PrinterInfo>> = _discoveredPrinters

    private val _errorMessage       = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _discoveryProgress  = MutableLiveData<String?>()
    val discoveryProgress: LiveData<String?> = _discoveryProgress

    val unreadNotificationCount: LiveData<Int> =
        database.notificationDao().getUnreadCount().asLiveData()

    val savedPrinters: LiveData<List<PrinterEntity>> =
        repository.getAllPrinters().asLiveData()

    private var refreshJob: Job? = null

    init {
        checkNetworkAndLoadPrinter()
        scheduleDbCleanup()
    }

    // ── Red y carga inicial ───────────────────────────────────────────────────

    private fun checkNetworkAndLoadPrinter() {
        // Dispatchers.IO: getDeviceIpAddress() enumera interfaces de red (I/O ligero)
        viewModelScope.launch(Dispatchers.IO) {
            val hasNetwork = printerDiscovery.isNetworkAvailable()
            val hasLocal   = printerDiscovery.isLocalNetworkAvailable()
            val deviceIp   = printerDiscovery.getDeviceIpAddress()
            val prefix     = printerDiscovery.getNetworkPrefix()

            _isNetworkAvailable.postValue(hasNetwork)

            if (!hasNetwork) {
                _errorMessage.postValue("Sin conexión de red. Conéctate al WiFi donde está la impresora.")
                return@launch
            }

            Log.d(TAG, "Red local: $hasLocal | IP: $deviceIp | Prefijo: $prefix")

            // Impresoras de redes anteriores → offline (sin borrar datos del usuario)
            if (prefix != null) repository.markStalePrintersOffline(prefix)

            val defaultPrinter = repository.getDefaultPrinter()
            if (defaultPrinter != null) {
                val savedPrefix = defaultPrinter.ipAddress.substringBeforeLast(".")
                if (prefix != null && savedPrefix != prefix) {
                    Log.w(TAG, "Impresora guardada ($savedPrefix.x) no es de la red actual ($prefix.x) — re-descubriendo")
                    _discoveryProgress.postValue("Red diferente detectada. Buscando impresoras…")
                    discoverPrinters()
                } else {
                    _currentPrinter.postValue(defaultPrinter)
                    refreshPrinterStatus(defaultPrinter)
                }
            } else {
                _discoveryProgress.postValue("Buscando impresoras en la red…")
                discoverPrinters()
            }
        }
    }

    private fun scheduleDbCleanup() {
        viewModelScope.launch {
            runCatching {
                val cutoff = System.currentTimeMillis() -
                        AppConstants.DB_OLD_JOBS_DAYS * 24 * 60 * 60 * 1000L
                database.printJobDao().deleteOldJobs(cutoff)
                database.notificationDao().deleteOldNotifications(cutoff)
            }
        }
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    fun discoverPrinters() {
        if (_isDiscovering.value == true) return
        viewModelScope.launch(Dispatchers.IO) {
            _isDiscovering.postValue(true)
            _errorMessage.postValue(null)

            val deviceIp = printerDiscovery.getDeviceIpAddress()
            val hasLocal = printerDiscovery.isLocalNetworkAvailable()

            if (!hasLocal && deviceIp == "0.0.0.0") {
                _discoveryProgress.postValue("❌ Sin red local.")
                _errorMessage.postValue("Conéctate al WiFi donde está la impresora y vuelve a intentar.")
                _isDiscovering.postValue(false)
                return@launch
            }

            _discoveryProgress.postValue("🔍 Buscando por mDNS… (tu IP: $deviceIp)")

            val found   = mutableListOf<PrinterInfo>()
            val seenIps = mutableSetOf<String>()
            var count   = 0

            // Fase 1: mDNS (obtiene el path IPP real de la impresora)
            try {
                withTimeout(AppConstants.DISCOVERY_TIMEOUT_MS) {
                    printerDiscovery.discoverPrinters()
                        .catch { e -> Log.w(TAG, "mDNS error: ${e.message}") }
                        .collect { printer ->
                            if (seenIps.add(printer.ipAddress)) {
                                count++
                                _discoveryProgress.postValue("✅ ${printer.displayName} (${printer.ipAddress})")
                                found.add(printer)
                                _discoveredPrinters.postValue(found.toList())
                                handleDiscoveredPrinter(printer)
                            }
                        }
                }
            } catch (e: TimeoutCancellationException) {
                Log.d(TAG, "mDNS timeout — $count encontradas")
            }

            // Fase 2: TCP scan si mDNS no encontró nada
            if (found.isEmpty() && hasLocal) {
                val prefix = printerDiscovery.getNetworkPrefix() ?: "?"
                _discoveryProgress.postValue("🔎 Escaneando red TCP ($prefix.x)…")
                try {
                    printerDiscovery.scanNetworkForPrinters()
                        .catch { e -> Log.e(TAG, "TCP: ${e.message}") }
                        .collect { printer ->
                            if (seenIps.add(printer.ipAddress)) {
                                _discoveryProgress.postValue("✅ TCP: ${printer.displayName}")
                                found.add(printer)
                                _discoveredPrinters.postValue(found.toList())
                                handleDiscoveredPrinter(printer)
                            }
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "TCP scan: ${e.message}")
                }
            }

            if (found.isEmpty()) {
                _errorMessage.postValue(
                    "No se encontraron impresoras.\n" +
                            "• Verifica que la impresora esté encendida\n" +
                            "• Confirma que el teléfono y la impresora están en la misma red WiFi\n" +
                            "• Ve a 'Impresoras' → '+ Agregar IP' e ingresa la IP de tu impresora"
                )
            } else {
                _discoveryProgress.postValue("✅ ${found.size} impresora(s) encontrada(s)")
            }

            _isDiscovering.postValue(false)
            delay(3000)
            _discoveryProgress.postValue(null)
        }
    }

    private suspend fun handleDiscoveredPrinter(printer: PrinterInfo) {
        val savedId = repository.saveDiscoveredPrinter(printer)
        if (_currentPrinter.value == null) {
            val saved = repository.getPrinterById(savedId)
            _currentPrinter.postValue(saved)
            saved?.let { refreshPrinterStatus(it) }
        }
    }

    // ── Estado impresora (solo bajo demanda) ──────────────────────────────────

    fun refreshPrinterStatus(printer: PrinterEntity? = _currentPrinter.value) {
        if (printer == null) return
        refreshJob?.cancel()
        // Sin Dispatchers.IO: repository.checkPrinterStatus es main-safe
        refreshJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val status = repository.checkPrinterStatus(printer)
                _printerStatus.value = status
                if (status != null) {
                    checkInkLevels(status.inkLevels)
                    if (!status.hasPaper) notificationManager.notifyNoPaper()
                }
            } catch (e: Exception) {
                _printerStatus.value = null
                _errorMessage.value  = "No se pudo conectar con ${printer.name}: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun checkInkLevels(inkLevels: InkLevels) {
        val t = AppConstants.INK_LOW_THRESHOLD
        if (inkLevels.cyan    in 1..t) notificationManager.notifyInkLow("cyan",    inkLevels.cyan)
        if (inkLevels.magenta in 1..t) notificationManager.notifyInkLow("magenta", inkLevels.magenta)
        if (inkLevels.yellow  in 1..t) notificationManager.notifyInkLow("yellow",  inkLevels.yellow)
        if (inkLevels.black   in 1..t) notificationManager.notifyInkLow("black",   inkLevels.black)
    }

    fun selectPrinter(printerId: Long) {
        viewModelScope.launch {
            repository.setDefault(printerId)
            val printer = repository.getPrinterById(printerId)
            _currentPrinter.value = printer
            printer?.let { refreshPrinterStatus(it) }
        }
    }

    fun clearError() { _errorMessage.value = null }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        // notificationManager es compartido a nivel app: NO se cancela aquí
    }

    companion object { private const val TAG = "DashboardVM" }
}
