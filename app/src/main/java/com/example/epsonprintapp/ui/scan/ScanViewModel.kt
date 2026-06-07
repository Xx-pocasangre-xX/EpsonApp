package com.example.epsonprintapp.ui.scan

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.epsonprintapp.AppConstants
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ScanViewModel — Escaneo con almacenamiento en DISCO (no en RAM).
 *
 * Problema anterior: guardaba bitmaps completos en RAM.
 * A 300 DPI, cada página A4 ocupa ~25 MB. Con 10 páginas → OutOfMemoryError.
 *
 * Solución: los bytes RAW del escáner se guardan en archivos temporales en disco.
 * En RAM solo viven miniaturas de 300px (~150 KB cada una).
 * Capacidad práctica: 100+ páginas sin problemas.
 *
 * PDF: usa android.graphics.pdf.PdfDocument (API nativa Android, sin dependencias
 * externas, sin problema de licencia AGPL). Procesa una página a la vez del disco.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    companion object { private const val TAG = "ScanViewModel" }

    private val database            = AppDatabase.getInstance(application)
    private val esclClient          = EsclClient()
    private val notificationManager = AppNotificationManager(application)

    // ── LiveData ───────────────────────────────────────────────────────────────

    private val _isScanning        = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _statusMessage     = MutableLiveData<String?>("Listo para escanear")
    val statusMessage: LiveData<String?> = _statusMessage

    private val _saveResult        = MutableLiveData<String?>()
    val saveResult: LiveData<String?> = _saveResult

    private val _lastThumbnail     = MutableLiveData<Bitmap?>()
    val lastPageThumbnail: LiveData<Bitmap?> = _lastThumbnail

    private val _thumbnails        = MutableLiveData<List<Bitmap>>(emptyList())
    val thumbnails: LiveData<List<Bitmap>> = _thumbnails

    private val _pageCount         = MutableLiveData(0)
    val pageCount: LiveData<Int> = _pageCount

    private val _hasScannedData    = MutableLiveData(false)
    val hasScannedData: LiveData<Boolean> = _hasScannedData

    val isSaveSuccess  = MutableLiveData(false)
    val errorMessage   = MutableLiveData<String?>()

    private val _useColor          = MutableLiveData(true)
    val useColor: LiveData<Boolean> = _useColor

    // ── Estado interno ────────────────────────────────────────────────────────

    /** Rutas a archivos JPEG temporales en disco — no bitmaps en RAM */
    private val pageFiles      = mutableListOf<File>()
    /** Miniaturas pequeñas en RAM (~150 KB cada una) */
    private val thumbnailList  = mutableListOf<Bitmap>()

    // @Volatile: leído/escrito desde Dispatchers.IO y Main
    @Volatile private var currentJobId: Long = -1
    private var scanDir: File? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch(Dispatchers.IO) {
            scanDir = File(getApplication<Application>().cacheDir, AppConstants.SCAN_TEMP_DIR)
                .also { if (!it.exists()) it.mkdirs() }
            cleanOldTempFiles()
        }
    }

    private fun cleanOldTempFiles() {
        try {
            val maxAgeMs = AppConstants.SCAN_TEMP_MAX_AGE_H * 3_600_000L
            scanDir?.listFiles()?.forEach { file ->
                if (System.currentTimeMillis() - file.lastModified() > maxAgeMs) {
                    file.delete()
                    Log.d(TAG, "Temporal antiguo eliminado: ${file.name}")
                }
            }
        } catch (e: Exception) { Log.w(TAG, "cleanOldTempFiles: ${e.message}") }
    }

    // ── Escanear ──────────────────────────────────────────────────────────────

    fun startScan(appendToExisting: Boolean = false) {
        if (_isScanning.value == true) return

        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.postValue(true)
            try {
                val printer = database.printerDao().getDefaultPrinter() ?: run {
                    _statusMessage.postValue("❌ No hay impresora configurada. Ve al Dashboard.")
                    return@launch
                }

                if (!appendToExisting) {
                    clearInternalState()
                    currentJobId = database.scanJobDao().insertScanJob(ScanJobEntity(
                        printerId  = printer.id,
                        resolution = 300,
                        colorMode  = if (_useColor.value == true) "COLOR" else "GRAYSCALE",
                        paperSize  = "A4",
                        status     = "PROCESSING"
                    ))
                } else if (currentJobId < 0) {
                    currentJobId = database.scanJobDao().insertScanJob(ScanJobEntity(
                        printerId  = printer.id,
                        resolution = 300,
                        colorMode  = if (_useColor.value == true) "COLOR" else "GRAYSCALE",
                        paperSize  = "A4",
                        status     = "PROCESSING"
                    ))
                }

                val pageNum = pageFiles.size + 1
                _statusMessage.postValue("🔍 Escaneando página $pageNum…")

                val result = esclClient.scan(
                    printer.esclUrl,
                    ScanOptions(
                        resolution = 300,
                        colorMode  = if (_useColor.value == true) ScanColorMode.COLOR else ScanColorMode.GRAYSCALE,
                        source     = ScanSource.FLATBED,
                        format     = ScanFormat.JPEG,
                        paperSize  = ScanPaperSize.A4,
                        intent     = ScanIntent.DOCUMENT
                    )
                )

                when (result) {
                    is ScanResult.Success -> handleScanSuccess(result, pageNum)
                    is ScanResult.Error   -> handleScanError(result.message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en startScan: ${e.message}", e)
                handleScanError("Error inesperado: ${e.message}")
            } finally {
                _isScanning.postValue(false)
            }
        }
    }

    private fun handleScanSuccess(result: ScanResult.Success, pageNum: Int) {
        val file = savePageToDisk(result.data, pageNum)
        if (file == null) { _statusMessage.postValue("❌ Error guardando página en disco"); return }

        pageFiles.add(file)
        val thumb = generateThumbnail(result.data)
        if (thumb != null) {
            thumbnailList.add(thumb)
            _lastThumbnail.postValue(thumb)
            _thumbnails.postValue(thumbnailList.toList())
        }

        val total  = pageFiles.size
        val sizeMb = pageFiles.sumOf { it.length() } / (1024.0 * 1024.0)
        _hasScannedData.postValue(true)
        _pageCount.postValue(total)
        _statusMessage.postValue(
            if (total == 1) "✅ Página 1 escaneada · Toca '➕ Agregar' para más"
            else "✅ $total páginas · %.1f MB en disco".format(sizeMb)
        )

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { database.scanJobDao().updateScanJobStatus(currentJobId, "SCANNED") }
        }
    }

    private fun handleScanError(message: String) {
        _statusMessage.postValue("❌ $message")
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (currentJobId > 0) database.scanJobDao().updateScanJobStatus(currentJobId, "FAILED", message)
            }
        }
        notificationManager.notifyScanError(message, currentJobId)
    }

    // ── Disco y miniaturas ────────────────────────────────────────────────────

    private fun savePageToDisk(bytes: ByteArray, pageNum: Int): File? = try {
        val dir  = scanDir ?: getApplication<Application>().cacheDir
        val file = File(dir, "scan_p${pageNum}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { it.write(bytes) }
        file
    } catch (e: Exception) { Log.e(TAG, "saveToDisk: ${e.message}"); null }

    private fun generateThumbnail(bytes: ByteArray): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val sample = calcSampleSize(opts.outWidth, AppConstants.SCAN_THUMBNAIL_WIDTH_PX)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize      = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    } catch (e: Exception) { null }

    private fun calcSampleSize(original: Int, target: Int): Int {
        var s = 1
        while (original / (s * 2) >= target) s *= 2
        return s
    }

    // ── Acciones ──────────────────────────────────────────────────────────────

    fun scanNextPage()             = startScan(appendToExisting = true)
    fun setColorMode(color: Boolean) { _useColor.value = color }

    fun deletePage(position: Int) {
        if (position < 0 || position >= pageFiles.size) return
        viewModelScope.launch(Dispatchers.IO) {
            pageFiles.getOrNull(position)?.delete()
            pageFiles.removeAt(position)
            val thumbToRecycle = thumbnailList.getOrNull(position)
            if (position < thumbnailList.size) thumbnailList.removeAt(position)
            _thumbnails.postValue(thumbnailList.toList())
            _pageCount.postValue(pageFiles.size)
            _hasScannedData.postValue(pageFiles.isNotEmpty())
            _statusMessage.postValue("Página ${position + 1} eliminada · ${pageFiles.size} restantes")
            delay(100) // dar tiempo a la UI para procesar postValue antes de reciclar
            thumbToRecycle?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    fun clearPages() {
        viewModelScope.launch(Dispatchers.IO) {
            clearInternalState()
            _statusMessage.postValue("Páginas borradas — listo para nuevo escaneo")
        }
    }

    private fun clearInternalState() {
        pageFiles.forEach { runCatching { it.delete() } }
        pageFiles.clear()
        val toRecycle = thumbnailList.toList()
        thumbnailList.clear()
        currentJobId = -1
        _thumbnails.postValue(emptyList())
        _lastThumbnail.postValue(null)
        _hasScannedData.postValue(false)
        _pageCount.postValue(0)
        viewModelScope.launch(Dispatchers.IO) {
            delay(150)
            toRecycle.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        }
    }

    // ── Guardar imágenes ──────────────────────────────────────────────────────

    fun saveAsImages(context: Context) {
        if (pageFiles.isEmpty()) { _saveResult.postValue("No hay páginas escaneadas"); return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ts    = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                var saved = 0; var failed = 0
                _statusMessage.postValue("💾 Guardando ${pageFiles.size} imágenes…")

                pageFiles.forEachIndexed { i, file ->
                    _statusMessage.postValue("💾 Guardando ${i + 1}/${pageFiles.size}…")
                    try {
                        val bytes = file.readBytes()
                        val cv    = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME,
                                "SCAN_${ts}_p%03d.jpg".format(i + 1))
                            put(MediaStore.Images.Media.MIME_TYPE,     "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Scans")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                                context.contentResolver.update(uri, cv, null, null)
                            }
                            saved++
                        } else failed++
                    } catch (e: Exception) { Log.e(TAG, "Error imagen ${i+1}: ${e.message}"); failed++ }
                }

                if (currentJobId > 0) database.scanJobDao().markScanCompleted(
                    currentJobId, "Pictures/Scans/SCAN_$ts",
                    pageFiles.sumOf { it.length() })

                notificationManager.notifyScanSuccess("SCAN_$ts ($saved imgs)", currentJobId)
                _saveResult.postValue(
                    when {
                        failed == 0 -> "✅ $saved ${if (saved == 1) "imagen guardada" else "imágenes guardadas"} en Galería › Scans"
                        saved  == 0 -> "❌ No se pudo guardar ninguna imagen"
                        else        -> "⚠️ $saved guardadas, $failed fallaron"
                    })
                if (saved > 0) isSaveSuccess.postValue(true)
            } catch (e: Exception) {
                _saveResult.postValue("❌ Error: ${e.message}")
            }
        }
    }

    // ── Guardar PDF (API nativa Android — sin iText7, sin problema de licencia) ──

    fun savePdfToUri(uri: Uri, contentResolver: android.content.ContentResolver) {
        if (pageFiles.isEmpty()) { _saveResult.postValue("No hay páginas para el PDF"); return }
        viewModelScope.launch(Dispatchers.IO) {
            var pdfDoc: PdfDocument? = null
            try {
                _statusMessage.postValue("📄 Creando PDF de ${pageFiles.size} páginas…")
                pdfDoc = PdfDocument()
                pageFiles.forEachIndexed { i, file ->
                    _statusMessage.postValue("📄 Procesando ${i + 1}/${pageFiles.size}…")
                    addPageToPdf(file, i + 1, pdfDoc)
                }
                contentResolver.openOutputStream(uri)?.use { pdfDoc.writeTo(it) }
                val n = pageFiles.size
                if (currentJobId > 0) database.scanJobDao().markScanCompleted(
                    currentJobId, uri.toString(),
                    runCatching {
                        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                    }.getOrDefault(0L))
                notificationManager.notifyScanSuccess("${uri.lastPathSegment} ($n págs)", currentJobId)
                _saveResult.postValue("✅ PDF guardado · $n ${if (n == 1) "página" else "páginas"}")
                isSaveSuccess.postValue(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error PDF: ${e.message}", e)
                _saveResult.postValue("❌ Error al crear PDF: ${e.message}")
            } finally {
                pdfDoc?.close()
            }
        }
    }

    /**
     * Agrega una página al PDF.
     * Lee del disco → decodifica con sampling → dibuja → recicla bitmap INMEDIATAMENTE.
     * Nunca hay más de un bitmap completo en RAM al mismo tiempo.
     */
    private fun addPageToPdf(file: File, pageNumber: Int, pdfDoc: PdfDocument) {
        var bitmap: Bitmap? = null
        try {
            val bytes = file.readBytes()
            val opts  = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

            val targetW  = (595 * AppConstants.SCAN_PDF_DPI / 72.0).toInt() // A4 a 150 DPI
            val sample   = calcSampleSize(opts.outWidth, targetW)
            bitmap       = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize      = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }) ?: createPlaceholder(pageNumber)

            val pageW = 595; val pageH = 842  // A4 en puntos (72 DPI)
            val imgAspect  = bitmap.width.toFloat() / bitmap.height.toFloat()
            val pageAspect = pageW.toFloat() / pageH.toFloat()
            val (dW, dH)   = if (imgAspect > pageAspect)
                Pair(pageW.toFloat(), pageW / imgAspect)
            else
                Pair(pageH * imgAspect, pageH.toFloat())

            val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create()
            val page     = pdfDoc.startPage(pageInfo)
            page.canvas.drawColor(Color.WHITE)
            val left = (pageW - dW) / 2f; val top = (pageH - dH) / 2f
            page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + dW, top + dH), null)
            pdfDoc.finishPage(page)

        } catch (e: Exception) {
            Log.e(TAG, "Error página $pageNumber en PDF: ${e.message}")
        } finally {
            bitmap?.recycle()  // SIEMPRE liberar, incluso si hay excepción
        }
    }

    private fun createPlaceholder(n: Int): Bitmap {
        val bmp = Bitmap.createBitmap(595, 842, Bitmap.Config.RGB_565)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawText("Página $n", 297f, 421f,
                Paint().apply { color = Color.GRAY; textSize = 36f; textAlign = Paint.Align.CENTER })
        }
        return bmp
    }

    // ── Limpieza ──────────────────────────────────────────────────────────────

    fun clearError()         { errorMessage.value = null }
    fun resetSaveSuccess()   { isSaveSuccess.value = false }
    fun clearStatusMessage() { _statusMessage.value = null }

    override fun onCleared() {
        super.onCleared()
        thumbnailList.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        notificationManager.cancel()
    }
}