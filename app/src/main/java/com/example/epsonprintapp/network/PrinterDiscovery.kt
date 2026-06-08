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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL

/**
 * PrinterDiscovery — Descubre impresoras en la red local.
 *
 * IMPORTANTE: Esta clase NO guarda ninguna IP hardcodeada.
 * Toda la información de impresoras viene de:
 * 1. mDNS (Bonjour) — el método más preciso, incluye el path IPP correcto
 * 2. Escaneo TCP de la red local — fallback cuando mDNS no responde
 * 3. Conexión manual por IP — cuando el usuario la ingresa explícitamente
 *
 * Para el Epson L3560:
 * - Puerto IPP: 631
 * - Path IPP: se obtiene del atributo mDNS "rp" (resource path)
 *   Típicamente "/ipp/print" pero puede variar
 * - Puerto eSCL: 80 (HTTP)
 * - Path eSCL: requiere habilitarlo en la impresora
 *   Panel impresora → Configuración → Config. de red → Avanzado → eSCL
 */
class PrinterDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "PrinterDiscovery"

        val SERVICE_TYPES = listOf(
            "_ipp._tcp.",
            "_ipps._tcp.",
            "_pdl-datastream._tcp.",
            "_printer._tcp.",
            "_print-caps._tcp.",
            "_scanner._tcp."
        )

        // Solo puertos que confirman una impresora (no RAW 9100 para IPP)
        // 9100 = RAW/PDL — NO enviar IPP aquí, solo detectar presencia
        val PRINTER_DETECTION_PORTS = listOf(631, 80, 9100, 515)
        val IPP_PORT = 631
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
            val net  = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(net) ?: return false
            val has  = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            has && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        } catch (e: Exception) {
            getDeviceIpAddress() != "0.0.0.0"
        }
    }

    fun isLocalNetworkAvailable(): Boolean {
        return try {
            val net  = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(net) ?: return false
            val isLocal = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isLocal) isPrivateIp(getDeviceIpAddress())
            else isLocal
        } catch (e: Exception) {
            isPrivateIp(getDeviceIpAddress())
        }
    }

    /**
     * Obtiene la IP del dispositivo en la red local.
     * Usa NetworkInterface (compatible con todas las versiones de Android).
     * NO usa WifiManager.connectionInfo (deprecado en API 31).
     */
    fun getDeviceIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val priority   = listOf("wlan", "eth", "rmnet", "tun", "tap")

            for (prefix in priority) {
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                    if (!iface.name.startsWith(prefix)) continue
                    for (addr in iface.inetAddresses) {
                        if (addr.isLoopbackAddress) continue
                        val host = addr.hostAddress ?: continue
                        if (!host.contains(':') && isPrivateIp(host)) {
                            Log.d(TAG, "IP: $host (${iface.name})")
                            return host
                        }
                    }
                }
            }

            // Fallback: cualquier IPv4 privada
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    val host = addr.hostAddress ?: continue
                    if (!host.contains(':') && isPrivateIp(host)) return host
                }
            }
            "0.0.0.0"
        } catch (e: Exception) {
            Log.e(TAG, "getDeviceIpAddress: ${e.message}")
            "0.0.0.0"
        }
    }

    fun getNetworkPrefix(): String? {
        val ip = getDeviceIpAddress()
        return if (ip == "0.0.0.0") null else ip.substringBeforeLast(".")
    }

    private fun isPrivateIp(ip: String): Boolean {
        if (ip.isBlank() || ip == "0.0.0.0") return false
        return ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                (1..31).any { ip.startsWith("172.$it.") }
    }

    fun isValidIpAddress(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { (it.toIntOrNull() ?: -1) in 0..255 }
    }

    // ── mDNS Discovery ────────────────────────────────────────────────────────

    /**
     * Descubre impresoras vía mDNS/Bonjour.
     *
     * El resultado incluye el path IPP REAL desde el atributo "rp" del
     * registro mDNS — así nunca usamos un path incorrecto hardcodeado.
     *
     * NsdManager solo permite 1 ResolveListener activo: usamos cola secuencial.
     */
    fun discoverPrinters(): Flow<PrinterInfo> = callbackFlow {
        val multicastLock = try {
            wifiManager.createMulticastLock("EpsonDiscovery").apply {
                setReferenceCounted(true); acquire()
            }
        } catch (e: Exception) { null }

        val discoveredIps   = mutableSetOf<String>()
        val pendingResolve  = ArrayDeque<NsdServiceInfo>()
        val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()
        var isResolving     = false

        fun resolveNext() {
            if (isResolving || pendingResolve.isEmpty()) return
            val info = pendingResolve.removeFirstOrNull() ?: return
            isResolving = true

            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo?, code: Int) {
                    Log.w(TAG, "Resolve fallido ${i?.serviceName}: $code")
                    isResolving = false; resolveNext()
                }
                override fun onServiceResolved(i: NsdServiceInfo?) {
                    isResolving = false
                    i ?: run { resolveNext(); return }

                    val ip = i.host?.hostAddress
                    if (ip.isNullOrBlank() || ip == "0.0.0.0" || !discoveredIps.add(ip)) {
                        resolveNext(); return
                    }

                    // Puerto IPP: usar 631 siempre para IPP
                    // Si mDNS reporta 9100 es PDL/RAW, el IPP real es 631
                    val rawPort  = i.port.takeIf { it > 0 } ?: IPP_PORT
                    val ippPort  = if (rawPort == 9100) IPP_PORT else rawPort

                    // Path IPP real desde atributo "rp" del registro mDNS
                    val ippPath  = extractAttr(i.attributes, "rp")
                        ?.let { if (it.startsWith("/")) it else "/$it" }
                        ?: "/ipp/print"

                    val model    = extractModel(i.attributes) ?: i.serviceName
                    val esclPath = extractAttr(i.attributes, "rs")
                        ?.let { if (it.startsWith("/")) it else "/$it" }
                        ?: "/eSCL"

                    Log.d(TAG, "mDNS: $model @ $ip:$ippPort$ippPath | escl=$esclPath")
                    trySend(PrinterInfo(
                        name          = model,
                        ipAddress     = ip,
                        ippPort       = ippPort,
                        ippPath       = ippPath,
                        esclPath      = esclPath,
                        model         = model,
                        supportsIpp   = true,
                        supportsEscl  = true,
                        discoveredVia = "mDNS"
                    ))
                    resolveNext()
                }
            })
        }

        SERVICE_TYPES.forEach { serviceType ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(st: String?, code: Int) {
                    Log.w(TAG, "mDNS inicio fallido $serviceType: $code")
                }
                override fun onStopDiscoveryFailed(st: String?, code: Int)  {}
                override fun onDiscoveryStarted(st: String?)                {}
                override fun onDiscoveryStopped(st: String?)                {}
                override fun onServiceFound(info: NsdServiceInfo?) {
                    info ?: return
                    Log.d(TAG, "Servicio encontrado: ${info.serviceName}")
                    pendingResolve.add(info)
                    resolveNext()
                }
                override fun onServiceLost(info: NsdServiceInfo?) {}
            }
            runCatching {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                activeListeners.add(listener)
            }
        }

        awaitClose {
            activeListeners.forEach { runCatching { nsdManager.stopServiceDiscovery(it) } }
            runCatching { if (multicastLock?.isHeld == true) multicastLock.release() }
        }
    }

    // ── TCP Scan ──────────────────────────────────────────────────────────────

    /**
     * Escanea la subred local en paralelo.
     * Detecta presencia por puertos, pero SIEMPRE guarda ippPort=631.
     */
    fun scanNetworkForPrinters(): Flow<PrinterInfo> = flow {
        val prefix   = getNetworkPrefix() ?: return@flow
        val deviceIp = getDeviceIpAddress()
        Log.d(TAG, "TCP scan: $prefix.1-254")

        val channel = Channel<PrinterInfo>(Channel.BUFFERED)
        val found   = mutableSetOf<String>()

        withContext(Dispatchers.IO) {
            coroutineScope {
                (1..254).map { host ->
                    async {
                        val ip = "$prefix.$host"
                        if (ip == deviceIp) return@async

                        // Detectar si hay impresora por cualquier puerto
                        val hasIpp  = isPortOpen(ip, 631)
                        val hasRaw  = isPortOpen(ip, 9100)
                        val hasHttp = isPortOpen(ip, 80)

                        if (hasIpp || hasRaw || hasHttp) {
                            val model = tryGetModelViaHttp(ip)
                            channel.trySend(PrinterInfo(
                                name          = model ?: "Impresora ($ip)",
                                ipAddress     = ip,
                                ippPort       = 631,      // SIEMPRE 631 para IPP
                                ippPath       = "/ipp/print",
                                esclPath      = "/eSCL",
                                model         = model,
                                supportsIpp   = hasIpp,
                                supportsEscl  = hasHttp,
                                discoveredVia = "TCP"
                            ))
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

    // ── Conexión manual ───────────────────────────────────────────────────────

    /**
     * Verifica una impresora por IP ingresada manualmente.
     * Prueba múltiples paths IPP para encontrar el correcto.
     */
    suspend fun connectByIp(ip: String, port: Int = IPP_PORT): PrinterInfo? {
        return withContext(Dispatchers.IO) {
            if (!isValidIpAddress(ip)) return@withContext null

            Log.d(TAG, "Conectando manualmente a $ip:$port")

            // Detectar puertos abiertos
            val hasIpp  = isPortOpen(ip, 631)
            val hasRaw  = isPortOpen(ip, 9100)
            val hasHttp = isPortOpen(ip, 80)

            if (!hasIpp && !hasRaw && !hasHttp) {
                Log.w(TAG, "Sin puertos de impresora en $ip")
                return@withContext null
            }

            // Intentar descubrir el path IPP correcto
            val ippPath = discoverIppPath(ip) ?: "/ipp/print"
            val model   = tryGetModelViaHttp(ip)

            Log.d(TAG, "Manual: $model @ $ip | ippPath=$ippPath")
            PrinterInfo(
                name          = model ?: "Impresora ($ip)",
                ipAddress     = ip.trim(),
                ippPort       = 631,
                ippPath       = ippPath,
                esclPath      = "/eSCL",
                model         = model,
                supportsIpp   = hasIpp,
                supportsEscl  = hasHttp,
                discoveredVia = "Manual"
            )
        }
    }

    /**
     * Descubre el path IPP real probando variantes conocidas.
     * Epson L3560: /ipp/print (estándar) o /ipp o /ipp/port1
     */
    private fun discoverIppPath(ip: String): String? {
        val candidates = listOf("/ipp/print", "/ipp", "/ipp/port1", "/")
        for (path in candidates) {
            return try {
                val conn = URL("http://$ip:631$path").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout    = 2000
                conn.requestMethod  = "POST"
                conn.setRequestProperty("Content-Type", "application/ipp")
                conn.setRequestProperty("Content-Length", "0")
                conn.doOutput = true
                conn.outputStream.write(ByteArray(0))
                // Cualquier respuesta (incluso error) confirma que el path existe
                val code = conn.responseCode
                conn.disconnect()
                if (code > 0) {
                    Log.d(TAG, "Path IPP encontrado: $path (HTTP $code)")
                    path
                } else continue
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isPortOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), AppConstants.SOCKET_TIMEOUT_MS); true }
    } catch (_: Exception) { false }

    private fun tryGetModelViaHttp(ip: String): String? = try {
        val conn = URL("http://$ip/").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500; conn.readTimeout = 1500
        conn.requestMethod = "GET"; conn.instanceFollowRedirects = false
        val html = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()?.take(60)
            ?.takeIf { it.isNotBlank() && it.length > 3 }
    } catch (_: Exception) { null }

    private fun extractAttr(attrs: Map<String, ByteArray>?, vararg keys: String): String? {
        if (attrs == null) return null
        for (key in keys) attrs[key]?.let { return String(it, Charsets.UTF_8) }
        return null
    }

    private fun extractModel(attrs: Map<String, ByteArray>?): String? =
        extractAttr(attrs, "ty", "product", "usb_MDL", "model", "note")
}

// ── PrinterInfo ───────────────────────────────────────────────────────────────

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
    val displayName: String get() = model?.takeIf { it.isNotBlank() } ?: name
}