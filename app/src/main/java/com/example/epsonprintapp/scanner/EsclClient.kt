package com.example.epsonprintapp.scanner

import android.util.Log
import android.util.Xml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * EsclClient - Cliente del protocolo eSCL (eSCL Scan Protocol)
 *
 * ¿QUÉ ES eSCL?
 * =============
 * eSCL (AirPrint Scan) es el protocolo estándar de Apple para escaneo inalámbrico.
 * Epson lo implementó en sus impresoras multifunción modernas.
 *
 * URL base para Epson EcoTank L3560: http://<IP>/eSCL
 *
 * ENDPOINTS eSCL:
 * ===============
 * GET  /eSCL/ScannerCapabilities  → Capacidades del escáner (XML)
 * GET  /eSCL/ScannerStatus        → Estado actual del escáner (XML)
 * POST /eSCL/ScanJobs             → Crear nuevo trabajo de escaneo
 * GET  /eSCL/ScanJobs/{id}/NextDocument  → Descargar imagen escaneada
 * DELETE /eSCL/ScanJobs/{id}      → Cancelar trabajo
 *
 * FLUJO COMPLETO DE ESCANEO:
 * ==========================
 * 1. POST /eSCL/ScanJobs con XML describiendo el escaneo
 *    → Respuesta: 201 Created, Location: /eSCL/ScanJobs/12345
 *
 * 2. GET /eSCL/ScanJobs/12345/NextDocument
 *    → Respuesta: 200 OK con imagen (JPEG/PNG/PDF)
 *    → O 503 si el escáner todavía está procesando
 *
 * FORMATO XML de petición de escaneo:
 * ====================================
 * <?xml version="1.0" encoding="UTF-8"?>
 * <scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03"
 *                    xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
 *   <pwg:Version>2.6</pwg:Version>
 *   <scan:Intent>Document</scan:Intent>
 *   <pwg:ScanRegions>
 *     <pwg:ScanRegion>
 *       <pwg:XOffset>0</pwg:XOffset>
 *       <pwg:YOffset>0</pwg:YOffset>
 *       <pwg:Width>2480</pwg:Width>   <!-- A4 a 300 DPI = 2480px -->
 *       <pwg:Height>3508</pwg:Height> <!-- A4 a 300 DPI = 3508px -->
 *       <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
 *     </pwg:ScanRegion>
 *   </pwg:ScanRegions>
 *   <scan:ColorMode>RGB24</scan:ColorMode>
 *   <scan:XResolution>300</scan:XResolution>
 *   <scan:YResolution>300</scan:YResolution>
 *   <pwg:InputSource>Platen</pwg:InputSource>
 *   <scan:DocumentFormat>image/jpeg</scan:DocumentFormat>
 * </scan:ScanSettings>
 */
class EsclClient {

    companion object {
        private const val TAG = "EsclClient"

        // Namespaces XML usados en eSCL
        private const val NS_ESCL = "http://schemas.hp.com/imaging/escl/2011/05/03"
        private const val NS_PWG = "http://www.pwg.org/schemas/2010/12/sm"

        // Timeout para escaneo (el proceso puede tardar varios segundos)
        private const val SCAN_TIMEOUT_S = 120L
        // Máximo de intentos para descargar el documento
        private const val MAX_POLL_ATTEMPTS = 20
        // Tiempo entre cada intento de descarga (ms)
        private const val POLL_INTERVAL_MS = 2_000L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(SCAN_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)  // Importante: eSCL usa redirects para job URLs
        .build()

    /**
     * Obtener capacidades del escáner
     *
     * Este endpoint devuelve un XML con todas las opciones disponibles:
     * - Resoluciones soportadas (75, 100, 150, 200, 300, 600 DPI)
     * - Modos de color (RGB24, Grayscale8, BlackAndWhite1)
     * - Fuentes de entrada (Platen=cama plana, Feeder=ADF)
     * - Formatos de salida (image/jpeg, image/png, application/pdf)
     * - Tamaños máximos de escaneo
     *
     * @param baseUrl URL base eSCL (ej: "http://192.168.1.10/eSCL")
     * @return ScannerCapabilities con las opciones disponibles
     */
    suspend fun getScannerCapabilities(baseUrl: String): ScannerCapabilities? {
        return try {
            val url = "$baseUrl/ScannerCapabilities"
            Log.d(TAG, "Consultando capacidades del escáner: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", "text/xml, application/xml")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Error al obtener capacidades: ${response.code}")
                return null
            }

            val xmlBody = response.body?.string() ?: return null
            Log.v(TAG, "Capacidades XML:\n$xmlBody")
            parseCapabilitiesXml(xmlBody)

        } catch (e: Exception) {
            Log.e(TAG, "Error en getScannerCapabilities: ${e.message}", e)
            null
        }
    }

    /**
     * Obtener estado del escáner
     *
     * Respuesta XML típica:
     * <scan:ScannerStatus>
     *   <pwg:Version>2.6</pwg:Version>
     *   <pwg:State>Idle</pwg:State>       → Idle, Processing, Testing, Stopped
     *   <scan:Jobs>
     *     <scan:JobInfo>
     *       <pwg:JobUri>/eSCL/ScanJobs/123</pwg:JobUri>
     *       <pwg:JobState>Completed</pwg:JobState>
     *     </scan:JobInfo>
     *   </scan:Jobs>
     * </scan:ScannerStatus>
     */
    suspend fun getScannerStatus(baseUrl: String): ScannerState {
        return try {
            val url = "$baseUrl/ScannerStatus"
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) return ScannerState.UNKNOWN

            val xml = response.body?.string() ?: return ScannerState.UNKNOWN
            parseScannerStateFromXml(xml)

        } catch (e: Exception) {
            Log.e(TAG, "Error al obtener estado del escáner: ${e.message}")
            ScannerState.UNKNOWN
        }
    }

    /**
     * Iniciar un trabajo de escaneo y descargar el resultado
     *
     * PROCESO COMPLETO:
     * 1. POST ScanSettings XML → obtener Job URI
     * 2. Polling GET NextDocument hasta que esté listo
     * 3. Retornar los bytes de la imagen
     *
     * @param baseUrl URL base eSCL
     * @param options Opciones de escaneo (resolución, color, fuente)
     * @return ByteArray con la imagen/PDF escaneado, o null si hay error
     */
    suspend fun scan(baseUrl: String, options: ScanOptions): ScanResult {
        return try {
            Log.d(TAG, "Iniciando escaneo con opciones: $options")

            // Paso 1: Crear trabajo de escaneo
            val jobUri = createScanJob(baseUrl, options)
                ?: return ScanResult.Error("No se pudo crear el trabajo de escaneo")

            Log.d(TAG, "Trabajo de escaneo creado: $jobUri")

            // Paso 2: Descargar el documento (con reintentos)
            val documentBytes = downloadScanDocument(baseUrl, jobUri)
                ?: return ScanResult.Error("Error al descargar el documento escaneado")

            Log.d(TAG, "Documento descargado: ${documentBytes.size} bytes")

            ScanResult.Success(
                data = documentBytes,
                mimeType = options.format.mimeType,
                resolution = options.resolution
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error en escaneo: ${e.message}", e)
            ScanResult.Error("Error inesperado: ${e.message}")
        }
    }

    /**
     * Crear trabajo de escaneo enviando XML con configuración
     *
     * POST /eSCL/ScanJobs
     * Content-Type: text/xml
     *
     * Respuesta exitosa: HTTP 201 Created
     * Header Location: /eSCL/ScanJobs/12345
     *
     * @return URI del trabajo creado (ej: "/eSCL/ScanJobs/12345")
     */
    private suspend fun createScanJob(baseUrl: String, options: ScanOptions): String? {
        val scanSettingsXml = buildScanSettingsXml(options)
        Log.v(TAG, "Enviando ScanSettings XML:\n$scanSettingsXml")

        val request = Request.Builder()
            .url("$baseUrl/ScanJobs")
            .post(scanSettingsXml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .addHeader("Content-Type", "text/xml; charset=utf-8")
            .build()

        // NO seguir el redirect automáticamente para capturar el Location header
        val noRedirectClient = httpClient.newBuilder()
            .followRedirects(false)
            .build()

        val response = noRedirectClient.newCall(request).execute()

        Log.d(TAG, "Respuesta a ScanJobs POST: ${response.code}")

        return when (response.code) {
            201 -> {
                // El URI del trabajo está en el header Location
                val location = response.header("Location")
                Log.d(TAG, "Job Location: $location")
                location
            }
            503 -> {
                Log.e(TAG, "Escáner ocupado (503)")
                null
            }
            else -> {
                Log.e(TAG, "Error al crear trabajo: ${response.code}")
                null
            }
        }
    }

    /**
     * Descargar el documento escaneado con polling
     *
     * GET /eSCL/ScanJobs/{id}/NextDocument
     *
     * Respuestas posibles:
     * 200 OK → Documento listo, cuerpo contiene la imagen
     * 503 Service Unavailable → Escáner todavía procesando, reintentar
     * 404 Not Found → Trabajo no existe o ya se descargó
     * 409 Conflict → No hay más páginas (para escaneo múltiple)
     */
    private suspend fun downloadScanDocument(baseUrl: String, jobUri: String): ByteArray? {
        val documentUrl = if (jobUri.startsWith("http")) {
            "$jobUri/NextDocument"
        } else {
            "http://${baseUrl.removePrefix("http://").substringBefore("/")}$jobUri/NextDocument"
        }

        Log.d(TAG, "Descargando documento desde: $documentUrl")

        for (attempt in 1..MAX_POLL_ATTEMPTS) {
            Log.d(TAG, "Intento $attempt/$MAX_POLL_ATTEMPTS de descarga")

            val request = Request.Builder()
                .url(documentUrl)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()

            when (response.code) {
                200 -> {
                    // ¡Documento listo!
                    val bytes = response.body?.bytes()
                    Log.d(TAG, "Documento descargado en intento $attempt")
                    return bytes
                }
                503 -> {
                    // Escáner todavía procesando, esperar y reintentar
                    Log.d(TAG, "Escáner procesando... esperando ${POLL_INTERVAL_MS}ms")
                    kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                }
                409 -> {
                    Log.d(TAG, "No hay más páginas disponibles")
                    return null
                }
                else -> {
                    Log.e(TAG, "Error inesperado al descargar: ${response.code}")
                    return null
                }
            }
        }

        Log.e(TAG, "Timeout: el escáner no respondió después de $MAX_POLL_ATTEMPTS intentos")
        return null
    }

    // =========================================================================
    // CONSTRUCTORES XML
    // =========================================================================

    /**
     * Construir el XML de configuración del escaneo (ScanSettings)
     *
     * Ejemplo real para escanear A4 a 300 DPI en color:
     *
     * Las dimensiones se expresan en 1/300 de pulgada (ThreeHundredthsOfInches)
     * A4 = 210mm × 297mm = 2480 × 3508 (a 300 DPI)
     * US Letter = 215.9mm × 279.4mm = 2550 × 3300 (a 300 DPI)
     */
    private fun buildScanSettingsXml(options: ScanOptions): String {
        // Calcular dimensiones del área de escaneo según el tamaño de papel
        val (width, height) = when (options.paperSize) {
            ScanPaperSize.A4 -> Pair(2480, 3508)      // A4 a 300 DPI
            ScanPaperSize.LETTER -> Pair(2550, 3300)   // US Letter a 300 DPI
            ScanPaperSize.AUTO -> Pair(2480, 3508)     // Default A4
        }

        // Escalar dimensiones según la resolución seleccionada
        val scaleFactor = options.resolution / 300.0
        val scaledWidth = (width * scaleFactor).toInt()
        val scaledHeight = (height * scaleFactor).toInt()

        val colorMode = when (options.colorMode) {
            ScanColorMode.COLOR -> "RGB24"
            ScanColorMode.GRAYSCALE -> "Grayscale8"
            ScanColorMode.BLACK_WHITE -> "BlackAndWhite1"
        }

        val inputSource = when (options.source) {
            ScanSource.FLATBED -> "Platen"   // Cama plana
            ScanSource.ADF -> "Feeder"       // Alimentador automático
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:scan="$NS_ESCL"
                   xmlns:pwg="$NS_PWG">
  <pwg:Version>2.6</pwg:Version>
  <scan:Intent>${options.intent.value}</scan:Intent>
  <pwg:ScanRegions>
    <pwg:ScanRegion>
      <pwg:XOffset>0</pwg:XOffset>
      <pwg:YOffset>0</pwg:YOffset>
      <pwg:Width>$scaledWidth</pwg:Width>
      <pwg:Height>$scaledHeight</pwg:Height>
      <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
    </pwg:ScanRegion>
  </pwg:ScanRegions>
  <scan:ColorMode>$colorMode</scan:ColorMode>
  <scan:XResolution>${options.resolution}</scan:XResolution>
  <scan:YResolution>${options.resolution}</scan:YResolution>
  <pwg:InputSource>$inputSource</pwg:InputSource>
  <scan:DocumentFormat>${options.format.mimeType}</scan:DocumentFormat>
  <scan:DocumentFormatExt>${options.format.mimeType}</scan:DocumentFormatExt>
</scan:ScanSettings>"""
    }

    // =========================================================================
    // PARSERS XML
    // =========================================================================

    /**
     * Parsear XML de capacidades del escáner
     *
     * Extrae resoluciones, modos de color y fuentes disponibles.
     */
    private fun parseCapabilitiesXml(xml: String): ScannerCapabilities {
        val resolutions = mutableListOf<Int>()
        val colorModes = mutableListOf<String>()
        val sources = mutableListOf<String>()
        var maxWidth = 2480
        var maxHeight = 3508
        var version = "2.6"

        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name ?: ""
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        when (currentTag) {
                            "XResolution", "YResolution" -> {
                                text.toIntOrNull()?.let {
                                    if (!resolutions.contains(it)) resolutions.add(it)
                                }
                            }
                            "ColorMode" -> {
                                if (!colorModes.contains(text)) colorModes.add(text)
                            }
                            "InputSource" -> {
                                if (!sources.contains(text)) sources.add(text)
                            }
                            "MaxWidth" -> maxWidth = text.toIntOrNull() ?: maxWidth
                            "MaxHeight" -> maxHeight = text.toIntOrNull() ?: maxHeight
                            "Version" -> version = text
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al parsear capacidades: ${e.message}")
        }

        return ScannerCapabilities(
            availableResolutions = resolutions.sorted(),
            availableColorModes = colorModes,
            availableSources = sources,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            version = version
        )
    }

    /**
     * Parsear estado del escáner desde XML
     */
    private fun parseScannerStateFromXml(xml: String): ScannerState {
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var state = "Idle"
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> currentTag = parser.name ?: ""
                    XmlPullParser.TEXT -> {
                        if (currentTag == "State") state = parser.text?.trim() ?: "Idle"
                    }
                }
                eventType = parser.next()
            }

            when (state.lowercase()) {
                "idle" -> ScannerState.IDLE
                "processing" -> ScannerState.PROCESSING
                "testing" -> ScannerState.TESTING
                "stopped" -> ScannerState.STOPPED
                else -> ScannerState.UNKNOWN
            }
        } catch (e: Exception) {
            ScannerState.UNKNOWN
        }
    }
}

// =========================================================================
// DATA CLASSES para eSCL
// =========================================================================

data class ScanOptions(
    val resolution: Int = 300,
    val colorMode: ScanColorMode = ScanColorMode.COLOR,
    val source: ScanSource = ScanSource.FLATBED,
    val format: ScanFormat = ScanFormat.JPEG,
    val paperSize: ScanPaperSize = ScanPaperSize.A4,
    val intent: ScanIntent = ScanIntent.DOCUMENT
)

enum class ScanColorMode { COLOR, GRAYSCALE, BLACK_WHITE }
enum class ScanSource { FLATBED, ADF }
enum class ScanPaperSize { A4, LETTER, AUTO }
enum class ScanIntent(val value: String) {
    DOCUMENT("Document"),
    TEXT_AND_GRAPHIC("TextAndGraphic"),
    PHOTO("Photo"),
    PREVIEW("Preview")
}

enum class ScanFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    PDF("application/pdf", "pdf")
}

enum class ScannerState { IDLE, PROCESSING, TESTING, STOPPED, UNKNOWN }

data class ScannerCapabilities(
    val availableResolutions: List<Int> = listOf(75, 150, 300, 600),
    val availableColorModes: List<String> = listOf("RGB24", "Grayscale8"),
    val availableSources: List<String> = listOf("Platen"),
    val maxWidth: Int = 2480,
    val maxHeight: Int = 3508,
    val version: String = "2.6"
)

/** Resultado del escaneo (sealed class para manejar éxito/error) */
sealed class ScanResult {
    data class Success(
        val data: ByteArray,
        val mimeType: String,
        val resolution: Int
    ) : ScanResult()

    data class Error(val message: String) : ScanResult()
}
