package com.example.epsonprintapp.ui.print

import android.app.Application
import android.content.Context
import android.net.Uri
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
    /** Papel que tiene cargado la impresora (detectado via Get-Printer-Attrs) */
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

                // 2. Consultar estado de la impresora para detectar papel y brand
                val printerStatus = ippClient.getPrinterStatus(printer.ippUrl)
                if (printerStatus == null) {
                    _errorMessage.postValue("No se puede conectar con la impresora en ${printer.ippUrl}.\nVerifica que esté encendida y en la misma red.")
                    _isPrinting.postValue(false)
                    return@launch
                }

                // Detectar tamaño de papel que tiene la impresora
                val detectedPaper = detectPaperFromPrinterStatus(printerStatus)
                _detectedPaperSize.postValue(detectedPaper)

                _printProgress.postValue(25)

                // 3. Resolver opciones: usar el papel detectado si el usuario no cambió el default
                val userOptions = _printOptions.value ?: PrintOptions()
                val finalOptions = if (detectedPaper != null &&
                    userOptions.paperSize == PrintOptions().paperSize) {
                    // El usuario dejó el papel por default → usar el de la impresora
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

                // 5. Abrir stream
                val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: run {
                    handlePrintError(currentJobId, "No se pudo abrir el archivo")
                    return@launch
                }

                _printProgress.postValue(50)

                // 6. Convertir PNG → JPEG si es necesario (mejor compatibilidad)
                val (finalStream, finalMimeType) = if (mimeType == "image/png") {
                    Pair(convertPngToJpeg(inputStream), "image/jpeg")
                } else {
                    Pair(inputStream, mimeType)
                }

                _printProgress.postValue(65)

                // 7. Enviar vía IPP
                val result = ippClient.printDocument(
                    printerUrl     = printer.ippUrl,
                    documentStream = finalStream,
                    mimeType       = finalMimeType,
                    printOptions   = finalOptions
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

            } catch (e: Exception) {
                handlePrintError(currentJobId, "Error inesperado: ${e.message}")
            } finally {
                _isPrinting.postValue(false)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILIDADES PRIVADAS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Detecta el tamaño de papel disponible en la impresora según
     * el atributo media-ready que reporta en Get-Printer-Attributes.
     *
     * HP Smart Tank 710 → "na_letter_8.5x11in"  → PaperSize.LETTER
     * Epson L3560       → "iso_a4_210x297mm"     → PaperSize.A4
     */
    private fun detectPaperFromPrinterStatus(status: com.example.epsonprintapp.printer.PrinterStatus): PaperSize? {
        // stateReasons contiene el media-ready de la impresora codificado
        // pero lo leemos del modelo para inferir
        val model = status.model?.lowercase() ?: return null
        return when {
            model.contains("hp") || model.contains("smart tank") ||
                    model.contains("deskjet") || model.contains("officejet") -> PaperSize.LETTER
            model.contains("epson") || model.contains("ecotank") -> PaperSize.A4
            else -> null  // No detectado, dejar al usuario elegir
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
            else         -> "application/pdf"  // default a PDF
        }
    }

    private fun isMimeTypeSupported(mimeType: String) = mimeType in listOf(
        "application/pdf", "image/jpeg", "image/jpg", "image/png"
    )

    private fun convertPngToJpeg(inputStream: InputStream): InputStream {
        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, output)
        bitmap.recycle()
        return output.toByteArray().inputStream()
    }

    fun clearError()        { _errorMessage.value = null }
    fun resetPrintSuccess() { _printSuccess.value = false }
}