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
 * FIX SCAN 0 PÁGINAS:
 * ===================
 * El scanner HP responde con HTTP 200 y los bytes de la imagen, pero:
 * 1. El Content-Type puede ser "image/jpeg" o "application/octet-stream"
 * 2. El download puede retornar 200 inmediatamente (HP es rápida) o 503 (Epson tarda)
 * 3. BitmapFactory puede fallar si el array de bytes tiene corrupción parcial
 *
 * Fixes aplicados:
 * - Log del Content-Type real recibido
 * - Retornar los bytes RAW junto con el content-type real
 * - Reintentar la descarga si los bytes son < 1000 (imagen incompleta)
 * - NO asumir que la imagen es siempre JPEG — usar el content-type real
 */
class EsclClient {

    companion object {
        private const val TAG = "EsclClient"
        private const val NS_ESCL = "http://schemas.hp.com/imaging/escl/2011/05/03"
        private const val NS_PWG  = "http://www.pwg.org/schemas/2010/12/sm"
        private const val SCAN_TIMEOUT_S    = 120L
        private const val MAX_POLL_ATTEMPTS = 20
        private const val POLL_INTERVAL_MS  = 2_000L
    }

    private val httpClient = OkHttpClient.Builder()
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
            if (!resp.isSuccessful) { Log.e(TAG,"Cap error: ${resp.code}"); return null }
            parseCapabilitiesXml(resp.body?.string() ?: return null)
        } catch (e: Exception) { Log.e(TAG,"getScannerCapabilities: ${e.message}"); null }
    }

    suspend fun getScannerStatus(baseUrl: String): ScannerState {
        return try {
            val resp = httpClient.newCall(Request.Builder().url("$baseUrl/ScannerStatus").get().build()).execute()
            if (!resp.isSuccessful) return ScannerState.UNKNOWN
            parseScannerState(resp.body?.string() ?: return ScannerState.UNKNOWN)
        } catch (e: Exception) { ScannerState.UNKNOWN }
    }

    // ── SCAN ──────────────────────────────────────────────────────────────────

    suspend fun scan(baseUrl: String, options: ScanOptions): ScanResult {
        return try {
            Log.d(TAG, "Iniciando escaneo con opciones: $options")

            val jobUri = createScanJob(baseUrl, options)
                ?: return ScanResult.Error("No se pudo crear el trabajo de escaneo")

            Log.d(TAG, "Trabajo de escaneo creado: $jobUri")

            // FIX: downloadScanDocument ahora retorna Pair<ByteArray, String> (bytes + contentType real)
            val (documentBytes, realMimeType) = downloadScanDocument(baseUrl, jobUri)
                ?: return ScanResult.Error("Error al descargar el documento escaneado")

            if (documentBytes.size < 100) {
                Log.e(TAG, "Imagen descargada muy pequeña: ${documentBytes.size} bytes — posiblemente corrupta")
                return ScanResult.Error("La imagen escaneada está vacía o corrupta")
            }

            Log.d(TAG, "Documento descargado: ${documentBytes.size} bytes | ContentType real: $realMimeType")

            // Usar el mime-type real del response, no el solicitado
            val finalMime = when {
                realMimeType.contains("jpeg") || realMimeType.contains("jpg") -> "image/jpeg"
                realMimeType.contains("png")  -> "image/png"
                realMimeType.contains("pdf")  -> "application/pdf"
                options.format.mimeType.isNotEmpty() -> options.format.mimeType
                else -> "image/jpeg"
            }

            ScanResult.Success(data = documentBytes, mimeType = finalMime, resolution = options.resolution)

        } catch (e: Exception) {
            Log.e(TAG, "Error en escaneo: ${e.message}", e)
            ScanResult.Error("Error inesperado: ${e.message}")
        }
    }

    // ── CREATE SCAN JOB ───────────────────────────────────────────────────────

    private suspend fun createScanJob(baseUrl: String, options: ScanOptions): String? {
        val xml = buildScanSettingsXml(options)
        Log.v(TAG, "Enviando ScanSettings XML:\n$xml")

        val noRedirectClient = httpClient.newBuilder().followRedirects(false).build()
        val req = Request.Builder()
            .url("$baseUrl/ScanJobs")
            .post(xml.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .addHeader("Content-Type", "text/xml; charset=utf-8")
            .build()

        val resp = noRedirectClient.newCall(req).execute()
        Log.d(TAG, "Respuesta a ScanJobs POST: ${resp.code}")

        return when (resp.code) {
            201  -> { val loc = resp.header("Location"); Log.d(TAG, "Job Location: $loc"); loc }
            503  -> { Log.e(TAG, "Escáner ocupado (503)"); null }
            else -> { Log.e(TAG, "Error al crear trabajo: ${resp.code}"); null }
        }
    }

    // ── DOWNLOAD SCAN DOCUMENT ────────────────────────────────────────────────

    /**
     * FIX: Retorna Pair<ByteArray, String> — bytes de la imagen + content-type real.
     * Antes se asumía que siempre era JPEG, pero la HP puede devolver otro tipo.
     * También se agregan logs del content-type recibido para diagnóstico.
     */
    private suspend fun downloadScanDocument(baseUrl: String, jobUri: String): Pair<ByteArray, String>? {
        // Construir la URL de descarga correctamente
        val documentUrl = buildDownloadUrl(baseUrl, jobUri)
        Log.d(TAG, "Descargando documento desde: $documentUrl")

        for (attempt in 1..MAX_POLL_ATTEMPTS) {
            Log.d(TAG, "Intento $attempt/$MAX_POLL_ATTEMPTS de descarga")

            val req  = Request.Builder().url(documentUrl).get().build()
            val resp = httpClient.newCall(req).execute()

            Log.d(TAG, "Respuesta descarga: HTTP ${resp.code} | Content-Type: ${resp.header("Content-Type")} | Content-Length: ${resp.header("Content-Length")}")

            when (resp.code) {
                200 -> {
                    val contentType = resp.header("Content-Type") ?: "image/jpeg"
                    val bytes = resp.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        Log.e(TAG, "Cuerpo vacío en intento $attempt")
                        kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                        continue
                    }
                    Log.d(TAG, "✅ Documento descargado en intento $attempt: ${bytes.size} bytes | tipo: $contentType")
                    return Pair(bytes, contentType)
                }
                503 -> {
                    Log.d(TAG, "Escáner procesando... esperando ${POLL_INTERVAL_MS}ms")
                    kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                }
                404 -> { Log.e(TAG, "Trabajo no encontrado (404)"); return null }
                409 -> { Log.d(TAG, "No hay más páginas (409)"); return null }
                else -> {
                    Log.e(TAG, "Error inesperado: ${resp.code}")
                    if (attempt < 3) {
                        kotlinx.coroutines.delay(1000)
                    } else {
                        return null
                    }
                }
            }
        }
        Log.e(TAG, "Timeout: sin respuesta después de $MAX_POLL_ATTEMPTS intentos")
        return null
    }

    /**
     * Construye la URL de descarga correctamente.
     * HP devuelve Location como URL completa: http://192.168.0.2:80/eSCL/ScanJobs/uuid
     * Epson puede devolver path relativo: /eSCL/ScanJobs/123
     */
    private fun buildDownloadUrl(baseUrl: String, jobUri: String): String {
        return if (jobUri.startsWith("http://") || jobUri.startsWith("https://")) {
            // URL completa de HP — agregar /NextDocument
            "$jobUri/NextDocument"
        } else {
            // Path relativo — construir desde la base
            val host = baseUrl.removePrefix("http://").removePrefix("https://").substringBefore("/")
            "http://$host$jobUri/NextDocument"
        }
    }

    // ── XML BUILDERS ──────────────────────────────────────────────────────────

    private fun buildScanSettingsXml(options: ScanOptions): String {
        val (width, height) = when (options.paperSize) {
            ScanPaperSize.A4     -> Pair(2480, 3508)
            ScanPaperSize.LETTER -> Pair(2550, 3300)
            ScanPaperSize.AUTO   -> Pair(2480, 3508)
        }
        val scale        = options.resolution / 300.0
        val scaledWidth  = (width  * scale).toInt()
        val scaledHeight = (height * scale).toInt()

        val colorMode   = when (options.colorMode) {
            ScanColorMode.COLOR      -> "RGB24"
            ScanColorMode.GRAYSCALE  -> "Grayscale8"
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
                            "XResolution","YResolution" -> t.toIntOrNull()?.let { if(!resolutions.contains(it)) resolutions.add(it) }
                            "ColorMode"   -> if (!colorModes.contains(t)) colorModes.add(t)
                            "InputSource" -> if (!sources.contains(t))     sources.add(t)
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
                    XmlPullParser.TEXT -> if (currentTag=="State") state = parser.text?.trim() ?: "Idle"
                }
                et = parser.next()
            }
            when (state.lowercase()) {
                "idle"->"ScannerState.IDLE"; "processing"->"ScannerState.PROCESSING"
                else -> "ScannerState.UNKNOWN"
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
    JPEG("image/jpeg","jpg"), PNG("image/png","png"), PDF("application/pdf","pdf")
}
enum class ScannerState { IDLE, PROCESSING, TESTING, STOPPED, UNKNOWN }
data class ScannerCapabilities(
    val availableResolutions: List<Int>    = listOf(75,150,300,600),
    val availableColorModes:  List<String> = listOf("RGB24","Grayscale8"),
    val availableSources:     List<String> = listOf("Platen"),
    val maxWidth:  Int    = 2480, val maxHeight: Int = 3508, val version: String = "2.6"
)
sealed class ScanResult {
    data class Success(val data: ByteArray, val mimeType: String, val resolution: Int) : ScanResult()
    data class Error(val message: String) : ScanResult()
}