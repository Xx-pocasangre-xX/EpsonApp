package com.example.epsonprintapp.ui.dashboard

import android.app.Application
import android.util.Log
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
import com.example.epsonprintapp.notifications.AppNotificationManager
import com.example.epsonprintapp.printer.IppClient
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
 * CAMBIOS CLAVE:
 * - SIN IP hardcodeada — todo viene de mDNS o TCP scan
 * - SIN refresh periódico automático (eliminado — causaba timeouts)
 * - Al guardar impresoras, siempre ippPort=631 (nunca 9100)
 * - Al mostrar la impresora, corrige URLs con puerto 9100 automáticamente
 * - cleanStaleEntries(): elimina impresoras con IPs que ya no están en la red actual
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val database            = AppDatabase.getInstance(application)
    private val printerDiscovery    = PrinterDiscovery(application)
    private val ippClient           = IppClient(application)
    private val notificationManager = AppNotificationManager(application)

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
        database.printerDao().getAllPrinters().asLiveData()

    private var refreshJob: Job? = null

    init {
        checkNetworkAndLoadPrinter()
        scheduleDbCleanup()
    }

    // ── Red y carga inicial ───────────────────────────────────────────────────

    private fun checkNetworkAndLoadPrinter() {
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

            // Limpiar impresoras de redes anteriores (IPs de otro prefijo)
            if (prefix != null) cleanStaleEntries(prefix)

            val defaultPrinter = database.printerDao().getDefaultPrinter()
            if (defaultPrinter != null) {
                // Verificar que la IP guardada pertenece a la red actual
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

    /**
     * Elimina de la BD las impresoras cuyo prefijo de red no coincide
     * con el prefijo de red actual. Esto evita que aparezcan impresoras
     * de redes WiFi anteriores (ej: trabajo vs casa).
     */
    private suspend fun cleanStaleEntries(currentPrefix: String) {
        val all = database.printerDao().getAllPrintersOnce()
        val stale = all.filter { printer ->
            val printerPrefix = printer.ipAddress.substringBeforeLast(".")
            printerPrefix != currentPrefix
        }
        if (stale.isNotEmpty()) {
            Log.d(TAG, "Eliminando ${stale.size} impresoras de redes anteriores")
            stale.forEach { database.printerDao().deletePrinter(it) }
        }
    }

    private fun scheduleDbCleanup() {
        viewModelScope.launch(Dispatchers.IO) {
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

            // Fase 1: mDNS (obtiene path IPP real)
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
                            "• Ve a 'Impresoras' → '+ Agregar IP' e ingresa: 192.168.1.20"
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
        val dao      = database.printerDao()
        val existing = dao.getPrinterByIp(printer.ipAddress)
        val isFirst  = dao.getPrinterCount() == 0

        // NUNCA guardar puerto 9100 como ippPort
        val safeIppPort = if (printer.ippPort == 9100 || printer.ippPort == 0) 631 else printer.ippPort

        val entity = PrinterEntity(
            id        = existing?.id ?: 0L,
            name      = printer.displayName,
            ipAddress = printer.ipAddress,
            ippPort   = safeIppPort,
            ippPath   = printer.ippPath,
            esclPath  = printer.esclPath,
            model     = printer.model,
            isDefault = isFirst || (existing?.isDefault == true),
            isOnline  = true,
            lastSeen  = System.currentTimeMillis()
        )
        val savedId = dao.insertPrinter(entity)
        Log.d(TAG, "Guardada: ${printer.displayName} @ ${printer.ipAddress}:$safeIppPort${printer.ippPath}")

        if (_currentPrinter.value == null || isFirst) {
            val saved = dao.getPrinterById(savedId)
            _currentPrinter.postValue(saved)
            saved?.let { refreshPrinterStatus(it) }
        }
    }

    // ── Estado impresora (solo bajo demanda) ──────────────────────────────────

    fun refreshPrinterStatus(printer: PrinterEntity? = _currentPrinter.value) {
        if (printer == null) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoading.postValue(true)
            try {
                // Construir URL correcta (nunca puerto 9100)
                val ippPort  = if (printer.ippPort == 9100 || printer.ippPort == 0) 631 else printer.ippPort
                val ippUrl   = "http://${printer.ipAddress}:$ippPort${printer.ippPath}"

                val status = ippClient.getPrinterStatus(ippUrl)
                if (status != null) {
                    _printerStatus.postValue(status)
                    database.printerDao().updatePrinterOnlineStatus(printer.id, true)
                    // Actualizar ippPort en BD si era incorrecto
                    if (printer.ippPort != ippPort) {
                        database.printerDao().updatePrinter(printer.copy(ippPort = ippPort))
                    }
                    checkInkLevels(status.inkLevels)
                    if (!status.hasPaper) notificationManager.notifyNoPaper()
                } else {
                    _printerStatus.postValue(null)
                    database.printerDao().updatePrinterOnlineStatus(printer.id, false)
                }
            } catch (e: Exception) {
                _printerStatus.postValue(null)
                _errorMessage.postValue("No se pudo conectar con ${printer.name}: ${e.message}")
            } finally {
                _isLoading.postValue(false)
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
        viewModelScope.launch(Dispatchers.IO) {
            database.printerDao().clearDefaultPrinter()
            database.printerDao().setDefaultPrinter(printerId)
            val printer = database.printerDao().getPrinterById(printerId)
            _currentPrinter.postValue(printer)
            printer?.let { refreshPrinterStatus(it) }
        }
    }

    fun clearError() { _errorMessage.value = null }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        notificationManager.cancel()
    }

    companion object { private const val TAG = "DashboardVM" }
}