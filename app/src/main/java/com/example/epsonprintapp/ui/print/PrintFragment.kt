package com.example.epsonprintapp.ui.print

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

class PrintFragment : Fragment() {

    private var _binding: FragmentPrintBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PrintViewModel by viewModels()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    selectedUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            viewModel.onFileSelected(selectedUri, requireContext())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPrintBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSpinners()
        setupCopiesSeekBar()
        setupObservers()
        setupClickListeners()
    }

    private fun setupSpinners() {
        val sizes = listOf("A4 (210×297 mm)", "Carta / Letter (216×279 mm)", "A3 (297×420 mm)", "Foto 4×6\"")
        binding.spinnerPaperSize.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, sizes
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerPaperSize.onItemSelectedListener = spinnerListener { pos ->
            viewModel.updateOptions(paperSize = when (pos) {
                0 -> PaperSize.A4; 1 -> PaperSize.LETTER; 2 -> PaperSize.A3; else -> PaperSize.PHOTO_4X6
            })
        }

        val colors = listOf("Color", "Blanco y Negro", "Automático")
        binding.spinnerColorMode.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, colors
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerColorMode.onItemSelectedListener = spinnerListener { pos ->
            viewModel.updateOptions(colorMode = when (pos) {
                0 -> ColorMode.COLOR; 1 -> ColorMode.MONOCHROME; else -> ColorMode.AUTO
            })
        }

        val duplex = listOf("Una cara", "Doble cara (lado largo)", "Doble cara (lado corto)")
        binding.spinnerDuplex.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, duplex
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerDuplex.onItemSelectedListener = spinnerListener { pos ->
            viewModel.updateOptions(duplex = when (pos) {
                0 -> DuplexMode.ONE_SIDED; 1 -> DuplexMode.TWO_SIDED_LONG; else -> DuplexMode.TWO_SIDED_SHORT
            })
        }

        val qualities = listOf("Borrador (rápido)", "Normal", "Alta calidad")
        binding.spinnerQuality.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, qualities
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerQuality.setSelection(1)
        binding.spinnerQuality.onItemSelectedListener = spinnerListener { pos ->
            viewModel.updateOptions(quality = when (pos) {
                0 -> PrintQuality.DRAFT; 1 -> PrintQuality.NORMAL; else -> PrintQuality.HIGH
            })
        }
    }

    private fun setupCopiesSeekBar() {
        binding.seekCopies.max      = 19
        binding.seekCopies.progress = 0
        binding.tvCopiesValue.text  = "1"
        binding.seekCopies.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                val copies = p + 1
                binding.tvCopiesValue.text = copies.toString()
                viewModel.updateOptions(copies = copies)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun setupObservers() {
        viewModel.selectedFileName.observe(viewLifecycleOwner) { fileName ->
            if (fileName != null) {
                binding.tvFileName.text       = "📄 $fileName"
                binding.tvFileName.isVisible  = true
                binding.layoutNoFile.isVisible = false
                binding.imagePreview.isVisible = true
                binding.btnPrint.isEnabled    = true
                viewModel.selectedFileUri.value?.let { showFilePreview(it) }
            } else {
                binding.tvFileName.isVisible  = false
                binding.layoutNoFile.isVisible = true
                binding.imagePreview.isVisible = false
                binding.btnPrint.isEnabled    = false
            }
        }

        viewModel.detectedPaperSize.observe(viewLifecycleOwner) { paper ->
            if (paper != null) {
                val pos  = when (paper) { PaperSize.A4 -> 0; PaperSize.LETTER -> 1; PaperSize.A3 -> 2; else -> 1 }
                val name = when (paper) { PaperSize.A4 -> "A4"; PaperSize.LETTER -> "Carta / Letter"; else -> paper.name }
                binding.spinnerPaperSize.setSelection(pos)
                binding.tvPrintStatus.text = "ℹ️ Papel detectado: $name"
            }
        }

        viewModel.isPrinting.observe(viewLifecycleOwner) { printing ->
            binding.progressPrint.isVisible  = printing
            binding.btnPrint.isEnabled       = !printing && viewModel.selectedFileName.value != null
            binding.btnSelectFile.isEnabled  = !printing
            if (!printing) binding.progressPrint.progress = 0
        }

        viewModel.printProgress.observe(viewLifecycleOwner) { progress ->
            binding.progressPrint.progress = progress
            binding.tvPrintStatus.text = when {
                progress == 0  -> ""
                progress < 20  -> "⏳ Conectando con la impresora..."
                progress < 35  -> "📋 Registrando trabajo..."
                progress < 65  -> "📂 Procesando archivo..."
                progress < 90  -> "📤 Enviando a impresora..."
                progress < 100 -> "✅ Finalizando..."
                else           -> "✅ ¡Trabajo enviado!"
            }
        }

        viewModel.printSuccess.observe(viewLifecycleOwner) { success ->
            if (success) {
                binding.tvPrintStatus.text = "✅ Impresión enviada correctamente"
                Toast.makeText(requireContext(), "✅ Impresión enviada", Toast.LENGTH_LONG).show()
                viewModel.resetPrintSuccess()
                binding.root.postDelayed({
                    if (isAdded) requireActivity().onBackPressedDispatcher.onBackPressed()
                }, 1500)
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                binding.tvPrintStatus.text = "❌ $it"
                Toast.makeText(requireContext(), "❌ $it", Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf(
                "application/pdf", "image/jpeg", "image/jpg", "image/png"
            ))
        }
        binding.btnPrint.setOnClickListener { viewModel.startPrinting(requireContext()) }
    }

    private fun showFilePreview(uri: Uri) {
        val mimeType = requireContext().contentResolver.getType(uri)
        when {
            mimeType == "application/pdf"           -> showPdfPreview(uri)
            mimeType?.startsWith("image/") == true  -> showImagePreview(uri)
        }
    }

    private fun showImagePreview(uri: Uri) {
        binding.imagePreview.isVisible = true
        com.bumptech.glide.Glide.with(this).load(uri).fitCenter()
            .placeholder(android.R.drawable.ic_menu_gallery).into(binding.imagePreview)
    }

    private fun showPdfPreview(uri: Uri) {
        binding.imagePreview.isVisible = true
        try {
            requireContext().contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val renderer  = android.graphics.pdf.PdfRenderer(descriptor)
                val pageCount = renderer.pageCount
                binding.tvPrintStatus.text =
                    "📄 PDF · $pageCount ${if (pageCount == 1) "página" else "páginas"}"
                if (pageCount > 0) {
                    val page    = renderer.openPage(0)
                    val targetW = 600
                    val targetH = (targetW.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt()
                    val bitmap  = android.graphics.Bitmap.createBitmap(
                        targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null,
                        android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    binding.imagePreview.setImageBitmap(bitmap)
                }
                renderer.close()
            }
        } catch (e: Exception) {
            binding.tvPrintStatus.text = "📄 Documento PDF"
        }
    }

    private fun spinnerListener(onSelected: (Int) -> Unit) =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelected(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}