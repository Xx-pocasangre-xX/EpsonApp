package com.example.epsonprintapp.ui.dashboard

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
import com.example.epsonprintapp.notifications.AppNotificationManager
import com.example.epsonprintapp.printer.IppClient
import com.example.epsonprintapp.printer.InkLevels
import com.example.epsonprintapp.printer.PrinterState
import com.example.epsonprintapp.printer.PrinterStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
    val isWifiConnected: LiveData<Boolean> = _isNetworkAvailable

    private val _isDiscovering      = MutableLiveData(false)
    val isDiscovering: LiveData<Boolean> = _isDiscovering

    private val _discoveredPrinters = MutableLiveData<List<PrinterInfo>>(emptyList())
    val discoveredPrinters: LiveData<List<PrinterInfo>> = _discoveredPrinters

    private val _errorMessage       = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    /** Mensajes de progreso en tiempo real durante el descubrimiento */
    private val _discoveryProgress  = MutableLiveData<String?>()
    val discoveryProgress: LiveData<String?> = _discoveryProgress

    val unreadNotificationCount: LiveData<Int> =
        database.notificationDao().getUnreadCount().asLiveData()

    val savedPrinters: LiveData<List<PrinterEntity>> =
        database.printerDao().getAllPrinters().asLiveData()

    private var statusRefreshJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        checkNetworkAndLoadPrinter()
        scheduleDbCleanup()
    }

    private fun checkNetworkAndLoadPrinter() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = printerDiscovery.isNetworkAvailable()
            _isNetworkAvailable.postValue(ok)
            if (!ok) {
                _errorMessage.postValue("Sin conexión de red. Conéctate al WiFi de la red local.")
                return@launch
            }
            val defaultPrinter = database.printerDao().getDefaultPrinter()
            if (defaultPrinter != null) {
                _currentPrinter.postValue(defaultPrinter)
                refreshPrinterStatus(defaultPrinter)
                startPeriodicStatusRefresh(defaultPrinter)
            } else {
                discoverPrinters()
            }
        }
    }

    /**
     * Limpia historial antiguo de la DB al arrancar.
     * Usa las queries que ya existían en los DAOs pero nunca se llamaban.
     */
    private fun scheduleDbCleanup() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val cutoff = System.currentTimeMillis() -
                        (AppConstants.DB_OLD_JOBS_DAYS * 24 * 60 * 60 * 1000L)
                database.printJobDao().deleteOldJobs(cutoff)
                database.notificationDao().deleteOldNotifications(cutoff)
            }
        }
    }

    // ── Descubrimiento ────────────────────────────────────────────────────────

    /**
     * Fase 1: mDNS con 6 tipos de servicio en paralelo.
     * Fase 2: Escaneo TCP paralelo si mDNS no encuentra nada.
     */
    fun discoverPrinters() {
        if (_isDiscovering.value == true) return

        viewModelScope.launch(Dispatchers.IO) {
            _isDiscovering.postValue(true)
            _errorMessage.postValue(null)
            _discoveryProgress.postValue("🔍 Buscando por mDNS…")

            val found      = mutableListOf<PrinterInfo>()
            var mDnsCount  = 0

            try {
                withTimeout(AppConstants.DISCOVERY_TIMEOUT_MS) {
                    printerDiscovery.discoverPrinters()
                        .catch { e -> android.util.Log.w("DashboardVM", "mDNS: ${e.message}") }
                        .collect { printer ->
                            mDnsCount++
                            _discoveryProgress.postValue(
                                "✅ ${printer.displayName} (${printer.ipAddress})")
                            handleDiscoveredPrinter(printer, found)
                        }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.d("DashboardVM", "mDNS timeout — $mDnsCount encontradas")
            }

            if (found.isEmpty()) {
                _discoveryProgress.postValue("🔎 Escaneando red por TCP…")
                try {
                    printerDiscovery.scanNetworkForPrinters()
                        .collect { printer ->
                            _discoveryProgress.postValue("✅ TCP: ${printer.ipAddress}")
                            handleDiscoveredPrinter(printer, found)
                        }
                } catch (e: Exception) {
                    android.util.Log.e("DashboardVM", "TCP scan: ${e.message}")
                }
            }

            if (found.isEmpty()) {
                _errorMessage.postValue(
                    "No se encontraron impresoras.\n" +
                            "• Verifica que esté encendida\n" +
                            "• Asegúrate de estar en la misma red WiFi\n" +
                            "• Puedes añadir una impresora manualmente")
            } else {
                _discoveryProgress.postValue("✅ ${found.size} impresora(s) encontrada(s)")
            }

            _isDiscovering.postValue(false)
            delay(2000)
            _discoveryProgress.postValue(null)
        }
    }

    private suspend fun handleDiscoveredPrinter(
        printer:   PrinterInfo,
        foundList: MutableList<PrinterInfo>
    ) {
        foundList.add(printer)
        _discoveredPrinters.postValue(foundList.toList())

        val existing = database.printerDao().getPrinterByIp(printer.ipAddress)
        val isFirst  = database.printerDao().getPrinterCount() == 0

        val entity = PrinterEntity(
            id        = existing?.id ?: 0,
            name      = printer.displayName,
            ipAddress = printer.ipAddress,
            ippPort   = printer.ippPort,
            ippPath   = printer.ippPath,
            esclPath  = printer.esclPath,
            model     = printer.model,
            isDefault = isFirst || existing?.isDefault == true,
            isOnline  = true
        )
        val savedId = database.printerDao().insertPrinter(entity)

        if (isFirst) {
            val saved = database.printerDao().getPrinterById(savedId)
            _currentPrinter.postValue(saved)
            saved?.let { refreshPrinterStatus(it); startPeriodicStatusRefresh(it) }
        }
    }

    // ── Estado de impresora ───────────────────────────────────────────────────

    fun refreshPrinterStatus(printer: PrinterEntity? = _currentPrinter.value) {
        if (printer == null) return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.postValue(true)
            try {
                val status = ippClient.getPrinterStatus(printer.ippUrl)
                if (status != null) {
                    _printerStatus.postValue(status)
                    database.printerDao().updatePrinterOnlineStatus(printer.id, true)
                    checkInkLevels(status.inkLevels)
                    if (!status.hasPaper) notificationManager.notifyNoPaper()
                } else {
                    _printerStatus.postValue(null)
                    database.printerDao().updatePrinterOnlineStatus(printer.id, false)
                    notificationManager.notifyPrinterOffline()
                }
            } catch (e: Exception) {
                _printerStatus.postValue(null)
                _errorMessage.postValue("No se pudo conectar: ${e.message}")
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    private fun startPeriodicStatusRefresh(printer: PrinterEntity) {
        statusRefreshJob?.cancel()
        statusRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(AppConstants.PRINTER_REFRESH_INTERVAL_MS)
                refreshPrinterStatus(printer)
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
            printer?.let { refreshPrinterStatus(it); startPeriodicStatusRefresh(it) }
        }
    }

    fun clearError() { _errorMessage.value = null }

    override fun onCleared() {
        super.onCleared()
        statusRefreshJob?.cancel()
        notificationManager.cancel()
    }
}