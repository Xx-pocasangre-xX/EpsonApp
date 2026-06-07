package com.example.epsonprintapp.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.ui.dashboard.DashboardViewModel
import com.example.epsonprintapp.ui.notifications.NotificationsViewModel
import com.example.epsonprintapp.ui.print.PrintViewModel
import com.example.epsonprintapp.ui.scan.ScanViewModel

/**
 * ViewModelFactory — Fábrica central de ViewModels.
 *
 * Simplificada respecto a la versión anterior:
 * - Eliminados los parámetros que antes se recibían pero nunca se usaban
 *   (discovery, ippClient, esclClient, snmpClient, notificationManager).
 *   Cada ViewModel construye sus dependencias internamente.
 * - Solo se recibe `database` porque NotificationsViewModel lo necesita
 *   explícitamente (no usa Application para crearlo).
 */
class ViewModelFactory(
    private val application: Application,
    private val database:    AppDatabase? = null
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(DashboardViewModel::class.java) ->
            DashboardViewModel(application) as T

        modelClass.isAssignableFrom(PrintViewModel::class.java) ->
            PrintViewModel(application) as T

        modelClass.isAssignableFrom(ScanViewModel::class.java) ->
            ScanViewModel(application) as T

        modelClass.isAssignableFrom(NotificationsViewModel::class.java) ->
            NotificationsViewModel(database ?: AppDatabase.getInstance(application)) as T

        else -> throw IllegalArgumentException("ViewModel desconocido: ${modelClass.name}")
    }
}