package com.example.epsonprintapp.printer

import android.content.Context
import android.util.Log
import com.example.epsonprintapp.AppConstants
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * IppClient — Cliente IPP 2.0 universal y thread-safe.
 *
 * Compatible con cualquier impresora IPP:
 * Epson, HP, Canon, Brother, Kyocera, Xerox, Ricoh, Samsung, Lexmark…
 *
 * Mejoras respecto a la versión anterior:
 * - requestIdCounter: AtomicInteger (antes ++ no era atómico → race condition)
 * - detectedMediaReady / detectedColorModes: @Volatile (thread-safe)
 * - Consulta capacidades del printer antes de imprimir (Get-Printer-Attributes)
 * - resolveMedia / resolveColorMode / resolveSides adaptan atributos por impresora
 * - Retry automático en modo mínimo si la impresora rechaza con 0x400
 * - Nuevos tamaños de papel: A5, Legal
 */
class IppClient(private val context: Context) {

    companion object {
        private const val TAG = "IppClient"

        private const val IPP_VERSION_MAJOR: Byte      = 0x02
        private const val IPP_VERSION_MINOR: Byte      = 0x00

        private const val OP_PRINT_JOB:          Short = 0x0002
        private const val OP_GET_PRINTER_ATTRS:   Short = 0x000B

        private const val TAG_OPERATION_ATTRIBUTES:  Byte = 0x01
        private const val TAG_JOB_ATTRIBUTES:        Byte = 0x02
        private const val TAG_END_ATTRIBUTES:        Byte = 0x03
        private const val TAG_INTEGER:               Byte = 0x21
        private const val TAG_BOOLEAN:               Byte = 0x22
        private const val TAG_KEYWORD:               Byte = 0x44
        private const val TAG_URI:                   Byte = 0x45
        private const val TAG_CHARSET:               Byte = 0x47
        private const val TAG_NATURAL_LANGUAGE:      Byte = 0x48
        private const val TAG_MIME_MEDIA_TYPE:       Byte = 0x49
        private const val TAG_NAME_WITHOUT_LANGUAGE: Byte = 0x42

        private val IPP_MEDIA_TYPE        = "application/ipp".toMediaType()
        private val IPP_SUCCESS_RANGE     = 0x0000..0x00FF

        // Thread-safe: AtomicInteger en lugar de @Volatile var con ++
        private val requestIdCounter = AtomicInteger(1)
        private fun nextId()         = requestIdCounter.getAndIncrement()
    }

    // Capacidades detectadas — @Volatile para visibilidad entre threads
    @Volatile var detectedMediaReady:   String?       = null;   private set
    @Volatile var detectedColorModes:   List<String>  = emptyList(); private set
    @Volatile var detectedSides:        List<String>  = emptyList(); private set
    @Volatile var detectedDocFormats:   List<String>  = emptyList(); private set
    @Volatile var supportsColor:        Boolean       = true;   private set
    @Volatile var supportsDuplex:       Boolean       = false;  private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(AppConstants.HTTP_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(AppConstants.HTTP_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(AppConstants.HTTP_WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ── GET PRINTER STATUS ────────────────────────────────────────────────────

    suspend fun getPrinterStatus(printerUrl: String): PrinterStatus? {
        return try {
            Log.d(TAG, "Consultando estado: $printerUrl")
            val req = Request.Builder()
                .url(printerUrl)
                .post(buildGetPrinterAttrs(printerUrl).toRequestBody(IPP_MEDIA_TYPE))
                .addHeader("Content-Type", "application/ipp")
                .addHeader("Accept",       "application/ipp")
                .build()
            val resp  = http.newCall(req).execute()
            val bytes = resp.body?.bytes() ?: return null
            parsePrinterAttrs(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "getPrinterStatus error: ${e.message}")
            null
        }
    }

    // ── PRINT DOCUMENT ────────────────────────────────────────────────────────

    /**
     * Envía un documento a imprimir.
     * Si falla con 0x0400 (atributos no reconocidos), reintenta en modo mínimo.
     */
    suspend fun printDocument(
        printerUrl:     String,
        documentStream: InputStream,
        mimeType:       String,
        printOptions:   PrintOptions
    ): PrintJobResult? {
        return try {
            val docBytes = documentStream.readBytes()
            Log.d(TAG, "Imprimiendo ${docBytes.size} bytes | $mimeType")

            var result = sendPrintJob(printerUrl, docBytes, mimeType, printOptions, minimal = false)

            // Retry con atributos mínimos si la impresora rechaza por atributo desconocido
            if (result?.statusCode == 0x0400 || result?.statusCode == 0x040B) {
                Log.w(TAG, "0x${result?.statusCode?.toString(16)} — reintentando modo mínimo")
                result = sendPrintJob(printerUrl, docBytes, mimeType, printOptions, minimal = true)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "printDocument error: ${e.message}")
            null
        }
    }

    private suspend fun sendPrintJob(
        printerUrl: String,
        docBytes:   ByteArray,
        mimeType:   String,
        options:    PrintOptions,
        minimal:    Boolean
    ): PrintJobResult? {
        val payload = buildPrintJob(printerUrl, mimeType, options, minimal) + docBytes
        val req = Request.Builder()
            .url(printerUrl)
            .post(payload.toRequestBody(IPP_MEDIA_TYPE))
            .addHeader("Content-Type", "application/ipp")
            .addHeader("Accept",       "application/ipp")
            .build()
        val resp  = http.newCall(req).execute()
        val bytes = resp.body?.bytes() ?: return null
        Log.d(TAG, "HTTP ${resp.code} al imprimir (minimal=$minimal)")
        return parsePrintJobResp(bytes)
    }

    // ── BUILDERS ──────────────────────────────────────────────────────────────

    private fun buildGetPrinterAttrs(url: String): ByteArray {
        val buf = ByteArrayOutputStream(); val out = DataOutputStream(buf)
        writeIppHeader(out, OP_GET_PRINTER_ATTRS)
        out.writeByte(TAG_OPERATION_ATTRIBUTES.toInt())
        ws(out, TAG_CHARSET,          "attributes-charset",          "utf-8")
        ws(out, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en-us")
        ws(out, TAG_URI,              "printer-uri",                 toIpp(url))
        // Pedir todas las capacidades relevantes
        ws(out, TAG_KEYWORD, "requested-attributes", "printer-state")
        we(out, TAG_KEYWORD, "printer-state-reasons")
        we(out, TAG_KEYWORD, "printer-is-accepting-jobs")
        we(out, TAG_KEYWORD, "printer-make-and-model")
        we(out, TAG_KEYWORD, "media-ready")
        we(out, TAG_KEYWORD, "media-default")
        we(out, TAG_KEYWORD, "media-supported")
        we(out, TAG_KEYWORD, "print-color-mode-supported")
        we(out, TAG_KEYWORD, "print-color-mode-default")
        we(out, TAG_KEYWORD, "sides-supported")
        we(out, TAG_KEYWORD, "sides-default")
        we(out, TAG_KEYWORD, "document-format-supported")
        we(out, TAG_KEYWORD, "copies-supported")
        we(out, TAG_KEYWORD, "marker-levels")
        we(out, TAG_KEYWORD, "marker-names")
        we(out, TAG_KEYWORD, "marker-types")
        we(out, TAG_KEYWORD, "marker-high-levels")
        we(out, TAG_KEYWORD, "marker-low-levels")
        out.writeByte(TAG_END_ATTRIBUTES.toInt())
        return buf.toByteArray()
    }

    private fun buildPrintJob(
        url:     String,
        mime:    String,
        opts:    PrintOptions,
        minimal: Boolean
    ): ByteArray {
        val buf = ByteArrayOutputStream(); val out = DataOutputStream(buf)
        writeIppHeader(out, OP_PRINT_JOB)
        out.writeByte(TAG_OPERATION_ATTRIBUTES.toInt())
        ws(out, TAG_CHARSET,               "attributes-charset",          "utf-8")
        ws(out, TAG_NATURAL_LANGUAGE,      "attributes-natural-language", "en-us")
        ws(out, TAG_URI,                   "printer-uri",                 toIpp(url))
        ws(out, TAG_NAME_WITHOUT_LANGUAGE, "job-name",                    "Job-${nextId()}")
        ws(out, TAG_MIME_MEDIA_TYPE,       "document-format",             mime)
        wb(out, "ipp-attribute-fidelity", false)  // tolerar attrs no reconocidos

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

    // ── RESOLVERS ADAPTATIVOS ─────────────────────────────────────────────────

    private fun resolveMedia(paperSize: PaperSize): String {
        detectedMediaReady?.takeIf { it.isNotBlank() }?.let { return it }
        return when (paperSize) {
            PaperSize.A4        -> "iso_a4_210x297mm"
            PaperSize.LETTER    -> "na_letter_8.5x11in"
            PaperSize.A3        -> "iso_a3_297x420mm"
            PaperSize.A5        -> "iso_a5_148x210mm"
            PaperSize.LEGAL     -> "na_legal_8.5x14in"
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
            ColorMode.COLOR -> listOf("color", "rgb-8", "auto").firstOrNull { it in supported }
            ColorMode.MONOCHROME -> listOf("monochrome", "black", "bi-level").firstOrNull { it in supported }
            ColorMode.AUTO -> listOf("auto", "color", "monochrome").firstOrNull { it in supported }
        }
    }

    private fun resolveSides(mode: DuplexMode): String? {
        val supported = detectedSides.map { it.lowercase() }
        return when (mode) {
            DuplexMode.ONE_SIDED       -> if ("one-sided" in supported || supported.isEmpty()) "one-sided" else null
            DuplexMode.TWO_SIDED_LONG  -> "two-sided-long-edge"
            DuplexMode.TWO_SIDED_SHORT -> "two-sided-short-edge"
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
        out.writeByte(tag.toInt()); out.writeShort(nb.size); out.write(nb)
        out.writeShort(vb.size);   out.write(vb)
    }

    private fun we(out: DataOutputStream, tag: Byte, value: String) {
        val vb = value.toByteArray(Charsets.UTF_8)
        out.writeByte(tag.toInt()); out.writeShort(0)
        out.writeShort(vb.size);   out.write(vb)
    }

    private fun wi(out: DataOutputStream, name: String, value: Int) {
        val nb = name.toByteArray(Charsets.US_ASCII)
        out.writeByte(TAG_INTEGER.toInt()); out.writeShort(nb.size); out.write(nb)
        out.writeShort(4); out.writeInt(value)
    }

    private fun wb(out: DataOutputStream, name: String, value: Boolean) {
        val nb = name.toByteArray(Charsets.US_ASCII)
        out.writeByte(TAG_BOOLEAN.toInt()); out.writeShort(nb.size); out.write(nb)
        out.writeShort(1); out.writeByte(if (value) 1 else 0)
    }

    private fun toIpp(url: String) = url
        .replaceFirst("http://",  "ipp://")
        .replaceFirst("https://", "ipps://")

    // ── PARSERS ───────────────────────────────────────────────────────────────

    private fun parsePrinterAttrs(bytes: ByteArray): PrinterStatus {
        if (bytes.size < 8) return PrinterStatus.offline()
        val sc    = readStatusCode(bytes)
        val attrs = parseAttrs(bytes)
        Log.d(TAG, "IPP status: 0x${sc.toString(16)}")

        val printerState = attrs["printer-state"]?.toIntOrNull() ?: 3
        val stateReasons = attrs["printer-state-reasons"] ?: "none"

        // Guardar capacidades detectadas (@Volatile — thread-safe para lectura)
        detectedMediaReady  = attrs["media-ready"]?.split(",")?.firstOrNull()?.trim()
            ?: attrs["media-default"]?.split(",")?.firstOrNull()?.trim()
        detectedColorModes  = attrs["print-color-mode-supported"]?.split(",")
            ?.map { it.trim() } ?: emptyList()
        detectedSides       = attrs["sides-supported"]?.split(",")
            ?.map { it.trim() } ?: emptyList()
        detectedDocFormats  = attrs["document-format-supported"]?.split(",")
            ?.map { it.trim() } ?: emptyList()
        supportsColor       = detectedColorModes.any { it.contains("color", ignoreCase = true) }
        supportsDuplex      = detectedSides.any { it.contains("two-sided", ignoreCase = true) }

        return PrinterStatus(
            state               = when (printerState) { 3 -> PrinterState.IDLE; 4 -> PrinterState.PROCESSING; 5 -> PrinterState.STOPPED; else -> PrinterState.UNKNOWN },
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

    private fun parseAttrs(bytes: ByteArray, startOffset: Int = 8): Map<String, String> {
        val map  = mutableMapOf<String, String>()
        var pos  = startOffset
        var name = ""
        while (pos < bytes.size) {
            val tag = bytes[pos].toInt() and 0xFF; pos++
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
                        val vb  = bytes.copyOfRange(pos, pos + vLen)
                        val v   = when (tag) {
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
            raw == -2                        -> -2  // EcoTank ilimitado
            raw < 0                          -> -1  // desconocido
            high != null && high > 0         -> (raw * 100) / high
            else                             -> raw
        }
        return when (levels.size) {
            1    -> InkLevels(black = norm(levels[0], highs?.getOrNull(0)))
            2    -> InkLevels(black = norm(levels[0], highs?.getOrNull(0)), cyan = norm(levels[1], highs?.getOrNull(1)))
            4    -> InkLevels(cyan = norm(levels[0], highs?.getOrNull(0)), magenta = norm(levels[1], highs?.getOrNull(1)), yellow = norm(levels[2], highs?.getOrNull(2)), black = norm(levels[3], highs?.getOrNull(3)))
            else -> InkLevels()
        }
    }

    private fun interpretError(code: Int): String = when (code) {
        0x0400 -> "Petición inválida (atributos no reconocidos)"
        0x0401 -> "URI no accesible"
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
    val state:               PrinterState,
    val stateReasons:        String,
    val inkLevels:           InkLevels,
    val hasPaper:            Boolean,
    val isAcceptingJobs:     Boolean,
    val rawStatusCode:       Int,
    val model:               String?      = null,
    val supportedColorModes: List<String> = emptyList(),
    val supportedSides:      List<String> = emptyList(),
    val supportedDocFormats: List<String> = emptyList()
) {
    val displayStatus: String get() = when (state) {
        PrinterState.IDLE       -> if (hasPaper) "Lista para imprimir" else "Sin papel"
        PrinterState.PROCESSING -> "Imprimiendo..."
        PrinterState.STOPPED    -> "Detenida: $stateReasons"
        PrinterState.UNKNOWN    -> "Estado desconocido"
    }
    companion object {
        fun offline() = PrinterStatus(PrinterState.UNKNOWN, "offline", InkLevels(), false, false, -1)
    }
}

enum class PrinterState { IDLE, PROCESSING, STOPPED, UNKNOWN }

data class InkLevels(
    val cyan: Int = -1, val magenta: Int = -1,
    val yellow: Int = -1, val black: Int = -1
) {
    val isAvailable: Boolean get() = cyan >= 0 || black >= 0
}

data class PrintJobResult(
    val success: Boolean, val jobId: Int, val jobState: Int,
    val stateReasons: String, val errorMessage: String?, val statusCode: Int
)

data class PrintOptions(
    val copies:    Int          = 1,
    val colorMode: ColorMode    = ColorMode.AUTO,
    val duplex:    DuplexMode   = DuplexMode.ONE_SIDED,
    val paperSize: PaperSize    = PaperSize.LETTER,
    val quality:   PrintQuality = PrintQuality.NORMAL
)

enum class ColorMode    { COLOR, MONOCHROME, AUTO }
enum class DuplexMode   { ONE_SIDED, TWO_SIDED_LONG, TWO_SIDED_SHORT }
enum class PaperSize    { A4, LETTER, A3, A5, LEGAL, PHOTO_4X6 }
enum class PrintQuality(val value: Int) { DRAFT(3), NORMAL(4), HIGH(5) }