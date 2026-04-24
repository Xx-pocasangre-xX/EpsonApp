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

    // PrintViewModel extends AndroidViewModel — default factory works fine
    private val viewModel: PrintViewModel by viewModels()

    // SAF file picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            requireContext().contentResolver.takePersistableUriPermission(
                selectedUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
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

    // ── UI setup ───────────────────────────────────────────────────────────────

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
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                viewModel.updateOptions(paperSize = when (position) {
                    0 -> PaperSize.A4; 1 -> PaperSize.LETTER; 2 -> PaperSize.A3
                    else -> PaperSize.PHOTO_4X6
                })
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupColorModeSpinner() {
        val modes = listOf("Color", "Blanco y Negro", "Automático")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerColorMode.adapter = adapter
        binding.spinnerColorMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                viewModel.updateOptions(colorMode = when (position) {
                    0 -> ColorMode.COLOR; 1 -> ColorMode.MONOCHROME; else -> ColorMode.AUTO
                })
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupDuplexSpinner() {
        val modes = listOf("Una cara", "Doble cara (lado largo)", "Doble cara (lado corto)")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDuplex.adapter = adapter
        binding.spinnerDuplex.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                viewModel.updateOptions(duplex = when (position) {
                    0 -> DuplexMode.ONE_SIDED; 1 -> DuplexMode.TWO_SIDED_LONG
                    else -> DuplexMode.TWO_SIDED_SHORT
                })
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupQualitySpinner() {
        val qualities = listOf("Borrador (rápido)", "Normal", "Alta calidad")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, qualities)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerQuality.adapter = adapter
        binding.spinnerQuality.setSelection(1)
        binding.spinnerQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                viewModel.updateOptions(quality = when (position) {
                    0 -> PrintQuality.DRAFT; 1 -> PrintQuality.NORMAL; else -> PrintQuality.HIGH
                })
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupCopiesSeekBar() {
        // FIX: correct IDs from fragment_print.xml: seekCopies, tvCopiesValue
        binding.seekCopies.max      = 19
        binding.seekCopies.progress = 0
        binding.tvCopiesValue.text  = "1"
        binding.seekCopies.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val copies = progress + 1
                binding.tvCopiesValue.text = copies.toString()
                viewModel.updateOptions(copies = copies)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ── Observers ──────────────────────────────────────────────────────────────

    private fun setupObservers() {
        // FIX: correct IDs from fragment_print.xml: tvFileName, layoutNoFile, btnPrint
        viewModel.selectedFileName.observe(viewLifecycleOwner) { fileName ->
            if (fileName != null) {
                binding.tvFileName.text     = fileName
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

        viewModel.isPrinting.observe(viewLifecycleOwner) { isPrinting ->
            binding.progressPrint.isVisible = isPrinting
            binding.btnPrint.isEnabled      = !isPrinting
            binding.btnSelectFile.isEnabled = !isPrinting
        }

        viewModel.printProgress.observe(viewLifecycleOwner) { progress ->
            binding.progressPrint.progress = progress
            binding.tvPrintStatus.text = when {
                progress < 20 -> "Preparando documento..."
                progress < 40 -> "Registrando trabajo..."
                progress < 60 -> "Procesando archivo..."
                progress < 90 -> "Enviando a impresora..."
                else          -> "Finalizando..."
            }
        }

        viewModel.printSuccess.observe(viewLifecycleOwner) { success ->
            if (success) {
                Toast.makeText(requireContext(), "✅ Impresión enviada correctamente", Toast.LENGTH_LONG).show()
                viewModel.resetPrintSuccess()
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

    // ── Click listeners ────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnSelectFile.setOnClickListener  { openFilePicker() }
        binding.btnPrint.setOnClickListener       { viewModel.startPrinting(requireContext()) }
    }

    private fun openFilePicker() {
        filePickerLauncher.launch(arrayOf("application/pdf", "image/jpeg", "image/jpg", "image/png"))
    }

    // ── Preview helpers ────────────────────────────────────────────────────────

    private fun showFilePreview(uri: Uri) {
        val mimeType = requireContext().contentResolver.getType(uri)
        when {
            mimeType == "application/pdf"           -> showPdfPreview(uri)
            mimeType?.startsWith("image/") == true  -> showImagePreview(uri)
        }
    }

    private fun showImagePreview(uri: Uri) {
        binding.imagePreview.isVisible = true
        com.bumptech.glide.Glide.with(this)
            .load(uri)
            .centerCrop()
            .into(binding.imagePreview)
    }

    private fun showPdfPreview(uri: Uri) {
        binding.imagePreview.isVisible = true
        try {
            val pfd = requireContext().contentResolver.openFileDescriptor(uri, "r")
            pfd?.use { descriptor ->
                val renderer   = android.graphics.pdf.PdfRenderer(descriptor)
                val pageCount  = renderer.pageCount
                // Show a simple PDF info text via status label
                binding.tvPrintStatus.text =
                    "📄 PDF con $pageCount ${if (pageCount == 1) "página" else "páginas"}"

                if (pageCount > 0) {
                    val page   = renderer.openPage(0)
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        300,
                        (300 * page.height / page.width.coerceAtLeast(1)),
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}