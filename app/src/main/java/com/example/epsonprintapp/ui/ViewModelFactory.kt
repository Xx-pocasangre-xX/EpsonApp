package com.example.epsonprintapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.network.PrinterDiscovery
import com.example.epsonprintapp.notifications.AppNotificationManager
import com.example.epsonprintapp.printer.IppClient
import com.example.epsonprintapp.scanner.EsclClient
import com.example.epsonprintapp.snmp.SnmpClient
import com.example.epsonprintapp.ui.dashboard.DashboardViewModel
import com.example.epsonprintapp.ui.notifications.NotificationsViewModel
import com.example.epsonprintapp.ui.print.PrintViewModel
import com.example.epsonprintapp.ui.scan.ScanViewModel

/**
 * ViewModelFactory — Fábrica central para todos los ViewModels.
 *
 * Kotlin no permite pasar parámetros al constructor de ViewModel directamente.
 * Esta fábrica recibe todas las dependencias posibles (opcionales) y construye
 * el ViewModel correcto según el tipo solicitado.
 *
 * Uso:
 *   val viewModel: DashboardViewModel by viewModels {
 *       ViewModelFactory(
 *           database = AppDatabase.getInstance(requireContext()),
 *           discovery = PrinterDiscovery(requireContext()),
 *           ...
 *       )
 *   }
 */
class ViewModelFactory(
    private val database: AppDatabase? = null,
    private val discovery: PrinterDiscovery? = null,
    private val ippClient: IppClient? = null,
    private val esclClient: EsclClient? = null,
    private val snmpClient: SnmpClient? = null,
    private val notificationManager: AppNotificationManager? = null
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            // ── DashboardViewModel ──────────────────────────────────────────────
            modelClass.isAssignableFrom(DashboardViewModel::class.java) -> {
                requireNotNull(database)     { "Database requerida para DashboardViewModel" }
                requireNotNull(discovery)    { "PrinterDiscovery requerida para DashboardViewModel" }
                requireNotNull(snmpClient)   { "SnmpClient requerido para DashboardViewModel" }
                requireNotNull(ippClient)    { "IppClient requerido para DashboardViewModel" }
                requireNotNull(notificationManager) { "NotificationManager requerido" }
                DashboardViewModel(
                    database            = database,
                    printerDiscovery    = discovery,
                    ippClient           = ippClient,
                    snmpClient          = snmpClient,
                    notificationManager = notificationManager
                ) as T
            }

            // ── PrintViewModel ──────────────────────────────────────────────────
            modelClass.isAssignableFrom(PrintViewModel::class.java) -> {
                requireNotNull(database)          { "Database requerida para PrintViewModel" }
                requireNotNull(ippClient)         { "IppClient requerido para PrintViewModel" }
                requireNotNull(notificationManager) { "NotificationManager requerido" }
                PrintViewModel(
                    database            = database,
                    ippClient           = ippClient,
                    notificationManager = notificationManager
                ) as T
            }

            // ── ScanViewModel ───────────────────────────────────────────────────
            modelClass.isAssignableFrom(ScanViewModel::class.java) -> {
                requireNotNull(database)          { "Database requerida para ScanViewModel" }
                requireNotNull(esclClient)        { "EsclClient requerido para ScanViewModel" }
                requireNotNull(notificationManager) { "NotificationManager requerido" }
                ScanViewModel(
                    database            = database,
                    esclClient          = esclClient,
                    notificationManager = notificationManager
                ) as T
            }

            // ── NotificationsViewModel ──────────────────────────────────────────
            modelClass.isAssignableFrom(NotificationsViewModel::class.java) -> {
                requireNotNull(database) { "Database requerida para NotificationsViewModel" }
                NotificationsViewModel(database) as T
            }

            else -> throw IllegalArgumentException("ViewModel desconocido: ${modelClass.name}")
        }
    }
}
