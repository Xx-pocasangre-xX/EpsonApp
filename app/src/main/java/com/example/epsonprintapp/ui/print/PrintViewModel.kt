package com.example.epsonprintapp.ui.print

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.epsonprintapp.AppConstants
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
import kotlin.math.sqrt

class PrintViewModel(application: Application) : AndroidViewModel(application) {

    private val database            = AppDatabase.getInstance(application)
    private val ippClient           = IppClient(application)
    private val notificationManager = AppNotificationManager(application)

    // ── UI State ───────────────────────────────────────────────────────────────

    private val _isPrinting    = MutableLiveData(false)
    val isPrinting: LiveData<Boolean> = _isPrinting

    private val _printProgress = MutableLiveData(0)
    val printProgress: LiveData<Int> = _printProgress

    private val _errorMessage  = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _printSuccess  = MutableLiveData(false)
    val printSuccess: LiveData<Boolean> = _printSuccess

    private val _printOptions  = MutableLiveData(PrintOptions())
    val printOptions: LiveData<PrintOptions> = _printOptions

    private val _selectedFileUri  = MutableLiveData<Uri?>()
    val selectedFileUri: LiveData<Uri?> = _selectedFileUri

    private val _selectedFileName = MutableLiveData<String?>()
    val selectedFileName: LiveData<String?> = _selectedFileName

    private val _detectedPaperSize = MutableLiveData<PaperSize?>()
    val detectedPaperSize: LiveData<PaperSize?> = _detectedPaperSize

    // @Volatile: modificado desde Dispatchers.IO, leído desde múltiples threads
    @Volatile private var currentJobId: Long = -1

    // ── Selección de archivo ───────────────────────────────────────────────────

    fun onFileSelected(uri: Uri, context: Context) {
        _selectedFileUri.value  = uri
        _selectedFileName.value = getFileName(uri, context)
        val mime = context.contentResolver.getType(uri)
        if (mime != null && !isMimeSupported(mime)) {
            _errorMessage.value = "Formato no soportado: $mime\nSoportados: PDF, JPEG, PNG"
        }
    }

    fun updateOptions(
        copies:    Int?          = null,
        colorMode: ColorMode?    = null,
        duplex:    DuplexMode?   = null,
        paperSize: PaperSize?    = null,
        quality:   PrintQuality? = null
    ) {
        val c = _printOptions.value ?: PrintOptions()
        _printOptions.value = c.copy(
            copies    = copies    ?: c.copies,
            colorMode = colorMode ?: c.colorMode,
            duplex    = duplex    ?: c.duplex,
            paperSize = paperSize ?: c.paperSize,
            quality   = quality   ?: c.quality
        )
    }

    // ── Impresión ──────────────────────────────────────────────────────────────

    fun startPrinting(context: Context) {
        val uri = _selectedFileUri.value ?: run {
            _errorMessage.value = "No hay archivo seleccionado"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isPrinting.postValue(true)
            _printProgress.postValue(5)

            try {
                // 1. Validar tamaño de archivo antes de cargarlo en memoria
                val fileSize = getFileSize(uri, context)
                if (fileSize > AppConstants.MAX_FILE_SIZE_BYTES) {
                    val mb = fileSize / (1024 * 1024)
                    _errorMessage.postValue(
                        "Archivo demasiado grande: ${mb} MB\nMáximo permitido: ${AppConstants.MAX_FILE_SIZE_MB} MB")
                    return@launch
                }

                // 2. Obtener impresora
                val printer = database.printerDao().getDefaultPrinter() ?: run {
                    _errorMessage.postValue("No hay impresora configurada. Ve al Dashboard y busca una.")
                    return@launch
                }
                _printProgress.postValue(15)

                // 3. Consultar estado e inferir papel
                val status = ippClient.getPrinterStatus(printer.ippUrl)
                if (status == null) {
                    _errorMessage.postValue(
                        "No se puede conectar con la impresora en ${printer.ippUrl}\n" +
                                "Verifica que esté encendida y en la misma red WiFi.")
                    return@launch
                }
                val detectedPaper = detectPaper(status.model)
                _detectedPaperSize.postValue(detectedPaper)
                _printProgress.postValue(25)

                // 4. Resolver opciones finales
                val userOpts   = _printOptions.value ?: PrintOptions()
                val finalOpts  = if (detectedPaper != null && userOpts.paperSize == PrintOptions().paperSize)
                    userOpts.copy(paperSize = detectedPaper) else userOpts

                val mimeType   = context.contentResolver.getType(uri)
                    ?: guessMime(uri)
                val fileName   = _selectedFileName.value ?: "documento"

                // 5. Registrar en DB
                val job = PrintJobEntity(
                    printerId  = printer.id,
                    fileName   = fileName,
                    mimeType   = mimeType,
                    status     = "PROCESSING",
                    copies     = finalOpts.copies,
                    colorMode  = finalOpts.colorMode.name,
                    paperSize  = finalOpts.paperSize.name,
                    isDuplex   = finalOpts.duplex != DuplexMode.ONE_SIDED
                )
                currentJobId = database.printJobDao().insertPrintJob(job)
                _printProgress.postValue(35)

                // 6. Enviar según tipo
                if (mimeType == "application/pdf") {
                    sendPdfAsImages(context, uri, printer.ippUrl, finalOpts, fileName)
                } else {
                    sendImageFile(context, uri, mimeType, printer.ippUrl, finalOpts, fileName)
                }

            } catch (e: Exception) {
                handleError("Error inesperado: ${e.message}")
            } finally {
                _isPrinting.postValue(false)
            }
        }
    }

    /**
     * Rasteriza el PDF página por página y envía cada una como JPEG.
     * Libera cada bitmap en el finally para garantizar que no queden en memoria.
     */
    private suspend fun sendPdfAsImages(
        context:    Context,
        uri:        Uri,
        url:        String,
        options:    PrintOptions,
        fileName:   String
    ) {
        var pfd:      ParcelFileDescriptor? = null
        var renderer: PdfRenderer?          = null

        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: run { handleError("No se pudo abrir el PDF"); return }

            renderer      = PdfRenderer(pfd)
            val pageCount = renderer.pageCount

            if (pageCount == 0) { handleError("El PDF no tiene páginas"); return }

            _printProgress.postValue(45)

            val dpi = AppConstants.PDF_RASTER_DPI
            val (targetW, targetH) = when (options.paperSize) {
                PaperSize.A4        -> Pair((8.27 * dpi).toInt(),  (11.69 * dpi).toInt())
                PaperSize.LETTER    -> Pair((8.5  * dpi).toInt(),  (11.0  * dpi).toInt())
                PaperSize.A3        -> Pair((11.69 * dpi).toInt(), (16.54 * dpi).toInt())
                PaperSize.A5        -> Pair((5.83 * dpi).toInt(),  (8.27  * dpi).toInt())
                PaperSize.LEGAL     -> Pair((8.5  * dpi).toInt(),  (14.0  * dpi).toInt())
                PaperSize.PHOTO_4X6 -> Pair((4    * dpi).toInt(),  (6     * dpi).toInt())
            }

            for (pageIndex in 0 until pageCount) {
                val progressStart = 45 + (pageIndex * 45 / pageCount)
                _printProgress.postValue(progressStart)

                var bitmap: Bitmap? = null
                val page            = renderer.openPage(pageIndex)

                try {
                    val aspect       = page.width.toFloat() / page.height.toFloat()
                    val targetAspect = targetW.toFloat() / targetH.toFloat()
                    val (rW, rH)     = if (aspect > targetAspect)
                        Pair(targetW, (targetW / aspect).toInt())
                    else
                        Pair((targetH * aspect).toInt(), targetH)

                    // Limitar resolución para evitar OOM
                    val maxPixels = 2048 * 2048
                    val pixels    = rW * rH
                    val (finalW, finalH) = if (pixels > maxPixels) {
                        val scale = sqrt(maxPixels.toDouble() / pixels)
                        Pair((rW * scale).toInt(), (rH * scale).toInt())
                    } else Pair(rW, rH)

                    bitmap = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val jpegBytes = bitmapToJpeg(bitmap)

                    val result = ippClient.printDocument(
                        url, jpegBytes.inputStream(), "image/jpeg", options)

                    if (result == null || !result.success) {
                        val msg = result?.errorMessage ?: "Error al enviar página ${pageIndex + 1}"
                        handleError(msg)
                        notificationManager.notifyPrintError(msg, fileName, currentJobId)
                        return
                    }
                } finally {
                    page.close()
                    // Liberar bitmap SIEMPRE, incluso si hay excepción
                    bitmap?.recycle()
                }
            }

            database.printJobDao().markJobCompleted(currentJobId)
            notificationManager.notifyPrintSuccess(fileName, currentJobId)
            _printSuccess.postValue(true)
            _printProgress.postValue(100)

        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    /**
     * Envía imagen directamente. PNG se convierte a JPEG para máxima compatibilidad.
     */
    private suspend fun sendImageFile(
        context:  Context,
        uri:      Uri,
        mimeType: String,
        url:      String,
        options:  PrintOptions,
        fileName: String
    ) {
        _printProgress.postValue(50)

        val inputStream = context.contentResolver.openInputStream(uri)
        if (inputStream == null) {
            handleError("No se pudo abrir el archivo seleccionado")
            return
        }

        val (finalStream, finalMime) = if (mimeType == "image/png") {
            Pair(convertPngToJpeg(inputStream), "image/jpeg")
        } else {
            Pair(inputStream, mimeType)
        }

        _printProgress.postValue(65)

        try {
            val result = ippClient.printDocument(url, finalStream, finalMime, options)
            _printProgress.postValue(90)

            if (result != null && result.success) {
                database.printJobDao().markJobCompleted(currentJobId)
                notificationManager.notifyPrintSuccess(fileName, currentJobId)
                _printSuccess.postValue(true)
                _printProgress.postValue(100)
            } else {
                val msg = result?.errorMessage
                    ?: "Error desconocido (0x${result?.statusCode?.toString(16) ?: "?"})"
                handleError(msg)
                notificationManager.notifyPrintError(msg, fileName, currentJobId)
            }
        } finally {
            runCatching { finalStream.close() }
        }
    }

    // ── Utilidades ─────────────────────────────────────────────────────────────

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, AppConstants.JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun convertPngToJpeg(inputStream: InputStream): InputStream {
        val original = android.graphics.BitmapFactory.decodeStream(inputStream)
        val bgBitmap = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        Canvas(bgBitmap).apply {
            drawColor(Color.WHITE)
            drawBitmap(original, 0f, 0f, null)
        }
        original.recycle()
        val out = ByteArrayOutputStream()
        bgBitmap.compress(Bitmap.CompressFormat.JPEG, AppConstants.JPEG_QUALITY, out)
        bgBitmap.recycle()
        return out.toByteArray().inputStream()
    }

    private fun detectPaper(model: String?): PaperSize? {
        val m = model?.lowercase() ?: return null
        return when {
            m.contains("hp") || m.contains("smart tank") ||
                    m.contains("deskjet") || m.contains("officejet") -> PaperSize.LETTER
            m.contains("epson") || m.contains("ecotank")     -> PaperSize.A4
            else                                              -> null
        }
    }

    private fun getFileSize(uri: Uri, context: Context): Long {
        return context.contentResolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }

    private fun getFileName(uri: Uri, context: Context): String {
        var name = "documento"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun guessMime(uri: Uri): String {
        val ext = uri.path?.substringAfterLast(".")?.lowercase() ?: ""
        return when (ext) {
            "pdf"        -> "application/pdf"
            "jpg","jpeg" -> "image/jpeg"
            "png"        -> "image/png"
            else         -> "application/pdf"
        }
    }

    private fun isMimeSupported(mime: String) =
        mime in listOf("application/pdf", "image/jpeg", "image/jpg", "image/png")

    private suspend fun handleError(msg: String) {
        if (currentJobId > 0) database.printJobDao().updateJobStatus(currentJobId, "FAILED", msg)
        _errorMessage.postValue(msg)
    }

    fun clearError()        { _errorMessage.value = null }
    fun resetPrintSuccess() { _printSuccess.value = false }

    override fun onCleared() {
        super.onCleared()
        notificationManager.cancel()
    }
}