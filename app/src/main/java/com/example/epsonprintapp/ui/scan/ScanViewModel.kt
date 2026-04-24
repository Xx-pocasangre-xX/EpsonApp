package com.example.epsonprintapp.ui.scan

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val database            = AppDatabase.getInstance(application)
    private val esclClient          = EsclClient()
    private val notificationManager = AppNotificationManager(application)

    // ── State ──────────────────────────────────────────────────────────────────
    private val _isScanning    = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _scanProgress  = MutableLiveData(0)
    val scanProgress: LiveData<Int> = _scanProgress

    private val _errorMessage  = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    private val _scannedBitmap = MutableLiveData<Bitmap?>(null)
    val scannedBitmap: LiveData<Bitmap?> = _scannedBitmap

    private val _scannedBytes  = MutableLiveData<ByteArray?>(null)
    private val _scanMimeType  = MutableLiveData("image/jpeg")

    private val _savedFilePath = MutableLiveData<String?>(null)
    val savedFilePath: LiveData<String?> = _savedFilePath

    private val _isSaveSuccess = MutableLiveData(false)
    val isSaveSuccess: LiveData<Boolean> = _isSaveSuccess

    // FIX: ScanFragment observes statusMessage and saveResult
    private val _statusMessage = MutableLiveData<String?>()
    val statusMessage: LiveData<String?> = _statusMessage

    private val _saveResult = MutableLiveData<String?>()
    val saveResult: LiveData<String?> = _saveResult

    // ── Options ────────────────────────────────────────────────────────────────
    private val _scanOptions = MutableLiveData(
        ScanOptions(resolution = 300, colorMode = ScanColorMode.COLOR,
            source = ScanSource.FLATBED, format = ScanFormat.JPEG, paperSize = ScanPaperSize.A4)
    )
    val scanOptions: LiveData<ScanOptions> = _scanOptions

    private var currentJobId: Long = -1

    // ── Scan ───────────────────────────────────────────────────────────────────

    // FIX: no parameters — Context is not needed (application is already available)
    fun startScan() {
        val options = _scanOptions.value ?: ScanOptions()
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.postValue(true)
            _scanProgress.postValue(10)
            _statusMessage.postValue("Iniciando escaneo…")

            try {
                val printer = database.printerDao().getDefaultPrinter() ?: run {
                    _statusMessage.postValue("No hay impresora/escáner configurado")
                    _isScanning.postValue(false)
                    return@launch
                }

                val scanJob = ScanJobEntity(
                    printerId  = printer.id,
                    resolution = options.resolution,
                    colorMode  = options.colorMode.name,
                    paperSize  = options.paperSize.name,
                    status     = "PROCESSING"
                )
                currentJobId = database.scanJobDao().insertScanJob(scanJob)
                _scanProgress.postValue(20)

                val result = esclClient.scan(printer.esclUrl, options)
                _scanProgress.postValue(80)

                when (result) {
                    is ScanResult.Success -> {
                        _scannedBytes.postValue(result.data)
                        _scanMimeType.postValue(result.mimeType)
                        if (result.mimeType.startsWith("image/")) {
                            val bmp = BitmapFactory.decodeByteArray(result.data, 0, result.data.size)
                            _scannedBitmap.postValue(bmp)
                        }
                        database.scanJobDao().updateScanJobStatus(currentJobId, "SCANNED")
                        _scanProgress.postValue(100)
                        _statusMessage.postValue("✅ Escaneo completado")
                    }
                    is ScanResult.Error -> {
                        database.scanJobDao().updateScanJobStatus(currentJobId, "FAILED", result.message)
                        _statusMessage.postValue("❌ ${result.message}")
                        notificationManager.notifyScanError(result.message, currentJobId)
                    }
                }
            } catch (e: Exception) {
                _statusMessage.postValue("Error de escaneo: ${e.message}")
            } finally {
                _isScanning.postValue(false)
            }
        }
    }

    // ── Edit ───────────────────────────────────────────────────────────────────

    // FIX: ScanFragment passes Int (SeekBar progress), not Float
    fun adjustBrightnessContrast(brightness: Int, contrast: Int) {
        val originalBitmap = _scannedBitmap.value ?: return
        // Map 0-200 → meaningful ColorMatrix values
        val brightnessF = brightness - 100f   // -100..+100
        val contrastF   = contrast / 100f     //   0..2.0

        viewModelScope.launch(Dispatchers.Default) {
            val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
                contrastF, 0f, 0f, 0f, brightnessF,
                0f, contrastF, 0f, 0f, brightnessF,
                0f, 0f, contrastF, 0f, brightnessF,
                0f, 0f, 0f, 1f, 0f
            ))
            val paint = android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
            }
            val adjusted = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height,
                Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(adjusted).drawBitmap(originalBitmap, 0f, 0f, paint)
            _scannedBitmap.postValue(adjusted)
        }
    }

    // FIX: ScanFragment calls launchCrop(context, fragment) — stub implementation
    fun launchCrop(context: Context, fragment: androidx.fragment.app.Fragment) {
        val bitmap = _scannedBitmap.value ?: run {
            _statusMessage.postValue("No hay imagen para recortar")
            return
        }
        // Save bitmap to a temp file then launch UCrop
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheFile = java.io.File(context.cacheDir, "crop_input_${System.currentTimeMillis()}.jpg")
                cacheFile.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                val inputUri  = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", cacheFile)
                val outputUri = android.net.Uri.fromFile(
                    java.io.File(context.cacheDir, "crop_output_${System.currentTimeMillis()}.jpg"))

                val ucropIntent = com.yalantis.ucrop.UCrop
                    .of(inputUri, outputUri)
                    .getIntent(context)

                fragment.startActivityForResult(ucropIntent, com.yalantis.ucrop.UCrop.REQUEST_CROP)
            } catch (e: Exception) {
                _statusMessage.postValue("Error al iniciar recorte: ${e.message}")
            }
        }
    }

    // ── Save ───────────────────────────────────────────────────────────────────

    fun saveToGallery(context: Context) {
        val bitmap = _scannedBitmap.value ?: run {
            _saveResult.postValue("No hay imagen para guardar")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName  = "SCAN_$timestamp.jpg"
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EpsonScans")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                uri?.let {
                    resolver.openOutputStream(it)?.use { os -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os) }
                    cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, cv, null, null)
                    if (currentJobId > 0) database.scanJobDao().markScanCompleted(
                        currentJobId, it.toString(), bitmap.byteCount.toLong())
                    notificationManager.notifyScanSuccess(fileName, currentJobId)
                    _saveResult.postValue("✅ Imagen guardada en galería")
                    _isSaveSuccess.postValue(true)
                }
            } catch (e: Exception) {
                _saveResult.postValue("Error al guardar: ${e.message}")
            }
        }
    }

    // FIX: savePdfToUri takes ContentResolver, not Context
    fun savePdfToUri(uri: Uri, contentResolver: android.content.ContentResolver) {
        val pdfBytes = _scannedBytes.value ?: run {
            _saveResult.postValue("No hay documento para guardar")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(pdfBytes) }
                val fileName = uri.lastPathSegment ?: "escaneo.pdf"
                if (currentJobId > 0) database.scanJobDao().markScanCompleted(
                    currentJobId, uri.toString(), pdfBytes.size.toLong())
                notificationManager.notifyScanSuccess(fileName, currentJobId)
                _saveResult.postValue("✅ PDF guardado correctamente")
                _isSaveSuccess.postValue(true)
            } catch (e: Exception) {
                _saveResult.postValue("Error al guardar PDF: ${e.message}")
            }
        }
    }

    // ── Option updates ─────────────────────────────────────────────────────────

    // FIX: ScanFragment calls updateOptions(dpi, colorMode, source, format)
    fun updateOptions(resolution: Int, colorMode: ScanColorMode, source: ScanSource, format: ScanFormat) {
        _scanOptions.value = (_scanOptions.value ?: ScanOptions()).copy(
            resolution = resolution,
            colorMode  = colorMode,
            source     = source,
            format     = format
        )
    }

    fun clearError() { _errorMessage.value = null }
    fun resetSaveSuccess() { _isSaveSuccess.value = false }
}