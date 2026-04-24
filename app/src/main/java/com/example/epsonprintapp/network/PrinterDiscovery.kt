package com.example.epsonprintapp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

class PrinterDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "PrinterDiscovery"
        const val SERVICE_TYPE_IPP = "_ipp._tcp."
        const val SERVICE_TYPE_SCANNER = "_scanner._tcp."
        const val DEFAULT_IPP_PORT = 631
        const val DISCOVERY_TIMEOUT_MS = 30_000L
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun getDeviceIpAddress(): String {
        val wifiInfo = wifiManager.connectionInfo
        val ipInt = wifiInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff,
            (ipInt shr 8) and 0xff,
            (ipInt shr 16) and 0xff,
            (ipInt shr 24) and 0xff
        )
    }

    fun discoverPrinters(): Flow<PrinterInfo> = callbackFlow {
        val multicastLock = wifiManager.createMulticastLock("EpsonPrintDiscovery").apply {
            setReferenceCounted(true)
            acquire()
        }

        // FIX: use the constant string, not a variable reference
        Log.d(TAG, "Iniciando descubrimiento mDNS para $SERVICE_TYPE_IPP")

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Error al resolver servicio: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { info ->
                    Log.d(TAG, "Servicio resuelto: ${info.serviceName}")
                    val printerInfo = PrinterInfo(
                        name = info.serviceName,
                        ipAddress = info.host?.hostAddress ?: "",
                        ippPort = info.port,
                        ippPath = "/ipp/print",
                        esclPath = "/eSCL",
                        model = extractModelFromAttributes(info.attributes)
                    )
                    trySend(printerInfo)
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Fallo al iniciar descubrimiento: $errorCode")
                close(Exception("Fallo al iniciar descubrimiento NSD: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Fallo al detener descubrimiento: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "Descubrimiento iniciado para: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d(TAG, "Descubrimiento detenido para: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { info ->
                    Log.d(TAG, "Servicio encontrado: ${info.serviceName}")
                    nsdManager.resolveService(info, resolveListener)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "Servicio perdido: ${serviceInfo?.serviceName}")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE_IPP, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar NSD: ${e.message}")
            close(e)
        }

        awaitClose {
            Log.d(TAG, "Deteniendo descubrimiento NSD")
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error al detener NSD: ${e.message}")
            }
            if (multicastLock.isHeld) {
                multicastLock.release()
            }
        }
    }

    fun scanNetworkForPrinters(): Flow<PrinterInfo> = flow {
        val deviceIp = getDeviceIpAddress()
        val networkPrefix = deviceIp.substringBeforeLast(".")
        for (host in 1..254) {
            val targetIp = "$networkPrefix.$host"
            if (isIppPortOpen(targetIp, DEFAULT_IPP_PORT)) {
                emit(PrinterInfo(
                    name = "Impresora en $targetIp",
                    ipAddress = targetIp,
                    ippPort = DEFAULT_IPP_PORT,
                    ippPath = "/ipp/print",
                    esclPath = "/eSCL",
                    model = "Epson EcoTank"
                ))
            }
        }
    }

    private fun isIppPortOpen(ipAddress: String, port: Int): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(ipAddress, port), 500)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun extractModelFromAttributes(attributes: Map<String, ByteArray>?): String? {
        if (attributes == null) return null
        val modelKeys = listOf("ty", "product", "usb_MDL", "model")
        for (key in modelKeys) {
            attributes[key]?.let { bytes -> return String(bytes, Charsets.UTF_8) }
        }
        return null
    }
}

data class PrinterInfo(
    val name: String,
    val ipAddress: String,
    val ippPort: Int = 631,
    val ippPath: String = "/ipp/print",
    val esclPath: String = "/eSCL",
    val model: String? = null
) {
    val ippUrl: String get() = "http://$ipAddress:$ippPort$ippPath"
    val esclUrl: String get() = "http://$ipAddress$esclPath"
    val snmpHost: String get() = ipAddress
}