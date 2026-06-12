package com.example.epsonprintapp.printer

import android.util.Log
import com.example.epsonprintapp.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * IppClient — Cliente IPP 2.0 (Internet Printing Protocol).
 *
 * Diseñado para la Epson L3560 pero compatible con cualquier impresora IPP:
 * - El puerto 9100 es RAW printing, NO es IPP. IPP siempre es puerto 631.
 *   fixIppUrl() fuerza el puerto 631 si detecta 9100.
 * - La L3560 a veces rechaza JPEG con HTTP 500: hay retry con
 *   application/octet-stream como fallback.
 * - ipp-attribute-fidelity = false siempre, para que la impresora ignore
 *   atributos que no soporte en lugar de rechazar el trabajo.
 * - La L3560 NO tiene dúplex automático: resolveSides() solo envía "sides"
 *   si la impresora reportó soportarlo, evitando errores 0x040B.
 *
 * Contrato de concurrencia: todas las funciones suspend son main-safe
 * (el I/O se aísla internamente en Dispatchers.IO). El documento se envía
 * en streaming — nunca se carga completo en memoria.
 */
class IppClient(baseClient: OkHttpClient) {

    companion object {
        private const val TAG = "IppClient"

        private const val IPP_VERSION_MAJOR: Byte      = 0x02
        private const val IPP_VERSION_MINOR: Byte      = 0x00
        private const val OP_PRINT_JOB:      Short     = 0x0002
        private const val OP_GET_PRINTER_ATTRS: Short  = 0x000B

        private const val TAG_OPERATION_ATTRIBUTES: Byte  = 0x01
        private const val TAG_JOB_ATTRIBUTES:       Byte  = 0x02
        private const val TAG_END_ATTRIBUTES:        Byte  = 0x03
        private const val TAG_INTEGER:               Byte  = 0x21
        private const val TAG_BOOLEAN:               Byte  = 0x22
        private const val TAG_KEYWORD:               Byte  = 0x44
        private const val TAG_URI:                   Byte  = 0x45
        private const val TAG_CHARSET:               Byte  = 0x47
        private const val TAG_NATURAL_LANGUAGE:      Byte  = 0x48
        private const val TAG_MIME_MEDIA_TYPE:       Byte  = 0x49
        private const val TAG_NAME_WITHOUT_LANGUAGE: Byte  = 0x42

        private val IPP_MEDIA_TYPE    = "application/ipp".toMediaType()
        private val IPP_SUCCESS_RANGE = 0x0000..0x00FF

        private val requestIdCounter = AtomicInteger(1)
        private fun nextId()         = requestIdCounter.getAndIncrement()

        /** Puerto IPP correcto. Si la URL tiene 9100 (RAW), lo corrige a 631. */
        fun fixIppUrl(url: String): String {
            return if (url.contains(":9100")) {
                url.replace(":9100", ":631")
            } else url
        }
    }

    // Capacidades detectadas en el último getPrinterStatus().
    // @Volatile: se escriben desde Dispatchers.IO y se leen desde cualquier hilo.
    @Volatile var detectedMediaReady:  String?       = null;   private set
    @Volatile var detectedColorModes:  List<String>  = emptyList(); private set
    @Volatile var detectedSides:       List<String>  = emptyList(); private set
    @Volatile var detectedDocFormats:  List<String>  = emptyList(); private set
    @Volatile var supportsColor:       Boolean       = true;   private set
    @Volatile var supportsDuplex:      Boolean       = false;  private set

    // Deriva del cliente base compartido: mismo pool de conexiones, timeouts propios
    private val http = baseClient.newBuilder()
        .readTimeout(AppConstants.HTTP_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(AppConstants.HTTP_WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    // ── GET PRINTER STATUS ────────────────────────────────────────────────────

    suspend fun getPrinterStatus(printerUrl: String): PrinterStatus? = withContext(Dispatchers.IO) {
        val correctedUrl = fixIppUrl(printerUrl)
        if (correctedUrl != printerUrl) {
            Log.d(TAG, "URL corregida: $printerUrl → $correctedUrl")
        }
        try {
            Log.d(TAG, "Consultando estado: $correctedUrl")
            val req = Request.Builder()
                .url(correctedUrl)
                .post(buildGetPrinterAttrs(correctedUrl).toRequestBody(IPP_MEDIA_TYPE))
                .addHeader("Accept", "application/ipp")
                .build()
            http.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "Respuesta vacía de getPrinterStatus (HTTP ${resp.code})")
                    null
                } else {
                    parsePrinterAttrs(bytes)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPrinterStatus error: ${e.message}")
            null
        }
    }

    // ── PRINT DOCUMENT ────────────────────────────────────────────────────────

    /**
     * Imprime un documento enviándolo en streaming.
     *
     * @param documentSource Proveedor del stream del documento. Se invoca una vez
     *   por intento (hasta 3 intentos), de modo que cada retry reabre el documento
     *   sin haberlo retenido en memoria.
     */
    suspend fun printDocument(
        printerUrl:     String,
        mimeType:       String,
        printOptions:   PrintOptions,
        documentSource: () -> InputStream?
    ): PrintJobResult? = withContext(Dispatchers.IO) {
        val correctedUrl = fixIppUrl(printerUrl)
        try {
            // Intento 1: con todas las opciones
            var result = sendPrintJob(correctedUrl, mimeType, printOptions, minimal = false, documentSource)

            // Intento 2: modo mínimo si la impresora rechazó atributos (0x0400 / 0x040B)
            if (result?.statusCode == 0x0400 || result?.statusCode == 0x040B) {
                Log.w(TAG, "Atributo rechazado → reintentando en modo mínimo")
                result = sendPrintJob(correctedUrl, mimeType, printOptions, minimal = true, documentSource)
            }

            // Intento 3: si HTTP 500 (la L3560 a veces rechaza JPEG), probar octet-stream
            if (result == null || result.statusCode == 500) {
                Log.w(TAG, "Fallo HTTP → reintentando con application/octet-stream")
                result = sendPrintJob(correctedUrl, "application/octet-stream", printOptions, minimal = true, documentSource)
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "printDocument error: ${e.message}")
            null
        }
    }

    private fun sendPrintJob(
        printerUrl:     String,
        mimeType:       String,
        options:        PrintOptions,
        minimal:        Boolean,
        documentSource: () -> InputStream?
    ): PrintJobResult? {
        val header   = buildPrintJob(printerUrl, mimeType, options, minimal)
        val document = documentSource()
            ?: return PrintJobResult(false, -1, 0, "", "No se pudo abrir el documento", -1)

        // Body en streaming: cabecera IPP + documento, sin copias intermedias en RAM
        val body = object : RequestBody() {
            override fun contentType(): MediaType = IPP_MEDIA_TYPE
            override fun isOneShot(): Boolean = true   // los reintentos los gestionamos nosotros
            override fun writeTo(sink: BufferedSink) {
                sink.write(header)
                document.use { sink.writeAll(it.source()) }
            }
        }

        return try {
            val req = Request.Builder()
                .url(printerUrl)
                .post(body)
                .addHeader("Accept", "application/ipp")
                .build()
            http.newCall(req).execute().use { resp ->
                Log.d(TAG, "HTTP ${resp.code} al imprimir (minimal=$minimal, mime=$mimeType)")
                when {
                    resp.code == 500 ->
                        PrintJobResult(false, -1, 0, "", "Error interno de la impresora (HTTP 500)", 500)

                    else -> {
                        val bytes = resp.body?.bytes()
                        if (bytes == null || bytes.isEmpty()) {
                            // Respuesta vacía con HTTP 200 = éxito en algunos firmware Epson
                            if (resp.code == 200) {
                                PrintJobResult(success = true, jobId = -1, jobState = 5,
                                    stateReasons = "completed", errorMessage = null, statusCode = 0)
                            } else null
                        } else {
                            parsePrintJobResp(bytes)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendPrintJob: ${e.message}")
            null
        }
    }

    // ── BUILDERS ──────────────────────────────────────────────────────────────

    private fun buildGetPrinterAttrs(url: String): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        writeIppHeader(out, OP_GET_PRINTER_ATTRS)
        out.writeByte(TAG_OPERATION_ATTRIBUTES.toInt())
        ws(out, TAG_CHARSET,          "attributes-charset",          "utf-8")
        ws(out, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en-us")
        ws(out, TAG_URI,              "printer-uri",                 toIpp(url))
        ws(out, TAG_KEYWORD, "requested-attributes", "printer-state")
        we(out, TAG_KEYWORD, "printer-state-reasons")
        we(out, TAG_KEYWORD, "printer-is-accepting-jobs")
        we(out, TAG_KEYWORD, "printer-make-and-model")
        we(out, TAG_KEYWORD, "media-ready")
        we(out, TAG_KEYWORD, "media-default")
        we(out, TAG_KEYWORD, "print-color-mode-supported")
        we(out, TAG_KEYWORD, "sides-supported")
        we(out, TAG_KEYWORD, "document-format-supported")
        we(out, TAG_KEYWORD, "marker-levels")
        we(out, TAG_KEYWORD, "marker-names")
        we(out, TAG_KEYWORD, "marker-high-levels")
        out.writeByte(TAG_END_ATTRIBUTES.toInt())
        return buf.toByteArray()
    }

    private fun buildPrintJob(url: String, mime: String, opts: PrintOptions, minimal: Boolean): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        writeIppHeader(out, OP_PRINT_JOB)
        out.writeByte(TAG_OPERATION_ATTRIBUTES.toInt())
        ws(out, TAG_CHARSET,               "attributes-charset",          "utf-8")
        ws(out, TAG_NATURAL_LANGUAGE,      "attributes-natural-language", "en-us")
        ws(out, TAG_URI,                   "printer-uri",                 toIpp(url))
        ws(out, TAG_NAME_WITHOUT_LANGUAGE, "job-name",                    "EpsonJob-${nextId()}")
        ws(out, TAG_MIME_MEDIA_TYPE,       "document-format",             mime)
        wb(out, "ipp-attribute-fidelity", false)

        out.writeByte(TAG_JOB_ATTRIBUTES.toInt())
        wi(out, "copies", opts.copies.coerceAtLeast(1))

        if (!minimal) {
            ws(out, TAG_KEYWORD, "media", resolveMedia(opts.paperSize))
            resolveColorMode(opts.colorMode)?.let { ws(out, TAG_KEYWORD, "print-color-mode", it) }
            resolveSides(opts.duplex)?.let        { ws(out, TAG_KEYWORD, "sides",            it) }
            wi(out, "print-quality", opts.quality.value)
        }

        out.writeByte(TAG_END_ATTRIBUTES.toInt())
        return buf.toByteArray()
    }

    // ── RESOLVERS ─────────────────────────────────────────────────────────────

    private fun resolveMedia(paperSize: PaperSize): String {
        // Prioridad: el papel que la impresora reporta tener cargado (media-ready)
        detectedMediaReady?.takeIf { it.isNotBlank() }?.let { return it }
        return when (paperSize) {
            PaperSize.A4        -> "iso_a4_210x297mm"
            PaperSize.LETTER    -> "na_letter_8.5x11in"
            PaperSize.LEGAL     -> "na_legal_8.5x14in"
            PaperSize.A5        -> "iso_a5_148x210mm"
            PaperSize.A3        -> "iso_a3_297x420mm"
            PaperSize.PHOTO_4X6 -> "na_index-4x6_4x6in"
        }
    }

    private fun resolveColorMode(mode: ColorMode): String? {
        val supported = detectedColorModes.map { it.lowercase() }
        if (supported.isEmpty()) return when (mode) {
            ColorMode.COLOR      -> "color"
            ColorMode.MONOCHROME -> "monochrome"
            ColorMode.AUTO       -> "auto"
        }
        return when (mode) {
            ColorMode.COLOR      -> listOf("color", "rgb-8", "auto").firstOrNull { it in supported }
            ColorMode.MONOCHROME -> listOf("monochrome", "black", "bi-level").firstOrNull { it in supported }
            ColorMode.AUTO       -> listOf("auto", "color", "monochrome").firstOrNull { it in supported }
        }
    }

    /**
     * Solo envía un modo dúplex si la impresora lo reportó como soportado.
     * La Epson L3560 no tiene dúplex automático: pedir two-sided causaría
     * un conflicto de atributos (0x040B); en su lugar se omite el atributo.
     */
    private fun resolveSides(mode: DuplexMode): String? {
        val supported = detectedSides.map { it.lowercase() }
        fun ifSupported(value: String) = value.takeIf { supported.isEmpty() || it in supported }
        return when (mode) {
            DuplexMode.ONE_SIDED       -> ifSupported("one-sided")
            DuplexMode.TWO_SIDED_LONG  -> ifSupported("two-sided-long-edge")
            DuplexMode.TWO_SIDED_SHORT -> ifSupported("two-sided-short-edge")
        }
    }

    // ── WRITE HELPERS ─────────────────────────────────────────────────────────

    private fun writeIppHeader(out: DataOutputStream, op: Short) {
        out.writeByte(IPP_VERSION_MAJOR.toInt())
        out.writeByte(IPP_VERSION_MINOR.toInt())
        out.writeShort(op.toInt())
        out.writeInt(nextId())
    }

    private fun ws(out: DataOutputStream, tag: Byte, name: String, value: String) {
        val nb = name.toByteArray(Charsets.US_ASCII)
        val vb = value.toByteArray(Charsets.UTF_8)
        out.writeByte(tag.toInt())
        out.writeShort(nb.size)
        out.write(nb)
        out.writeShort(vb.size)
        out.write(vb)
    }

    private fun we(out: DataOutputStream, tag: Byte, value: String) {
        val vb = value.toByteArray(Charsets.UTF_8)
        out.writeByte(tag.toInt())
        out.writeShort(0)
        out.writeShort(vb.size)
        out.write(vb)
    }

    private fun wi(out: DataOutputStream, name: String, value: Int) {
        val nb = name.toByteArray(Charsets.US_ASCII)
        out.writeByte(TAG_INTEGER.toInt())
        out.writeShort(nb.size)
        out.write(nb)
        out.writeShort(4)
        out.writeInt(value)
    }

    private fun wb(out: DataOutputStream, name: String, value: Boolean) {
        val nb = name.toByteArray(Charsets.US_ASCII)
        out.writeByte(TAG_BOOLEAN.toInt())
        out.writeShort(nb.size)
        out.write(nb)
        out.writeShort(1)
        out.writeByte(if (value) 1 else 0)
    }

    private fun toIpp(url: String) =
        url.replaceFirst("http://", "ipp://").replaceFirst("https://", "ipps://")

    // ── PARSERS ───────────────────────────────────────────────────────────────

    private fun parsePrinterAttrs(bytes: ByteArray): PrinterStatus {
        if (bytes.size < 8) return PrinterStatus.offline()
        val sc    = readStatusCode(bytes)
        val attrs = parseAttrs(bytes)
        Log.d(TAG, "IPP status: 0x${sc.toString(16)}")

        val printerState = attrs["printer-state"]?.toIntOrNull() ?: 3
        val stateReasons = attrs["printer-state-reasons"] ?: "none"

        detectedMediaReady = attrs["media-ready"]?.split(",")?.firstOrNull()?.trim()
            ?: attrs["media-default"]?.split(",")?.firstOrNull()?.trim()
        detectedColorModes = attrs["print-color-mode-supported"]?.split(",")?.map { it.trim() } ?: emptyList()
        detectedSides      = attrs["sides-supported"]?.split(",")?.map { it.trim() } ?: emptyList()
        detectedDocFormats = attrs["document-format-supported"]?.split(",")?.map { it.trim() } ?: emptyList()
        supportsColor      = detectedColorModes.any { it.contains("color", ignoreCase = true) }
        supportsDuplex     = detectedSides.any { it.contains("two-sided", ignoreCase = true) }

        return PrinterStatus(
            state               = when (printerState) {
                3    -> PrinterState.IDLE
                4    -> PrinterState.PROCESSING
                5    -> PrinterState.STOPPED
                else -> PrinterState.UNKNOWN
            },
            stateReasons        = stateReasons,
            inkLevels           = parseInkLevels(attrs),
            hasPaper            = !stateReasons.contains("media-empty") && !stateReasons.contains("media-needed"),
            isAcceptingJobs     = attrs["printer-is-accepting-jobs"]?.toBoolean() ?: true,
            rawStatusCode       = sc,
            model               = attrs["printer-make-and-model"],
            supportedColorModes = detectedColorModes,
            supportedSides      = detectedSides,
            supportedDocFormats = detectedDocFormats
        )
    }

    private fun parsePrintJobResp(bytes: ByteArray): PrintJobResult {
        if (bytes.size < 8) return PrintJobResult(false, -1, 0, "", "Respuesta vacía", -1)
        val sc    = readStatusCode(bytes)
        val attrs = parseAttrs(bytes)
        val ok    = sc in IPP_SUCCESS_RANGE
        if (!ok) Log.e(TAG, "IPP error: 0x${sc.toString(16)} — ${interpretError(sc)}")
        return PrintJobResult(
            success      = ok,
            jobId        = attrs["job-id"]?.toIntOrNull() ?: -1,
            jobState     = attrs["job-state"]?.toIntOrNull() ?: 0,
            stateReasons = attrs["job-state-reasons"] ?: "unknown",
            errorMessage = if (!ok) interpretError(sc) else null,
            statusCode   = sc
        )
    }

    private fun readStatusCode(bytes: ByteArray): Int =
        ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)

    private fun parseAttrs(bytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var pos = 8
        var name = ""
        while (pos < bytes.size) {
            val tag = bytes[pos].toInt() and 0xFF
            pos++
            when (tag) {
                0x03 -> return map
                0x01, 0x02, 0x04, 0x05, 0x06 -> continue
                else -> if (tag >= 0x10) {
                    if (pos + 2 > bytes.size) break
                    val nLen = readShort(bytes, pos); pos += 2
                    if (nLen > 0 && pos + nLen <= bytes.size) name = String(bytes, pos, nLen, Charsets.UTF_8)
                    pos += nLen
                    if (pos + 2 > bytes.size) break
                    val vLen = readShort(bytes, pos); pos += 2
                    if (vLen > 0 && pos + vLen <= bytes.size) {
                        val vb = bytes.copyOfRange(pos, pos + vLen)
                        val v  = when (tag) {
                            0x21, 0x23 -> if (vLen == 4) ByteBuffer.wrap(vb).int.toString() else String(vb, Charsets.UTF_8)
                            0x22       -> (vb[0].toInt() != 0).toString()
                            else       -> String(vb, Charsets.UTF_8)
                        }
                        map[name] = if (map.containsKey(name)) "${map[name]},$v" else v
                    }
                    pos += vLen
                }
            }
        }
        return map
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun parseInkLevels(attrs: Map<String, String>): InkLevels {
        val levels = attrs["marker-levels"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: return InkLevels()
        val highs  = attrs["marker-high-levels"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
        fun norm(raw: Int, high: Int?): Int = when {
            raw == -2                -> -2
            raw < 0                  -> -1
            high != null && high > 0 -> (raw * 100) / high
            else                     -> raw
        }
        return when (levels.size) {
            1    -> InkLevels(black = norm(levels[0], highs?.getOrNull(0)))
            2    -> InkLevels(black = norm(levels[0], highs?.getOrNull(0)), cyan = norm(levels[1], highs?.getOrNull(1)))
            4    -> InkLevels(
                cyan    = norm(levels[0], highs?.getOrNull(0)),
                magenta = norm(levels[1], highs?.getOrNull(1)),
                yellow  = norm(levels[2], highs?.getOrNull(2)),
                black   = norm(levels[3], highs?.getOrNull(3))
            )
            else -> InkLevels()
        }
    }

    private fun interpretError(code: Int): String = when (code) {
        0x0400 -> "Petición inválida"
        0x040A -> "Formato no soportado"
        0x040B -> "Atributos en conflicto"
        0x0503 -> "Impresora ocupada"
        0x0507 -> "Sin papel"
        0x0508 -> "Atasco de papel"
        else   -> "Error IPP 0x${code.toString(16).uppercase()}"
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

data class PrinterStatus(
    val state: PrinterState, val stateReasons: String, val inkLevels: InkLevels,
    val hasPaper: Boolean, val isAcceptingJobs: Boolean, val rawStatusCode: Int,
    val model: String? = null,
    val supportedColorModes: List<String> = emptyList(),
    val supportedSides:      List<String> = emptyList(),
    val supportedDocFormats: List<String> = emptyList()
) {
    val displayStatus: String get() = when (state) {
        PrinterState.IDLE       -> if (hasPaper) "Lista para imprimir" else "Sin papel"
        PrinterState.PROCESSING -> "Imprimiendo…"
        PrinterState.STOPPED    -> "Detenida: $stateReasons"
        PrinterState.UNKNOWN    -> "Estado desconocido"
    }
    companion object { fun offline() = PrinterStatus(PrinterState.UNKNOWN, "offline", InkLevels(), false, false, -1) }
}

enum class PrinterState { IDLE, PROCESSING, STOPPED, UNKNOWN }

data class InkLevels(
    val cyan: Int = -1, val magenta: Int = -1, val yellow: Int = -1, val black: Int = -1
) { val isAvailable: Boolean get() = cyan >= 0 || black >= 0 }

data class PrintJobResult(
    val success: Boolean, val jobId: Int, val jobState: Int,
    val stateReasons: String, val errorMessage: String?, val statusCode: Int
)

/**
 * Opciones de impresión. Por defecto A4: es el papel estándar de la
 * Epson L3560 (serie EcoTank). El tamaño real se ajusta automáticamente
 * al papel cargado que reporte la impresora (media-ready).
 */
data class PrintOptions(
    val copies: Int = 1, val colorMode: ColorMode = ColorMode.AUTO,
    val duplex: DuplexMode = DuplexMode.ONE_SIDED, val paperSize: PaperSize = PaperSize.A4,
    val quality: PrintQuality = PrintQuality.NORMAL
)

enum class ColorMode    { COLOR, MONOCHROME, AUTO }
enum class DuplexMode   { ONE_SIDED, TWO_SIDED_LONG, TWO_SIDED_SHORT }
enum class PaperSize    { A4, LETTER, LEGAL, A5, A3, PHOTO_4X6 }
enum class PrintQuality(val value: Int) { DRAFT(3), NORMAL(4), HIGH(5) }
