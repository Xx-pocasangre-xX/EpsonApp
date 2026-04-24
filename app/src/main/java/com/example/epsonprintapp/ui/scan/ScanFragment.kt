package com.example.epsonprintapp.ui.scan

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.epsonprintapp.R
import com.example.epsonprintapp.scanner.ScanColorMode
import com.example.epsonprintapp.scanner.ScanFormat
import com.example.epsonprintapp.scanner.ScanSource

class ScanFragment : Fragment() {

    // ScanViewModel extends AndroidViewModel — default factory is fine
    private val viewModel: ScanViewModel by viewModels()

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var imagePreview:      ImageView
    private lateinit var btnScan:           Button
    private lateinit var btnCrop:           Button
    private lateinit var btnSavePdf:        Button
    private lateinit var btnSaveGallery:    Button
    private lateinit var progressBar:       ProgressBar
    private lateinit var tvStatus:          TextView
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerColor:      Spinner
    private lateinit var spinnerSource:     Spinner
    private lateinit var spinnerFormat:     Spinner
    private lateinit var seekBrightness:    SeekBar
    private lateinit var seekContrast:      SeekBar
    private lateinit var tvBrightnessValue: TextView
    private lateinit var tvContrastValue:   TextView
    private lateinit var layoutEditTools:   LinearLayout

    // FIX: savePdfToUri receives ContentResolver, not Context
    private val savePdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { viewModel.savePdfToUri(it, requireContext().contentResolver) }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupSpinners()
        setupSeekBars()
        observeViewModel()
        setupClickListeners()
    }

    // ── Bind views ─────────────────────────────────────────────────────────────
    private fun bindViews(view: View) {
        imagePreview      = view.findViewById(R.id.imagePreview)
        btnScan           = view.findViewById(R.id.btnScan)
        btnCrop           = view.findViewById(R.id.btnCrop)
        btnSavePdf        = view.findViewById(R.id.btnSavePdf)
        btnSaveGallery    = view.findViewById(R.id.btnSaveGallery)
        progressBar       = view.findViewById(R.id.progressBar)
        tvStatus          = view.findViewById(R.id.tvStatus)
        spinnerResolution = view.findViewById(R.id.spinnerResolution)
        spinnerColor      = view.findViewById(R.id.spinnerColor)
        spinnerSource     = view.findViewById(R.id.spinnerSource)
        spinnerFormat     = view.findViewById(R.id.spinnerFormat)
        seekBrightness    = view.findViewById(R.id.seekBrightness)
        seekContrast      = view.findViewById(R.id.seekContrast)
        tvBrightnessValue = view.findViewById(R.id.tvBrightnessValue)
        tvContrastValue   = view.findViewById(R.id.tvContrastValue)
        layoutEditTools   = view.findViewById(R.id.layoutEditTools)
    }

    // ── Spinners ───────────────────────────────────────────────────────────────
    private fun setupSpinners() {
        fun adapter(items: Array<String>) = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, items)

        spinnerResolution.adapter = adapter(arrayOf("75 DPI", "150 DPI", "300 DPI", "600 DPI"))
        spinnerResolution.setSelection(2)

        spinnerColor.adapter  = adapter(ScanColorMode.values().map { it.name }.toTypedArray())
        spinnerSource.adapter = adapter(ScanSource.values().map { it.name }.toTypedArray())
        spinnerFormat.adapter = adapter(ScanFormat.values().map { it.name }.toTypedArray())

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateScanOptions()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerResolution.onItemSelectedListener = listener
        spinnerColor.onItemSelectedListener      = listener
        spinnerSource.onItemSelectedListener     = listener
        spinnerFormat.onItemSelectedListener     = listener
    }

    // ── SeekBars ───────────────────────────────────────────────────────────────
    private fun setupSeekBars() {
        seekBrightness.max      = 200
        seekBrightness.progress = 100
        seekContrast.max        = 200
        seekContrast.progress   = 100

        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvBrightnessValue.text = "Brillo: $p"
                // FIX: both params are Int (SeekBar progress)
                viewModel.adjustBrightnessContrast(p, seekContrast.progress)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvContrastValue.text = "Contraste: $p"
                viewModel.adjustBrightnessContrast(seekBrightness.progress, p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    // ── Observe ────────────────────────────────────────────────────────────────
    private fun observeViewModel() {
        viewModel.isScanning.observe(viewLifecycleOwner) { scanning ->
            progressBar.visibility = if (scanning) View.VISIBLE else View.GONE
            btnScan.isEnabled      = !scanning
        }

        // FIX: statusMessage LiveData now exists on ScanViewModel
        viewModel.statusMessage.observe(viewLifecycleOwner) { msg ->
            tvStatus.text = msg ?: "Listo para escanear"
        }

        viewModel.scannedBitmap.observe(viewLifecycleOwner) { bitmap ->
            if (bitmap != null) {
                imagePreview.setImageBitmap(bitmap)
                layoutEditTools.visibility = View.VISIBLE
                btnSaveGallery.isEnabled   = true
                btnSavePdf.isEnabled       = true
                btnCrop.isEnabled          = true
            } else {
                layoutEditTools.visibility = View.GONE
                btnSaveGallery.isEnabled   = false
                btnSavePdf.isEnabled       = false
                btnCrop.isEnabled          = false
            }
        }

        // FIX: saveResult LiveData now exists on ScanViewModel
        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            result?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Click listeners ────────────────────────────────────────────────────────
    private fun setupClickListeners() {
        btnScan.setOnClickListener {
            updateScanOptions()
            // FIX: startScan() takes no arguments
            viewModel.startScan()
        }

        btnCrop.setOnClickListener {
            // FIX: launchCrop(context, fragment)
            viewModel.launchCrop(requireContext(), this)
        }

        btnSavePdf.setOnClickListener {
            savePdfLauncher.launch("scan_${System.currentTimeMillis()}.pdf")
        }

        btnSaveGallery.setOnClickListener {
            viewModel.saveToGallery(requireContext())
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private fun updateScanOptions() {
        val dpi = when (spinnerResolution.selectedItemPosition) {
            0 -> 75; 1 -> 150; 2 -> 300; else -> 600
        }
        // FIX: updateOptions now takes (Int, ScanColorMode, ScanSource, ScanFormat)
        viewModel.updateOptions(
            resolution = dpi,
            colorMode  = ScanColorMode.values()[spinnerColor.selectedItemPosition],
            source     = ScanSource.values()[spinnerSource.selectedItemPosition],
            format     = ScanFormat.values()[spinnerFormat.selectedItemPosition]
        )
    }
}