package com.example.epsonprintapp.ui.print

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.epsonprintapp.R
import com.example.epsonprintapp.databinding.FragmentPrintBinding
import com.example.epsonprintapp.printer.ColorMode
import com.example.epsonprintapp.printer.DuplexMode
import com.example.epsonprintapp.printer.PaperSize
import com.example.epsonprintapp.printer.PrintQuality

/**
 * PrintFragment - Pantalla de impresión
 *
 * FLUJO DE LA PANTALLA:
 * =====================
 * 1. Mostrar área para seleccionar archivo
 * 2. Usuario toca "Seleccionar archivo"
 * 3. Se abre el picker de archivos del sistema (SAF)
 * 4. Usuario elige PDF o imagen
 * 5. Se muestra preview del archivo y opciones
 * 6. Usuario configura: copias, color, papel, calidad
 * 7. Toca "IMPRIMIR"
 * 8. Se muestra progreso de impresión
 * 9. Se muestra resultado (éxito/error)
 *
 * STORAGE ACCESS FRAMEWORK (SAF):
 * =================================
 * En Android moderno, no se accede directamente al almacenamiento.
 * Se usa ActivityResultContracts.OpenDocument() para abrir el picker
 * del sistema, que retorna un Uri al archivo seleccionado.
 *
 * El Uri tiene forma: content://com.android.providers.downloads.documents/...
 * Se puede leer con ContentResolver.openInputStream(uri)
 * No requiere permisos READ_EXTERNAL_STORAGE en Android 10+
 */
class PrintFragment : Fragment() {

    private var _binding: FragmentPrintBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PrintViewModel by viewModels()

/**
 * Launcher para el picker de archivos (SAF)
 *
 * ActivityResultContracts.OpenDocument:
 * - Abre el explorador de archivos del sistema
 * - Retorna el Uri del archivo seleccionado
 * - Soporta múltiples tipos MIME
 * - No requiere permisos de almacenamiento en Android 11+
 *
 * Tipos MIME que aceptamos:
 * - "application/pdf" → Documentos PDF
 * - "image/*" → Cualquier imagen (JPEG, PNG, etc.)
*/

 */
private val filePickerLauncher = registerForActivityResult(
ActivityResultContracts.OpenDocument()
) { uri: Uri? ->
uri?.let { selectedUri ->
// Persist permission para poder acceder al archivo más tarde
requireContext().contentResolver.takePersistableUriPermission(
selectedUri,
Intent.FLAG_GRANT_READ_URI_PERMISSION
)
viewModel.onFileSelected(selectedUri, requireContext())
}
}

override fun onCreateView(
inflater: LayoutInflater,
container: ViewGroup?,
savedInstanceState: Bundle?
): View {
_binding = FragmentPrintBinding.inflate(inflater, container, false)
return binding.root
}

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
super.onViewCreated(view, savedInstanceState)
setupUI()
setupObservers()
setupClickListeners()
}

// =========================================================================
// CONFIGURACIÓN DE UI
// =========================================================================

private fun setupUI() {
setupPaperSizeSpinner()
setupColorModeSpinner()
setupDuplexSpinner()
setupQualitySpinner()
setupCopiesSeekBar()
}

private fun setupPaperSizeSpinner() {
val sizes = listOf("A4", "Carta (US Letter)", "A3", "Foto 4×6\"")
val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sizes)
adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
binding.spinnerPaperSize.adapter = adapter

binding.spinnerPaperSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
val paperSize = when (position) {
0 -> PaperSize.A4
1 -> PaperSize.LETTER
2 -> PaperSize.A3
3 -> PaperSize.PHOTO_4X6
else -> PaperSize.A4
}
viewModel.updateOptions(paperSize = paperSize)
}
override fun onNothingSelected(parent: AdapterView<*>?) {}
}
}

private fun setupColorModeSpinner() {
val modes = listOf("Color", "Blanco y Negro", "Automático")
val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modes)
adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
binding.spinnerColorMode.adapter = adapter

binding.spinnerColorMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
val mode = when (position) {
0 -> ColorMode.COLOR
1 -> ColorMode.MONOCHROME
2 -> ColorMode.AUTO
else -> ColorMode.COLOR
}
viewModel.updateOptions(colorMode = mode)
}
override fun onNothingSelected(parent: AdapterView<*>?) {}
}
}

private fun setupDuplexSpinner() {
val modes = listOf("Una cara", "Doble cara (lado largo)", "Doble cara (lado corto)")
val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modes)
adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
binding.spinnerDuplex.adapter = adapter

binding.spinnerDuplex.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
val mode = when (position) {
0 -> DuplexMode.ONE_SIDED
1 -> DuplexMode.TWO_SIDED_LONG
2 -> DuplexMode.TWO_SIDED_SHORT
else -> DuplexMode.ONE_SIDED
}
viewModel.updateOptions(duplex = mode)
}
override fun onNothingSelected(parent: AdapterView<*>?) {}
}
}

private fun setupQualitySpinner() {
val qualities = listOf("Borrador (rápido)", "Normal", "Alta calidad")
val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, qualities)
adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
binding.spinnerQuality.adapter = adapter
binding.spinnerQuality.setSelection(1)  // Normal por defecto

binding.spinnerQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
val quality = when (position) {
0 -> PrintQuality.DRAFT
1 -> PrintQuality.NORMAL
2 -> PrintQuality.HIGH
else -> PrintQuality.NORMAL
}
viewModel.updateOptions(quality = quality)
}
override fun onNothingSelected(parent: AdapterView<*>?) {}
}
}

private fun setupCopiesSeekBar() {
binding.seekBarCopies.max = 19  // 1-20 copias (max - 1 porque empieza en 0)
binding.seekBarCopies.progress = 0  // 1 copia por defecto
binding.textCopiesValue.text = "1"

binding.seekBarCopies.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
val copies = progress + 1  // 1-20
binding.textCopiesValue.text = copies.toString()
viewModel.updateOptions(copies = copies)
}
override fun onStartTrackingTouch(seekBar: SeekBar?) {}
override fun onStopTrackingTouch(seekBar: SeekBar?) {}
})
}

// =========================================================================
// OBSERVERS
// =========================================================================

private fun setupObservers() {
viewModel.selectedFileName.observe(viewLifecycleOwner) { fileName ->
if (fileName != null) {
binding.textFileName.text = fileName
binding.layoutFileSelected.isVisible = true
binding.layoutSelectFile.isVisible = false
binding.buttonPrint.isEnabled = true

// Mostrar preview según tipo
val uri = viewModel.selectedFileUri.value
uri?.let { showFilePreview(it) }
} else {
binding.layoutSelectFile.isVisible = true
binding.layoutFileSelected.isVisible = false
binding.buttonPrint.isEnabled = false
}
}

viewModel.isPrinting.observe(viewLifecycleOwner) { isPrinting ->
binding.printProgressLayout.isVisible = isPrinting
binding.printOptionsCard.isEnabled = !isPrinting
binding.buttonPrint.isEnabled = !isPrinting
binding.buttonSelectFile.isEnabled = !isPrinting
}

viewModel.printProgress.observe(viewLifecycleOwner) { progress ->
binding.progressPrint.progress = progress
binding.textPrintProgress.text = when {
progress < 20 -> "Preparando documento..."
progress < 40 -> "Registrando trabajo..."
progress < 60 -> "Procesando archivo..."
progress < 90 -> "Enviando a impresora..."
else -> "Finalizando..."
}
}

viewModel.printSuccess.observe(viewLifecycleOwner) { success ->
if (success) {
Toast.makeText(requireContext(), "✅ Impresión enviada correctamente", Toast.LENGTH_LONG).show()
viewModel.resetPrintSuccess()
// Volver al dashboard
requireActivity().onBackPressedDispatcher.onBackPressed()
}
}

viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
error?.let {
Toast.makeText(requireContext(), "❌ $it", Toast.LENGTH_LONG).show()
viewModel.clearError()
}
}
}

// =========================================================================
// CLICK LISTENERS
// =========================================================================

private fun setupClickListeners() {
// Seleccionar archivo via SAF
binding.buttonSelectFile.setOnClickListener {
openFilePicker()
}

// Área de drop también abre el picker
binding.layoutSelectFile.setOnClickListener {
openFilePicker()
}

// Botón de cambiar archivo
binding.buttonChangeFile.setOnClickListener {
openFilePicker()
}

// Botón IMPRIMIR
binding.buttonPrint.setOnClickListener {
viewModel.startPrinting(requireContext())
}
}

/**
 * Abrir el selector de archivos del sistema (SAF)
 *
 * Se pasa un array con los tipos MIME aceptados.
 * El sistema muestra solo archivos de esos tipos.
*/
private fun openFilePicker() {
filePickerLauncher.launch(
arrayOf(
"application/pdf",    // PDF
"image/jpeg",         // JPEG
"image/jpg",          // JPG
"image/png"           // PNG
)
)
}

/**
 * Mostrar preview del archivo seleccionado
 *
 * Para PDF: mostrar primera página con PdfRenderer
 * Para imagen: cargar con Glide
*/
private fun showFilePreview(uri: Uri) {
val mimeType = requireContext().contentResolver.getType(uri)

when {
mimeType == "application/pdf" -> showPdfPreview(uri)
mimeType?.startsWith("image/") == true -> showImagePreview(uri)
}
}

private fun showImagePreview(uri: Uri) {
binding.imagePreview.isVisible = true
binding.pdfPreviewText.isVisible = false

// Usar Glide para cargar la imagen (maneja memoria automáticamente)
com.bumptech.glide.Glide.with(this)
.load(uri)
.centerCrop()
.into(binding.imagePreview)
}

private fun showPdfPreview(uri: Uri) {
binding.imagePreview.isVisible = false
binding.pdfPreviewText.isVisible = true

// Contar páginas del PDF
try {
val parcelFileDescriptor = requireContext().contentResolver.openFileDescriptor(uri, "r")
parcelFileDescriptor?.use { pfd ->
val pdfRenderer = android.graphics.pdf.PdfRenderer(pfd)
val pageCount = pdfRenderer.pageCount
binding.pdfPreviewText.text = "📄 PDF con $pageCount ${if (pageCount == 1) "página" else "páginas"}"

// Renderizar primera página como thumbnail
if (pageCount > 0) {
val page = pdfRenderer.openPage(0)
val bitmap = android.graphics.Bitmap.createBitmap(
300,
(300 * page.height / page.width),
android.graphics.Bitmap.Config.ARGB_8888
)
page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
page.close()
binding.imagePreview.setImageBitmap(bitmap)
binding.imagePreview.isVisible = true
}
pdfRenderer.close()
}
} catch (e: Exception) {
binding.pdfPreviewText.text = "📄 Documento PDF"
}
}

override fun onDestroyView() {
super.onDestroyView()
_binding = null
}
}
