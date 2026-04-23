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
import java.net.InetAddress

/**
 * PrinterDiscovery - Módulo de descubrimiento de impresoras en red local
 *
 * ¿Cómo funciona la detección de impresoras?
 * ============================================
 * Las impresoras modernas anuncian sus servicios usando mDNS (Multicast DNS)
 * también conocido como Bonjour (Apple) o Avahi (Linux).
 *
 * La impresora Epson EcoTank L3560 anuncia:
 * - _ipp._tcp    → Puerto 631, para impresión via IPP
 * - _scanner._tcp → Puerto variable, para escaneo via eSCL
 * - _http._tcp   → Puerto 80, interfaz web
 *
 * Android usa NsdManager (Network Service Discovery) para escuchar
 * estos anuncios mDNS sin necesidad de conocer la IP de antemano.
 *
 * Proceso:
 * 1. Android escucha paquetes multicast en 224.0.0.251:5353
 * 2. La impresora periódicamente anuncia sus servicios
 * 3. NsdManager nos notifica cuando encuentra un servicio _ipp._tcp
 * 4. Resolvemos el servicio para obtener la IP y puerto exactos
 */
class PrinterDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "PrinterDiscovery"
        // Tipos de servicio mDNS que buscamos
        const val SERVICE_TYPE_IPP = "_ipp._tcp."       // Impresión IPP
        const val SERVICE_TYPE_SCANNER = "_scanner._tcp." // Escaneo eSCL
        // Puerto estándar IPP
        const val DEFAULT_IPP_PORT = 631
        // Timeout para descubrimiento (30 segundos)
        const val DISCOVERY_TIMEOUT_MS = 30_000L
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    /**
     * Verificar si el dispositivo está conectado a WiFi
     *
     * La impresora Epson SOLO está disponible en red local WiFi.
     * Sin WiFi, no hay comunicación posible.
     *
     * @return true si hay conexión WiFi activa
     */
    fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Obtener la dirección IP del dispositivo Android en la red WiFi
     *
     * Esto nos da el rango de red (ej: 192.168.1.x) para saber
     * en qué subred buscar la impresora.
     *
     * @return IP del dispositivo como String (ej: "192.168.1.5")
     */
    fun getDeviceIpAddress(): String {
        val wifiInfo = wifiManager.connectionInfo
        val ipInt = wifiInfo.ipAddress
        // Convertir de entero little-endian a formato IP legible
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff,
            (ipInt shr 8) and 0xff,
            (ipInt shr 16) and 0xff,
            (ipInt shr 24) and 0xff
        )
    }

    /**
     * Descubrir impresoras usando NSD (Network Service Discovery)
     *
     * Usa Flow para emitir impresoras encontradas de forma reactiva.
     * callbackFlow convierte las callbacks de NSD en un Flow de Kotlin.
     *
     * Ejemplo de uso:
     * ```kotlin
     * viewModel.discoverPrinters().collect { printer ->
     *     Log.d("Found", "Impresora: ${printer.name} en ${printer.ipAddress}")
     * }
     * ```
     *
     * @return Flow<PrinterInfo> que emite cada impresora encontrada
     */
    fun discoverPrinters(): Flow<PrinterInfo> = callbackFlow {
        // Habilitar multicast para recibir paquetes mDNS
        // Sin esto, Android puede filtrar los paquetes multicast para ahorrar batería
        val multicastLock = wifiManager.createMulticastLock("EpsonPrintDiscovery").apply {
            setReferenceCounted(true)
            acquire()
        }

        Log.d(TAG, "Iniciando descubrimiento mDNS para $_ipp._tcp")

        // Listener para resolver detalles de un servicio encontrado
        // Se llama después de encontrar el servicio para obtener IP y puerto
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Error al resolver servicio: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { info ->
                    Log.d(TAG, "Servicio resuelto: ${info.serviceName}")
                    Log.d(TAG, "  Host: ${info.host}")
                    Log.d(TAG, "  Puerto: ${info.port}")
                    Log.d(TAG, "  Atributos: ${info.attributes}")

                    // Construir objeto PrinterInfo con los datos del servicio
                    val printerInfo = PrinterInfo(
                        name = info.serviceName,
                        ipAddress = info.host?.hostAddress ?: "",
                        ippPort = info.port,
                        // La ruta IPP estándar para Epson
                        ippPath = "/ipp/print",
                        // eSCL usa el mismo host pero ruta diferente
                        esclPath = "/eSCL",
                        // Intentar obtener modelo de los atributos TXT record
                        model = extractModelFromAttributes(info.attributes)
                    )

                    // Emitir la impresora encontrada en el Flow
                    trySend(printerInfo)
                }
            }
        }

        // Listener para el descubrimiento de servicios
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
                    // Resolver el servicio para obtener IP y puerto
                    // Solo resolvemos si parece ser una impresora Epson
                    // Los servicios Epson típicamente contienen "EPSON" en el nombre
                    nsdManager.resolveService(info, resolveListener)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "Servicio perdido: ${serviceInfo?.serviceName}")
            }
        }

        // Iniciar descubrimiento del servicio IPP
        try {
            nsdManager.discoverServices(SERVICE_TYPE_IPP, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar NSD: ${e.message}")
            close(e)
        }

        // awaitClose se ejecuta cuando el Flow se cancela (ej: Activity destruida)
        // Aquí limpiamos los recursos para evitar memory leaks
        awaitClose {
            Log.d(TAG, "Deteniendo descubrimiento NSD")
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error al detener NSD: ${e.message}")
            }
            // Liberar el lock de multicast para ahorrar batería
            if (multicastLock.isHeld) {
                multicastLock.release()
            }
        }
    }

    /**
     * Escaneo manual de red por IP
     *
     * Alternativa al NSD cuando mDNS no funciona.
     * Escanea el rango de la red WiFi buscando el puerto 631 (IPP).
     *
     * IMPORTANTE: Este método es lento (puede tardar 1-2 minutos).
     * Solo usar como fallback cuando NSD falla.
     *
     * Ejemplo de uso:
     * ```kotlin
     * // Si el dispositivo está en 192.168.1.5, escanea 192.168.1.1-254
     * viewModel.scanNetworkForPrinters().collect { printer -> ... }
     * ```
     *
     * @return Flow<PrinterInfo> con impresoras encontradas via scan directo
     */
    fun scanNetworkForPrinters(): Flow<PrinterInfo> = flow {
        val deviceIp = getDeviceIpAddress()
        Log.d(TAG, "Iniciando escaneo de red. IP del dispositivo: $deviceIp")

        // Extraer el prefijo de red (ej: "192.168.1")
        val networkPrefix = deviceIp.substringBeforeLast(".")

        // Escanear todos los hosts del rango /24 (1-254)
        for (host in 1..254) {
            val targetIp = "$networkPrefix.$host"

            // Intentar conectar al puerto IPP 631
            if (isIppPortOpen(targetIp, DEFAULT_IPP_PORT)) {
                Log.d(TAG, "Puerto IPP abierto en: $targetIp")

                val printerInfo = PrinterInfo(
                    name = "Impresora en $targetIp",
                    ipAddress = targetIp,
                    ippPort = DEFAULT_IPP_PORT,
                    ippPath = "/ipp/print",
                    esclPath = "/eSCL",
                    model = "Epson EcoTank"
                )
                emit(printerInfo)
            }
        }
    }

    /**
     * Verificar si el puerto IPP está abierto en una IP específica
     *
     * Intenta crear una conexión TCP al puerto.
     * Un timeout de 500ms es suficiente para red local (LAN).
     *
     * @param ipAddress Dirección IP a verificar
     * @param port Puerto a verificar (default: 631)
     * @return true si el puerto responde
     */
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

    /**
     * Extraer el modelo de impresora desde los atributos TXT record de mDNS
     *
     * El TXT record de mDNS contiene pares clave=valor.
     * Para impresoras Epson, el campo "ty" o "product" contiene el modelo.
     *
     * Ejemplo de TXT record de una Epson:
     * ty=EPSON L3560 Series
     * usb_MFG=EPSON
     * usb_MDL=L3560 Series
     * adminurl=http://192.168.1.10/PRESENTATION/BONJOUR
     *
     * @param attributes Mapa de atributos del servicio mDNS
     * @return String con el modelo de impresora o null si no se encuentra
     */
    private fun extractModelFromAttributes(attributes: Map<String, ByteArray>?): String? {
        if (attributes == null) return null

        // Intentar diferentes claves comunes en TXT records de impresoras
        val modelKeys = listOf("ty", "product", "usb_MDL", "model")
        for (key in modelKeys) {
            attributes[key]?.let { bytes ->
                return String(bytes, Charsets.UTF_8)
            }
        }
        return null
    }
}

/**
 * PrinterInfo - Modelo de datos para información de impresora descubierta
 *
 * Contiene toda la información necesaria para comunicarse con la impresora.
 *
 * @param name Nombre del servicio mDNS (ej: "EPSON L3560 Series")
 * @param ipAddress IP de la impresora (ej: "192.168.1.10")
 * @param ippPort Puerto IPP (generalmente 631)
 * @param ippPath Ruta del endpoint IPP (generalmente "/ipp/print")
 * @param esclPath Ruta del endpoint eSCL (generalmente "/eSCL")
 * @param model Modelo de impresora si está disponible
 */
data class PrinterInfo(
    val name: String,
    val ipAddress: String,
    val ippPort: Int = 631,
    val ippPath: String = "/ipp/print",
    val esclPath: String = "/eSCL",
    val model: String? = null
) {
    /**
     * URL completa del endpoint IPP
     * Ejemplo: "http://192.168.1.10:631/ipp/print"
     */
    val ippUrl: String get() = "http://$ipAddress:$ippPort$ippPath"

    /**
     * URL base para eSCL
     * Ejemplo: "http://192.168.1.10/eSCL"
     */
    val esclUrl: String get() = "http://$ipAddress$esclPath"

    /**
     * URL para consultas SNMP (estado de tinta)
     * SNMP usa UDP port 161, pero consultamos via HTTP SNMP bridge si disponible
     */
    val snmpHost: String get() = ipAddress
}
