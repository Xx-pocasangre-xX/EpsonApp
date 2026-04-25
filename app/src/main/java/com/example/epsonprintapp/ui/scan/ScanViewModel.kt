package com.example.epsonprintapp.ui.scan

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
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
import com.example.epsonprintapp.scanner.ScanOptions
import com.example.epsonprintapp.scanner.ScanPaperSize
import com.example.epsonprintapp.scanner.ScanResult
import com.example.epsonprintapp.scanner.ScanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    companion object { private const val TAG = "ScanViewModel" }

    private val database            = AppDatabase.getInstance(application)
    private val esclClient          = EsclClient()
    private val notificationManager = AppNotificationManager(application)

    // ── State ──────────────────────────────────────────────────────────────────
    private val _isScanning    = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _statusMessage = MutableLiveData<String?>()
    val statusMessage: LiveData<String?> = _statusMessage

    private val _saveResult = MutableLiveData<String?>()
    val saveResult: LiveData<String?> = _saveResult

    /** Última página escaneada (para preview) */
    private val _scannedBitmap = MutableLiveData<Bitmap?>(null)
    val scannedBitmap: LiveData<Bitmap?> = _scannedBitmap

    /** Todas las páginas acumuladas */
    private val _scannedPages = MutableLiveData<List<Bitmap>>(emptyList())
    val scannedPages: LiveData<List<Bitmap>> = _scannedPages

    val isSaveSuccess = MutableLiveData(false)
    val errorMessage  = MutableLiveData<String?>(null)
    val savedFilePath = MutableLiveData<String?>(null)

    // Opciones — solo color vs B&N, el resto fijo
    private val _useColor = MutableLiveData(true)
    val useColor: LiveData<Boolean> = _useColor

    // Acumulación interna
    private val accumulatedPages = mutableListOf<Bitmap>()
    private val accumulatedBytes = mutableListOf<ByteArray>()
    private var currentJobId: Long = -1

    // ── Scan ───────────────────────────────────────────────────────────────────

    fun startScan(appendToExisting: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.postValue(true)
            val pageNum = if (appendToExisting) accumulatedPages.size + 1 else 1
            _statusMessage.postValue("🔍 Escaneando página $pageNum…")

            try {
                val printer = database.printerDao().getDefaultPrinter() ?: run {
                    _statusMessage.postValue("❌ No hay impresora configurada")
                    _isScanning.postValue(false)
                    return@launch
                }

                if (!appendToExisting) {
                    accumulatedPages.clear()
                    accumulatedBytes.clear()
                    _scannedPages.postValue(emptyList())
                    val scanJob = ScanJobEntity(
                        printerId  = printer.id,
                        resolution = 300,
                        colorMode  = if (_useColor.value == true) "COLOR" else "GRAYSCALE",
                        paperSize  = "A4",
                        status     = "PROCESSING"
                    )
                    currentJobId = database.scanJobDao().insertScanJob(scanJob)
                }

                val options = ScanOptions(
                    resolution = 300,
                    colorMode  = if (_useColor.value == true) ScanColorMode.COLOR else ScanColorMode.GRAYSCALE,
                    source     = ScanSource.FLATBED,
                    format     = ScanFormat.JPEG,
                    paperSize  = ScanPaperSize.A4
                )

                val result = esclClient.scan(printer.esclUrl, options)

                when (result) {
                    is ScanResult.Success -> {
                        Log.d(TAG, "Scan exitoso: ${result.data.size} bytes | tipo: ${result.mimeType}")

                        // FIX: Decodificación robusta del bitmap
                        val bmp = decodeBitmapRobust(result.data, result.mimeType)

                        if (bmp != null) {
                            accumulatedPages.add(bmp)
                            accumulatedBytes.add(result.data)
                            _scannedBitmap.postValue(bmp)
                            _scannedPages.postValue(accumulatedPages.toList())

                            database.scanJobDao().updateScanJobStatus(currentJobId, "SCANNED")
                            val total = accumulatedPages.size
                            _statusMessage.postValue(
                                "✅ Página $total escaneada · Toca '➕ Agregar' para otra página o 'Guardar'"
                            )
                        } else {
                            Log.e(TAG, "BitmapFactory falló al decodificar ${result.data.size} bytes")
                            // Guardar los bytes de todas formas para PDF
                            accumulatedBytes.add(result.data)
                            database.scanJobDao().updateScanJobStatus(currentJobId, "SCANNED")
                            _statusMessage.postValue(
                                "✅ Escaneo completado (${result.data.size / 1024}KB) — " +
                                        "la vista previa no está disponible, pero puedes guardar como PDF"
                            )
                        }
                    }
                    is ScanResult.Error -> {
                        database.scanJobDao().updateScanJobStatus(currentJobId, "FAILED", result.message)
                        _statusMessage.postValue("❌ ${result.message}")
                        notificationManager.notifyScanError(result.message, currentJobId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en scan: ${e.message}", e)
                _statusMessage.postValue("❌ Error: ${e.message}")
            } finally {
                _isScanning.postValue(false)
            }
        }
    }

    /**
     * Decodificación robusta de bitmap.
     * BitmapFactory.decodeByteArray() puede fallar silenciosamente si:
     * 1. Los bytes no son JPEG/PNG válidos
     * 2. Hay un header HTTP adicional en los bytes
     * 3. La imagen está en formato JFIF con metadatos extra
     *
     * Se intenta múltiples estrategias:
     * 1. Decodificación directa
     * 2. Buscar el inicio del JPEG (SOI marker 0xFF 0xD8) si hay bytes extra al inicio
     * 3. Decodificación con BitmapFactory.Options más tolerantes
     */
    private fun decodeBitmapRobust(bytes: ByteArray, mimeType: String): Bitmap? {
        if (bytes.size < 10) {
            Log.e(TAG, "Array demasiado pequeño: ${bytes.size} bytes")
            return null
        }

        // Intento 1: decodificación directa
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp != null) {
            Log.d(TAG, "Bitmap decodificado directamente: ${bmp.width}×${bmp.height}")
            return bmp
        }

        Log.w(TAG, "Decodificación directa falló, buscando SOI marker...")
        Log.d(TAG, "Primeros 20 bytes: ${bytes.take(20).joinToString(" ") { "%02X".format(it) }}")

        // Intento 2: buscar el SOI marker JPEG (FF D8)
        if (mimeType.contains("jpeg") || mimeType.contains("jpg")) {
            for (i in 0 until minOf(bytes.size - 1, 512)) {
                if (bytes[i] == 0xFF.toByte() && bytes[i+1] == 0xD8.toByte()) {
                    if (i > 0) Log.d(TAG, "SOI marker encontrado en posición $i, saltando $i bytes")
                    bmp = BitmapFactory.decodeByteArray(bytes, i, bytes.size - i)
                    if (bmp != null) {
                        Log.d(TAG, "Bitmap decodificado desde offset $i: ${bmp.width}×${bmp.height}")
                        return bmp
                    }
                    break
                }
            }
        }

        // Intento 3: buscar PNG header (89 50 4E 47)
        if (mimeType.contains("png")) {
            for (i in 0 until minOf(bytes.size - 3, 512)) {
                if (bytes[i] == 0x89.toByte() && bytes[i+1] == 0x50.toByte() &&
                    bytes[i+2] == 0x4E.toByte() && bytes[i+3] == 0x47.toByte()) {
                    bmp = BitmapFactory.decodeByteArray(bytes, i, bytes.size - i)
                    if (bmp != null) {
                        Log.d(TAG, "PNG decodificado desde offset $i: ${bmp.width}×${bmp.height}")
                        return bmp
                    }
                    break
                }
            }
        }

        // Intento 4: usar InputStream para mayor compatibilidad
        try {
            bmp = BitmapFactory.decodeStream(bytes.inputStream())
            if (bmp != null) {
                Log.d(TAG, "Bitmap decodificado via InputStream: ${bmp.width}×${bmp.height}")
                return bmp
            }
        } catch (e: Exception) {
            Log.e(TAG, "InputStream decode falló: ${e.message}")
        }

        Log.e(TAG, "Todos los intentos de decodificación fallaron para ${bytes.size} bytes")
        return null
    }

    fun scanNextPage() = startScan(appendToExisting = true)

    fun setColorMode(useColor: Boolean) { _useColor.value = useColor }

    fun clearPages() {
        accumulatedPages.clear()
        accumulatedBytes.clear()
        _scannedPages.postValue(emptyList())
        _scannedBitmap.postValue(null)
        _statusMessage.postValue("Páginas borradas — listo para nuevo escaneo")
    }

    // ── GUARDAR COMO IMAGEN(ES) ───────────────────────────────────────────────

    fun saveAsImages(context: Context) {
        if (accumulatedPages.isEmpty() && accumulatedBytes.isEmpty()) {
            _saveResult.postValue("No hay páginas escaneadas")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val timestamp  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                var savedCount = 0

                // Si tenemos bitmaps guardamos desde ahí; si no, desde los bytes raw
                val pagesToSave = if (accumulatedPages.isNotEmpty()) {
                    accumulatedPages.mapIndexed { i, bmp ->
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        out.toByteArray()
                    }
                } else {
                    accumulatedBytes
                }

                pagesToSave.forEachIndexed { index, imgBytes ->
                    val fileName = "SCAN_${timestamp}_p${index + 1}.jpg"
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EpsonScans")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    uri?.let {
                        resolver.openOutputStream(it)?.use { os -> os.write(imgBytes) }
                        cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(it, cv, null, null)
                        savedCount++
                    }
                }

                if (currentJobId > 0) {
                    database.scanJobDao().markScanCompleted(
                        currentJobId,
                        "Pictures/EpsonScans/SCAN_$timestamp",
                        pagesToSave.sumOf { it.size }.toLong()
                    )
                }
                notificationManager.notifyScanSuccess("SCAN_$timestamp ($savedCount imágenes)", currentJobId)
                _saveResult.postValue("✅ $savedCount ${if(savedCount==1)"imagen guardada" else "imágenes guardadas"} en Galería")
                isSaveSuccess.postValue(true)
            } catch (e: Exception) {
                _saveResult.postValue("❌ Error al guardar: ${e.message}")
            }
        }
    }

    // ── GUARDAR COMO PDF ──────────────────────────────────────────────────────

    fun savePdfToUri(uri: Uri, contentResolver: android.content.ContentResolver) {
        if (accumulatedPages.isEmpty() && accumulatedBytes.isEmpty()) {
            _saveResult.postValue("No hay páginas para guardar como PDF")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pdfBytes = buildMultiPagePdf()
                contentResolver.openOutputStream(uri)?.use { it.write(pdfBytes) }
                val pageCount = maxOf(accumulatedPages.size, accumulatedBytes.size)
                if (currentJobId > 0)
                    database.scanJobDao().markScanCompleted(currentJobId, uri.toString(), pdfBytes.size.toLong())
                notificationManager.notifyScanSuccess("${uri.lastPathSegment} ($pageCount páginas)", currentJobId)
                _saveResult.postValue("✅ PDF guardado · $pageCount ${if(pageCount==1)"página" else "páginas"}")
                isSaveSuccess.postValue(true)
            } catch (e: Exception) {
                _saveResult.postValue("❌ Error al guardar PDF: ${e.message}")
            }
        }
    }

    private fun buildMultiPagePdf(): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            val writer   = com.itextpdf.kernel.pdf.PdfWriter(out)
            val pdfDoc   = com.itextpdf.kernel.pdf.PdfDocument(writer)
            val document = com.itextpdf.layout.Document(pdfDoc)

            // Usar bitmaps si existen, sino los bytes raw
            val pages = if (accumulatedPages.isNotEmpty()) {
                accumulatedPages.map { bmp ->
                    val o = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, o)
                    o.toByteArray()
                }
            } else {
                accumulatedBytes
            }

            pages.forEachIndexed { index, imgBytes ->
                val imgData  = com.itextpdf.io.image.ImageDataFactory.create(imgBytes)
                val pdfImage = com.itextpdf.layout.element.Image(imgData)
                val pageW    = com.itextpdf.kernel.geom.PageSize.A4.width
                val pageH    = com.itextpdf.kernel.geom.PageSize.A4.height
                pdfImage.scaleToFit(pageW - 40f, pageH - 40f)
                pdfImage.setMarginLeft(20f).setMarginTop(20f)
                if (index > 0) document.add(
                    com.itextpdf.layout.element.AreaBreak(
                        com.itextpdf.layout.properties.AreaBreakType.NEXT_PAGE))
                document.add(pdfImage)
            }
            document.close()
        } catch (e: Exception) {
            Log.e("ScanViewModel", "Error creando PDF: ${e.message}")
            val fallback = ByteArrayOutputStream()
            (accumulatedPages.lastOrNull())?.compress(Bitmap.CompressFormat.JPEG, 90, fallback)
            return fallback.toByteArray()
        }
        return out.toByteArray()
    }

    fun clearError()         { errorMessage.value = null }
    fun resetSaveSuccess()   { isSaveSuccess.value = false }
    fun clearStatusMessage() { _statusMessage.value = null }
}