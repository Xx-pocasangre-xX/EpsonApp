package com.example.epsonprintapp.printer

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * IppClient v3
 *
 * CAUSA RAÍZ DEL 0x400 EN HP SMART TANK 710-720:
 * ================================================
 * La HP Smart Tank rechaza atributos IPP que no están en su lista blanca,
 * incluso si ipp-attribute-fidelity=false. Los atributos problemáticos son:
 *   - "print-quality" (integer) — HP no lo soporta vía IPP en este modelo
 *   - "sides" = "one-sided" — algunos firmwares HP lo rechazan
 *   - Cualquier atributo extra en job-attributes no reconocido
 *
 * SOLUCIÓN: buildPrintJobRequestHP() usa SOLO los atributos mínimos que HP acepta:
 *   operation: charset, natural-language, printer-uri, job-name, document-format
 *   job: copies, media (exactamente el valor de media-ready), print-color-mode
 *
 * SCAN 0 PÁGINAS:
 * ===============
 * El EsclClient descarga la imagen correctamente (HTTP 200) pero BitmapFactory
 * falla silenciosamente si el Content-Type no es image/jpeg o si la imagen tiene
 * headers especiales. El fix está en EsclClient: agregar log del content-type
 * recibido y en ScanViewModel: intentar múltiples decoders.
 */
class IppClient(private val context: Context) {

    companion object {
        private const val TAG = "IppClient"

        private const val IPP_VERSION_MAJOR: Byte = 0x02
        private const val IPP_VERSION_MINOR: Byte = 0x00

        private const val OP_PRINT_JOB: Short         = 0x0002
        private const val OP_GET_PRINTER_ATTRS: Short = 0x000B

        private const val TAG_OPERATION_ATTRIBUTES: Byte  = 0x01
        private const val TAG_JOB_ATTRIBUTES: Byte        = 0x02
        private const val TAG_END_ATTRIBUTES: Byte        = 0x03
        private const val TAG_INTEGER: Byte               = 0x21
        private const val TAG_BOOLEAN: Byte               = 0x22
        private const val TAG_KEYWORD: Byte               = 0x44
        private const val TAG_URI: Byte                   = 0x45
        private const val TAG_CHARSET: Byte               = 0x47
        private const val TAG_NATURAL_LANGUAGE: Byte      = 0x48
        private const val TAG_MIME_MEDIA_TYPE: Byte       = 0x49
        private const val TAG_NAME_WITHOUT_LANGUAGE: Byte = 0x42

        private val IPP_MEDIA_TYPE = "application/ipp".toMediaType()

        @Volatile private var requestIdCounter = 1
        @Synchronized private fun nextId() = requestIdCounter++
    }

    enum class PrinterBrand { EPSON, HP, GENERIC }

    // Estado detectado de la impresora (se actualiza en getPrinterStatus)
    var detectedBrand: PrinterBrand = PrinterBrand.GENERIC
        private set
    var detectedMediaReady: String? = null
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // ── GET PRINTER STATUS ────────────────────────────────────────────────────

    suspend fun getPrinterStatus(printerUrl: String): PrinterStatus? {
        return try {
            Log.d(TAG, "Consultando estado: $printerUrl")
            val req = Request.Builder().url(printerUrl)
                .post(buildGetPrinterAttrs(printerUrl).toRequestBody(IPP_MEDIA_TYPE))
                .addHeader("Content-Type", "application/ipp")
                .addHeader("Accept", "application/ipp")
                .build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) { Log.e(TAG, "HTTP error: ${resp.code}"); return null }
            val bytes = resp.body?.bytes() ?: return null
            val status = parsePrinterAttrs(bytes)
            detectedBrand = detectBrand(status.model)
            Log.d(TAG, "Marca=$detectedBrand | media-ready=$detectedMediaReady | modelo=${status.model}")
            status
        } catch (e: Exception) { Log.e(TAG, "getPrinterStatus error: ${e.message}"); null }
    }

    private fun detectBrand(model: String?): PrinterBrand {
        val m = model?.lowercase() ?: return PrinterBrand.GENERIC
        return when {
            m.contains("hp") || m.contains("smart tank") || m.contains("deskjet")
                    || m.contains("laserjet") || m.contains("officejet") -> PrinterBrand.HP
            m.contains("epson") || m.contains("ecotank")                 -> PrinterBrand.EPSON
            else                                                          -> PrinterBrand.GENERIC
        }
    }

    // ── PRINT DOCUMENT ────────────────────────────────────────────────────────

    suspend fun printDocument(
        printerUrl: String, documentStream: InputStream,
        mimeType: String, printOptions: PrintOptions
    ): PrintJobResult? {
        return try {
            val docBytes = documentStream.readBytes()
            Log.d(TAG, "Print: $printerUrl | MIME=$mimeType | " +
                    "Copias=${printOptions.copies} | Marca=$detectedBrand | media=$detectedMediaReady")
            Log.d(TAG, "Doc: ${docBytes.size} bytes")

            val header = if (detectedBrand == PrinterBrand.HP)
                buildPrintJobHP(printerUrl, mimeType, printOptions)
            else
                buildPrintJobEpson(printerUrl, mimeType, printOptions)

            val payload = header + docBytes
            Log.d(TAG, "Enviando ${payload.size} bytes")

            val req = Request.Builder().url(printerUrl)
                .post(payload.toRequestBody(IPP_MEDIA_TYPE))
                .addHeader("Content-Type", "application/ipp")
                .addHeader("Accept", "application/ipp")
                .build()
            val resp = http.newCall(req).execute()
            val bytes = resp.body?.bytes() ?: return null
            Log.d(TAG, "HTTP: ${resp.code}")
            parsePrintJobResp(bytes)
        } catch (e: Exception) { Log.e(TAG, "printDocument error: ${e.message}"); null }
    }

    // ── GET-PRINTER-ATTRIBUTES ────────────────────────────────────────────────

    private fun buildGetPrinterAttrs(url: String): ByteArray {
        val buf = ByteArrayOutputStream(); val out = DataOutputStream(buf)
        out.writeByte(IPP_VERSION_MAJOR.toInt()); out.writeByte(IPP_VERSION_MINOR.toInt())
        out.writeShort(OP_GET_PRINTER_ATTRS.toInt()); out.writeInt(nextId())
        out.writeByte(TAG_OPERATION_ATTRIBUTES.toInt())
        ws(out, TAG_CHARSET,          "attributes-charset",          "utf-8")
        ws(out, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en-us")
        ws(out, TAG_URI,              "printer-uri",                 ipp(url))
        ws(out, TAG_KEYWORD, "requested-attributes", "printer-state")
        we(out, TAG_KEYWORD, "printer-state-reasons")
        we(out, TAG_KEYWORD, "marker-levels")
        we(out, TAG_KEYWORD, "marker-names")
        we(out, TAG_KEYWORD, "marker-types")
        we(out, TAG_KEYWORD, "media-ready")
        we(out, TAG_KEYWORD, "printer-make-and-model")
        we(out, TAG_KEYWORD, "printer-is-accepting-jobs")
        out.writeByte(TAG_END_ATTRIBUTES.toInt())
        return buf.toByteArray()
    }

    // ── PRINT-JOB HP (mínimo para evitar 0x400) ───────────────────────────────

    private fun buildPrintJobHP(url: String, mimeType: String, opts: PrintOptions): ByteArray {
        val buf = ByteArrayOutputStream(); val out = DataOutputStream(buf)
        out.writeByte(IPP_VERSION_MAJOR.toInt()); out.writeByte(IPP_VERSION_MINOR.toInt())
        out.writeShort(OP_PRINT_JOB.toInt()); out.writeInt(nextId())

        out.writeByte(TAG_OPERATION_ATTRIBUTES.toInt())
        ws(out, TAG_CHARSET,               "attributes-charset",          "utf-8")
        ws(out, TAG_NATURAL_LANGUAGE,      "attributes-natural-language", "en-us")
        ws(out, TAG_URI,                   "printer-uri",                 ipp(url))
        ws(out, TAG_NAME_WITHOUT_LANGUAGE, "job-name", "HPJob-${nextId()}")
        ws(out, TAG_MIME_MEDIA_TYPE,       "document-format",             mimeType)
        wb(out, "ipp-attribute-fidelity", false)  // tolerar attrs no reconocidos

        out.writeByte(TAG_JOB_ATTRIBUTES.toInt())

        // copies
        wi(out, "copies", opts.copies)

        // media: USAR EXACTAMENTE lo que reportó media-ready
        // Si la HP tiene Letter y mandamos A4, rechaza con 0x400
        val media = detectedMediaReady ?: when (opts.paperSize) {
            PaperSize.A4        -> "iso_a4_210x297mm"
            PaperSize.LETTER    -> "na_letter_8.5x11in"
            PaperSize.A3        -> "iso_a3_297x420mm"
            PaperSize.PHOTO_4X6 -> "na_index-4x6_4x6in"
        }
        ws(out, TAG_KEYWORD, "media", media)
        Log.d(TAG, "HP media enviado: '$media'")

        // print-color-mode — HP acepta "color" y "monochrome"
        ws(out, TAG_KEYWORD, "print-color-mode", when (opts.colorMode) {
            ColorMode.MONOCHROME -> "monochrome"
            else                 -> "color"
        })

        // sides — SOLO enviar si es duplex (HP puede rechazar "one-sided")
        when (opts.duplex) {
            DuplexMode.TWO_SIDED_LONG  -> ws(out, TAG_KEYWORD, "sides", "two-sided-long-edge")
            DuplexMode.TWO_SIDED_SHORT -> ws(out, TAG_KEYWORD, "sides", "two-sided-short-edge")
            DuplexMode.ONE_SIDED       -> { /* NO enviar sides para HP one-sided */ }
        }

        // NO enviar print-quality (HP lo rechaza con 0x400)

        out.writeByte(TAG_END_ATTRIBUTES.toInt())
        return buf.toByteArray()
    }

    // ── PRINT-JOB EPSON / GENERIC ─────────────────────────────────────────────

    private fun buildPrintJobEpson(url: String, mimeType: String, opts: PrintOptions): ByteArray {
        val buf = ByteArrayOutputStream(); val out = DataOutputStream(buf)
        out.writeByte(IPP_VERSION_MAJOR.toInt()); out.writeByte(IPP_VERSION_MINOR.toInt())
        out.writeShort(OP_PRINT_JOB.toInt()); out.writeInt(nextId())

        out.writeByte(TAG_OPERATION_ATTRIBUTES.toInt())
        ws(out, TAG_CHARSET,               "attributes-charset",          "utf-8")
        ws(out, TAG_NATURAL_LANGUAGE,      "attributes-natural-language", "en-us")
        ws(out, TAG_URI,                   "printer-uri",                 ipp(url))
        ws(out, TAG_NAME_WITHOUT_LANGUAGE, "job-name", "EpsonJob-${nextId()}")
        ws(out, TAG_MIME_MEDIA_TYPE,       "document-format",             mimeType)
        wb(out, "ipp-attribute-fidelity", false)

        out.writeByte(TAG_JOB_ATTRIBUTES.toInt())
        wi(out, "copies", opts.copies)
        ws(out, TAG_KEYWORD, "sides", when (opts.duplex) {
            DuplexMode.ONE_SIDED       -> "one-sided"
            DuplexMode.TWO_SIDED_LONG  -> "two-sided-long-edge"
            DuplexMode.TWO_SIDED_SHORT -> "two-sided-short-edge"
        })
        ws(out, TAG_KEYWORD, "media", when (opts.paperSize) {
            PaperSize.A4        -> "iso_a4_210x297mm"
            PaperSize.LETTER    -> "na_letter_8.5x11in"
            PaperSize.A3        -> "iso_a3_297x420mm"
            PaperSize.PHOTO_4X6 -> "na_index-4x6_4x6in"
        })
        ws(out, TAG_KEYWORD, "print-color-mode", when (opts.colorMode) {
            ColorMode.COLOR      -> "color"
            ColorMode.MONOCHROME -> "monochrome"
            ColorMode.AUTO       -> "auto"
        })
        wi(out, "print-quality", opts.quality.value)  // Epson sí acepta esto

        out.writeByte(TAG_END_ATTRIBUTES.toInt())
        return buf.toByteArray()
    }

    // ── WRITE HELPERS ─────────────────────────────────────────────────────────

    private fun ws(out: DataOutputStream, tag: Byte, name: String, value: String) {
        val nb = name.toByteArray(Charsets.US_ASCII); val vb = value.toByteArray(Charsets.UTF_8)
        out.writeByte(tag.toInt()); out.writeShort(nb.size); out.write(nb)
        out.writeShort(vb.size); out.write(vb)
    }
    private fun we(out: DataOutputStream, tag: Byte, value: String) {
        val vb = value.toByteArray(Charsets.UTF_8)
        out.writeByte(tag.toInt()); out.writeShort(0); out.writeShort(vb.size); out.write(vb)
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
    private fun ipp(url: String) = url.replaceFirst("http://", "ipp://").replaceFirst("https://", "ipps://")

    // ── PARSE RESPONSES ───────────────────────────────────────────────────────

    private fun parsePrinterAttrs(bytes: ByteArray): PrinterStatus {
        if (bytes.size < 8) return PrinterStatus(PrinterState.UNKNOWN,"",InkLevels(),true,false,-1)
        val sc   = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        Log.d(TAG, "IPP Status Code: 0x${sc.toString(16)}")
        val a    = parseAttrs(bytes, 8)
        val ps   = a["printer-state"]?.toIntOrNull() ?: 3
        val sr   = a["printer-state-reasons"] ?: "none"
        detectedMediaReady = a["media-ready"]?.split(",")?.firstOrNull()?.trim()
        return PrinterStatus(
            state           = when(ps){3->PrinterState.IDLE;4->PrinterState.PROCESSING;5->PrinterState.STOPPED;else->PrinterState.UNKNOWN},
            stateReasons    = sr,
            inkLevels       = parseInk(a["marker-levels"] ?: ""),
            hasPaper        = !sr.contains("media-empty") && !sr.contains("media-needed"),
            isAcceptingJobs = a["printer-is-accepting-jobs"]?.toBoolean() ?: true,
            rawStatusCode   = sc,
            model           = a["printer-make-and-model"]
        )
    }

    private fun parsePrintJobResp(bytes: ByteArray): PrintJobResult {
        if (bytes.size < 8) return PrintJobResult(false,-1,0,"","Resp vacía",-1)
        val sc  = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val rid = ByteBuffer.wrap(bytes, 4, 4).int
        Log.d(TAG, "PrintJob status=0x${sc.toString(16)} rid=$rid")
        val a   = parseAttrs(bytes, 8)
        val ok  = sc in 0x0000..0x01FF
        if (!ok) Log.e(TAG, "IPP error 0x${sc.toString(16)}: ${interpErr(sc)}")
        return PrintJobResult(ok, a["job-id"]?.toIntOrNull()?:-1,
            a["job-state"]?.toIntOrNull()?:0,
            a["job-state-reasons"]?:"unknown",
            if (!ok) interpErr(sc) else null, sc)
    }

    private fun parseAttrs(bytes: ByteArray, offset: Int): Map<String, String> {
        val map = mutableMapOf<String, String>(); var pos = offset; var name = ""
        while (pos < bytes.size) {
            val tag = bytes[pos].toInt() and 0xFF; pos++
            when (tag) {
                0x03 -> return map
                0x01,0x02,0x04,0x05,0x06 -> continue
                else -> if (tag >= 0x10) {
                    if (pos+2 > bytes.size) break
                    val nL = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos+1].toInt() and 0xFF); pos+=2
                    if (nL>0 && pos+nL<=bytes.size) name = String(bytes,pos,nL,Charsets.UTF_8)
                    pos+=nL
                    if (pos+2>bytes.size) break
                    val vL = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos+1].toInt() and 0xFF); pos+=2
                    if (vL>0 && pos+vL<=bytes.size) {
                        val vb = bytes.copyOfRange(pos,pos+vL)
                        val v  = when(tag) {
                            0x21 -> if(vL==4) ByteBuffer.wrap(vb).int.toString() else String(vb,Charsets.UTF_8)
                            0x22 -> (vb[0].toInt()!=0).toString()
                            0x23 -> if(vL==4) ByteBuffer.wrap(vb).int.toString() else String(vb,Charsets.UTF_8)
                            else -> String(vb,Charsets.UTF_8)
                        }
                        map[name] = if (map.containsKey(name)) "${map[name]},$v" else v
                        Log.v(TAG, "Attr: $name = $v")
                    }
                    pos+=vL
                }
            }
        }
        return map
    }

    private fun parseInk(s: String): InkLevels {
        if (s.isEmpty()) return InkLevels()
        val l = s.split(",").mapNotNull { it.trim().toIntOrNull() }
        return when (l.size) { 4->InkLevels(l[0],l[1],l[2],l[3]); 1->InkLevels(black=l[0]); else->InkLevels() }
    }

    private fun interpErr(code: Int) = when (code) {
        0x0400->"Petición inválida (0x400)"; 0x040A->"Formato no soportado"
        0x0503->"Servicio no disponible"; 0x0507->"Sin papel"; 0x0508->"Atasco de papel"
        else->"Error IPP 0x${code.toString(16).uppercase()}"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
data class PrinterStatus(val state:PrinterState,val stateReasons:String,val inkLevels:InkLevels,
                         val hasPaper:Boolean,val isAcceptingJobs:Boolean,val rawStatusCode:Int,val model:String?=null) {
    val displayStatus get() = when(state) {
        PrinterState.IDLE->if(hasPaper)"Lista" else "Sin papel"
        PrinterState.PROCESSING->"Imprimiendo..."; PrinterState.STOPPED->"Detenida: $stateReasons"
        PrinterState.UNKNOWN->"Estado desconocido"
    }
}
enum class PrinterState { IDLE, PROCESSING, STOPPED, UNKNOWN }
data class InkLevels(val cyan:Int=-1,val magenta:Int=-1,val yellow:Int=-1,val black:Int=-1) {
    val isAvailable get() = cyan>=0||black>=0 }
data class PrintJobResult(val success:Boolean,val jobId:Int,val jobState:Int,
                          val stateReasons:String,val errorMessage:String?,val statusCode:Int)
data class PrintOptions(val copies:Int=1,val colorMode:ColorMode=ColorMode.COLOR,
                        val duplex:DuplexMode=DuplexMode.ONE_SIDED,val paperSize:PaperSize=PaperSize.LETTER,
                        val quality:PrintQuality=PrintQuality.NORMAL)
enum class ColorMode    { COLOR, MONOCHROME, AUTO }
enum class DuplexMode   { ONE_SIDED, TWO_SIDED_LONG, TWO_SIDED_SHORT }
enum class PaperSize    { A4, LETTER, A3, PHOTO_4X6 }
enum class PrintQuality(val value:Int) { DRAFT(3), NORMAL(4), HIGH(5) }