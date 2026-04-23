package com.example.epsonprintapp.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.database.entities.NotificationEntity
import com.example.epsonprintapp.database.entities.PrinterEntity
import com.example.epsonprintapp.network.PrinterDiscovery
import com.example.epsonprintapp.network.PrinterInfo
import com.example.epsonprintapp.notifications.AppNotificationManager
import com.example.epsonprintapp.printer.IppClient
import com.example.epsonprintapp.printer.InkLevels
import com.example.epsonprintapp.printer.PrinterState
import com.example.epsonprintapp.printer.PrinterStatus
import com.example.epsonprintapp.snmp.SnmpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * DashboardViewModel - ViewModel del panel principal
 *
 * ¿POR QUÉ VIEWMODEL?
 * ====================
 * - Sobrevive a los cambios de configuración (rotación de pantalla)
 * - No tiene referencia directa a la UI (evita memory leaks)
 * - Gestiona el ciclo de vida de coroutines con viewModelScope
 * - Centraliza la lógica de negocio separándola de la UI
 *
 * PATRÓN MVVM:
 * Model (DB, Network) ← ViewModel → View (Fragment/Activity)
 *                            ↕
 *                       LiveData/Flow
 *
 * La View solo OBSERVA LiveData. Nunca llama métodos de red directamente.
 * El ViewModel maneja todos los estados: loading, success, error.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    // ===== DEPENDENCIAS =====
    private val database = AppDatabase.getInstance(application)
    private val printerDiscovery = PrinterDiscovery(application)
    private val ippClient = IppClient(application)
    private val snmpClient = SnmpClient()
    private val notificationManager = AppNotificationManager(application)

    // ===== ESTADO DE LA IMPRESORA =====

    /** Estado de carga general */
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    /** Estado de la impresora (null = no conectada) */
    private val _printerStatus = MutableLiveData<PrinterStatus?>(null)
    val printerStatus: LiveData<PrinterStatus?> = _printerStatus

    /** Impresora actualmente seleccionada */
    private val _currentPrinter = MutableLiveData<PrinterEntity?>(null)
    val currentPrinter: LiveData<PrinterEntity?> = _currentPrinter

    /** Estado de la conexión WiFi */
    private val _isWifiConnected = MutableLiveData(false)
    val isWifiConnected: LiveData<Boolean> = _isWifiConnected

    /** Proceso de descubrimiento activo */
    private val _isDiscovering = MutableLiveData(false)
    val isDiscovering: LiveData<Boolean> = _isDiscovering

    /** Lista de impresoras encontradas */
    private val _discoveredPrinters = MutableLiveData<List<PrinterInfo>>(emptyList())
    val discoveredPrinters: LiveData<List<PrinterInfo>> = _discoveredPrinters

    /** Mensajes de error para mostrar al usuario */
    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    // ===== DATOS DE BASE DE DATOS =====

    /**
     * Notificaciones no leídas (de Room DB via Flow→LiveData)
     * Se actualiza automáticamente cuando hay nuevas notificaciones
     */
    val unreadNotificationCount: LiveData<Int> =
        database.notificationDao().getUnreadCount().asLiveData()

    /**
     * Impresoras guardadas en DB
     */
    val savedPrinters: LiveData<List<PrinterEntity>> =
        database.printerDao().getAllPrinters().asLiveData()

    // ===== JOB DE ACTUALIZACIÓN PERIÓDICA =====
    private var statusRefreshJob: Job? = null

    // =========================================================================
    // INICIALIZACIÓN
    // =========================================================================

    init {
        // Al crear el ViewModel, verificar estado inicial
        checkWifiAndLoadPrinter()
    }

    /**
     * Verificar WiFi y cargar la impresora predeterminada
     *
     * Flujo de inicio:
     * 1. ¿Hay WiFi? → Si no, mostrar advertencia
     * 2. ¿Hay impresora guardada? → Cargarla y verificar estado
     * 3. ¿No hay impresora? → Iniciar descubrimiento automático
     */
    private fun checkWifiAndLoadPrinter() {
        viewModelScope.launch(Dispatchers.IO) {
            // Verificar WiFi
            val wifiConnected = printerDiscovery.isWifiConnected()
            _isWifiConnected.postValue(wifiConnected)

            if (!wifiConnected) {
                _errorMessage.postValue("Sin conexión WiFi. Conecta tu dispositivo al WiFi de la red local.")
                return@launch
            }

            // Buscar impresora predeterminada en DB
            val defaultPrinter = database.printerDao().getDefaultPrinter()

            if (defaultPrinter != null) {
                _currentPrinter.postValue(defaultPrinter)
                refreshPrinterStatus(defaultPrinter)
                // Iniciar actualización periódica cada 30 segundos
                startPeriodicStatusRefresh(defaultPrinter)
            } else {
                // No hay impresora guardada, iniciar descubrimiento
                discoverPrinters()
            }
        }
    }

    // =========================================================================
    // DESCUBRIMIENTO DE IMPRESORAS
    // =========================================================================

    /**
     * Iniciar descubrimiento de impresoras en la red
     *
     * Usa NSD (mDNS) para encontrar servicios _ipp._tcp.
     * Guarda las impresoras encontradas en DB automáticamente.
     * Si encuentra exactamente una, la selecciona como predeterminada.
     */
    fun discoverPrinters() {
        if (_isDiscovering.value == true) return

        viewModelScope.launch(Dispatchers.IO) {
            _isDiscovering.postValue(true)
            _errorMessage.postValue(null)

            val foundPrinters = mutableListOf<PrinterInfo>()

            try {
                // Timeout de 30 segundos para el descubrimiento
                withTimeout(PrinterDiscovery.DISCOVERY_TIMEOUT_MS) {
                    printerDiscovery.discoverPrinters()
                        .catch { e ->
                            // Si NSD falla, intentar escaneo manual
                            if (e.message?.contains("NSD") == true) {
                                printerDiscovery.scanNetworkForPrinters().collect { printer ->
                                    handleDiscoveredPrinter(printer, foundPrinters)
                                }
                            }
                        }
                        .collect { printer ->
                            handleDiscoveredPrinter(printer, foundPrinters)
                        }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Timeout normal, no es un error
                if (foundPrinters.isEmpty()) {
                    _errorMessage.postValue(
                        "No se encontraron impresoras. Asegúrate de que la impresora " +
                                "esté encendida y en la misma red WiFi."
                    )
                }
            } catch (e: Exception) {
                _errorMessage.postValue("Error en el descubrimiento: ${e.message}")
            } finally {
                _isDiscovering.postValue(false)
            }
        }
    }

    /**
     * Procesar una impresora descubierta
     */
    private suspend fun handleDiscoveredPrinter(
        printer: PrinterInfo,
        foundList: MutableList<PrinterInfo>
    ) {
        foundList.add(printer)
        _discoveredPrinters.postValue(foundList.toList())

        // Guardar en DB (o actualizar si ya existe)
        val existingPrinter = database.printerDao().getPrinterByIp(printer.ipAddress)
        val isFirst = database.printerDao().getPrinterCount() == 0

        val entity = PrinterEntity(
            id = existingPrinter?.id ?: 0,
            name = printer.name,
            ipAddress = printer.ipAddress,
            ippPort = printer.ippPort,
            ippPath = printer.ippPath,
            esclPath = printer.esclPath,
            model = printer.model,
            isDefault = isFirst || existingPrinter?.isDefault == true,
            isOnline = true
        )

        val savedId = database.printerDao().insertPrinter(entity)

        // Si es la primera/única impresora, seleccionarla automáticamente
        if (isFirst) {
            val savedPrinter = database.printerDao().getPrinterById(savedId)
            _currentPrinter.postValue(savedPrinter)
            savedPrinter?.let { refreshPrinterStatus(it) }
        }
    }

    // =========================================================================
    // ESTADO DE IMPRESORA
    // =========================================================================

    /**
     * Actualizar estado de la impresora actual
     *
     * Consulta IPP Get-Printer-Attributes para obtener:
     * - Estado (idle/processing/stopped)
     * - Niveles de tinta
     * - Disponibilidad de papel
     */
    fun refreshPrinterStatus(printer: PrinterEntity? = _currentPrinter.value) {
        if (printer == null) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.postValue(true)

            try {
                val status = ippClient.getPrinterStatus(printer.ippUrl)

                if (status != null) {
                    _printerStatus.postValue(status)

                    // Actualizar online status en DB
                    database.printerDao().updatePrinterOnlineStatus(printer.id, true)

                    // Verificar niveles de tinta y generar alertas si es necesario
                    checkInkLevels(status.inkLevels)

                    // Verificar si no hay papel
                    if (!status.hasPaper) {
                        notificationManager.notifyNoPaper()
                    }

                } else {
                    // No se pudo conectar
                    _printerStatus.postValue(null)
                    database.printerDao().updatePrinterOnlineStatus(printer.id, false)
                    notificationManager.notifyPrinterOffline()
                }

            } catch (e: Exception) {
                _printerStatus.postValue(null)
                _errorMessage.postValue("No se pudo conectar con la impresora: ${e.message}")
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    /**
     * Actualización periódica del estado
     *
     * Cancela cualquier actualización previa y crea una nueva.
     * Se cancela automáticamente cuando el ViewModel se destruye.
     */
    private fun startPeriodicStatusRefresh(printer: PrinterEntity) {
        statusRefreshJob?.cancel()
        statusRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(30_000)  // Actualizar cada 30 segundos
                refreshPrinterStatus(printer)
            }
        }
    }

    /**
     * Verificar niveles de tinta y generar notificaciones si es bajo
     *
     * Umbral de alerta: 20% o menos
     */
    private fun checkInkLevels(inkLevels: InkLevels) {
        val threshold = 20
        if (inkLevels.cyan in 1..threshold) notificationManager.notifyInkLow("cyan", inkLevels.cyan)
        if (inkLevels.magenta in 1..threshold) notificationManager.notifyInkLow("magenta", inkLevels.magenta)
        if (inkLevels.yellow in 1..threshold) notificationManager.notifyInkLow("yellow", inkLevels.yellow)
        if (inkLevels.black in 1..threshold) notificationManager.notifyInkLow("black", inkLevels.black)
    }

    // =========================================================================
    // SELECCIÓN DE IMPRESORA
    // =========================================================================

    /**
     * Cambiar la impresora activa
     */
    fun selectPrinter(printerId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            // Actualizar default en DB
            database.printerDao().clearDefaultPrinter()
            database.printerDao().setDefaultPrinter(printerId)

            val printer = database.printerDao().getPrinterById(printerId)
            _currentPrinter.postValue(printer)
            printer?.let {
                refreshPrinterStatus(it)
                startPeriodicStatusRefresh(it)
            }
        }
    }

    /**
     * Limpiar mensaje de error
     */
    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // Cancelar job de actualización periódica cuando el ViewModel se destruye
        statusRefreshJob?.cancel()
    }
}
