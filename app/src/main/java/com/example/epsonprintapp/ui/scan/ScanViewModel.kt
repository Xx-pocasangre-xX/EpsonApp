package com.example.epsonprintapp.ui.scan

import android.app.Application
import android.content.ContentValues
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
import com.example.epsonprintapp.appContainer
import com.example.epsonprintapp.database.entities.ScanJobEntity
import com.example.epsonprintapp.scanner.ScanColorMode
import com.example.epsonprintapp.scanner.ScanFormat
import com.example.epsonprintapp.scanner.ScanIntent
import com.example.epsonprintapp.scanner.ScanOptions
import com.example.epsonprintapp.scanner.ScanPaperSize
import com.example.epsonprintapp.scanner.ScanResult
import com.example.epsonprintapp.scanner.ScanSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ScanViewModel — Escaneo eSCL con almacenamiento en DISCO (no en RAM).
 *
 * Memoria: los bytes RAW del escáner van a archivos temporales en cache.
 * En RAM solo viven miniaturas RGB_565 de 300 px (~150 KB cada una).
 * Las miniaturas NO se reciclan manualmente: son pequeñas y el GC las
 * gestiona. (El recycle() manual con delay() que había antes era una
 * condición de carrera: podía reciclar un bitmap mientras el RecyclerView
 * lo dibujaba → crash "trying to use a recycled bitmap".)
 *
 * Concurrencia: pageFiles y thumbnailList SOLO se mutan desde el hilo Main
 * (viewModelScope sin dispatcher). El trabajo pesado (red, disco, decode)
 * se aísla en Dispatchers.IO con withContext, pero las listas nunca se
 * tocan desde ahí. Así no hay carreras entre escanear/borrar/guardar.
 *
 * PDF: usa android.graphics.pdf.PdfDocument (API nativa, sin licencias AGPL),
 * procesando una página a la vez desde disco.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    companion object { private const val TAG = "ScanViewModel" }

    private val container           = application.appContainer
    private val database            = container.database
    private val repository          = container.printerRepository
    private val esclClient          = container.esclClient
    private val notificationManager = container.notificationManager

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

    private val _isSaveSuccess     = MutableLiveData(false)
    val isSaveSuccess: LiveData<Boolean> = _isSaveSuccess

    private val _useColor          = MutableLiveData(true)
    val useColor: LiveData<Boolean> = _useColor

    // ── Estado interno (SOLO se muta desde el hilo Main) ──────────────────────

    /** Rutas a archivos JPEG temporales en disco — no bitmaps en RAM */
    private val pageFiles     = mutableListOf<File>()
    /** Miniaturas pequeñas en RAM (~150 KB cada una) */
    private val thumbnailList = mutableListOf<Bitmap>()

    private var currentJobId: Long = -1

    /** Directorio de temporales. lazy sincronizado; mkdirs() es barato. */
    private val scanDir: File by lazy {
        File(getApplication<Application>().cacheDir, AppConstants.SCAN_TEMP_DIR)
            .apply { mkdirs() }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch(Dispatchers.IO) { cleanOldTempFiles() }
    }

    private fun cleanOldTempFiles() {
        try {
            val maxAgeMs = AppConstants.SCAN_TEMP_MAX_AGE_H * 3_600_000L
            scanDir.listFiles()?.forEach { file ->
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

        // Main: las listas y LiveData se tocan aquí; la red/disco va a IO adentro
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val printer = repository.getDefaultPrinter() ?: run {
                    _statusMessage.value = "❌ No hay impresora configurada. Ve al Dashboard."
                    return@launch
                }

                if (!appendToExisting) clearInternalState()

                // Crear registro en BD si no hay trabajo activo
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

                val pageNum   = pageFiles.size + 1
                val ipAddress = printer.ipAddress

                // ── Verificar/redescubrir el endpoint eSCL ───────────────────
                // En la L3560 el path correcto es http://<ip>/eSCL (puerto 80),
                // pero el guardado en BD puede estar desactualizado.
                _statusMessage.value = "🔎 Verificando escáner en $ipAddress…"

                var esclBaseUrl = printer.esclUrl
                val caps        = esclClient.getScannerCapabilities(esclBaseUrl)

                if (caps == null) {
                    _statusMessage.value = "🔍 Buscando path eSCL alternativo…"
                    val discovered = esclClient.discoverEsclPath(ipAddress)

                    if (discovered != null) {
                        esclBaseUrl = discovered
                        // Guardar el path corregido para próximas sesiones
                        val newPath = discovered
                            .removePrefix("http://$ipAddress")
                            .removePrefix(":80")
                            .removePrefix(":443")
                            .ifEmpty { "/eSCL" }
                        repository.updateEsclPath(printer, newPath)
                        Log.d(TAG, "✅ Path eSCL actualizado en BD: $newPath")
                    } else {
                        handleScanError(
                            "No se encontró el servicio de escaneo en $ipAddress.\n\n" +
                                    "Pasos para habilitarlo en el Epson L3560:\n" +
                                    "1. Ve al panel de la impresora\n" +
                                    "2. Configuración → Configuración de red → Avanzado\n" +
                                    "3. Activa 'Escanear en red' o 'eSCL'\n" +
                                    "4. Reinicia la impresora"
                        )
                        return@launch
                    }
                }

                _statusMessage.value = "🔍 Escaneando página $pageNum…"

                val result = esclClient.scan(
                    esclBaseUrl,
                    ScanOptions(
                        resolution = 300,
                        colorMode  = if (_useColor.value == true) ScanColorMode.COLOR else ScanColorMode.GRAYSCALE,
                        source     = ScanSource.FLATBED,
                        format     = ScanFormat.JPEG,
                        paperSize  = ScanPaperSize.A4,   // papel estándar de la L3560
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
                _isScanning.value = false
            }
        }
    }

    /** Se ejecuta en Main; el I/O de disco y el decode van a IO. */
    private suspend fun handleScanSuccess(result: ScanResult.Success, pageNum: Int) {
        val (file, thumb) = withContext(Dispatchers.IO) {
            savePageToDisk(result.data, pageNum) to generateThumbnail(result.data)
        }
        if (file == null) {
            _statusMessage.value = "❌ Error guardando página en disco"
            return
        }

        pageFiles.add(file)
        if (thumb != null) {
            thumbnailList.add(thumb)
            _lastThumbnail.value = thumb
            _thumbnails.value    = thumbnailList.toList()
        }

        val total  = pageFiles.size
        val sizeMb = withContext(Dispatchers.IO) { pageFiles.sumOf { it.length() } } / (1024.0 * 1024.0)
        _hasScannedData.value = true
        _pageCount.value      = total
        _statusMessage.value  =
            if (total == 1) "✅ Página 1 escaneada · Toca '➕ Agregar' para más"
            else "✅ $total páginas · %.1f MB en disco".format(sizeMb)

        runCatching { database.scanJobDao().updateScanJobStatus(currentJobId, "SCANNED") }
    }

    private suspend fun handleScanError(message: String) {
        _statusMessage.value = "❌ $message"
        runCatching {
            if (currentJobId > 0) database.scanJobDao().updateScanJobStatus(currentJobId, "FAILED", message)
        }
        notificationManager.notifyScanError(message, currentJobId)
    }

    // ── Disco y miniaturas (llamar desde Dispatchers.IO) ──────────────────────

    private fun savePageToDisk(bytes: ByteArray, pageNum: Int): File? = try {
        val file = File(scanDir, "scan_p${pageNum}_${System.currentTimeMillis()}.jpg")
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

    fun scanNextPage()               = startScan(appendToExisting = true)
    fun setColorMode(color: Boolean) { _useColor.value = color }

    fun deletePage(position: Int) {
        viewModelScope.launch {
            // Main: mutación secuencial y segura de las listas
            if (position < 0 || position >= pageFiles.size) return@launch
            val file = pageFiles.removeAt(position)
            if (position < thumbnailList.size) thumbnailList.removeAt(position)

            _thumbnails.value     = thumbnailList.toList()
            _pageCount.value      = pageFiles.size
            _hasScannedData.value = pageFiles.isNotEmpty()
            _statusMessage.value  = "Página ${position + 1} eliminada · ${pageFiles.size} restantes"

            withContext(Dispatchers.IO) { file.delete() }   // solo el I/O sale de Main
        }
    }

    fun clearPages() {
        viewModelScope.launch {
            clearInternalState()
            _statusMessage.value = "Páginas borradas — listo para nuevo escaneo"
        }
    }

    /** Llamar SOLO desde el hilo Main (viewModelScope sin dispatcher). */
    private suspend fun clearInternalState() {
        val filesToDelete = pageFiles.toList()
        pageFiles.clear()
        thumbnailList.clear()      // sin recycle(): el GC libera las miniaturas
        currentJobId = -1
        _thumbnails.value     = emptyList()
        _lastThumbnail.value  = null
        _hasScannedData.value = false
        _pageCount.value      = 0
        withContext(Dispatchers.IO) {
            filesToDelete.forEach { runCatching { it.delete() } }
        }
    }

    // ── Guardar imágenes ──────────────────────────────────────────────────────

    fun saveAsImages() {
        val files = pageFiles.toList()   // snapshot en Main
        if (files.isEmpty()) { _saveResult.value = "No hay páginas escaneadas"; return }

        val resolver = getApplication<Application>().contentResolver

        viewModelScope.launch {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                _statusMessage.value = "💾 Guardando ${files.size} imágenes…"

                val (saved, failed) = withContext(Dispatchers.IO) {
                    var ok = 0
                    var fail = 0
                    files.forEachIndexed { i, file ->
                        _statusMessage.postValue("💾 Guardando ${i + 1}/${files.size}…")
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
                            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                            if (uri != null) {
                                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    cv.clear()
                                    cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                                    resolver.update(uri, cv, null, null)
                                }
                                ok++
                            } else fail++
                        } catch (e: Exception) {
                            Log.e(TAG, "Error imagen ${i + 1}: ${e.message}")
                            fail++
                        }
                    }
                    ok to fail
                }

                if (currentJobId > 0) database.scanJobDao().markScanCompleted(
                    currentJobId, "Pictures/Scans/SCAN_$ts",
                    withContext(Dispatchers.IO) { files.sumOf { it.length() } })

                notificationManager.notifyScanSuccess("SCAN_$ts ($saved imgs)", currentJobId)
                _saveResult.value = when {
                    failed == 0 -> "✅ $saved ${if (saved == 1) "imagen guardada" else "imágenes guardadas"} en Galería › Scans"
                    saved  == 0 -> "❌ No se pudo guardar ninguna imagen"
                    else        -> "⚠️ $saved guardadas, $failed fallaron"
                }
                if (saved > 0) _isSaveSuccess.value = true
            } catch (e: Exception) {
                _saveResult.value = "❌ Error: ${e.message}"
            }
        }
    }

    // ── Guardar PDF (API nativa Android) ──────────────────────────────────────

    fun savePdfToUri(uri: Uri) {
        val files = pageFiles.toList()   // snapshot en Main
        if (files.isEmpty()) { _saveResult.value = "No hay páginas para el PDF"; return }

        val resolver = getApplication<Application>().contentResolver

        viewModelScope.launch {
            try {
                _statusMessage.value = "📄 Creando PDF de ${files.size} páginas…"

                withContext(Dispatchers.IO) {
                    val pdfDoc = PdfDocument()
                    try {
                        files.forEachIndexed { i, file ->
                            _statusMessage.postValue("📄 Procesando ${i + 1}/${files.size}…")
                            addPageToPdf(file, i + 1, pdfDoc)
                        }
                        resolver.openOutputStream(uri)?.use { pdfDoc.writeTo(it) }
                    } finally {
                        pdfDoc.close()
                    }
                }

                val n = files.size
                if (currentJobId > 0) database.scanJobDao().markScanCompleted(
                    currentJobId, uri.toString(),
                    withContext(Dispatchers.IO) {
                        runCatching {
                            resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                        }.getOrDefault(0L)
                    })
                notificationManager.notifyScanSuccess("${uri.lastPathSegment} ($n págs)", currentJobId)
                _saveResult.value    = "✅ PDF guardado · $n ${if (n == 1) "página" else "páginas"}"
                _isSaveSuccess.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Error PDF: ${e.message}", e)
                _saveResult.value = "❌ Error al crear PDF: ${e.message}"
            }
        }
    }

    /**
     * Agrega una página al PDF (llamar desde Dispatchers.IO).
     * Lee del disco → decodifica con sampling → dibuja → recicla el bitmap.
     * Aquí el recycle() SÍ es seguro: el bitmap es local, nadie más lo referencia.
     */
    private fun addPageToPdf(file: File, pageNumber: Int, pdfDoc: PdfDocument) {
        var bitmap: Bitmap? = null
        try {
            val bytes = file.readBytes()
            val opts  = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

            val targetW = (595 * AppConstants.SCAN_PDF_DPI / 72.0).toInt() // A4 a 150 DPI
            val sample  = calcSampleSize(opts.outWidth, targetW)
            bitmap      = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize      = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }) ?: createPlaceholder(pageNumber)

            val pageW = 595
            val pageH = 842  // A4 en puntos (72 DPI)
            val imgAspect  = bitmap.width.toFloat() / bitmap.height.toFloat()
            val pageAspect = pageW.toFloat() / pageH.toFloat()
            val (dW, dH)   = if (imgAspect > pageAspect)
                pageW.toFloat() to pageW / imgAspect
            else
                pageH * imgAspect to pageH.toFloat()

            val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create()
            val page     = pdfDoc.startPage(pageInfo)
            page.canvas.drawColor(Color.WHITE)
            val left = (pageW - dW) / 2f
            val top  = (pageH - dH) / 2f
            page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + dW, top + dH), null)
            pdfDoc.finishPage(page)

        } catch (e: Exception) {
            Log.e(TAG, "Error página $pageNumber en PDF: ${e.message}")
        } finally {
            bitmap?.recycle()
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

    fun resetSaveSuccess()   { _isSaveSuccess.value = false }
    fun clearStatusMessage() { _statusMessage.value = null }
}
