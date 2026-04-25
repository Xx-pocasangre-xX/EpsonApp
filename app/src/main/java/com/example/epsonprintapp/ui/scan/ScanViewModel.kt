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

    private val _isScanning    = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _statusMessage = MutableLiveData<String?>("Listo para escanear")
    val statusMessage: LiveData<String?> = _statusMessage

    private val _saveResult = MutableLiveData<String?>()
    val saveResult: LiveData<String?> = _saveResult

    private val _scannedBitmap = MutableLiveData<Bitmap?>(null)
    val scannedBitmap: LiveData<Bitmap?> = _scannedBitmap

    private val _scannedPages = MutableLiveData<List<Bitmap>>(emptyList())
    val scannedPages: LiveData<List<Bitmap>> = _scannedPages

    /** TRUE cuando hay bytes reales disponibles para guardar */
    private val _hasScannedData = MutableLiveData(false)
    val hasScannedData: LiveData<Boolean> = _hasScannedData

    private val _pageCount = MutableLiveData(0)
    val pageCount: LiveData<Int> = _pageCount

    val isSaveSuccess = MutableLiveData(false)
    val errorMessage  = MutableLiveData<String?>(null)

    private val _useColor = MutableLiveData(true)
    val useColor: LiveData<Boolean> = _useColor

    // Datos acumulados de la sesión
    private val accumulatedBitmaps = mutableListOf<Bitmap>()
    private val accumulatedBytes   = mutableListOf<ByteArray>()
    private var currentJobId: Long = -1

    // ── ESCANEAR ───────────────────────────────────────────────────────────────

    fun startScan(appendToExisting: Boolean = false) {
        if (_isScanning.value == true) {
            Log.w(TAG, "Escaneo ya en curso, ignorando")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.postValue(true)

            try {
                val printer = database.printerDao().getDefaultPrinter() ?: run {
                    _statusMessage.postValue("❌ No hay impresora configurada. Ve al Dashboard.")
                    _isScanning.postValue(false)
                    return@launch
                }

                // Nueva sesión: limpiar estado y crear job en DB
                if (!appendToExisting) {
                    clearInternalState()
                    currentJobId = database.scanJobDao().insertScanJob(
                        ScanJobEntity(
                            printerId  = printer.id,
                            resolution = 300,
                            colorMode  = if (_useColor.value == true) "COLOR" else "GRAYSCALE",
                            paperSize  = "A4",
                            status     = "PROCESSING"
                        )
                    )
                    Log.d(TAG, "Nueva sesión. Job ID: $currentJobId")
                } else {
                    // Página adicional: reutilizar job o crear uno si no existe
                    if (currentJobId < 0) {
                        currentJobId = database.scanJobDao().insertScanJob(
                            ScanJobEntity(
                                printerId  = printer.id,
                                resolution = 300,
                                colorMode  = if (_useColor.value == true) "COLOR" else "GRAYSCALE",
                                paperSize  = "A4",
                                status     = "PROCESSING"
                            )
                        )
                    }
                }

                val pageNum = accumulatedBytes.size + 1
                _statusMessage.postValue("🔍 Escaneando página $pageNum…")

                val result = esclClient.scan(
                    printer.esclUrl,
                    ScanOptions(
                        resolution = 300,
                        colorMode  = if (_useColor.value == true) ScanColorMode.COLOR else ScanColorMode.GRAYSCALE,
                        source     = ScanSource.FLATBED,
                        format     = ScanFormat.JPEG,
                        paperSize  = ScanPaperSize.A4,
                        intent     = com.example.epsonprintapp.scanner.ScanIntent.DOCUMENT
                    )
                )

                when (result) {
                    is ScanResult.Success -> handleScanSuccess(result, pageNum)
                    is ScanResult.Error   -> handleScanError(result.message)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Excepción en startScan: ${e.message}", e)
                handleScanError("Error inesperado: ${e.message}")
            } finally {
                _isScanning.postValue(false)
            }
        }
    }

    private fun handleScanSuccess(result: ScanResult.Success, pageNum: Int) {
        Log.d(TAG, "Scan OK pág $pageNum: ${result.data.size} bytes | mime: ${result.mimeType}")

        // Guardar bytes RAW siempre
        accumulatedBytes.add(result.data)
        val total = accumulatedBytes.size
        _hasScannedData.postValue(true)
        _pageCount.postValue(total)

        // Intentar decode para preview
        val bitmap = decodeBitmapRobust(result.data, result.mimeType)

        if (bitmap != null) {
            accumulatedBitmaps.add(bitmap)
            _scannedBitmap.postValue(bitmap)
            _scannedPages.postValue(accumulatedBitmaps.toList())
            Log.d(TAG, "Preview OK: ${bitmap.width}×${bitmap.height} | bitmaps acumulados: ${accumulatedBitmaps.size}")
            _statusMessage.postValue(
                if (total == 1) "✅ Página 1 escaneada · Toca '➕ Agregar' para más páginas"
                else "✅ Página $total escaneada · Total: $total páginas"
            )
        } else {
            Log.w(TAG, "Preview no disponible para pág $total")
            _scannedPages.postValue(accumulatedBitmaps.toList())
            _statusMessage.postValue(
                "✅ Pág $total escaneada (${result.data.size / 1024} KB) · Sin preview · Puedes guardar"
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { database.scanJobDao().updateScanJobStatus(currentJobId, "SCANNED") }
        }
    }

    private fun handleScanError(message: String) {
        _statusMessage.postValue("❌ $message")
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (currentJobId > 0)
                    database.scanJobDao().updateScanJobStatus(currentJobId, "FAILED", message)
            }
        }
        notificationManager.notifyScanError(message, currentJobId)
    }

    // ── DECODE ROBUSTO ────────────────────────────────────────────────────────

    private fun decodeBitmapRobust(bytes: ByteArray, mimeType: String): Bitmap? {
        if (bytes.size < 100) return null

        Log.d(TAG, "Decodificando ${bytes.size} bytes | mime: $mimeType")
        Log.d(TAG, "Primeros 16 bytes: ${bytes.take(16).joinToString(" ") { "%02X".format(it) }}")

        // 1. Decode directo
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .getOrNull()?.let { Log.d(TAG, "✅ Decode directo ${it.width}×${it.height}"); return it }

        // 2. Buscar JPEG SOI (FF D8 FF)
        for (i in 0 until minOf(bytes.size - 2, 1024)) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte() && bytes[i + 2] == 0xFF.toByte()) {
                if (i > 0) Log.d(TAG, "JPEG SOI en offset $i")
                runCatching { BitmapFactory.decodeByteArray(bytes, i, bytes.size - i) }
                    .getOrNull()?.let { Log.d(TAG, "✅ Decode JPEG@$i: ${it.width}×${it.height}"); return it }
                break
            }
        }

        // 3. Buscar PNG (89 50 4E 47)
        for (i in 0 until minOf(bytes.size - 3, 1024)) {
            if (bytes[i] == 0x89.toByte() && bytes[i + 1] == 0x50.toByte() &&
                bytes[i + 2] == 0x4E.toByte() && bytes[i + 3] == 0x47.toByte()) {
                runCatching { BitmapFactory.decodeByteArray(bytes, i, bytes.size - i) }
                    .getOrNull()?.let { Log.d(TAG, "✅ Decode PNG@$i: ${it.width}×${it.height}"); return it }
                break
            }
        }

        // 4. Via InputStream
        runCatching { BitmapFactory.decodeStream(bytes.inputStream()) }
            .getOrNull()?.let { Log.d(TAG, "✅ Decode InputStream: ${it.width}×${it.height}"); return it }

        // 5. Si es multipart, extraer JPEG interno
        if (mimeType.contains("multipart", ignoreCase = true) ||
            mimeType.contains("octet", ignoreCase = true)) {
            extractFirstJpeg(bytes)?.let { jpeg ->
                runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
                    .getOrNull()?.let { Log.d(TAG, "✅ Decode multipart/octet: ${it.width}×${it.height}"); return it }
            }
        }

        Log.e(TAG, "❌ Todos los decoders fallaron")
        return null
    }

    private fun extractFirstJpeg(bytes: ByteArray): ByteArray? {
        for (i in 0 until minOf(bytes.size - 1, bytes.size)) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte()) {
                for (j in bytes.size - 1 downTo i + 1) {
                    if (bytes[j - 1] == 0xFF.toByte() && bytes[j] == 0xD9.toByte()) {
                        return bytes.copyOfRange(i, j + 1)
                    }
                }
                return bytes.copyOfRange(i, bytes.size)
            }
        }
        return null
    }

    // ── ACCIONES ───────────────────────────────────────────────────────────────

    fun scanNextPage() = startScan(appendToExisting = true)

    fun setColorMode(useColor: Boolean) { _useColor.value = useColor }

    fun clearPages() {
        clearInternalState()
        _statusMessage.postValue("Páginas borradas — listo para nuevo escaneo")
    }

    private fun clearInternalState() {
        accumulatedBitmaps.forEach { runCatching { it.recycle() } }
        accumulatedBitmaps.clear()
        accumulatedBytes.clear()
        currentJobId = -1
        _scannedPages.postValue(emptyList())
        _scannedBitmap.postValue(null)
        _hasScannedData.postValue(false)
        _pageCount.postValue(0)
    }

    // ── GUARDAR IMÁGENES ──────────────────────────────────────────────────────

    fun saveAsImages(context: Context) {
        if (accumulatedBytes.isEmpty()) { _saveResult.postValue("No hay páginas escaneadas"); return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                var saved = 0
                accumulatedBytes.forEachIndexed { i, raw ->
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "SCAN_${ts}_p${i + 1}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EpsonScans")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    uri?.let {
                        context.contentResolver.openOutputStream(it)?.use { os -> os.write(raw) }
                        cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(it, cv, null, null)
                        saved++
                    }
                }
                if (currentJobId > 0)
                    database.scanJobDao().markScanCompleted(
                        currentJobId, "Pictures/EpsonScans/SCAN_$ts",
                        accumulatedBytes.sumOf { it.size }.toLong()
                    )
                notificationManager.notifyScanSuccess("SCAN_$ts ($saved imágenes)", currentJobId)
                _saveResult.postValue("✅ $saved ${if (saved == 1) "imagen guardada" else "imágenes guardadas"} en Galería")
                isSaveSuccess.postValue(true)
            } catch (e: Exception) {
                _saveResult.postValue("❌ Error al guardar: ${e.message}")
            }
        }
    }

    // ── GUARDAR PDF ───────────────────────────────────────────────────────────

    fun savePdfToUri(uri: Uri, contentResolver: android.content.ContentResolver) {
        if (accumulatedBytes.isEmpty()) { _saveResult.postValue("No hay páginas para PDF"); return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pdf = buildMultiPagePdf()
                if (pdf.isEmpty()) { _saveResult.postValue("❌ No se pudo crear el PDF"); return@launch }
                contentResolver.openOutputStream(uri)?.use { it.write(pdf) }
                val n = accumulatedBytes.size
                if (currentJobId > 0)
                    database.scanJobDao().markScanCompleted(currentJobId, uri.toString(), pdf.size.toLong())
                notificationManager.notifyScanSuccess("${uri.lastPathSegment} ($n págs)", currentJobId)
                _saveResult.postValue("✅ PDF guardado · $n ${if (n == 1) "página" else "páginas"}")
                isSaveSuccess.postValue(true)
            } catch (e: Exception) {
                _saveResult.postValue("❌ Error al guardar PDF: ${e.message}")
            }
        }
    }

    private fun buildMultiPagePdf(): ByteArray {
        val out = ByteArrayOutputStream()
        return try {
            val writer   = com.itextpdf.kernel.pdf.PdfWriter(out)
            val pdfDoc   = com.itextpdf.kernel.pdf.PdfDocument(writer)
            val document = com.itextpdf.layout.Document(pdfDoc)
            accumulatedBytes.forEachIndexed { i, raw ->
                runCatching {
                    val img  = com.itextpdf.layout.element.Image(
                        com.itextpdf.io.image.ImageDataFactory.create(raw)
                    )
                    val w = com.itextpdf.kernel.geom.PageSize.A4.width
                    val h = com.itextpdf.kernel.geom.PageSize.A4.height
                    img.scaleToFit(w - 40f, h - 40f)
                    img.setMarginLeft(20f).setMarginTop(20f)
                    if (i > 0) document.add(
                        com.itextpdf.layout.element.AreaBreak(
                            com.itextpdf.layout.properties.AreaBreakType.NEXT_PAGE))
                    document.add(img)
                }.onFailure { Log.e(TAG, "Error pág $i en PDF: ${it.message}") }
            }
            document.close()
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error creando PDF: ${e.message}")
            ByteArray(0)
        }
    }

    fun clearError()         { errorMessage.value = null }
    fun resetSaveSuccess()   { isSaveSuccess.value = false }
    fun clearStatusMessage() { _statusMessage.value = null }

    override fun onCleared() {
        super.onCleared()
        accumulatedBitmaps.forEach { runCatching { it.recycle() } }
    }
}