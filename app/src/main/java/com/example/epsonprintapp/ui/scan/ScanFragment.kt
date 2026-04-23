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
import androidx.lifecycle.ViewModelProvider
import com.example.epsonprintapp.R
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.scanner.EsclClient
import com.example.epsonprintapp.scanner.ScanColorMode
import com.example.epsonprintapp.scanner.ScanFormat
import com.example.epsonprintapp.scanner.ScanSource
import com.example.epsonprintapp.ui.ViewModelFactory

/**
 * ScanFragment — Interfaz de escaneo usando protocolo eSCL.
 *
 * Flujo de uso:
 * 1. Usuario configura resolución, color y fuente
 * 2. Presiona "Escanear" → se crea un ScanJob en Room y se llama a EsclClient
 * 3. La imagen escaneada se muestra en un ImageView para previsualización
 * 4. Usuario puede ajustar brillo/contraste, recortar (UCrop), o convertir a PDF
 * 5. Guardar en galería (automático) o exportar PDF (SAF)
 */
class ScanFragment : Fragment() {

    // ── ViewModel ──────────────────────────────────────────────────────────────
    private val viewModel: ScanViewModel by viewModels {
        ViewModelFactory(
            database = AppDatabase.getInstance(requireContext()),
            esclClient = EsclClient()
        )
    }

    // ── Vistas ──────────────────────────────────────────────────────────────────
    private lateinit var imagePreview: ImageView
    private lateinit var btnScan: Button
    private lateinit var btnCrop: Button
    private lateinit var btnSavePdf: Button
    private lateinit var btnSaveGallery: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerColor: Spinner
    private lateinit var spinnerSource: Spinner
    private lateinit var spinnerFormat: Spinner
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekContrast: SeekBar
    private lateinit var tvBrightnessValue: TextView
    private lateinit var tvContrastValue: TextView
    private lateinit var layoutEditTools: LinearLayout

    // ── SAF: selector de destino para guardar PDF ───────────────────────────────
    private val savePdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { viewModel.savePdfToUri(it, requireContext().contentResolver) }
    }

    // ── UCrop: resultado del recorte ────────────────────────────────────────────
    // (UCrop devuelve resultado vía onActivityResult; aquí se maneja en ViewModel)

    // ───────────────────────────────────────────────────────────────────────────
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

    // ── Enlazar vistas ──────────────────────────────────────────────────────────
    private fun bindViews(view: View) {
        imagePreview    = view.findViewById(R.id.imagePreview)
        btnScan         = view.findViewById(R.id.btnScan)
        btnCrop         = view.findViewById(R.id.btnCrop)
        btnSavePdf      = view.findViewById(R.id.btnSavePdf)
        btnSaveGallery  = view.findViewById(R.id.btnSaveGallery)
        progressBar     = view.findViewById(R.id.progressBar)
        tvStatus        = view.findViewById(R.id.tvStatus)
        spinnerResolution = view.findViewById(R.id.spinnerResolution)
        spinnerColor    = view.findViewById(R.id.spinnerColor)
        spinnerSource   = view.findViewById(R.id.spinnerSource)
        spinnerFormat   = view.findViewById(R.id.spinnerFormat)
        seekBrightness  = view.findViewById(R.id.seekBrightness)
        seekContrast    = view.findViewById(R.id.seekContrast)
        tvBrightnessValue = view.findViewById(R.id.tvBrightnessValue)
        tvContrastValue = view.findViewById(R.id.tvContrastValue)
        layoutEditTools = view.findViewById(R.id.layoutEditTools)
    }

    // ── Configurar spinners ─────────────────────────────────────────────────────
    private fun setupSpinners() {
        // Resoluciones disponibles (DPI)
        val resolutions = arrayOf("75 DPI", "150 DPI", "300 DPI", "600 DPI")
        spinnerResolution.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, resolutions
        )
        spinnerResolution.setSelection(2) // 300 DPI por defecto

        // Modos de color
        val colorModes = ScanColorMode.values().map { it.name }.toTypedArray()
        spinnerColor.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, colorModes
        )

        // Fuente de escaneo
        val sources = ScanSource.values().map { it.name }.toTypedArray()
        spinnerSource.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, sources
        )

        // Formato de salida
        val formats = ScanFormat.values().map { it.name }.toTypedArray()
        spinnerFormat.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, formats
        )

        // Listener para actualizar opciones en ViewModel
        val selectionListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateScanOptions()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerResolution.onItemSelectedListener = selectionListener
        spinnerColor.onItemSelectedListener      = selectionListener
        spinnerSource.onItemSelectedListener     = selectionListener
        spinnerFormat.onItemSelectedListener     = selectionListener
    }

    // ── Configurar seek bars de edición ─────────────────────────────────────────
    private fun setupSeekBars() {
        // Brillo: 0-200 (100 = normal)
        seekBrightness.max      = 200
        seekBrightness.progress = 100

        // Contraste: 0-200 (100 = normal)
        seekContrast.max      = 200
        seekContrast.progress = 100

        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                tvBrightnessValue.text = "Brillo: $p"
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

    // ── Observar LiveData del ViewModel ─────────────────────────────────────────
    private fun observeViewModel() {
        // Estado de carga
        viewModel.isScanning.observe(viewLifecycleOwner) { scanning ->
            progressBar.visibility = if (scanning) View.VISIBLE else View.GONE
            btnScan.isEnabled      = !scanning
        }

        // Mensaje de estado
        viewModel.statusMessage.observe(viewLifecycleOwner) { msg ->
            tvStatus.text = msg
        }

        // Bitmap escaneado → mostrar en preview y activar herramientas de edición
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

        // Guardado exitoso
        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            result?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Listeners de botones ────────────────────────────────────────────────────
    private fun setupClickListeners() {
        btnScan.setOnClickListener {
            updateScanOptions()
            viewModel.startScan(requireContext())
        }

        btnCrop.setOnClickListener {
            // UCrop necesita un Uri de origen; guardamos el bitmap temporalmente
            viewModel.launchCrop(requireContext(), this)
        }

        btnSavePdf.setOnClickListener {
            val timestamp = System.currentTimeMillis()
            savePdfLauncher.launch("scan_$timestamp.pdf")
        }

        btnSaveGallery.setOnClickListener {
            viewModel.saveToGallery(requireContext())
        }
    }

    // ── Actualizar opciones de escaneo desde los spinners ───────────────────────
    private fun updateScanOptions() {
        val resolutionDpi = when (spinnerResolution.selectedItemPosition) {
            0 -> 75; 1 -> 150; 2 -> 300; else -> 600
        }
        val colorMode = ScanColorMode.values()[spinnerColor.selectedItemPosition]
        val source    = ScanSource.values()[spinnerSource.selectedItemPosition]
        val format    = ScanFormat.values()[spinnerFormat.selectedItemPosition]

        viewModel.updateOptions(resolutionDpi, colorMode, source, format)
    }
}
