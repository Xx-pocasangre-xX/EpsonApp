package com.example.epsonprintapp.scanner

import android.util.Log
import android.util.Xml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * EsclClient — Protocolo eSCL (AirPrint Scan)
 *
 * FLUJO ESCL CORRECTO:
 * ====================
 * 1. POST  {base}/ScanJobs          → 201 Created + Location: {base}/ScanJobs/{uuid}
 * 2. GET   {jobUri}/NextDocument    → 200 OK + bytes imagen  (o 503 mientras procesa)
 * 3. GET   {jobUri}/NextDocument    → 404 o 409 cuando no hay más páginas
 *
 * PROBLEMAS COMUNES:
 * - La impresora devuelve Location como URL completa o path relativo según el modelo
 * - Content-Type puede ser "image/jpeg", "application/octet-stream" o incluso "multipart/x-mixed-replace"
 * - Epson a veces tarda varios segundos devolviendo 503 antes del 200
 * - HP a veces devuelve 200 inmediatamente con los bytes completos
 */
class EsclClient {

    companion object {
        private const val TAG = "EsclClient"
        private const val NS_ESCL = "http://schemas.hp.com/imaging/escl/2011/05/03"
        private const val NS_PWG  = "http://www.pwg.org/schemas/2010/12/sm"
        private const val SCAN_TIMEOUT_S    = 120L
        private const val MAX_POLL_ATTEMPTS = 30
        private const val POLL_INTERVAL_MS  = 2_000L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(SCAN_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)   // Manejar redirects manualmente para capturar Location
        .build()

    // Cliente separado para descarga (sigue redirects, timeout más largo)
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(SCAN_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ── SCANNER CAPABILITIES ──────────────────────────────────────────────────

    suspend fun getScannerCapabilities(baseUrl: String): ScannerCapabilities? {
        return try {
            val url  = "$baseUrl/ScannerCapabilities"
            val req  = Request.Builder().url(url).get()
                .addHeader("Accept", "text/xml, application/xml").build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) { Log.e(TAG, "Cap error: ${resp.code}"); return null }
            parseCapabilitiesXml(resp.body?.string() ?: return null)
        } catch (e: Exception) { Log.e(TAG, "getScannerCapabilities: ${e.message}"); null }
    }

    suspend fun getScannerStatus(baseUrl: String): ScannerState {
        return try {
            val resp = downloadClient.newCall(
                Request.Builder().url("$baseUrl/ScannerStatus").get().build()
            ).execute()
            if (!resp.isSuccessful) return ScannerState.UNKNOWN
            parseScannerState(resp.body?.string() ?: return ScannerState.UNKNOWN)
        } catch (e: Exception) { ScannerState.UNKNOWN }
    }

    // ── SCAN ──────────────────────────────────────────────────────────────────

    suspend fun scan(baseUrl: String, options: ScanOptions): ScanResult {
        return try {
            Log.d(TAG, "=== Iniciando escaneo === baseUrl=$baseUrl")

            // Paso 1: Crear el trabajo de escaneo
            val jobUri = createScanJob(baseUrl, options)
                ?: return ScanResult.Error("No se pudo crear el trabajo de escaneo. Verifica que el escáner esté listo.")

            Log.d(TAG, "Job URI recibido: $jobUri")

            // Paso 2: Descargar el documento
            val nextDocUrl = buildNextDocumentUrl(baseUrl, jobUri)
            Log.d(TAG, "URL de descarga: $nextDocUrl")

            val (documentBytes, realMimeType) = pollForDocument(nextDocUrl)
                ?: return ScanResult.Error("Tiempo de espera agotado o error al descargar el escaneo.")

            Log.d(TAG, "Descarga OK: ${documentBytes.size} bytes | mime: $realMimeType")

            if (documentBytes.size < 500) {
                return ScanResult.Error("Imagen recibida demasiado pequeña (${documentBytes.size} bytes). Reintenta.")
            }

            val finalMime = normalizeMimeType(realMimeType, options.format.mimeType)
            ScanResult.Success(data = documentBytes, mimeType = finalMime, resolution = options.resolution)

        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado en scan: ${e.message}", e)
            ScanResult.Error("Error: ${e.message}")
        }
    }

    // ── CREATE SCAN JOB ───────────────────────────────────────────────────────

    private suspend fun createScanJob(baseUrl: String, options: ScanOptions): String? {
        val xml = buildScanSettingsXml(options)
        Log.d(TAG, "POST a $baseUrl/ScanJobs")
        Log.v(TAG, "XML:\n$xml")

        return try {
            val req = Request.Builder()
                .url("$baseUrl/ScanJobs")
                .post(xml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .addHeader("Content-Type", "text/xml; charset=utf-8")
                .addHeader("Accept", "*/*")
                .build()

            val resp = httpClient.newCall(req).execute()
            Log.d(TAG, "POST ScanJobs → HTTP ${resp.code}")

            when (resp.code) {
                201 -> {
                    val location = resp.header("Location")
                    Log.d(TAG, "Location header: $location")
                    location
                }
                // Algunos Epson responden 200 en vez de 201
                200 -> {
                    val location = resp.header("Location")
                        ?: resp.header("location")
                    Log.d(TAG, "HTTP 200 con Location: $location")
                    location
                }
                503 -> {
                    Log.e(TAG, "Escáner ocupado (503)")
                    null
                }
                else -> {
                    val body = try { resp.body?.string() } catch (e: Exception) { "" }
                    Log.e(TAG, "Error creando job: HTTP ${resp.code} | body: $body")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción en createScanJob: ${e.message}")
            null
        }
    }

    // ── POLL FOR DOCUMENT ─────────────────────────────────────────────────────

    /**
     * Hace polling hasta obtener los bytes del documento escaneado.
     * Devuelve Pair<ByteArray, String> (bytes, contentType) o null si falla.
     */
    private suspend fun pollForDocument(nextDocUrl: String): Pair<ByteArray, String>? {
        for (attempt in 1..MAX_POLL_ATTEMPTS) {
            Log.d(TAG, "Intento $attempt/$MAX_POLL_ATTEMPTS → GET $nextDocUrl")

            try {
                val req  = Request.Builder()
                    .url(nextDocUrl)
                    .get()
                    .addHeader("Accept", "image/jpeg, image/png, application/pdf, */*")
                    .build()

                val resp = downloadClient.newCall(req).execute()
                val code = resp.code
                val contentType = resp.header("Content-Type") ?: "application/octet-stream"
                val contentLength = resp.header("Content-Length")

                Log.d(TAG, "HTTP $code | Content-Type: $contentType | Content-Length: $contentLength")

                when (code) {
                    200 -> {
                        val bytes = resp.body?.bytes()
                        if (bytes == null || bytes.isEmpty()) {
                            Log.w(TAG, "Cuerpo vacío en intento $attempt, reintentando...")
                            kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                            continue
                        }
                        Log.d(TAG, "✅ Documento recibido: ${bytes.size} bytes")
                        // Log de los primeros bytes para diagnóstico
                        Log.d(TAG, "Primeros bytes: ${bytes.take(16).joinToString(" ") { "%02X".format(it) }}")
                        return Pair(bytes, contentType)
                    }
                    202 -> {
                        // Algunos escáneres devuelven 202 Accepted mientras procesan
                        Log.d(TAG, "202 Accepted, esperando...")
                        kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                    }
                    503 -> {
                        Log.d(TAG, "503 - Escáner procesando, esperando ${POLL_INTERVAL_MS}ms...")
                        kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                    }
                    404 -> {
                        Log.e(TAG, "404 - Trabajo no encontrado o expirado")
                        return null
                    }
                    409 -> {
                        Log.d(TAG, "409 - No hay más páginas")
                        return null
                    }
                    else -> {
                        Log.e(TAG, "HTTP $code inesperado en intento $attempt")
                        if (attempt >= 3) return null
                        kotlinx.coroutines.delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Excepción en intento $attempt: ${e.message}")
                if (attempt >= 3) return null
                kotlinx.coroutines.delay(1000)
            }
        }

        Log.e(TAG, "Timeout: $MAX_POLL_ATTEMPTS intentos sin éxito")
        return null
    }

    // ── URL BUILDING ──────────────────────────────────────────────────────────

    /**
     * Construye la URL correcta para NextDocument.
     *
     * Casos posibles del Location header:
     * - URL completa:  "http://192.168.1.5:80/eSCL/ScanJobs/abc123"
     * - Path absoluto: "/eSCL/ScanJobs/abc123"
     * - Path relativo: "ScanJobs/abc123"
     */
    private fun buildNextDocumentUrl(baseUrl: String, jobUri: String): String {
        val cleanBase = baseUrl.trimEnd('/')

        return when {
            // URL completa — usar directamente + /NextDocument
            jobUri.startsWith("http://") || jobUri.startsWith("https://") -> {
                "${jobUri.trimEnd('/')}/NextDocument"
            }
            // Path absoluto (empieza con /)
            jobUri.startsWith("/") -> {
                // Extraer host del baseUrl
                val hostPart = cleanBase.removePrefix("http://").removePrefix("https://")
                    .substringBefore("/")
                val scheme = if (cleanBase.startsWith("https")) "https" else "http"
                "$scheme://$hostPart${jobUri.trimEnd('/')}/NextDocument"
            }
            // Path relativo
            else -> {
                "$cleanBase/$jobUri/NextDocument"
            }
        }
    }

    private fun normalizeMimeType(received: String, requested: String): String {
        return when {
            received.contains("jpeg") || received.contains("jpg") -> "image/jpeg"
            received.contains("png")  -> "image/png"
            received.contains("pdf")  -> "application/pdf"
            // Si el Content-Type recibido no es útil, usar el solicitado
            received.contains("octet-stream") || received.contains("*/*") -> {
                when {
                    requested.contains("jpeg") -> "image/jpeg"
                    requested.contains("png")  -> "image/png"
                    requested.contains("pdf")  -> "application/pdf"
                    else -> "image/jpeg"
                }
            }
            else -> requested.ifEmpty { "image/jpeg" }
        }
    }

    // ── XML BUILDERS ──────────────────────────────────────────────────────────

    private fun buildScanSettingsXml(options: ScanOptions): String {
        // Dimensiones en 1/300 de pulgada
        val (width, height) = when (options.paperSize) {
            ScanPaperSize.A4     -> Pair(2480, 3508)
            ScanPaperSize.LETTER -> Pair(2550, 3300)
            ScanPaperSize.AUTO   -> Pair(2480, 3508)
        }
        // Escalar dimensiones según resolución
        val scale        = options.resolution / 300.0
        val scaledWidth  = (width  * scale).toInt()
        val scaledHeight = (height * scale).toInt()

        val colorMode = when (options.colorMode) {
            ScanColorMode.COLOR       -> "RGB24"
            ScanColorMode.GRAYSCALE   -> "Grayscale8"
            ScanColorMode.BLACK_WHITE -> "BlackAndWhite1"
        }
        val inputSource = when (options.source) {
            ScanSource.FLATBED -> "Platen"
            ScanSource.ADF     -> "Feeder"
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

    // ── XML PARSERS ───────────────────────────────────────────────────────────

    private fun parseCapabilitiesXml(xml: String): ScannerCapabilities {
        val resolutions = mutableListOf<Int>()
        val colorModes  = mutableListOf<String>()
        val sources     = mutableListOf<String>()
        var maxWidth = 2480; var maxHeight = 3508; var version = "2.6"
        try {
            val parser = Xml.newPullParser(); parser.setInput(StringReader(xml))
            var et = parser.eventType; var currentTag = ""
            while (et != XmlPullParser.END_DOCUMENT) {
                when (et) {
                    XmlPullParser.START_TAG -> currentTag = parser.name ?: ""
                    XmlPullParser.TEXT -> {
                        val t = parser.text?.trim() ?: ""
                        when (currentTag) {
                            "XResolution", "YResolution" ->
                                t.toIntOrNull()?.let { if (!resolutions.contains(it)) resolutions.add(it) }
                            "ColorMode"   -> if (!colorModes.contains(t)) colorModes.add(t)
                            "InputSource" -> if (!sources.contains(t))    sources.add(t)
                            "MaxWidth"    -> maxWidth  = t.toIntOrNull() ?: maxWidth
                            "MaxHeight"   -> maxHeight = t.toIntOrNull() ?: maxHeight
                            "Version"     -> version   = t
                        }
                    }
                }
                et = parser.next()
            }
        } catch (e: Exception) { Log.e(TAG, "parseCapabilities: ${e.message}") }
        return ScannerCapabilities(resolutions.sorted(), colorModes, sources, maxWidth, maxHeight, version)
    }

    private fun parseScannerState(xml: String): ScannerState {
        return try {
            val parser = Xml.newPullParser(); parser.setInput(StringReader(xml))
            var state = "Idle"; var currentTag = ""; var et = parser.eventType
            while (et != XmlPullParser.END_DOCUMENT) {
                when (et) {
                    XmlPullParser.START_TAG -> currentTag = parser.name ?: ""
                    XmlPullParser.TEXT -> if (currentTag == "State") state = parser.text?.trim() ?: "Idle"
                }
                et = parser.next()
            }
            when (state.lowercase()) {
                "idle"       -> ScannerState.IDLE
                "processing" -> ScannerState.PROCESSING
                "testing"    -> ScannerState.TESTING
                "stopped"    -> ScannerState.STOPPED
                else         -> ScannerState.UNKNOWN
            }
        } catch (e: Exception) { ScannerState.UNKNOWN }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
data class ScanOptions(
    val resolution: Int            = 300,
    val colorMode:  ScanColorMode  = ScanColorMode.COLOR,
    val source:     ScanSource     = ScanSource.FLATBED,
    val format:     ScanFormat     = ScanFormat.JPEG,
    val paperSize:  ScanPaperSize  = ScanPaperSize.A4,
    val intent:     ScanIntent     = ScanIntent.DOCUMENT
)
enum class ScanColorMode   { COLOR, GRAYSCALE, BLACK_WHITE }
enum class ScanSource      { FLATBED, ADF }
enum class ScanPaperSize   { A4, LETTER, AUTO }
enum class ScanIntent(val value: String) {
    DOCUMENT("Document"), TEXT_AND_GRAPHIC("TextAndGraphic"), PHOTO("Photo"), PREVIEW("Preview")
}
enum class ScanFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), PDF("application/pdf", "pdf")
}
enum class ScannerState { IDLE, PROCESSING, TESTING, STOPPED, UNKNOWN }
data class ScannerCapabilities(
    val availableResolutions: List<Int>    = listOf(75, 150, 300, 600),
    val availableColorModes:  List<String> = listOf("RGB24", "Grayscale8"),
    val availableSources:     List<String> = listOf("Platen"),
    val maxWidth:  Int    = 2480,
    val maxHeight: Int    = 3508,
    val version:   String = "2.6"
)
sealed class ScanResult {
    data class Success(val data: ByteArray, val mimeType: String, val resolution: Int) : ScanResult()
    data class Error(val message: String) : ScanResult()
}