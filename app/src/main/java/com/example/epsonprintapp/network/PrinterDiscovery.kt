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
        const val SERVICE_TYPE_IPP     = "_ipp._tcp."
        const val SERVICE_TYPE_SCANNER = "_scanner._tcp."
        const val DEFAULT_IPP_PORT     = 631
        const val DISCOVERY_TIMEOUT_MS = 30_000L
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    /**
     * Verificar si hay conectividad de red local (no solo WiFi puro).
     *
     * FIX: el método anterior solo chequeaba TRANSPORT_WIFI, lo que
     * falla si el dispositivo está en modo VPN, o si el sistema reporta
     * la red como TRANSPORT_WIFI + TRANSPORT_VPN al mismo tiempo.
     *
     * La lógica correcta es:
     * 1. ¿Hay red activa? → Si no, definitivamente sin conexión
     * 2. ¿Tiene capacidad de red local (NOT_VPN o tiene WIFI)? → Conectado
     * 3. Fallback: si WiFi está habilitado y conectado, asumir OK
     */
    fun isWifiConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork

            if (network == null) {
                Log.w(TAG, "No hay red activa")
                return false
            }

            val capabilities = cm.getNetworkCapabilities(network)
            if (capabilities == null) {
                Log.w(TAG, "Sin capabilities de red, intentando fallback WiFi")
                return isWifiEnabledFallback()
            }

            // Chequeo primario: tiene transporte WiFi O Ethernet (red local)
            val hasLocalTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

            // Chequeo secundario: tiene internet o red local (NOT necesariamente internet)
            val hasConnectivity = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)

            if (hasLocalTransport) {
                Log.d(TAG, "Conectado vía WiFi/Ethernet")
                return true
            }

            // Si hay VPN activa pero el WiFi subyacente está conectado, permitir
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                val wifiEnabled = isWifiEnabledFallback()
                Log.d(TAG, "Conexión VPN detectada, WiFi subyacente: $wifiEnabled")
                return wifiEnabled
            }

            // Fallback final: si el adaptador WiFi está habilitado y tiene IP
            Log.w(TAG, "Transport no identificado, usando fallback")
            isWifiEnabledFallback()

        } catch (e: Exception) {
            Log.e(TAG, "Error verificando conectividad: ${e.message}")
            // En caso de error, asumir conectado para no bloquear al usuario
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun isWifiEnabledFallback(): Boolean {
        return try {
            val wifiInfo = wifiManager.connectionInfo
            wifiManager.isWifiEnabled && wifiInfo != null && wifiInfo.networkId != -1
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    fun getDeviceIpAddress(): String {
        return try {
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt == 0) return "0.0.0.0"
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                (ipInt shr 8) and 0xff,
                (ipInt shr 16) and 0xff,
                (ipInt shr 24) and 0xff
            )
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    fun discoverPrinters(): Flow<PrinterInfo> = callbackFlow {
        val multicastLock = try {
            wifiManager.createMulticastLock("EpsonPrintDiscovery").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo adquirir multicast lock: ${e.message}")
            null
        }

        Log.d(TAG, "Iniciando descubrimiento mDNS para $SERVICE_TYPE_IPP")

        // Lista de resolvers activos para evitar conflictos en NSD
        val activeResolvers = mutableSetOf<String>()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Error al resolver servicio: $errorCode")
                serviceInfo?.let { activeResolvers.remove(it.serviceName) }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { info ->
                    activeResolvers.remove(info.serviceName)
                    Log.d(TAG, "Servicio resuelto: ${info.serviceName} → ${info.host?.hostAddress}:${info.port}")
                    val ip = info.host?.hostAddress ?: return
                    if (ip.isEmpty() || ip == "0.0.0.0") return

                    val printerInfo = PrinterInfo(
                        name      = info.serviceName,
                        ipAddress = ip,
                        ippPort   = info.port,
                        ippPath   = extractIppPath(info.attributes) ?: "/ipp/print",
                        esclPath  = "/eSCL",
                        model     = extractModelFromAttributes(info.attributes)
                    )
                    trySend(printerInfo)
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Fallo al iniciar descubrimiento: $errorCode")
                close(Exception("Fallo NSD: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Fallo al detener descubrimiento: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "Descubrimiento iniciado para: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d(TAG, "Descubrimiento detenido")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { info ->
                    Log.d(TAG, "Servicio encontrado: ${info.serviceName}")
                    if (!activeResolvers.contains(info.serviceName)) {
                        activeResolvers.add(info.serviceName)
                        try {
                            nsdManager.resolveService(info, resolveListener)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error al resolver ${info.serviceName}: ${e.message}")
                            activeResolvers.remove(info.serviceName)
                        }
                    }
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
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
            try { if (multicastLock?.isHeld == true) multicastLock.release() } catch (_: Exception) {}
        }
    }

    fun scanNetworkForPrinters(): Flow<PrinterInfo> = flow {
        val deviceIp = getDeviceIpAddress()
        if (deviceIp == "0.0.0.0") {
            Log.w(TAG, "No se pudo obtener IP del dispositivo para escaneo de red")
            return@flow
        }
        val networkPrefix = deviceIp.substringBeforeLast(".")
        Log.d(TAG, "Escaneando red $networkPrefix.1-254 en puerto $DEFAULT_IPP_PORT")

        for (host in 1..254) {
            val targetIp = "$networkPrefix.$host"
            if (isIppPortOpen(targetIp, DEFAULT_IPP_PORT)) {
                Log.d(TAG, "Puerto IPP abierto en: $targetIp")
                emit(PrinterInfo(
                    name      = "Impresora en $targetIp",
                    ipAddress = targetIp,
                    ippPort   = DEFAULT_IPP_PORT,
                    ippPath   = "/ipp/print",
                    esclPath  = "/eSCL",
                    model     = null
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
        } catch (_: Exception) { false }
    }

    private fun extractModelFromAttributes(attributes: Map<String, ByteArray>?): String? {
        if (attributes == null) return null
        for (key in listOf("ty", "product", "usb_MDL", "model", "pdl")) {
            attributes[key]?.let { return String(it, Charsets.UTF_8) }
        }
        return null
    }

    private fun extractIppPath(attributes: Map<String, ByteArray>?): String? {
        if (attributes == null) return null
        // Algunos dispositivos anuncian la ruta IPP en el atributo "rp"
        attributes["rp"]?.let { return "/" + String(it, Charsets.UTF_8) }
        return null
    }
}

data class PrinterInfo(
    val name:      String,
    val ipAddress: String,
    val ippPort:   Int    = 631,
    val ippPath:   String = "/ipp/print",
    val esclPath:  String = "/eSCL",
    val model:     String? = null
) {
    val ippUrl:   String get() = "http://$ipAddress:$ippPort$ippPath"
    val esclUrl:  String get() = "http://$ipAddress$esclPath"
    val snmpHost: String get() = ipAddress
}