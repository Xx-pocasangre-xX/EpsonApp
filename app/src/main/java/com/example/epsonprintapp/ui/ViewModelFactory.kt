package com.example.epsonprintapp.ui

import android.app.Application
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
 * FIX: DashboardViewModel, PrintViewModel and ScanViewModel extend AndroidViewModel
 * so they receive (application: Application) as their first constructor argument.
 * NotificationsViewModel only needs the database.
 *
 * The factory accepts an Application so it can construct any of these.
 */
class ViewModelFactory(
    private val application: Application,
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
            // DashboardViewModel(application) — all deps come from within the VM
            modelClass.isAssignableFrom(DashboardViewModel::class.java) -> {
                DashboardViewModel(application) as T
            }

            // PrintViewModel(application) — deps resolved inside the VM
            modelClass.isAssignableFrom(PrintViewModel::class.java) -> {
                PrintViewModel(application) as T
            }

            // ScanViewModel(application) — deps resolved inside the VM
            modelClass.isAssignableFrom(ScanViewModel::class.java) -> {
                ScanViewModel(application) as T
            }

            // NotificationsViewModel only needs the database
            modelClass.isAssignableFrom(NotificationsViewModel::class.java) -> {
                val db = database ?: AppDatabase.getInstance(application)
                NotificationsViewModel(db) as T
            }

            else -> throw IllegalArgumentException("ViewModel desconocido: ${modelClass.name}")
        }
    }
}