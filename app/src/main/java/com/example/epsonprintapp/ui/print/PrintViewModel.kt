package com.example.epsonprintapp.ui.print

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.example.epsonprintapp.appContainer
import com.example.epsonprintapp.database.entities.PrintJobEntity
import com.example.epsonprintapp.printer.ColorMode
import com.example.epsonprintapp.printer.DuplexMode
import com.example.epsonprintapp.printer.PaperSize
import com.example.epsonprintapp.printer.PrintOptions
import com.example.epsonprintapp.printer.PrintQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * PrintViewModel — Flujo de impresión.
 *
 * Pensado para la Epson L3560 (compatible con otras impresoras IPP):
 * - La L3560 no rasteriza PDF: los PDF se convierten a JPEG página por página.
 * - PNG se convierte a JPEG (mejor compatibilidad con el firmware Epson).
 * - El tamaño de papel por defecto es A4, pero el papel real cargado
 *   (media-ready) que reporta la impresora siempre tiene prioridad.
 *
 * Notas de diseño:
 * - NO recibe Context de la Activity: usa el Application context propio
 *   (un Context de Activity retenido durante una impresión de minutos
 *   fugaría toda la jerarquía de vistas al rotar).
 * - Los documentos se envían en streaming (proveedor de InputStream que
 *   se reabre en cada reintento) — nunca se cargan completos en RAM.
 */
class PrintViewModel(application: Application) : AndroidViewModel(application) {

    private val container           = application.appContainer
    private val database            = container.database
    private val repository          = container.printerRepository
    private val ippClient           = container.ippClient
    private val notificationManager = container.notificationManager

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

    fun onFileSelected(uri: Uri) {
        val app = getApplication<Application>()
        _selectedFileUri.value  = uri
        _selectedFileName.value = getFileName(uri)
        val mime = app.contentResolver.getType(uri)
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

    fun startPrinting() {
        val uri = _selectedFileUri.value ?: run {
            _errorMessage.value = "No hay archivo seleccionado"
            return
        }
        if (_isPrinting.value == true) return

        val app = getApplication<Application>()

        viewModelScope.launch(Dispatchers.IO) {
            _isPrinting.postValue(true)
            _printProgress.postValue(5)

            try {
                // 1. Validar tamaño de archivo
                val fileSize = getFileSize(uri)
                if (fileSize <= 0L) {
                    _errorMessage.postValue("El archivo está vacío o no se puede leer")
                    return@launch
                }
                if (fileSize > AppConstants.MAX_FILE_SIZE_BYTES) {
                    val mb = fileSize / (1024 * 1024)
                    _errorMessage.postValue(
                        "Archivo demasiado grande: $mb MB\nMáximo permitido: ${AppConstants.MAX_FILE_SIZE_MB} MB")
                    return@launch
                }

                // 2. Obtener impresora
                val printer = repository.getDefaultPrinter() ?: run {
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
                val userOpts  = _printOptions.value ?: PrintOptions()
                val finalOpts = if (detectedPaper != null && userOpts.paperSize == PrintOptions().paperSize)
                    userOpts.copy(paperSize = detectedPaper) else userOpts

                val mimeType = normalizeMime(app.contentResolver.getType(uri) ?: guessMime(uri))
                val fileName = _selectedFileName.value ?: "documento"

                // 5. Registrar en DB
                val job = PrintJobEntity(
                    printerId  = printer.id,
                    fileName   = fileName,
                    mimeType   = mimeType,
                    fileSize   = fileSize,
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
                    sendPdfAsImages(uri, printer.ippUrl, finalOpts, fileName)
                } else {
                    sendImageFile(uri, mimeType, printer.ippUrl, finalOpts, fileName)
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
     * (La L3560 no acepta PDF directamente.)
     * Cada bitmap se libera en el finally — nunca hay más de uno en memoria.
     */
    private suspend fun sendPdfAsImages(
        uri:      Uri,
        url:      String,
        options:  PrintOptions,
        fileName: String
    ) {
        val app = getApplication<Application>()
        var pfd:      ParcelFileDescriptor? = null
        var renderer: PdfRenderer?          = null

        try {
            pfd = app.contentResolver.openFileDescriptor(uri, "r")
                ?: run { handleError("No se pudo abrir el PDF"); return }

            renderer      = PdfRenderer(pfd)
            val pageCount = renderer.pageCount

            if (pageCount == 0) { handleError("El PDF no tiene páginas"); return }

            _printProgress.postValue(45)

            val dpi = AppConstants.PDF_RASTER_DPI
            val (targetW, targetH) = when (options.paperSize) {
                PaperSize.A4        -> (8.27 * dpi).toInt()  to (11.69 * dpi).toInt()
                PaperSize.LETTER    -> (8.5  * dpi).toInt()  to (11.0  * dpi).toInt()
                PaperSize.LEGAL     -> (8.5  * dpi).toInt()  to (14.0  * dpi).toInt()
                PaperSize.A5        -> (5.83 * dpi).toInt()  to (8.27  * dpi).toInt()
                PaperSize.A3        -> (11.69 * dpi).toInt() to (16.54 * dpi).toInt()
                PaperSize.PHOTO_4X6 -> (4    * dpi).toInt()  to (6     * dpi).toInt()
            }

            for (pageIndex in 0 until pageCount) {
                _printProgress.postValue(45 + (pageIndex * 45 / pageCount))

                var bitmap: Bitmap? = null
                val page            = renderer.openPage(pageIndex)

                try {
                    val aspect       = page.width.toFloat() / page.height.toFloat()
                    val targetAspect = targetW.toFloat() / targetH.toFloat()
                    val (rW, rH)     = if (aspect > targetAspect)
                        targetW to (targetW / aspect).toInt()
                    else
                        (targetH * aspect).toInt() to targetH

                    // Limitar resolución para evitar OOM
                    val maxPixels = 2048 * 2048
                    val pixels    = rW * rH
                    val (finalW, finalH) = if (pixels > maxPixels) {
                        val scale = sqrt(maxPixels.toDouble() / pixels)
                        (rW * scale).toInt() to (rH * scale).toInt()
                    } else rW to rH

                    bitmap = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val jpegBytes = bitmapToJpeg(bitmap)

                    val result = ippClient.printDocument(url, "image/jpeg", options) {
                        jpegBytes.inputStream()
                    }

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
     * Envía imagen en streaming directo desde el ContentResolver.
     * PNG se convierte a JPEG (la L3560 a veces rechaza PNG con HTTP 500).
     */
    private suspend fun sendImageFile(
        uri:      Uri,
        mimeType: String,
        url:      String,
        options:  PrintOptions,
        fileName: String
    ) {
        val app = getApplication<Application>()
        _printProgress.postValue(50)

        // Proveedor de stream: se reabre en cada reintento del IppClient,
        // sin retener el documento en memoria entre intentos.
        val (finalMime, source) = if (mimeType == "image/png") {
            val jpegBytes = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { convertPngToJpeg(it.readBytes()) }
            } ?: run { handleError("No se pudo abrir el archivo seleccionado"); return }
            "image/jpeg" to { jpegBytes.inputStream() }
        } else {
            mimeType to { app.contentResolver.openInputStream(uri) }
        }

        _printProgress.postValue(65)

        val result = ippClient.printDocument(url, finalMime, options, source)
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
    }

    // ── Utilidades ─────────────────────────────────────────────────────────────

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, AppConstants.JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun convertPngToJpeg(pngBytes: ByteArray): ByteArray {
        val original = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        val bgBitmap = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        Canvas(bgBitmap).apply {
            drawColor(Color.WHITE)   // fondo blanco para PNG con transparencia
            drawBitmap(original, 0f, 0f, null)
        }
        original.recycle()
        val out = ByteArrayOutputStream()
        bgBitmap.compress(Bitmap.CompressFormat.JPEG, AppConstants.JPEG_QUALITY, out)
        bgBitmap.recycle()
        return out.toByteArray()
    }

    /** La L3560 (EcoTank) usa A4 como papel estándar; las HP de la región, Letter. */
    private fun detectPaper(model: String?): PaperSize? {
        val m = model?.lowercase() ?: return null
        return when {
            m.contains("hp") || m.contains("smart tank") ||
                    m.contains("deskjet") || m.contains("officejet") -> PaperSize.LETTER
            m.contains("epson") || m.contains("ecotank")             -> PaperSize.A4
            else                                                     -> null
        }
    }

    private fun getFileSize(uri: Uri): Long {
        return getApplication<Application>().contentResolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }

    private fun getFileName(uri: Uri): String {
        var name = "documento"
        getApplication<Application>().contentResolver
            .query(uri, null, null, null, null)?.use { cursor ->
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
            "pdf"         -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png"         -> "image/png"
            else          -> "application/pdf"
        }
    }

    /** "image/jpg" no es un MIME real: el firmware Epson espera "image/jpeg". */
    private fun normalizeMime(mime: String): String =
        if (mime == "image/jpg") "image/jpeg" else mime

    private fun isMimeSupported(mime: String) =
        normalizeMime(mime) in listOf("application/pdf", "image/jpeg", "image/png")

    private suspend fun handleError(msg: String) {
        if (currentJobId > 0) database.printJobDao().updateJobStatus(currentJobId, "FAILED", msg)
        _errorMessage.postValue(msg)
    }

    fun clearError()        { _errorMessage.value = null }
    fun resetPrintSuccess() { _printSuccess.value = false }
}
