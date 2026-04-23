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
 * IppClient - Cliente del protocolo IPP (Internet Printing Protocol)
 *
 * ¿QUÉ ES IPP?
 * ============
 * IPP (Internet Printing Protocol) es el estándar moderno para impresión en red.
 * Usa HTTP como transporte, con un formato binario especial para el cuerpo.
 *
 * URL para Epson EcoTank L3560: http://<IP>:631/ipp/print
 *
 * FORMATO DE UN MENSAJE IPP:
 * ==========================
 * Todo mensaje IPP tiene esta estructura binaria:
 *
 * ┌─────────────────────────────────────────┐
 * │ Version (2 bytes)    → 0x0200 = IPP/2.0 │
 * │ Operation/Status (2 bytes)               │
 * │ Request-ID (4 bytes) → ID único          │
 * │ Attribute Groups...                      │
 * │   Tag (1 byte)                           │
 * │   Name Length (2 bytes)                  │
 * │   Name (N bytes)                         │
 * │   Value Length (2 bytes)                 │
 * │   Value (M bytes)                        │
 * │ End-of-Attributes (1 byte) → 0x03        │
 * │ [Datos del documento]                    │
 * └─────────────────────────────────────────┘
 *
 * OPERACIONES IPP PRINCIPALES:
 * ============================
 * 0x0002 → Print-Job         (enviar trabajo de impresión)
 * 0x000B → Get-Printer-Attrs (obtener estado de impresora)
 * 0x0004 → Validate-Job      (validar sin imprimir)
 * 0x0006 → Cancel-Job        (cancelar impresión)
 * 0x0009 → Get-Job-Attrs     (obtener estado del trabajo)
 *
 * EJEMPLO REAL de petición Get-Printer-Attributes:
 * POST /ipp/print HTTP/1.1
 * Host: 192.168.1.10:631
 * Content-Type: application/ipp
 * Content-Length: 94
 *
 * [Bytes IPP]
 * 0200 000B 00000001           <- Version, Get-Printer-Attrs, Request-ID=1
 * 01                           <- begin-operation-attributes
 * 47 0012 attributes-charset 0005 utf-8
 * 48 0011 attributes-natural-language 0005 en-us
 * 45 000B printer-uri 001C ipp://192.168.1.10/ipp/print
 * 03                           <- end-of-attributes
 */
class IppClient(private val context: Context) {

    companion object {
        private const val TAG = "IppClient"

        // Versión IPP 2.0
        private const val IPP_VERSION_MAJOR: Byte = 0x02
        private const val IPP_VERSION_MINOR: Byte = 0x00

        // ===== OPERATION IDs =====
        private const val OP_PRINT_JOB: Short = 0x0002
        private const val OP_VALIDATE_JOB: Short = 0x0004
        private const val OP_GET_JOB_ATTRS: Short = 0x0009
        private const val OP_CANCEL_JOB: Short = 0x0006
        private const val OP_GET_PRINTER_ATTRS: Short = 0x000B

        // ===== ATTRIBUTE TAGS =====
        // Estos bytes identifican el tipo de cada atributo en el mensaje IPP
        private const val TAG_OPERATION_ATTRIBUTES: Byte = 0x01  // Inicio de grupo operation
        private const val TAG_JOB_ATTRIBUTES: Byte = 0x02        // Inicio de grupo job
        private const val TAG_END_ATTRIBUTES: Byte = 0x03        // Fin de todos los atributos
        private const val TAG_PRINTER_ATTRIBUTES: Byte = 0x04    // Inicio de grupo printer

        // Value Tags - tipo de valor del atributo
        private const val TAG_INTEGER: Byte = 0x21               // Entero de 4 bytes
        private const val TAG_BOOLEAN: Byte = 0x22               // Boolean (0/1)
        private const val TAG_ENUM: Byte = 0x23                  // Enumeración (4 bytes)
        private const val TAG_OCTET_STRING: Byte = 0x30          // String de bytes
        private const val TAG_TEXT_WITHOUT_LANGUAGE: Byte = 0x41 // Texto sin idioma
        private const val TAG_NAME_WITHOUT_LANGUAGE: Byte = 0x42 // Nombre sin idioma
        private const val TAG_KEYWORD: Byte = 0x44               // Keyword
        private const val TAG_URI: Byte = 0x45                   // URI
        private const val TAG_CHARSET: Byte = 0x47               // Charset
        private const val TAG_NATURAL_LANGUAGE: Byte = 0x48      // Idioma natural
        private const val TAG_MIME_MEDIA_TYPE: Byte = 0x49       // Tipo MIME

        // Content-Type para peticiones IPP
        private val IPP_MEDIA_TYPE = "application/ipp".toMediaType()
    }

    /**
     * OkHttpClient configurado para comunicación IPP
     *
     * Tiempos de espera generosos porque:
     * - La impresora puede tardar en responder si está en reposo
     * - Los archivos grandes tardan en transferirse
     */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)    // Timeout de conexión TCP
        .readTimeout(120, TimeUnit.SECONDS)      // Timeout de lectura (archivos grandes)
        .writeTimeout(120, TimeUnit.SECONDS)     // Timeout de escritura
        .build()

    /**
     * Obtener atributos/estado de la impresora
     *
     * Envía una operación Get-Printer-Attributes al endpoint IPP.
     * La respuesta incluye estado, niveles de tinta, capacidades, etc.
     *
     * Ejemplo de respuesta IPP (decodificada):
     * printer-state: 3 (idle/lista)
     * printer-state-reasons: none
     * marker-levels: [70, 85, 60, 90] (CMYK en porcentaje)
     * media-ready: iso_a4_210x297mm
     *
     * @param printerUrl URL del endpoint IPP (ej: "http://192.168.1.10:631/ipp/print")
     * @return PrinterStatus con el estado actual o null si hay error
     */
    suspend fun getPrinterStatus(printerUrl: String): PrinterStatus? {
        return try {
            Log.d(TAG, "Consultando estado de impresora: $printerUrl")

            // Construir petición Get-Printer-Attributes
            val ippRequest = buildGetPrinterAttributesRequest(printerUrl)

            val request = Request.Builder()
                .url(printerUrl)
                .post(ippRequest.toRequestBody(IPP_MEDIA_TYPE))
                .addHeader("Content-Type", "application/ipp")
                .addHeader("Accept", "application/ipp")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Error HTTP: ${response.code}")
                return null
            }

            // Parsear la respuesta IPP binaria
            val responseBytes = response.body?.bytes() ?: return null
            parsePrinterAttributesResponse(responseBytes)

        } catch (e: Exception) {
            Log.e(TAG, "Error al obtener estado: ${e.message}", e)
            null
        }
    }

    /**
     * Enviar trabajo de impresión
     *
     * FLUJO COMPLETO DE IMPRESIÓN IPP:
     * =================================
     * 1. Construir cabecera IPP con operación Print-Job (0x0002)
     * 2. Agregar atributos de operación (printer-uri, charset, etc.)
     * 3. Agregar atributos del trabajo (copies, sides, media, etc.)
     * 4. Concatenar con los datos del documento (PDF/imagen)
     * 5. Enviar via HTTP POST al endpoint /ipp/print
     * 6. Parsear respuesta para obtener job-id y estado
     *
     * @param printerUrl URL del endpoint IPP
     * @param documentStream Stream del documento a imprimir
     * @param mimeType Tipo MIME: "application/pdf" o "image/jpeg"
     * @param printOptions Configuración de impresión (copias, color, etc.)
     * @return PrintJobResult con el job-id y estado, o null si hay error
     */
    suspend fun printDocument(
        printerUrl: String,
        documentStream: InputStream,
        mimeType: String,
        printOptions: PrintOptions
    ): PrintJobResult? {
        return try {
            Log.d(TAG, "Iniciando impresión en: $printerUrl")
            Log.d(TAG, "MIME Type: $mimeType, Copias: ${printOptions.copies}")

            // Leer todos los bytes del documento
            // Para documentos grandes, esto podría necesitar streaming
            val documentBytes = documentStream.readBytes()
            Log.d(TAG, "Tamaño del documento: ${documentBytes.size} bytes")

            // Construir el mensaje IPP completo
            val ippHeader = buildPrintJobRequest(printerUrl, mimeType, printOptions)

            // El mensaje final = cabecera IPP + datos del documento
            val fullRequest = ippHeader + documentBytes

            val request = Request.Builder()
                .url(printerUrl)
                .post(fullRequest.toRequestBody(IPP_MEDIA_TYPE))
                .addHeader("Content-Type", "application/ipp")
                .build()

            Log.d(TAG, "Enviando ${fullRequest.size} bytes a la impresora")

            val response = httpClient.newCall(request).execute()
            val responseBytes = response.body?.bytes() ?: return null

            Log.d(TAG, "Respuesta HTTP: ${response.code}")

            // Parsear respuesta para obtener job-id
            parsePrintJobResponse(responseBytes)

        } catch (e: Exception) {
            Log.e(TAG, "Error al imprimir: ${e.message}", e)
            null
        }
    }

    // =========================================================================
    // CONSTRUCTORES DE MENSAJES IPP
    // =========================================================================

    /**
     * Construir petición Get-Printer-Attributes en formato binario IPP
     *
     * ESTRUCTURA BINARIA REAL (hex):
     * 02 00        → IPP version 2.0
     * 00 0B        → Operation: Get-Printer-Attributes (0x000B)
     * 00 00 00 01  → Request-ID: 1
     * 01           → begin-operation-attributes
     * 47           → charset tag
     * 00 12        → name length: 18
     * 61 74 74 72 69 62 75 74 65 73 2D 63 68 61 72 73 65 74  → "attributes-charset"
     * 00 05        → value length: 5
     * 75 74 66 2D 38  → "utf-8"
     * 48           → natural-language tag
     * 00 1B        → name length: 27
     * [attributes-natural-language]
     * 00 05        → value length: 5
     * 65 6E 2D 75 73  → "en-us"
     * 45           → URI tag
     * 00 0B        → name length: 11
     * 70 72 69 6E 74 65 72 2D 75 72 69  → "printer-uri"
     * 00 XX        → URI length
     * [ipp://...]   → URI de la impresora
     * 03           → end-of-attributes
     */
    private fun buildGetPrinterAttributesRequest(printerUrl: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)

        // Header IPP
        out.writeByte(IPP_VERSION_MAJOR.toInt())  // Version mayor: 2
        out.writeByte(IPP_VERSION_MINOR.toInt())  // Version menor: 0
        out.writeShort(OP_GET_PRINTER_ATTRS.toInt()) // Operación
        out.writeInt(1)                               // Request ID

        // Grupo: operation-attributes
        out.writeByte(TAG_OPERATION_ATTRIBUTES.toInt())

        // Atributo: attributes-charset = "utf-8"
        writeIppAttribute(out, TAG_CHARSET, "attributes-charset", "utf-8")

        // Atributo: attributes-natural-language = "en-us"
        writeIppAttribute(out, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en-us")

        // Atributo: printer-uri = URI de la impresora
        // Convertir URL HTTP a URI IPP (mismo host/puerto, diferente esquema)
        val printerUri = printerUrl.replace("http://", "ipp://")
        writeIppAttribute(out, TAG_URI, "printer-uri", printerUri)

        // Solicitar atributos específicos que nos interesan
        // requested-attributes le dice a la impresora qué datos queremos
        writeIppAttribute(out, TAG_KEYWORD, "requested-attributes", "printer-state")
        writeIppAttributeAdditional(out, TAG_KEYWORD, "printer-state-reasons")
        writeIppAttributeAdditional(out, TAG_KEYWORD, "marker-levels")
        writeIppAttributeAdditional(out, TAG_KEYWORD, "marker-names")
        writeIppAttributeAdditional(out, TAG_KEYWORD, "marker-types")
        writeIppAttributeAdditional(out, TAG_KEYWORD, "media-ready")
        writeIppAttributeAdditional(out, TAG_KEYWORD, "printer-make-and-model")
        writeIppAttributeAdditional(out, TAG_KEYWORD, "printer-is-accepting-jobs")

        // Fin de atributos
        out.writeByte(TAG_END_ATTRIBUTES.toInt())

        return buffer.toByteArray()
    }

    /**
     * Construir petición Print-Job con todas las opciones de impresión
     *
     * Atributos importantes del trabajo (job-attributes):
     * - copies: número de copias (integer)
     * - sides: "one-sided" / "two-sided-long-edge" / "two-sided-short-edge"
     * - media: "iso_a4_210x297mm" / "na_letter_8.5x11in"
     * - print-color-mode: "color" / "monochrome"
     * - print-quality: 3=draft, 4=normal, 5=high
     * - document-format: "application/pdf" / "image/jpeg"
     *
     * @param printerUrl URL del endpoint IPP
     * @param mimeType MIME type del documento
     * @param options Opciones de impresión configuradas por el usuario
     */
    private fun buildPrintJobRequest(
        printerUrl: String,
        mimeType: String,
        options: PrintOptions
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)

        // Header IPP
        out.writeByte(IPP_VERSION_MAJOR.toInt())
        out.writeByte(IPP_VERSION_MINOR.toInt())
        out.writeShort(OP_PRINT_JOB.toInt())  // Print-Job
        out.writeInt(System.currentTimeMillis().toInt()) // Request ID único

        // ===== GRUPO: operation-attributes =====
        out.writeByte(TAG_OPERATION_ATTRIBUTES.toInt())

        writeIppAttribute(out, TAG_CHARSET, "attributes-charset", "utf-8")
        writeIppAttribute(out, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en-us")

        val printerUri = printerUrl.replace("http://", "ipp://")
        writeIppAttribute(out, TAG_URI, "printer-uri", printerUri)

        // Nombre del trabajo (visible en historial de la impresora)
        writeIppAttribute(out, TAG_NAME_WITHOUT_LANGUAGE, "job-name", "EpsonPrint-${System.currentTimeMillis()}")

        // Formato del documento
        writeIppAttribute(out, TAG_MIME_MEDIA_TYPE, "document-format", mimeType)

        // ===== GRUPO: job-attributes =====
        out.writeByte(TAG_JOB_ATTRIBUTES.toInt())

        // Número de copias
        writeIppIntegerAttribute(out, "copies", options.copies)

        // Doble cara
        // "one-sided" = solo una cara
        // "two-sided-long-edge" = doble cara encuadernación lado largo (normal)
        // "two-sided-short-edge" = doble cara encuadernación lado corto (calendario)
        val sidesValue = when (options.duplex) {
            DuplexMode.ONE_SIDED -> "one-sided"
            DuplexMode.TWO_SIDED_LONG -> "two-sided-long-edge"
            DuplexMode.TWO_SIDED_SHORT -> "two-sided-short-edge"
        }
        writeIppAttribute(out, TAG_KEYWORD, "sides", sidesValue)

        // Tamaño de papel
        // IPP usa nombres estándar PWG Media Size:
        // iso_a4_210x297mm = A4
        // na_letter_8.5x11in = Carta (US Letter)
        // iso_a3_297x420mm = A3
        val mediaValue = when (options.paperSize) {
            PaperSize.A4 -> "iso_a4_210x297mm"
            PaperSize.LETTER -> "na_letter_8.5x11in"
            PaperSize.A3 -> "iso_a3_297x420mm"
            PaperSize.PHOTO_4X6 -> "na_index-4x6_4x6in"
        }
        writeIppAttribute(out, TAG_KEYWORD, "media", mediaValue)

        // Modo de color
        // "color" = impresión a color
        // "monochrome" = blanco y negro
        // "auto" = decide la impresora
        val colorMode = when (options.colorMode) {
            ColorMode.COLOR -> "color"
            ColorMode.MONOCHROME -> "monochrome"
            ColorMode.AUTO -> "auto"
        }
        writeIppAttribute(out, TAG_KEYWORD, "print-color-mode", colorMode)

        // Calidad de impresión
        // 3 = Draft (borrador, rápido y menos tinta)
        // 4 = Normal
        // 5 = Best (alta calidad, más tinta)
        writeIppIntegerAttribute(out, "print-quality", options.quality.value)

        // Fin de atributos
        out.writeByte(TAG_END_ATTRIBUTES.toInt())

        return buffer.toByteArray()
        // NOTA: Los bytes del documento se concatenan DESPUÉS en printDocument()
    }

    // =========================================================================
    // ESCRITURA DE ATRIBUTOS IPP
    // =========================================================================

    /**
     * Escribir un atributo IPP de tipo String
     *
     * Formato binario:
     * [tag 1B][name_len 2B][name NB][value_len 2B][value MB]
     *
     * Ejemplo para charset "utf-8":
     * 47 0012 attributes-charset 0005 utf-8
     */
    private fun writeIppAttribute(
        out: DataOutputStream,
        tag: Byte,
        name: String,
        value: String
    ) {
        out.writeByte(tag.toInt())                    // Value tag
        out.writeShort(name.length)                   // Name length
        out.write(name.toByteArray(Charsets.US_ASCII)) // Name bytes
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        out.writeShort(valueBytes.size)               // Value length
        out.write(valueBytes)                          // Value bytes
    }

    /**
     * Escribir un valor adicional para el atributo anterior (set de valores)
     *
     * Cuando un atributo tiene múltiples valores (como requested-attributes),
     * los valores adicionales tienen nombre vacío (longitud 0).
     *
     * Formato:
     * [tag 1B][0x0000 2B (sin nombre)][value_len 2B][value MB]
     */
    private fun writeIppAttributeAdditional(out: DataOutputStream, tag: Byte, value: String) {
        out.writeByte(tag.toInt())
        out.writeShort(0)  // Nombre vacío = valor adicional del atributo anterior
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        out.writeShort(valueBytes.size)
        out.write(valueBytes)
    }

    /**
     * Escribir un atributo IPP de tipo Integer (4 bytes, big-endian)
     *
     * Formato:
     * [0x21 tag][name_len 2B][name NB][0x0004 2B][value 4B]
     */
    private fun writeIppIntegerAttribute(out: DataOutputStream, name: String, value: Int) {
        out.writeByte(TAG_INTEGER.toInt())
        out.writeShort(name.length)
        out.write(name.toByteArray(Charsets.US_ASCII))
        out.writeShort(4)  // Los integers IPP siempre son 4 bytes
        out.writeInt(value)
    }

    // =========================================================================
    // PARSEO DE RESPUESTAS IPP
    // =========================================================================

    /**
     * Parsear respuesta de Get-Printer-Attributes
     *
     * La respuesta IPP tiene el mismo formato binario que la petición.
     * Buscamos atributos específicos:
     * - printer-state: 3=idle, 4=processing, 5=stopped
     * - printer-state-reasons: "none", "media-empty", "toner-low", etc.
     * - marker-levels: porcentaje de tinta [C, M, Y, K] o [C, M, Y, Bk]
     *
     * Estados de impresora (printer-state):
     * 3 → idle (lista para imprimir)
     * 4 → processing (imprimiendo)
     * 5 → stopped (error o sin papel)
     */
    private fun parsePrinterAttributesResponse(bytes: ByteArray): PrinterStatus {
        // Los primeros 8 bytes son el header
        val statusCode = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        Log.d(TAG, "IPP Status Code: 0x${statusCode.toString(16)}")

        // Parsear todos los atributos del cuerpo
        val attributes = parseIppAttributes(bytes, 8)

        // Extraer valores específicos
        val printerState = attributes["printer-state"]?.toIntOrNull() ?: 3
        val stateReasons = attributes["printer-state-reasons"] ?: "none"
        val markerLevels = attributes["marker-levels"] ?: ""
        val isAccepting = attributes["printer-is-accepting-jobs"]?.toBoolean() ?: true

        // Interpretar el estado
        val status = when (printerState) {
            3 -> PrinterState.IDLE
            4 -> PrinterState.PROCESSING
            5 -> PrinterState.STOPPED
            else -> PrinterState.UNKNOWN
        }

        // Parsear niveles de tinta (vienen como enteros separados por coma)
        val inkLevels = parseInkLevels(markerLevels)

        // Determinar si hay papel basándose en state-reasons
        val hasPaper = !stateReasons.contains("media-empty") &&
                !stateReasons.contains("media-needed")

        return PrinterStatus(
            state = status,
            stateReasons = stateReasons,
            inkLevels = inkLevels,
            hasPaper = hasPaper,
            isAcceptingJobs = isAccepting,
            rawStatusCode = statusCode
        )
    }

    /**
     * Parsear respuesta de Print-Job
     *
     * Respuesta exitosa contiene:
     * - job-id: ID único del trabajo (para hacer seguimiento)
     * - job-state: 3=pending, 4=pending-held, 5=processing, 9=completed
     * - job-state-reasons: "none", "job-queued", etc.
     *
     * Códigos de error IPP comunes:
     * 0x0000 → successful-ok
     * 0x0001 → successful-ok-ignored-or-substituted-attributes
     * 0x0400 → client-error-bad-request
     * 0x0401 → client-error-forbidden
     * 0x040A → client-error-document-format-not-supported
     * 0x0500 → server-error-internal-error
     * 0x0503 → server-error-service-unavailable
     */
    private fun parsePrintJobResponse(bytes: ByteArray): PrintJobResult {
        val statusCode = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val requestId = ByteBuffer.wrap(bytes, 4, 4).int

        Log.d(TAG, "Print-Job Response - Status: 0x${statusCode.toString(16)}, RequestID: $requestId")

        val attributes = parseIppAttributes(bytes, 8)
        val jobId = attributes["job-id"]?.toIntOrNull() ?: -1
        val jobState = attributes["job-state"]?.toIntOrNull() ?: 0
        val jobStateReasons = attributes["job-state-reasons"] ?: "unknown"

        // Interpretar código de estado
        val success = statusCode in 0x0000..0x01FF  // Rango "successful"
        val errorMessage = if (!success) {
            interpretIppErrorCode(statusCode)
        } else null

        return PrintJobResult(
            success = success,
            jobId = jobId,
            jobState = jobState,
            stateReasons = jobStateReasons,
            errorMessage = errorMessage,
            statusCode = statusCode
        )
    }

    /**
     * Parser genérico de atributos IPP
     *
     * Lee el stream de bytes IPP y extrae todos los atributos clave-valor.
     * Este es el corazón del parser: navega la estructura binaria IPP.
     *
     * @param bytes Array de bytes de la respuesta IPP
     * @param offset Posición inicial (después del header de 8 bytes)
     * @return Mapa de nombre de atributo → valor como String
     */
    private fun parseIppAttributes(bytes: ByteArray, offset: Int): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        var pos = offset
        var currentName = ""

        while (pos < bytes.size) {
            val tag = bytes[pos].toInt() and 0xFF
            pos++

            when {
                // Tags de grupo (no tienen valor directo)
                tag == 0x01 || tag == 0x02 || tag == 0x03 || tag == 0x04 || tag == 0x05 -> {
                    if (tag == 0x03) return attributes  // End-of-attributes
                    continue
                }

                // Atributo con valor
                tag >= 0x10 -> {
                    if (pos + 2 > bytes.size) break

                    // Leer longitud del nombre
                    val nameLen = ((bytes[pos].toInt() and 0xFF) shl 8) or
                            (bytes[pos + 1].toInt() and 0xFF)
                    pos += 2

                    // Leer nombre (puede ser vacío para valores adicionales)
                    if (nameLen > 0 && pos + nameLen <= bytes.size) {
                        currentName = String(bytes, pos, nameLen, Charsets.UTF_8)
                        pos += nameLen
                    } else {
                        pos += nameLen
                    }

                    if (pos + 2 > bytes.size) break

                    // Leer longitud del valor
                    val valueLen = ((bytes[pos].toInt() and 0xFF) shl 8) or
                            (bytes[pos + 1].toInt() and 0xFF)
                    pos += 2

                    // Leer valor
                    if (valueLen > 0 && pos + valueLen <= bytes.size) {
                        val valueBytes = bytes.copyOfRange(pos, pos + valueLen)
                        pos += valueLen

                        // Decodificar valor según el tag
                        val valueStr = when (tag) {
                            0x21 -> { // Integer (4 bytes big-endian)
                                if (valueLen == 4) {
                                    ByteBuffer.wrap(valueBytes).int.toString()
                                } else valueBytes.toString(Charsets.UTF_8)
                            }
                            0x22 -> { // Boolean
                                if (valueLen == 1) (valueBytes[0].toInt() != 0).toString()
                                else valueBytes.toString(Charsets.UTF_8)
                            }
                            0x23 -> { // Enum (4 bytes)
                                if (valueLen == 4) {
                                    ByteBuffer.wrap(valueBytes).int.toString()
                                } else valueBytes.toString(Charsets.UTF_8)
                            }
                            else -> valueBytes.toString(Charsets.UTF_8)
                        }

                        // Acumular múltiples valores con coma
                        if (attributes.containsKey(currentName)) {
                            attributes[currentName] = "${attributes[currentName]},$valueStr"
                        } else {
                            attributes[currentName] = valueStr
                        }

                        Log.v(TAG, "Atributo IPP: $currentName = $valueStr")
                    } else {
                        pos += valueLen
                    }
                }
            }
        }

        return attributes
    }

    /**
     * Parsear niveles de tinta desde el atributo marker-levels
     *
     * El atributo marker-levels contiene porcentajes de tinta.
     * Para Epson L3560 (sistema de tinta EcoTank):
     * - 4 valores: Cyan, Magenta, Yellow, Black
     * - Rango: 0-100 (porcentaje)
     * - -1 = desconocido
     * - -2 = ilimitado (tinta recargable)
     *
     * Ejemplo: "70,85,60,90" → C:70%, M:85%, Y:60%, K:90%
     */
    private fun parseInkLevels(markerLevels: String): InkLevels {
        if (markerLevels.isEmpty()) return InkLevels()

        val levels = markerLevels.split(",").mapNotNull { it.trim().toIntOrNull() }

        return when (levels.size) {
            4 -> InkLevels(
                cyan = levels[0],
                magenta = levels[1],
                yellow = levels[2],
                black = levels[3]
            )
            1 -> InkLevels(black = levels[0])
            else -> InkLevels()
        }
    }

    /**
     * Interpretar códigos de error IPP a mensajes amigables
     *
     * Los códigos IPP están definidos en RFC 8011 Section 6.4
     */
    private fun interpretIppErrorCode(code: Int): String {
        return when (code) {
            0x0400 -> "Petición inválida. Verifica el formato del documento."
            0x0401 -> "Acceso denegado por la impresora."
            0x0402 -> "El usuario no tiene permiso para imprimir."
            0x0404 -> "El trabajo de impresión no existe."
            0x0405 -> "El conflicto en los atributos del trabajo."
            0x040A -> "El formato del documento no está soportado por esta impresora."
            0x040B -> "El URI del documento es inaccesible."
            0x0500 -> "Error interno de la impresora."
            0x0501 -> "La operación no está soportada."
            0x0502 -> "La impresora está ocupada, intenta más tarde."
            0x0503 -> "Servicio no disponible. La impresora puede estar apagada."
            0x0507 -> "La impresora no tiene papel."
            0x0508 -> "La impresora tiene un atasco de papel."
            else -> "Error desconocido (código: 0x${code.toString(16).uppercase()})"
        }
    }
}

// =========================================================================
// DATA CLASSES - Modelos de datos
// =========================================================================

/**
 * Estado actual de la impresora
 */
data class PrinterStatus(
    val state: PrinterState,
    val stateReasons: String,
    val inkLevels: InkLevels,
    val hasPaper: Boolean,
    val isAcceptingJobs: Boolean,
    val rawStatusCode: Int,
    val model: String? = null
) {
    /**
     * Mensaje de estado amigable para mostrar al usuario
     */
    val displayStatus: String get() = when (state) {
        PrinterState.IDLE -> if (hasPaper) "Lista" else "Sin papel"
        PrinterState.PROCESSING -> "Imprimiendo..."
        PrinterState.STOPPED -> interpretStateReasons(stateReasons)
        PrinterState.UNKNOWN -> "Estado desconocido"
    }

    private fun interpretStateReasons(reasons: String): String {
        return when {
            reasons.contains("media-empty") -> "Sin papel - Agrega papel"
            reasons.contains("media-jam") -> "Atasco de papel"
            reasons.contains("toner-empty") -> "Tinta agotada"
            reasons.contains("toner-low") -> "Tinta baja"
            reasons.contains("cover-open") -> "Cubierta abierta"
            reasons.contains("offline") -> "Impresora offline"
            reasons.contains("shutdown") -> "Impresora apagada"
            else -> "Error: $reasons"
        }
    }
}

/** Estado de la impresora (valores del atributo printer-state) */
enum class PrinterState { IDLE, PROCESSING, STOPPED, UNKNOWN }

/**
 * Niveles de tinta de la impresora
 * Valores: 0-100 (porcentaje), -1 (desconocido), -2 (ilimitado)
 */
data class InkLevels(
    val cyan: Int = -1,
    val magenta: Int = -1,
    val yellow: Int = -1,
    val black: Int = -1
) {
    val isAvailable: Boolean get() = cyan >= 0 || black >= 0
}

/** Resultado de un trabajo de impresión */
data class PrintJobResult(
    val success: Boolean,
    val jobId: Int,
    val jobState: Int,
    val stateReasons: String,
    val errorMessage: String?,
    val statusCode: Int
)

/** Opciones de configuración de impresión */
data class PrintOptions(
    val copies: Int = 1,
    val colorMode: ColorMode = ColorMode.COLOR,
    val duplex: DuplexMode = DuplexMode.ONE_SIDED,
    val paperSize: PaperSize = PaperSize.A4,
    val quality: PrintQuality = PrintQuality.NORMAL
)

/** Modo de color para impresión */
enum class ColorMode { COLOR, MONOCHROME, AUTO }

/** Modo de impresión doble cara */
enum class DuplexMode { ONE_SIDED, TWO_SIDED_LONG, TWO_SIDED_SHORT }

/** Tamaño de papel estándar */
enum class PaperSize { A4, LETTER, A3, PHOTO_4X6 }

/** Calidad de impresión con su valor IPP correspondiente */
enum class PrintQuality(val value: Int) {
    DRAFT(3),   // 3 = borrador
    NORMAL(4),  // 4 = normal
    HIGH(5)     // 5 = alta calidad
}
