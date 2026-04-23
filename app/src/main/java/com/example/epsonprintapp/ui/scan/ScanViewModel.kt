package com.example.epsonprintapp.ui.scan

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.database.entities.ScanJobEntity
import com.example.epsonprintapp.notifications.AppNotificationManager
import com.example.epsonprintapp.scanner.EsclClient
import com.example.epsonprintapp.scanner.ScanColorMode
import com.example.epsonprintapp.scanner.ScanFormat
import com.example.epsonprintapp.scanner.ScanIntent
import com.example.epsonprintapp.scanner.ScanOptions
import com.example.epsonprintapp.scanner.ScanPaperSize
import com.example.epsonprintapp.scanner.ScanResult
import com.example.epsonprintapp.scanner.ScanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ScanViewModel - Lógica de escaneo via eSCL
 *
 * FLUJO DE ESCANEO:
 * =================
 * 1. Usuario configura opciones (resolución, color, fuente)
 * 2. Toca "ESCANEAR"
 * 3. Se crea trabajo eSCL (POST /eSCL/ScanJobs)
 * 4. Se descarga el resultado (GET /eSCL/ScanJobs/{id}/NextDocument)
 * 5. Se muestra el resultado en el editor
 * 6. Usuario puede: guardar, editar, convertir a PDF
 *
 * EDICIÓN BÁSICA:
 * ===============
 * La imagen escaneada puede editarse antes de guardar:
 * - Recorte con UCrop
 * - Brillo/contraste con ColorMatrix
 * - Conversión a PDF con PDFDocument
 *
 * GUARDADO:
 * =========
 * - PDF: via SAF (Storage Access Framework) en carpeta elegida por usuario
 * - Imagen: en MediaStore (galería de fotos) automáticamente
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val esclClient = EsclClient()
    private val notificationManager = AppNotificationManager(application)

    // ===== ESTADO =====
    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _scanProgress = MutableLiveData(0)
    val scanProgress: LiveData<Int> = _scanProgress

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    private val _scannedBitmap = MutableLiveData<Bitmap?>(null)
    val scannedBitmap: LiveData<Bitmap?> = _scannedBitmap

    private val _scannedBytes = MutableLiveData<ByteArray?>(null)

    private val _scanMimeType = MutableLiveData<String>("image/jpeg")

    private val _savedFilePath = MutableLiveData<String?>(null)
    val savedFilePath: LiveData<String?> = _savedFilePath

    private val _isSaveSuccess = MutableLiveData(false)
    val isSaveSuccess: LiveData<Boolean> = _isSaveSuccess

    // ===== OPCIONES =====
    private val _scanOptions = MutableLiveData(
        ScanOptions(
            resolution = 300,
            colorMode = ScanColorMode.COLOR,
            source = ScanSource.FLATBED,
            format = ScanFormat.JPEG,
            paperSize = ScanPaperSize.A4
        )
    )
    val scanOptions: LiveData<ScanOptions> = _scanOptions

    private var currentJobId: Long = -1

    // =========================================================================
    // ESCANEO
    // =========================================================================

    /**
     * Iniciar escaneo
     */
    fun startScan() {
        val options = _scanOptions.value ?: ScanOptions()

        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.postValue(true)
            _scanProgress.postValue(10)

            try {
                val printer = database.printerDao().getDefaultPrinter()
                    ?: run {
                        _errorMessage.postValue("No hay impresora/escáner configurado")
                        _isScanning.postValue(false)
                        return@launch
                    }

                // Crear registro en DB
                val scanJob = ScanJobEntity(
                    printerId = printer.id,
                    resolution = options.resolution,
                    colorMode = options.colorMode.name,
                    paperSize = options.paperSize.name,
                    status = "PROCESSING"
                )
                currentJobId = database.scanJobDao().insertScanJob(scanJob)
                _scanProgress.postValue(20)

                // Ejecutar escaneo eSCL
                val result = esclClient.scan(printer.esclUrl, options)
                _scanProgress.postValue(80)

                when (result) {
                    is ScanResult.Success -> {
                        _scannedBytes.postValue(result.data)
                        _scanMimeType.postValue(result.mimeType)

                        // Decodificar a Bitmap para mostrar preview
                        if (result.mimeType.startsWith("image/")) {
                            val bitmap = BitmapFactory.decodeByteArray(result.data, 0, result.data.size)
                            _scannedBitmap.postValue(bitmap)
                        }

                        database.scanJobDao().updateScanJobStatus(currentJobId, "SCANNED")
                        _scanProgress.postValue(100)
                    }

                    is ScanResult.Error -> {
                        database.scanJobDao().updateScanJobStatus(currentJobId, "FAILED", result.message)
                        _errorMessage.postValue(result.message)
                        notificationManager.notifyScanError(result.message, currentJobId)
                    }
                }

            } catch (e: Exception) {
                _errorMessage.postValue("Error de escaneo: ${e.message}")
            } finally {
                _isScanning.postValue(false)
            }
        }
    }

    // =========================================================================
    // EDICIÓN
    // =========================================================================

    /**
     * Ajustar brillo y contraste del escaneo
     *
     * Usa ColorMatrix de Android para manipular los píxeles.
     * Brillo: -100 a +100 (0 = sin cambio)
     * Contraste: 0.1 a 3.0 (1.0 = sin cambio)
     */
    fun adjustBrightnessContrast(brightness: Float, contrast: Float) {
        val originalBitmap = _scannedBitmap.value ?: return

        viewModelScope.launch(Dispatchers.Default) {
            val colorMatrix = android.graphics.ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            val paint = android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
            }

            val adjustedBitmap = Bitmap.createBitmap(
                originalBitmap.width,
                originalBitmap.height,
                Bitmap.Config.ARGB_8888
            )

            val canvas = android.graphics.Canvas(adjustedBitmap)
            canvas.drawBitmap(originalBitmap, 0f, 0f, paint)

            _scannedBitmap.postValue(adjustedBitmap)
        }
    }

    /**
     * Convertir imagen escaneada a PDF
     *
     * Crea un PDF de una página con la imagen escaneada.
     * Útil para archivar documentos escaneados en formato PDF.
     */
    fun convertToPdf(context: Context) {
        val bitmap = _scannedBitmap.value ?: run {
            _errorMessage.value = "No hay imagen para convertir"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pdfDocument = android.graphics.pdf.PdfDocument()

                // Crear página A4 a 72 DPI (estándar PDF)
                val pageWidth = 595   // A4 a 72 DPI
                val pageHeight = 842  // A4 a 72 DPI

                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                    pageWidth, pageHeight, 1
                ).create()

                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // Escalar la imagen para que quepa en la página
                val scale = minOf(
                    pageWidth.toFloat() / bitmap.width,
                    pageHeight.toFloat() / bitmap.height
                )
                val scaledWidth = (bitmap.width * scale).toInt()
                val scaledHeight = (bitmap.height * scale).toInt()
                val left = (pageWidth - scaledWidth) / 2f
                val top = (pageHeight - scaledHeight) / 2f

                val destRect = android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight)
                canvas.drawBitmap(bitmap, null, destRect, null)

                pdfDocument.finishPage(page)

                // Guardar PDF en bytes
                val outputStream = java.io.ByteArrayOutputStream()
                pdfDocument.writeTo(outputStream)
                pdfDocument.close()

                _scannedBytes.postValue(outputStream.toByteArray())
                _scanMimeType.postValue("application/pdf")

            } catch (e: Exception) {
                _errorMessage.postValue("Error al convertir a PDF: ${e.message}")
            }
        }
    }

    // =========================================================================
    // GUARDADO
    // =========================================================================

    /**
     * Guardar imagen escaneada en la galería de fotos
     *
     * Usa MediaStore API (Android 10+) para guardar sin permisos
     * de almacenamiento legacy.
     *
     * La imagen aparecerá en la galería del dispositivo.
     */
    fun saveToGallery(context: Context) {
        val bitmap = _scannedBitmap.value ?: run {
            _errorMessage.value = "No hay imagen para guardar"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "SCAN_$timestamp.jpg"

                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EpsonScans")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { imageUri ->
                    resolver.openOutputStream(imageUri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                    }

                    // Marcar como disponible
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)

                    _savedFilePath.postValue("Galería: $fileName")

                    // Actualizar DB
                    if (currentJobId > 0) {
                        database.scanJobDao().markScanCompleted(
                            currentJobId,
                            imageUri.toString(),
                            bitmap.byteCount.toLong()
                        )
                    }

                    notificationManager.notifyScanSuccess(fileName, currentJobId)
                    _isSaveSuccess.postValue(true)
                }

            } catch (e: Exception) {
                _errorMessage.postValue("Error al guardar en galería: ${e.message}")
            }
        }
    }

    /**
     * Guardar PDF en ubicación elegida por el usuario (SAF)
     *
     * @param uri Uri de destino elegido por el usuario via SAF
     * @param context Context para acceder al ContentResolver
     */
    fun savePdfToUri(uri: Uri, context: Context) {
        val pdfBytes = _scannedBytes.value ?: run {
            _errorMessage.value = "No hay documento para guardar"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(pdfBytes)
                }

                val fileName = uri.lastPathSegment ?: "escaneo.pdf"

                if (currentJobId > 0) {
                    database.scanJobDao().markScanCompleted(
                        currentJobId,
                        uri.toString(),
                        pdfBytes.size.toLong()
                    )
                }

                notificationManager.notifyScanSuccess(fileName, currentJobId)
                _isSaveSuccess.postValue(true)
                _savedFilePath.postValue(fileName)

            } catch (e: Exception) {
                _errorMessage.postValue("Error al guardar PDF: ${e.message}")
            }
        }
    }

    fun updateResolution(dpi: Int) {
        _scanOptions.value = _scanOptions.value?.copy(resolution = dpi)
    }

    fun updateColorMode(mode: ScanColorMode) {
        _scanOptions.value = _scanOptions.value?.copy(colorMode = mode)
    }

    fun updateFormat(format: ScanFormat) {
        _scanOptions.value = _scanOptions.value?.copy(format = format)
    }

    fun updateSource(source: ScanSource) {
        _scanOptions.value = _scanOptions.value?.copy(source = source)
    }

    fun clearError() { _errorMessage.value = null }
    fun resetSaveSuccess() { _isSaveSuccess.value = false }
}
