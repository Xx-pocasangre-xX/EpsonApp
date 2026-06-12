package com.example.epsonprintapp.scanner

import android.util.Log
import android.util.Xml
import com.example.epsonprintapp.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * EsclClient — Protocolo eSCL (AirPrint Scan).
 *
 * Epson L3560: expone eSCL en el puerto 80 (HTTP) bajo el path "/eSCL".
 * Requiere tener habilitado "Escanear en red"/eSCL en el panel de la impresora:
 *   Configuración → Configuración de red → Avanzado → eSCL.
 *
 * Estrategia de descubrimiento (sirve para otros modelos también):
 * 1. Probar paths conocidos en puerto 80, luego 443/8080/631
 * 2. Último recurso: buscar referencias eSCL en la página web de la impresora
 *
 * Contrato de concurrencia: todas las funciones suspend públicas son main-safe
 * (aíslan su I/O en Dispatchers.IO). Todas las respuestas HTTP se cierran con
 * use{} — sin fugas de conexiones del pool.
 */
class EsclClient(baseClient: OkHttpClient) {

    companion object {
        private const val TAG = "EsclClient"
        private const val NS_ESCL = "http://schemas.hp.com/imaging/escl/2011/05/03"
        private const val NS_PWG  = "http://www.pwg.org/schemas/2010/12/sm"
        private const val SCAN_TIMEOUT_S = 120L
    }

    // Los tres clientes derivan del base compartido (mismo pool de conexiones),
    // cada uno con los timeouts/redirects que su fase necesita.
    private val httpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)   // algunos Epson redirigen
        .build()

    private val scanClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(SCAN_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val downloadClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(SCAN_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ── Descubrimiento del path eSCL ──────────────────────────────────────────

    /**
     * Descubre la URL base eSCL correcta para esta impresora.
     * Para la L3560 normalmente es "http://<ip>/eSCL" (puerto 80).
     *
     * @return URL base (ej: "http://192.168.1.20/eSCL") o null si no responde
     */
    suspend fun discoverEsclPath(ipAddress: String): String? = withContext(Dispatchers.IO) {
        // Combinaciones a intentar: (puerto, path) — la primera es la de la L3560
        val candidates = listOf(
            80   to "/eSCL",
            80   to "/ESCL",
            80   to "/escl",
            443  to "/eSCL",
            8080 to "/eSCL",
            631  to "/eSCL",   // algunos modelos sirven eSCL también en 631
            80   to "/eSCL/",
        )

        for ((port, path) in candidates) {
            val scheme  = if (port == 443) "https" else "http"
            val portStr = if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) "" else ":$port"
            val testUrl = "$scheme://$ipAddress$portStr$path/ScannerCapabilities"

            Log.d(TAG, "Probando eSCL: $testUrl")
            try {
                val req = Request.Builder()
                    .url(testUrl)
                    .get()
                    .addHeader("Accept", "text/xml, application/xml, */*")
                    .build()
                val found = httpClient.newCall(req).execute().use { resp ->
                    Log.d(TAG, "  → HTTP ${resp.code}")
                    resp.code == 200
                }
                if (found) {
                    val baseUrl = "$scheme://$ipAddress$portStr$path"
                    Log.d(TAG, "✅ Path eSCL encontrado: $baseUrl")
                    return@withContext baseUrl
                }
            } catch (e: Exception) {
                Log.w(TAG, "  → Error: ${e.message}")
            }
        }

        // Último recurso: buscar referencias eSCL en la página web de la impresora
        tryDiscoverFromWebPage(ipAddress)
            ?: run { Log.e(TAG, "❌ No se encontró path eSCL válido en $ipAddress"); null }
    }

    /** Busca referencias a eSCL en el HTML de la interfaz web de la impresora. */
    private fun tryDiscoverFromWebPage(ipAddress: String): String? = try {
        val req = Request.Builder().url("http://$ipAddress/").get().build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (body != null && Regex("escl", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                Log.d(TAG, "Referencia eSCL encontrada en la página web")
                "http://$ipAddress/eSCL"
            } else null
        }
    } catch (e: Exception) {
        null
    }

    // ── Scanner Capabilities / Status ─────────────────────────────────────────

    suspend fun getScannerCapabilities(baseUrl: String): ScannerCapabilities? =
        withContext(Dispatchers.IO) {
            try {
                val url = if (baseUrl.endsWith("/ScannerCapabilities")) baseUrl
                else "$baseUrl/ScannerCapabilities"
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Accept", "text/xml, application/xml, */*")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "ScannerCapabilities HTTP ${resp.code} en $url")
                        null
                    } else {
                        resp.body?.string()?.let(::parseCapabilitiesXml)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getScannerCapabilities: ${e.message}")
                null
            }
        }

    suspend fun getScannerStatus(baseUrl: String): ScannerState = withContext(Dispatchers.IO) {
        try {
            val url = if (baseUrl.endsWith("/ScannerStatus")) baseUrl
            else "$baseUrl/ScannerStatus"
            downloadClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) ScannerState.UNKNOWN
                else resp.body?.string()?.let(::parseScannerState) ?: ScannerState.UNKNOWN
            }
        } catch (e: Exception) {
            ScannerState.UNKNOWN
        }
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    suspend fun scan(baseUrl: String, options: ScanOptions): ScanResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "=== Iniciando escaneo === baseUrl=$baseUrl")

            val ipAddress = extractIpFromUrl(baseUrl)

            // Verificar ScannerCapabilities con el path actual
            val caps = getScannerCapabilities(baseUrl)
            if (caps == null) {
                Log.w(TAG, "ScannerCapabilities no responde. Redescubriendo path…")
                if (ipAddress != null) {
                    val discovered = discoverEsclPath(ipAddress)
                    if (discovered != null && discovered != baseUrl) {
                        return@withContext scan(discovered, options)
                    }
                }
                return@withContext ScanResult.Error(
                    "El escáner no responde.\n" +
                            "• Reinicia la impresora e intenta de nuevo\n" +
                            "• Verifica que el escaneo en red (eSCL) esté habilitado en la impresora"
                )
            }

            Log.d(TAG, "ScannerCapabilities OK. Resoluciones: ${caps.availableResolutions}")

            // Esperar IDLE (máx ~20s)
            var waitAttempts = 0
            while (waitAttempts < 10) {
                val state = getScannerStatus(baseUrl)
                if (state != ScannerState.PROCESSING) break
                Log.d(TAG, "Escáner ocupado, esperando…")
                delay(AppConstants.SCAN_POLL_INTERVAL_MS)
                waitAttempts++
            }

            // Crear trabajo de escaneo
            val jobUri = createScanJob(baseUrl, options)
                ?: return@withContext ScanResult.Error(
                    "No se pudo crear el trabajo de escaneo.\n" +
                            "• Asegúrate de que no hay otro escaneo en curso\n" +
                            "• Coloca el documento en el cristal y vuelve a intentar"
                )

            Log.d(TAG, "Job URI: $jobUri")

            val nextDocUrl = buildNextDocumentUrl(baseUrl, jobUri)
            Log.d(TAG, "Descargando de: $nextDocUrl")

            val (bytes, mimeType) = pollForDocument(nextDocUrl)
                ?: return@withContext ScanResult.Error(
                    "Tiempo de espera agotado.\n" +
                            "• Verifica que hay un documento en el cristal\n" +
                            "• Intenta de nuevo"
                )

            Log.d(TAG, "✅ Escaneo completado: ${bytes.size} bytes")

            if (bytes.size < 500) {
                return@withContext ScanResult.Error(
                    "Imagen muy pequeña (${bytes.size} bytes). Reintenta con un documento en el cristal.")
            }

            ScanResult.Success(
                data       = bytes,
                mimeType   = normalizeMimeType(mimeType, options.format.mimeType),
                resolution = options.resolution
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error en scan: ${e.message}", e)
            ScanResult.Error("Error inesperado: ${e.message}")
        }
    }

    // ── Create Scan Job ───────────────────────────────────────────────────────

    private fun createScanJob(baseUrl: String, options: ScanOptions): String? {
        val xml = buildScanSettingsXml(options)
        val url = "$baseUrl/ScanJobs"
        Log.d(TAG, "POST $url")

        return try {
            val req = Request.Builder()
                .url(url)
                .post(xml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .addHeader("Accept", "*/*")
                .build()

            scanClient.newCall(req).execute().use { resp ->
                Log.d(TAG, "POST ScanJobs → HTTP ${resp.code}")
                when (resp.code) {
                    200, 201 -> resp.header("Location") ?: resp.header("location")
                    503      -> { Log.e(TAG, "503 Escáner ocupado"); null }
                    404      -> { Log.e(TAG, "404 ScanJobs — path incorrecto"); null }
                    else     -> { Log.e(TAG, "HTTP ${resp.code} inesperado"); null }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "createScanJob: ${e.message}")
            null
        }
    }

    // ── Poll for document ─────────────────────────────────────────────────────

    private suspend fun pollForDocument(nextDocUrl: String): Pair<ByteArray, String>? {
        for (attempt in 1..AppConstants.MAX_SCAN_POLL_ATTEMPTS) {
            Log.d(TAG, "Intento $attempt/${AppConstants.MAX_SCAN_POLL_ATTEMPTS} → GET $nextDocUrl")
            try {
                val outcome = downloadClient.newCall(
                    Request.Builder().url(nextDocUrl).get()
                        .addHeader("Accept", "image/jpeg, image/png, application/pdf, */*")
                        .build()
                ).execute().use { resp ->
                    val contentType = resp.header("Content-Type") ?: "application/octet-stream"
                    Log.d(TAG, "HTTP ${resp.code} | $contentType")
                    when (resp.code) {
                        200      -> PollOutcome.Document(resp.body?.bytes(), contentType)
                        202, 503 -> PollOutcome.Wait
                        404, 409 -> PollOutcome.Abort
                        else     -> PollOutcome.Unexpected
                    }
                }

                when (outcome) {
                    is PollOutcome.Document -> {
                        val bytes = outcome.bytes
                        if (bytes != null && bytes.isNotEmpty()) return bytes to outcome.contentType
                        delay(AppConstants.SCAN_POLL_INTERVAL_MS)
                    }
                    PollOutcome.Wait       -> delay(AppConstants.SCAN_POLL_INTERVAL_MS)
                    PollOutcome.Abort      -> return null
                    PollOutcome.Unexpected -> {
                        if (attempt >= 3) return null
                        delay(1_000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Excepción intento $attempt: ${e.message}")
                if (attempt >= 3) return null
                delay(1_000)
            }
        }
        return null
    }

    private sealed interface PollOutcome {
        class Document(val bytes: ByteArray?, val contentType: String) : PollOutcome
        data object Wait       : PollOutcome
        data object Abort      : PollOutcome
        data object Unexpected : PollOutcome
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildNextDocumentUrl(baseUrl: String, jobUri: String): String {
        val cleanBase = baseUrl.trimEnd('/')
        return when {
            jobUri.startsWith("http://") || jobUri.startsWith("https://") ->
                "${jobUri.trimEnd('/')}/NextDocument"
            jobUri.startsWith("/") -> {
                val host   = cleanBase.removePrefix("http://").removePrefix("https://").substringBefore("/")
                val scheme = if (cleanBase.startsWith("https")) "https" else "http"
                "$scheme://$host${jobUri.trimEnd('/')}/NextDocument"
            }
            else -> "$cleanBase/$jobUri/NextDocument"
        }
    }

    private fun extractIpFromUrl(url: String): String? = try {
        url.removePrefix("http://").removePrefix("https://")
            .substringBefore("/").substringBefore(":")
            .takeIf { it.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) }
    } catch (e: Exception) { null }

    private fun normalizeMimeType(received: String, requested: String): String = when {
        received.contains("jpeg") || received.contains("jpg") -> "image/jpeg"
        received.contains("png")  -> "image/png"
        received.contains("pdf")  -> "application/pdf"
        received.contains("octet-stream") || received.contains("*/*") ->
            when {
                requested.contains("jpeg") -> "image/jpeg"
                requested.contains("png")  -> "image/png"
                requested.contains("pdf")  -> "application/pdf"
                else                       -> "image/jpeg"
            }
        else -> requested.ifEmpty { "image/jpeg" }
    }

    // ── XML Builders ──────────────────────────────────────────────────────────

    private fun buildScanSettingsXml(options: ScanOptions): String {
        val (width, height) = when (options.paperSize) {
            ScanPaperSize.A4     -> 2480 to 3508
            ScanPaperSize.LETTER -> 2550 to 3300
            ScanPaperSize.AUTO   -> 2480 to 3508
        }
        val scale        = options.resolution / 300.0
        val colorMode    = when (options.colorMode) {
            ScanColorMode.COLOR       -> "RGB24"
            ScanColorMode.GRAYSCALE   -> "Grayscale8"
            ScanColorMode.BLACK_WHITE -> "BlackAndWhite1"
        }
        val inputSource  = when (options.source) {
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
      <pwg:Width>${(width * scale).toInt()}</pwg:Width>
      <pwg:Height>${(height * scale).toInt()}</pwg:Height>
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

    // ── XML Parsers ───────────────────────────────────────────────────────────

    private fun parseCapabilitiesXml(xml: String): ScannerCapabilities {
        val resolutions = mutableListOf<Int>()
        val colorModes  = mutableListOf<String>()
        val sources     = mutableListOf<String>()
        var maxWidth = 2480
        var maxHeight = 3508
        var version = "2.6"
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var et = parser.eventType
            var tag = ""
            while (et != XmlPullParser.END_DOCUMENT) {
                when (et) {
                    XmlPullParser.START_TAG -> tag = parser.name ?: ""
                    XmlPullParser.TEXT -> {
                        val t = parser.text?.trim() ?: ""
                        when (tag) {
                            "XResolution", "YResolution" ->
                                t.toIntOrNull()?.let { if (it !in resolutions) resolutions.add(it) }
                            "ColorMode"   -> if (t !in colorModes) colorModes.add(t)
                            "InputSource" -> if (t !in sources)    sources.add(t)
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

    private fun parseScannerState(xml: String): ScannerState = try {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var state = "Idle"
        var tag = ""
        var et = parser.eventType
        while (et != XmlPullParser.END_DOCUMENT) {
            when (et) {
                XmlPullParser.START_TAG -> tag = parser.name ?: ""
                XmlPullParser.TEXT -> if (tag == "State") state = parser.text?.trim() ?: "Idle"
            }
            et = parser.next()
        }
        when (state.lowercase()) {
            "idle"       -> ScannerState.IDLE
            "processing" -> ScannerState.PROCESSING
            "stopped"    -> ScannerState.STOPPED
            else         -> ScannerState.UNKNOWN
        }
    } catch (e: Exception) { ScannerState.UNKNOWN }
}

// ── Data classes ──────────────────────────────────────────────────────────────

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
    DOCUMENT("Document"), TEXT_AND_GRAPHIC("TextAndGraphic"),
    PHOTO("Photo"), PREVIEW("Preview")
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

/**
 * Resultado de un escaneo. Success NO es data class a propósito:
 * un equals()/hashCode() generado sobre ByteArray compararía por referencia
 * (comportamiento roto que el compilador señala con warning).
 */
sealed class ScanResult {
    class Success(val data: ByteArray, val mimeType: String, val resolution: Int) : ScanResult()
    data class Error(val message: String) : ScanResult()
}
