package com.example.epsonprintapp

import android.content.Context
import com.example.epsonprintapp.data.PrinterRepository
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.network.PrinterDiscovery
import com.example.epsonprintapp.notifications.AppNotificationManager
import com.example.epsonprintapp.printer.IppClient
import com.example.epsonprintapp.scanner.EsclClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * AppContainer — Inyección de dependencias manual (Service Locator).
 *
 * Una sola instancia por proceso, creada perezosamente desde [EpsonPrintApp].
 *
 * Por qué existe:
 * - Un único OkHttpClient base compartido (pool de conexiones, dispatcher y
 *   threads compartidos). Antes cada ViewModel creaba su propio IppClient con
 *   su propio OkHttpClient → 4-6 pools de conexiones vivos a la vez.
 * - Los ViewModels dejan de construir sus dependencias: las obtienen de aquí,
 *   lo que permite sustituirlas en tests.
 * - IppClient cachea las capacidades detectadas de la impresora (media-ready,
 *   modos de color, dúplex): compartir la instancia entre pantallas evita
 *   re-consultarlas.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Cliente HTTP base. Los clientes específicos derivan de él con newBuilder()
     *  (comparten pool de conexiones) ajustando solo sus timeouts. */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(AppConstants.HTTP_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    val printerDiscovery: PrinterDiscovery by lazy { PrinterDiscovery(appContext) }

    val ippClient: IppClient by lazy { IppClient(httpClient) }

    val esclClient: EsclClient by lazy { EsclClient(httpClient) }

    val notificationManager: AppNotificationManager by lazy {
        AppNotificationManager(appContext, database)
    }

    val printerRepository: PrinterRepository by lazy {
        PrinterRepository(database.printerDao(), printerDiscovery, ippClient)
    }
}

/** Acceso cómodo al contenedor desde cualquier Context (Activity, Application…). */
val Context.appContainer: AppContainer
    get() = (applicationContext as EpsonPrintApp).container
