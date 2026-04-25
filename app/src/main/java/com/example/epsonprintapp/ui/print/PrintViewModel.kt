package com.example.epsonprintapp.ui.print

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.database.entities.PrintJobEntity
import com.example.epsonprintapp.notifications.AppNotificationManager
import com.example.epsonprintapp.printer.ColorMode
import com.example.epsonprintapp.printer.DuplexMode
import com.example.epsonprintapp.printer.IppClient
import com.example.epsonprintapp.printer.PaperSize
import com.example.epsonprintapp.printer.PrintOptions
import com.example.epsonprintapp.printer.PrintQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream

class PrintViewModel(application: Application) : AndroidViewModel(application) {

    private val database            = AppDatabase.getInstance(application)
    private val ippClient           = IppClient(application)
    private val notificationManager = AppNotificationManager(application)

    // ── UI State ───────────────────────────────────────────────────────────────
    private val _isPrinting    = MutableLiveData(false)
    val isPrinting: LiveData<Boolean> = _isPrinting

    private val _printProgress = MutableLiveData(0)
    val printProgress: LiveData<Int> = _printProgress

    private val _errorMessage  = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    private val _printSuccess  = MutableLiveData(false)
    val printSuccess: LiveData<Boolean> = _printSuccess

    // ── Print Options ──────────────────────────────────────────────────────────
    private val _printOptions  = MutableLiveData(PrintOptions())
    val printOptions: LiveData<PrintOptions> = _printOptions

    // ── File ───────────────────────────────────────────────────────────────────
    private val _selectedFileUri  = MutableLiveData<Uri?>(null)
    val selectedFileUri: LiveData<Uri?> = _selectedFileUri

    private val _selectedFileName = MutableLiveData<String?>(null)
    val selectedFileName: LiveData<String?> = _selectedFileName

    private var currentJobId: Long = -1

    // ── Papel detectado de la impresora ────────────────────────────────────────
    private val _detectedPaperSize = MutableLiveData<PaperSize?>(null)
    val detectedPaperSize: LiveData<PaperSize?> = _detectedPaperSize

    // ── File selection ─────────────────────────────────────────────────────────

    fun onFileSelected(uri: Uri, context: Context) {
        _selectedFileUri.value = uri
        _selectedFileName.value = getFileNameFromUri(uri, context)

        val mimeType = context.contentResolver.getType(uri)
        if (mimeType != null && !isMimeTypeSupported(mimeType)) {
            _errorMessage.value = "Formato no soportado: $mimeType\nSoportados: PDF, JPEG, PNG"
        }
    }

    fun updateOptions(
        copies:    Int?          = null,
        colorMode: ColorMode?    = null,
        duplex:    DuplexMode?   = null,
        paperSize: PaperSize?    = null,
        quality:   PrintQuality? = null
    ) {
        val current = _printOptions.value ?: PrintOptions()
        _printOptions.value = current.copy(
            copies    = copies    ?: current.copies,
            colorMode = colorMode ?: current.colorMode,
            duplex    = duplex    ?: current.duplex,
            paperSize = paperSize ?: current.paperSize,
            quality   = quality   ?: current.quality
        )
    }

    // ── Printing ───────────────────────────────────────────────────────────────

    fun startPrinting(context: Context) {
        val uri = _selectedFileUri.value ?: run {
            _errorMessage.value = "No hay archivo seleccionado"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isPrinting.postValue(true)
            _printProgress.postValue(5)

            try {
                // 1. Obtener impresora
                val printer = database.printerDao().getDefaultPrinter() ?: run {
                    _errorMessage.postValue("No hay impresora configurada. Ve al Dashboard y busca una.")
                    _isPrinting.postValue(false)
                    return@launch
                }

                _printProgress.postValue(15)

                // 2. Consultar estado de la impresora
                val printerStatus = ippClient.getPrinterStatus(printer.ippUrl)
                if (printerStatus == null) {
                    _errorMessage.postValue("No se puede conectar con la impresora en ${printer.ippUrl}.\nVerifica que esté encendida y en la misma red.")
                    _isPrinting.postValue(false)
                    return@launch
                }

                val detectedPaper = detectPaperFromPrinterStatus(printerStatus)
                _detectedPaperSize.postValue(detectedPaper)
                _printProgress.postValue(25)

                // 3. Resolver opciones finales
                val userOptions = _printOptions.value ?: PrintOptions()
                val finalOptions = if (detectedPaper != null &&
                    userOptions.paperSize == PrintOptions().paperSize) {
                    userOptions.copy(paperSize = detectedPaper)
                } else {
                    userOptions
                }

                val mimeType = context.contentResolver.getType(uri)
                    ?: detectMimeTypeFromUri(uri, context)
                val fileName = _selectedFileName.value ?: "documento"

                // 4. Registrar en DB
                val printJob = PrintJobEntity(
                    printerId  = printer.id,
                    fileName   = fileName,
                    mimeType   = mimeType,
                    status     = "PROCESSING",
                    copies     = finalOptions.copies,
                    colorMode  = finalOptions.colorMode.name,
                    paperSize  = finalOptions.paperSize.name,
                    isDuplex   = finalOptions.duplex != DuplexMode.ONE_SIDED
                )
                currentJobId = database.printJobDao().insertPrintJob(printJob)
                _printProgress.postValue(35)

                // 5. Preparar y enviar según tipo de archivo
                val isPdf = mimeType == "application/pdf"

                if (isPdf) {
                    // PDF: rasterizar cada página a JPEG y enviar como imagen
                    sendPdfAsImages(context, uri, printer.ippUrl, finalOptions, fileName)
                } else {
                    // Imagen: enviar directamente como JPEG
                    sendImageFile(context, uri, mimeType, printer.ippUrl, finalOptions, fileName)
                }

            } catch (e: Exception) {
                handlePrintError(currentJobId, "Error inesperado: ${e.message}")
            } finally {
                _isPrinting.postValue(false)
            }
        }
    }

    /**
     * Rasteriza el PDF página por página y envía cada una como JPEG.
     * Las impresoras Epson/HP generalmente NO aceptan PDF vía IPP directo;
     * sí aceptan image/jpeg sin problema.
     */
    private suspend fun sendPdfAsImages(
        context: Context,
        uri: Uri,
        printerUrl: String,
        options: PrintOptions,
        fileName: String
    ) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: run { handlePrintError(currentJobId, "No se pudo abrir el PDF"); return }

            renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount

            if (pageCount == 0) {
                handlePrintError(currentJobId, "El PDF no tiene páginas")
                return
            }

            _printProgress.postValue(45)

            // Calcular el ancho objetivo en píxeles según el papel seleccionado y 200 DPI
            // (200 DPI es suficiente para impresión y no satura la red)
            val dpi = 200
            val (targetW, targetH) = when (options.paperSize) {
                PaperSize.A4     -> Pair((8.27 * dpi).toInt(), (11.69 * dpi).toInt())  // 1654 x 2339
                PaperSize.LETTER -> Pair((8.5  * dpi).toInt(), (11.0  * dpi).toInt())  // 1700 x 2200
                PaperSize.A3     -> Pair((11.69 * dpi).toInt(),(16.54 * dpi).toInt())  // 2338 x 3308
                PaperSize.PHOTO_4X6 -> Pair((4 * dpi).toInt(), (6 * dpi).toInt())      // 800 x 1200
            }

            var lastResult: com.example.epsonprintapp.printer.PrintJobResult? = null

            for (pageIndex in 0 until pageCount) {
                val progressStart = 45 + (pageIndex * 45 / pageCount)
                val progressEnd   = 45 + ((pageIndex + 1) * 45 / pageCount)
                _printProgress.postValue(progressStart)

                val page = renderer.openPage(pageIndex)

                // Mantener aspect ratio
                val pageAspect = page.width.toFloat() / page.height.toFloat()
                val targetAspect = targetW.toFloat() / targetH.toFloat()
                val (renderW, renderH) = if (pageAspect > targetAspect) {
                    Pair(targetW, (targetW / pageAspect).toInt())
                } else {
                    Pair((targetH * pageAspect).toInt(), targetH)
                }

                val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                // Fondo blanco (las impresoras no entienden transparencia)
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                // Comprimir a JPEG
                val jpegBytes = bitmapToJpeg(bitmap, quality = 90)
                bitmap.recycle()

                _printProgress.postValue(progressEnd)

                // Enviar esta página
                val result = ippClient.printDocument(
                    printerUrl     = printerUrl,
                    documentStream = jpegBytes.inputStream(),
                    mimeType       = "image/jpeg",
                    printOptions   = options
                )

                lastResult = result

                if (result == null || !result.success) {
                    val errorMsg = result?.errorMessage
                        ?: "Error al enviar página ${pageIndex + 1}"
                    handlePrintError(currentJobId, errorMsg)
                    notificationManager.notifyPrintError(errorMsg, fileName, currentJobId)
                    return
                }
            }

            // Todas las páginas enviadas exitosamente
            database.printJobDao().markJobCompleted(currentJobId)
            notificationManager.notifyPrintSuccess(fileName, currentJobId)
            _printSuccess.postValue(true)
            _printProgress.postValue(100)

        } catch (e: Exception) {
            handlePrintError(currentJobId, "Error al procesar PDF: ${e.message}")
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    /**
     * Envía una imagen (JPEG o PNG) directamente a la impresora.
     * PNG se convierte a JPEG para máxima compatibilidad.
     */
    private suspend fun sendImageFile(
        context: Context,
        uri: Uri,
        mimeType: String,
        printerUrl: String,
        options: PrintOptions,
        fileName: String
    ) {
        _printProgress.postValue(50)

        val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: run {
            handlePrintError(currentJobId, "No se pudo abrir el archivo")
            return
        }

        val (finalStream, finalMimeType) = if (mimeType == "image/png") {
            Pair(convertPngToJpeg(inputStream), "image/jpeg")
        } else {
            Pair(inputStream, mimeType)
        }

        _printProgress.postValue(65)

        val result = ippClient.printDocument(
            printerUrl     = printerUrl,
            documentStream = finalStream,
            mimeType       = finalMimeType,
            printOptions   = options
        )

        finalStream.close()
        _printProgress.postValue(90)

        if (result != null && result.success) {
            database.printJobDao().markJobCompleted(currentJobId)
            notificationManager.notifyPrintSuccess(fileName, currentJobId)
            _printSuccess.postValue(true)
            _printProgress.postValue(100)
        } else {
            val errorMsg = result?.errorMessage
                ?: "Error desconocido (código: 0x${result?.statusCode?.toString(16) ?: "?"})"
            handlePrintError(currentJobId, errorMsg)
            notificationManager.notifyPrintError(errorMsg, fileName, currentJobId)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILIDADES PRIVADAS
    // ─────────────────────────────────────────────────────────────────────────

    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int = 90): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun detectPaperFromPrinterStatus(status: com.example.epsonprintapp.printer.PrinterStatus): PaperSize? {
        val model = status.model?.lowercase() ?: return null
        return when {
            model.contains("hp") || model.contains("smart tank") ||
                    model.contains("deskjet") || model.contains("officejet") -> PaperSize.LETTER
            model.contains("epson") || model.contains("ecotank") -> PaperSize.A4
            else -> null
        }
    }

    private suspend fun handlePrintError(jobId: Long, error: String) {
        if (jobId > 0) database.printJobDao().updateJobStatus(jobId, "FAILED", error)
        _errorMessage.postValue(error)
    }

    private fun getFileNameFromUri(uri: Uri, context: Context): String {
        var name = "documento"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun detectMimeTypeFromUri(uri: Uri, context: Context): String {
        val extension = uri.path?.substringAfterLast(".")?.lowercase() ?: ""
        return when (extension) {
            "pdf"        -> "application/pdf"
            "jpg","jpeg" -> "image/jpeg"
            "png"        -> "image/png"
            else         -> "application/pdf"
        }
    }

    private fun isMimeTypeSupported(mimeType: String) = mimeType in listOf(
        "application/pdf", "image/jpeg", "image/jpg", "image/png"
    )

    private fun convertPngToJpeg(inputStream: InputStream): InputStream {
        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
        val output = ByteArrayOutputStream()
        // Fondo blanco antes de comprimir (PNG puede tener transparencia)
        val bgBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(bgBitmap).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        bitmap.recycle()
        bgBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
        bgBitmap.recycle()
        return output.toByteArray().inputStream()
    }

    fun clearError()        { _errorMessage.value = null }
    fun resetPrintSuccess() { _printSuccess.value = false }
}