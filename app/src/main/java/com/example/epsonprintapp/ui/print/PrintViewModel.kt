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

/**
 * PrintViewModel - Lógica de impresión
 *
 * FLUJO DE IMPRESIÓN COMPLETO:
 * ============================
 * 1. Usuario selecciona archivo (PDF/imagen via SAF)
 * 2. Se muestra preview del archivo
 * 3. Usuario configura opciones (copias, color, papel)
 * 4. Se valida contra la impresora (Validate-Job IPP)
 * 5. Se envía el documento (Print-Job IPP)
 * 6. Se guarda en historial (Room DB)
 * 7. Se muestra notificación de resultado
 *
 * Storage Access Framework (SAF):
 * ================================
 * SAF es el sistema moderno de Android para acceder a archivos sin
 * permisos de almacenamiento legacy.
 *
 * El usuario selecciona el archivo en el picker del sistema.
 * Recibimos un Uri → lo convertimos a InputStream → lo enviamos a la impresora.
 * El Uri es temporal y solo válido durante la sesión.
 *
 * TIPOS MIME SOPORTADOS:
 * - application/pdf → PDF (enviar directamente a la impresora)
 * - image/jpeg → JPEG (enviar directamente o convertir a PDF)
 * - image/png → PNG (convertir a JPEG antes de imprimir)
 */
class PrintViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val ippClient = IppClient(application)
    private val notificationManager = AppNotificationManager(application)

    // ===== ESTADO DE UI =====
    private val _isPrinting = MutableLiveData(false)
    val isPrinting: LiveData<Boolean> = _isPrinting

    private val _printProgress = MutableLiveData(0)
    val printProgress: LiveData<Int> = _printProgress

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    private val _printSuccess = MutableLiveData(false)
    val printSuccess: LiveData<Boolean> = _printSuccess

    // ===== OPCIONES DE IMPRESIÓN =====
    private val _printOptions = MutableLiveData(PrintOptions())
    val printOptions: LiveData<PrintOptions> = _printOptions

    // ===== ARCHIVO SELECCIONADO =====
    private val _selectedFileUri = MutableLiveData<Uri?>(null)
    val selectedFileUri: LiveData<Uri?> = _selectedFileUri

    private val _selectedFileName = MutableLiveData<String?>(null)
    val selectedFileName: LiveData<String?> = _selectedFileName

    // ===== JOB ACTUAL =====
    private var currentJobId: Long = -1

    // =========================================================================
    // SELECCIÓN DE ARCHIVO
    // =========================================================================

    /**
     * Procesar archivo seleccionado via SAF
     *
     * @param uri Uri del archivo seleccionado por el usuario
     * @param context Context para resolver el Uri a un InputStream
     */
    fun onFileSelected(uri: Uri, context: Context) {
        _selectedFileUri.value = uri

        // Obtener nombre del archivo desde el Uri
        val fileName = getFileNameFromUri(uri, context)
        _selectedFileName.value = fileName

        // Validar que sea un tipo soportado
        val mimeType = context.contentResolver.getType(uri)
        if (mimeType != null && !isMimeTypeSupported(mimeType)) {
            _errorMessage.value = "Formato no soportado: $mimeType\n" +
                    "Soportados: PDF, JPEG, PNG"
        }
    }

    /**
     * Actualizar opciones de impresión
     */
    fun updateOptions(
        copies: Int? = null,
        colorMode: ColorMode? = null,
        duplex: DuplexMode? = null,
        paperSize: PaperSize? = null,
        quality: PrintQuality? = null
    ) {
        val current = _printOptions.value ?: PrintOptions()
        _printOptions.value = current.copy(
            copies = copies ?: current.copies,
            colorMode = colorMode ?: current.colorMode,
            duplex = duplex ?: current.duplex,
            paperSize = paperSize ?: current.paperSize,
            quality = quality ?: current.quality
        )
    }

    // =========================================================================
    // IMPRESIÓN
    // =========================================================================

    /**
     * Iniciar proceso de impresión
     *
     * @param context Context para resolver el Uri
     */
    fun startPrinting(context: Context) {
        val uri = _selectedFileUri.value ?: run {
            _errorMessage.value = "No hay archivo seleccionado"
            return
        }

        val options = _printOptions.value ?: PrintOptions()

        viewModelScope.launch(Dispatchers.IO) {
            _isPrinting.postValue(true)
            _printProgress.postValue(0)

            try {
                // Obtener la impresora predeterminada
                val printer = database.printerDao().getDefaultPrinter()
                    ?: run {
                        _errorMessage.postValue("No hay impresora configurada")
                        _isPrinting.postValue(false)
                        return@launch
                    }

                // Obtener tipo MIME del archivo
                val mimeType = context.contentResolver.getType(uri)
                    ?: detectMimeTypeFromUri(uri, context)

                val fileName = _selectedFileName.value ?: "documento"

                // Crear registro en DB (estado PENDING)
                val printJob = PrintJobEntity(
                    printerId = printer.id,
                    fileName = fileName,
                    mimeType = mimeType,
                    status = "PROCESSING",
                    copies = options.copies,
                    colorMode = options.colorMode.name,
                    paperSize = options.paperSize.name,
                    isDuplex = options.duplex != DuplexMode.ONE_SIDED
                )
                currentJobId = database.printJobDao().insertPrintJob(printJob)
                _printProgress.postValue(20)

                // Abrir stream del archivo
                val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                    ?: run {
                        handlePrintError(currentJobId, "No se pudo abrir el archivo")
                        return@launch
                    }

                _printProgress.postValue(40)

                // Para PNG, convertir a JPEG (mejor compatibilidad con impresoras)
                val (finalStream, finalMimeType) = if (mimeType == "image/png") {
                    Pair(convertPngToJpeg(inputStream, context), "image/jpeg")
                } else {
                    Pair(inputStream, mimeType)
                }

                _printProgress.postValue(60)

                // Enviar trabajo de impresión via IPP
                val result = ippClient.printDocument(
                    printerUrl = printer.ippUrl,
                    documentStream = finalStream,
                    mimeType = finalMimeType,
                    printOptions = options
                )

                finalStream.close()
                _printProgress.postValue(90)

                if (result != null && result.success) {
                    // Impresión exitosa
                    database.printJobDao().markJobCompleted(currentJobId)
                    notificationManager.notifyPrintSuccess(fileName, currentJobId)
                    _printSuccess.postValue(true)
                    _printProgress.postValue(100)
                } else {
                    // Error de impresión
                    val errorMsg = result?.errorMessage ?: "Error desconocido al imprimir"
                    handlePrintError(currentJobId, errorMsg)
                    notificationManager.notifyPrintError(errorMsg, fileName, currentJobId)
                }

            } catch (e: Exception) {
                handlePrintError(currentJobId, e.message ?: "Error inesperado")
            } finally {
                _isPrinting.postValue(false)
            }
        }
    }

    /**
     * Manejar error de impresión
     */
    private suspend fun handlePrintError(jobId: Long, error: String) {
        if (jobId > 0) {
            database.printJobDao().updateJobStatus(jobId, "FAILED", error)
        }
        _errorMessage.postValue(error)
    }

    // =========================================================================
    // UTILIDADES
    // =========================================================================

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
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }

    private fun isMimeTypeSupported(mimeType: String): Boolean {
        return mimeType in listOf(
            "application/pdf",
            "image/jpeg",
            "image/jpg",
            "image/png"
        )
    }

    /**
     * Convertir imagen PNG a JPEG para compatibilidad
     *
     * Algunas impresoras manejan JPEG mejor que PNG.
     * La conversión es simple: decodificar → comprimir como JPEG.
     */
    private fun convertPngToJpeg(inputStream: InputStream, context: Context): InputStream {
        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, output)
        bitmap.recycle()
        return output.toByteArray().inputStream()
    }

    fun clearError() { _errorMessage.value = null }
    fun resetPrintSuccess() { _printSuccess.value = false }
}
