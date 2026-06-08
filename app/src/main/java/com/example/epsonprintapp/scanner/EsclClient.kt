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
 * EsclClient — Protocolo eSCL (AirPrint Scan).
 *
 * CORRECCIONES para Epson L3560 (y modelos similares):
 * =====================================================
 * El Epson L3560 expone eSCL en el puerto 80 con paths que incluyen
 * el número de serie o un identificador único. El path "/eSCL" devuelve
 * 404 porque la ruta real es algo como "/eSCL" pero en el puerto 80
 * con la URL completa del dispositivo.
 *
 * Estrategia de descubrimiento:
 * 1. Probar paths conocidos en puerto 80 (sin especificar puerto en URL)
 * 2. Probar en puerto 443 (HTTPS)
 * 3. Intentar leer el IPP Printer Attributes para encontrar el scanner-uri
 * 4. Probar paths alternativos conocidos para modelos Epson
 */
class EsclClient {

    companion object {
        private const val TAG = "EsclClient"
        private const val NS_ESCL = "http://schemas.hp.com/imaging/escl/2011/05/03"
        private const val NS_PWG  = "http://www.pwg.org/schemas/2010/12/sm"
        private const val SCAN_TIMEOUT_S    = 120L
        private const val MAX_POLL_ATTEMPTS = 30
        private const val POLL_INTERVAL_MS  = 2_000L

        // Paths que prueban Epson L3560 y modelos EcoTank / ET series
        // El L3560 usa "/eSCL" pero a veces requiere puerto 80 explícito
        // o la URL completa incluyendo el host del mDNS
        val ESCL_CANDIDATE_PATHS = listOf(
            "/eSCL",          // path estándar sin puerto explícito
            "/eSCL/",
            "/ESCL",
            "/escl",
            "/eSCL/ScannerCapabilities"  // algunos modelos solo responden aquí
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)   // seguir redirects — algunos Epson redirigen
        .build()

    private val scanClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(SCAN_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(SCAN_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ── Descubrimiento del path eSCL ──────────────────────────────────────────

    /**
     * Descubre el path eSCL correcto para esta impresora.
     *
     * Para Epson L3560: prueba múltiples combinaciones de host:puerto/path.
     * El L3560 expone eSCL en el puerto 80 (HTTP) del host principal.
     *
     * @param ipAddress IP de la impresora (ej: "192.168.1.20")
     * @return URL base correcta (ej: "http://192.168.1.20/eSCL") o null
     */
    suspend fun discoverEsclPath(ipAddress: String): String? {
        // Combinaciones a intentar: (puerto, path)
        val candidates = listOf(
            Pair(80,   "/eSCL"),
            Pair(80,   "/ESCL"),
            Pair(80,   "/escl"),
            Pair(443,  "/eSCL"),
            Pair(8080, "/eSCL"),
            Pair(631,  "/eSCL"),   // algunos modelos sirven eSCL también en 631
            Pair(80,   "/eSCL/"),
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
                val resp = httpClient.newCall(req).execute()
                val code = resp.code
                Log.d(TAG, "  → HTTP $code")

                if (code == 200) {
                    val baseUrl = "$scheme://$ipAddress$portStr$path"
                    Log.d(TAG, "✅ Path eSCL encontrado: $baseUrl")
                    return baseUrl
                }
                resp.close()
            } catch (e: Exception) {
                Log.w(TAG, "  → Error: ${e.message}")
            }
        }

        // Último recurso: intentar leer la página web de la impresora
        // para encontrar referencias al servicio de escaneo
        val webPath = tryDiscoverFromWebPage(ipAddress)
        if (webPath != null) return webPath

        Log.e(TAG, "❌ No se encontró path eSCL válido en $ipAddress")
        return null
    }

    /**
     * Intenta encontrar el path eSCL desde la página web de la impresora.
     * Algunas impresoras Epson muestran su URL eSCL en la interfaz web.
     */
    private suspend fun tryDiscoverFromWebPage(ipAddress: String): String? {
        return try {
            val req  = Request.Builder().url("http://$ipAddress/").get().build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            resp.close()

            // Buscar referencias a eSCL en el HTML
            val patterns = listOf(
                Regex("/eSCL", RegexOption.IGNORE_CASE),
                Regex("escl", RegexOption.IGNORE_CASE)
            )
            for (pattern in patterns) {
                val match = pattern.find(body)
                if (match != null) {
                    Log.d(TAG, "Path eSCL encontrado en HTML: ${match.value}")
                    return "http://$ipAddress/eSCL"
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ── Scanner Capabilities ──────────────────────────────────────────────────

    suspend fun getScannerCapabilities(baseUrl: String): ScannerCapabilities? {
        return try {
            val url  = if (baseUrl.endsWith("/ScannerCapabilities")) baseUrl
            else "$baseUrl/ScannerCapabilities"
            val req  = Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", "text/xml, application/xml, */*")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "ScannerCapabilities HTTP ${resp.code} en $url")
                return null
            }
            parseCapabilitiesXml(resp.body?.string() ?: return null)
        } catch (e: Exception) {
            Log.w(TAG, "getScannerCapabilities: ${e.message}")
            null
        }
    }

    suspend fun getScannerStatus(baseUrl: String): ScannerState {
        return try {
            val url  = if (baseUrl.endsWith("/ScannerStatus")) baseUrl
            else "$baseUrl/ScannerStatus"
            val resp = downloadClient.newCall(
                Request.Builder().url(url).get().build()
            ).execute()
            if (!resp.isSuccessful) return ScannerState.UNKNOWN
            parseScannerState(resp.body?.string() ?: return ScannerState.UNKNOWN)
        } catch (e: Exception) {
            ScannerState.UNKNOWN
        }
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    suspend fun scan(baseUrl: String, options: ScanOptions): ScanResult {
        return try {
            Log.d(TAG, "=== Iniciando escaneo === baseUrl=$baseUrl")

            val ipAddress = extractIpFromUrl(baseUrl)

            // Verificar ScannerCapabilities con el path actual
            val caps = getScannerCapabilities(baseUrl)
            if (caps == null) {
                Log.w(TAG, "ScannerCapabilities no responde. Redescubriendo path…")
                if (ipAddress != null) {
                    val discovered = discoverEsclPath(ipAddress)
                    if (discovered != null && discovered != baseUrl) {
                        return scan(discovered, options)
                    }
                }
                return ScanResult.Error(
                    "El escáner no responde.\n" +
                            "• Reinicia la impresora e intenta de nuevo\n" +
                            "• Verifica que el escaneo en red esté habilitado en la configuración de la impresora"
                )
            }

            Log.d(TAG, "ScannerCapabilities OK. Resoluciones: ${caps.availableResolutions}")

            // Esperar IDLE (máx 20s)
            var waitAttempts = 0
            while (waitAttempts < 10) {
                val state = getScannerStatus(baseUrl)
                if (state != ScannerState.PROCESSING) break
                Log.d(TAG, "Escáner ocupado, esperando…")
                kotlinx.coroutines.delay(2000)
                waitAttempts++
            }

            // Crear trabajo de escaneo
            val jobUri = createScanJob(baseUrl, options)
                ?: return ScanResult.Error(
                    "No se pudo crear el trabajo de escaneo.\n" +
                            "• Asegúrate de que no hay otro escaneo en curso\n" +
                            "• Coloca el documento en el cristal y vuelve a intentar"
                )

            Log.d(TAG, "Job URI: $jobUri")

            val nextDocUrl = buildNextDocumentUrl(baseUrl, jobUri)
            Log.d(TAG, "Descargando de: $nextDocUrl")

            val result = pollForDocument(nextDocUrl)
                ?: return ScanResult.Error(
                    "Tiempo de espera agotado.\n" +
                            "• Verifica que hay un documento en el cristal\n" +
                            "• Intenta de nuevo"
                )

            val (bytes, mimeType) = result
            Log.d(TAG, "✅ Escaneo completado: ${bytes.size} bytes")

            if (bytes.size < 500) {
                return ScanResult.Error("Imagen muy pequeña (${bytes.size} bytes). Reintenta con un documento en el cristal.")
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

    private suspend fun createScanJob(baseUrl: String, options: ScanOptions): String? {
        val xml = buildScanSettingsXml(options)
        val url = "$baseUrl/ScanJobs"
        Log.d(TAG, "POST $url")

        return try {
            val req = Request.Builder()
                .url(url)
                .post(xml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .addHeader("Content-Type", "text/xml; charset=utf-8")
                .addHeader("Accept", "*/*")
                .build()

            val resp = scanClient.newCall(req).execute()
            Log.d(TAG, "POST ScanJobs → HTTP ${resp.code}")

            when (resp.code) {
                201  -> resp.header("Location") ?: resp.header("location")
                200  -> resp.header("Location") ?: resp.header("location")
                503  -> { Log.e(TAG, "503 Escáner ocupado"); null }
                404  -> { Log.e(TAG, "404 ScanJobs — path incorrecto"); null }
                else -> { Log.e(TAG, "HTTP ${resp.code} inesperado"); null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "createScanJob: ${e.message}")
            null
        }
    }

    // ── Poll for document ─────────────────────────────────────────────────────

    private suspend fun pollForDocument(nextDocUrl: String): Pair<ByteArray, String>? {
        for (attempt in 1..MAX_POLL_ATTEMPTS) {
            Log.d(TAG, "Intento $attempt/$MAX_POLL_ATTEMPTS → GET $nextDocUrl")
            try {
                val resp        = downloadClient.newCall(
                    Request.Builder().url(nextDocUrl).get()
                        .addHeader("Accept", "image/jpeg, image/png, application/pdf, */*")
                        .build()
                ).execute()
                val code        = resp.code
                val contentType = resp.header("Content-Type") ?: "application/octet-stream"
                Log.d(TAG, "HTTP $code | $contentType")

                when (code) {
                    200 -> {
                        val bytes = resp.body?.bytes()
                        if (bytes == null || bytes.isEmpty()) {
                            kotlinx.coroutines.delay(POLL_INTERVAL_MS); continue
                        }
                        return Pair(bytes, contentType)
                    }
                    202, 503 -> kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                    404, 409 -> return null
                    else     -> { if (attempt >= 3) return null; kotlinx.coroutines.delay(1000) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Excepción intento $attempt: ${e.message}")
                if (attempt >= 3) return null
                kotlinx.coroutines.delay(1000)
            }
        }
        return null
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
            when { requested.contains("jpeg") -> "image/jpeg"
                requested.contains("png")  -> "image/png"
                requested.contains("pdf")  -> "application/pdf"
                else                       -> "image/jpeg" }
        else -> requested.ifEmpty { "image/jpeg" }
    }

    // ── XML Builders ──────────────────────────────────────────────────────────

    private fun buildScanSettingsXml(options: ScanOptions): String {
        val (width, height) = when (options.paperSize) {
            ScanPaperSize.A4     -> Pair(2480, 3508)
            ScanPaperSize.LETTER -> Pair(2550, 3300)
            ScanPaperSize.AUTO   -> Pair(2480, 3508)
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
        var maxWidth = 2480; var maxHeight = 3508; var version = "2.6"
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var et = parser.eventType; var tag = ""
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
        val parser = Xml.newPullParser(); parser.setInput(StringReader(xml))
        var state = "Idle"; var tag = ""; var et = parser.eventType
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
sealed class ScanResult {
    data class Success(val data: ByteArray, val mimeType: String, val resolution: Int) : ScanResult()
    data class Error(val message: String) : ScanResult()
}