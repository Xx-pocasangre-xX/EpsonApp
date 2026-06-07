package com.example.epsonprintapp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.example.epsonprintapp.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * PrinterDiscovery — Descubre CUALQUIER impresora de red.
 *
 * Estrategias (en orden):
 * 1. mDNS — 6 tipos de servicio en paralelo (Epson, HP, Canon, Brother, Kyocera, Xerox…)
 * 2. TCP scan — 254 hosts en paralelo como fallback (~2 segundos)
 */
class PrinterDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "PrinterDiscovery"

        /** Todos los tipos de servicio mDNS de impresoras conocidos */
        val SERVICE_TYPES = listOf(
            "_ipp._tcp.",            // IPP estándar universal
            "_ipps._tcp.",           // IPP sobre TLS
            "_pdl-datastream._tcp.", // RAW/PDL (Kyocera, Xerox, Brother corporativo)
            "_printer._tcp.",        // LPD clásico
            "_print-caps._tcp.",     // Capacidades (algunos Brother)
            "_scanner._tcp."         // Escáneres independientes
        )

        /** Puertos que indican que hay una impresora en ese host */
        val PRINTER_PORTS = listOf(631, 9100, 515, 80)

        const val DEFAULT_IPP_PORT = 631
        const val DEFAULT_RAW_PORT = 9100
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    // ── Conectividad ──────────────────────────────────────────────────────────

    fun isNetworkAvailable(): Boolean {
        return try {
            val cm      = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps    = cm.getNetworkCapabilities(network) ?: return isWifiFallback()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && isWifiFallback())
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando red: ${e.message}")
            true
        }
    }

    private fun isWifiFallback(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cm      = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps    = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            wifiManager.isWifiEnabled && info != null && info.networkId != -1
        }
    } catch (e: Exception) { false }

    fun getDeviceIpAddress(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cm      = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork ?: return "0.0.0.0"
                val lp      = cm.getLinkProperties(network) ?: return "0.0.0.0"
                lp.linkAddresses
                    .firstOrNull { it.address is java.net.Inet4Address }
                    ?.address?.hostAddress ?: "0.0.0.0"
            } else {
                @Suppress("DEPRECATION")
                val ipInt = wifiManager.connectionInfo.ipAddress
                if (ipInt == 0) return "0.0.0.0"
                String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xFF,
                    (ipInt shr 8) and 0xFF,
                    (ipInt shr 16) and 0xFF,
                    (ipInt shr 24) and 0xFF
                )
            }
        } catch (e: Exception) { "0.0.0.0" }
    }

    // ── Descubrimiento mDNS ───────────────────────────────────────────────────

    fun discoverPrinters(): Flow<PrinterInfo> = callbackFlow {
        val multicastLock = try {
            wifiManager.createMulticastLock("UniversalPrinterDiscovery").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) { null }

        val activeResolvers = mutableSetOf<String>()
        val discoveredIps   = mutableSetOf<String>()
        val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                info?.let { activeResolvers.remove(it.serviceName) }
            }
            override fun onServiceResolved(info: NsdServiceInfo?) {
                info ?: return
                activeResolvers.remove(info.serviceName)
                val ip = info.host?.hostAddress ?: return
                if (ip.isEmpty() || ip == "0.0.0.0" || !discoveredIps.add(ip)) return

                val port     = info.port.takeIf { it > 0 } ?: DEFAULT_IPP_PORT
                val ippPath  = extractAttr(info.attributes, "rp")
                    ?.let { if (it.startsWith("/")) it else "/$it" } ?: "/ipp/print"
                val model    = extractModel(info.attributes) ?: info.serviceName

                Log.d(TAG, "mDNS: $model @ $ip:$port")
                trySend(PrinterInfo(
                    name          = model,
                    ipAddress     = ip,
                    ippPort       = port,
                    ippPath       = ippPath,
                    esclPath      = "/eSCL",
                    model         = model,
                    supportsIpp   = true,
                    supportsEscl  = true,
                    discoveredVia = "mDNS"
                ))
            }
        }

        SERVICE_TYPES.forEach { serviceType ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(st: String?, code: Int) {
                    Log.w(TAG, "mDNS falló para $serviceType: $code")
                }
                override fun onStopDiscoveryFailed(st: String?, code: Int) {}
                override fun onDiscoveryStarted(st: String?) {}
                override fun onDiscoveryStopped(st: String?) {}
                override fun onServiceFound(info: NsdServiceInfo?) {
                    info ?: return
                    if (activeResolvers.add(info.serviceName)) {
                        try { nsdManager.resolveService(info, resolveListener) }
                        catch (e: Exception) { activeResolvers.remove(info.serviceName) }
                    }
                }
                override fun onServiceLost(info: NsdServiceInfo?) {}
            }
            try {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                activeListeners.add(listener)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo iniciar mDNS para $serviceType: ${e.message}")
            }
        }

        awaitClose {
            activeListeners.forEach { runCatching { nsdManager.stopServiceDiscovery(it) } }
            runCatching { if (multicastLock?.isHeld == true) multicastLock.release() }
        }
    }

    // ── Escaneo TCP paralelo ──────────────────────────────────────────────────

    /**
     * Escanea toda la subred en paralelo.
     * 254 hosts simultáneos ≈ 1-2 segundos (antes era secuencial: hasta 127s).
     */
    fun scanNetworkForPrinters(): Flow<PrinterInfo> = flow {
        val deviceIp = getDeviceIpAddress()
        if (deviceIp == "0.0.0.0") { Log.w(TAG, "IP no disponible"); return@flow }

        val prefix = deviceIp.substringBeforeLast(".")
        Log.d(TAG, "Escaneando $prefix.1-254 en paralelo...")

        val channel = Channel<PrinterInfo>(Channel.BUFFERED)
        val found   = mutableSetOf<String>()

        withContext(Dispatchers.IO) {
            coroutineScope {
                (1..254).map { host ->
                    async {
                        val ip = "$prefix.$host"
                        if (ip == deviceIp) return@async
                        for (port in PRINTER_PORTS) {
                            if (isPortOpen(ip, port)) {
                                val model = tryGetModelViaHttp(ip)
                                channel.trySend(PrinterInfo(
                                    name          = model ?: "Impresora ($ip)",
                                    ipAddress     = ip,
                                    ippPort       = if (port == DEFAULT_IPP_PORT) port else DEFAULT_IPP_PORT,
                                    ippPath       = "/ipp/print",
                                    esclPath      = "/eSCL",
                                    model         = model,
                                    supportsIpp   = isPortOpen(ip, DEFAULT_IPP_PORT),
                                    supportsEscl  = isPortOpen(ip, 80),
                                    discoveredVia = "TCP (puerto $port)"
                                ))
                                break
                            }
                        }
                    }
                }.awaitAll()
                channel.close()
            }
        }

        for (info in channel) {
            if (found.add(info.ipAddress)) {
                Log.d(TAG, "TCP: ${info.displayName} @ ${info.ipAddress}")
                emit(info)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isPortOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), AppConstants.SOCKET_TIMEOUT_MS); true }
    } catch (_: Exception) { false }

    private fun tryGetModelViaHttp(ip: String): String? = try {
        val conn = java.net.URL("http://$ip/").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 1000
        conn.readTimeout    = 1000
        conn.requestMethod  = "GET"
        val title = extractHtmlTitle(conn.inputStream.bufferedReader().readText())
        conn.disconnect()
        title?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun extractHtmlTitle(html: String): String? =
        Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()?.take(60)

    private fun extractAttr(attrs: Map<String, ByteArray>?, vararg keys: String): String? {
        if (attrs == null) return null
        for (key in keys) attrs[key]?.let { return String(it, Charsets.UTF_8) }
        return null
    }

    private fun extractModel(attrs: Map<String, ByteArray>?): String? =
        extractAttr(attrs, "ty", "product", "usb_MDL", "model", "note")
}

// ─────────────────────────────────────────────────────────────────────────────

data class PrinterInfo(
    val name:          String,
    val ipAddress:     String,
    val ippPort:       Int     = 631,
    val ippPath:       String  = "/ipp/print",
    val esclPath:      String  = "/eSCL",
    val model:         String? = null,
    val supportsIpp:   Boolean = true,
    val supportsEscl:  Boolean = true,
    val discoveredVia: String  = "mDNS"
) {
    val ippUrl:      String get() = "http://$ipAddress:$ippPort$ippPath"
    val esclUrl:     String get() = "http://$ipAddress$esclPath"
    val webUrl:      String get() = "http://$ipAddress/"
    val displayName: String get() = model?.takeIf { it.isNotBlank() } ?: name
}