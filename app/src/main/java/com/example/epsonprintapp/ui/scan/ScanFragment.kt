package com.example.epsonprintapp.ui.scan

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.epsonprintapp.R
import com.google.android.material.card.MaterialCardView

class ScanFragment : Fragment() {

    private val viewModel: ScanViewModel by viewModels()

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var imagePreview:  ImageView
    private lateinit var btnScan:       View
    private lateinit var btnScanMore:   View
    private lateinit var btnModeColor:  View
    private lateinit var btnModeBW:     View
    private lateinit var btnSaveImages: View
    private lateinit var btnSavePdf:    View
    private lateinit var btnClearPages: View
    private lateinit var progressBar:   ProgressBar
    private lateinit var tvStatus:      TextView
    private lateinit var tvPageCount:   TextView
    private lateinit var cardPages:     MaterialCardView
    private lateinit var cardPreview:   MaterialCardView
    private lateinit var cardSave:      MaterialCardView
    private lateinit var rvPages:       RecyclerView

    private lateinit var pagesAdapter: ScannedPagesAdapter

    private val savePdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { viewModel.savePdfToUri(it, requireContext().contentResolver) }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupPagesAdapter()
        observeViewModel()
        setupClickListeners()
        // Color por defecto resaltado
        setColorSelected(true)
    }

    // ── Bind views ─────────────────────────────────────────────────────────────
    private fun bindViews(view: View) {
        imagePreview  = view.findViewById(R.id.imagePreview)
        btnScan       = view.findViewById(R.id.btnScan)
        btnScanMore   = view.findViewById(R.id.btnScanMore)
        btnModeColor  = view.findViewById(R.id.btnModeColor)
        btnModeBW     = view.findViewById(R.id.btnModeBW)
        btnSaveImages = view.findViewById(R.id.btnSaveImages)
        btnSavePdf    = view.findViewById(R.id.btnSavePdf)
        btnClearPages = view.findViewById(R.id.btnClearPages)
        progressBar   = view.findViewById(R.id.progressBar)
        tvStatus      = view.findViewById(R.id.tvStatus)
        tvPageCount   = view.findViewById(R.id.tvPageCount)
        cardPages     = view.findViewById(R.id.cardPages)
        cardPreview   = view.findViewById(R.id.cardPreview)
        cardSave      = view.findViewById(R.id.cardSave)
        rvPages       = view.findViewById(R.id.rvPages)
    }

    // ── Pages adapter ──────────────────────────────────────────────────────────
    private fun setupPagesAdapter() {
        pagesAdapter = ScannedPagesAdapter { position ->
            val pages = viewModel.scannedPages.value ?: return@ScannedPagesAdapter
            if (position < pages.size) {
                imagePreview.setImageBitmap(pages[position])
                cardPreview.isVisible = true
            }
        }
        rvPages.layoutManager = LinearLayoutManager(
            requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvPages.adapter = pagesAdapter
    }

    // ── Observe ────────────────────────────────────────────────────────────────
    private fun observeViewModel() {
        viewModel.isScanning.observe(viewLifecycleOwner) { scanning ->
            progressBar.isVisible  = scanning
            btnScan.isEnabled      = !scanning
            btnScanMore.isEnabled  = !scanning && (viewModel.scannedPages.value?.isNotEmpty() == true)
            btnSaveImages.isEnabled = !scanning
            btnSavePdf.isEnabled    = !scanning
        }

        viewModel.statusMessage.observe(viewLifecycleOwner) { msg ->
            tvStatus.text = msg ?: "Listo para escanear"
        }

        // Última página → preview grande
        viewModel.scannedBitmap.observe(viewLifecycleOwner) { bitmap ->
            if (bitmap != null) {
                imagePreview.setImageBitmap(bitmap)
                cardPreview.isVisible = true
            }
        }

        // Páginas acumuladas → miniaturas + mostrar/ocultar cards
        viewModel.scannedPages.observe(viewLifecycleOwner) { pages ->
            pagesAdapter.submitList(pages)
            val count = pages.size
            cardPages.isVisible     = count > 0
            cardSave.isVisible      = count > 0
            btnScanMore.isEnabled   = count > 0 && viewModel.isScanning.value == false
            tvPageCount.text        = "📄 $count ${if (count == 1) "página escaneada" else "páginas escaneadas"}"
        }

        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            result?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Click listeners ────────────────────────────────────────────────────────
    private fun setupClickListeners() {
        // Selección de modo color
        btnModeColor.setOnClickListener {
            viewModel.setColorMode(true)
            setColorSelected(true)
        }
        btnModeBW.setOnClickListener {
            viewModel.setColorMode(false)
            setColorSelected(false)
        }

        // Escanear (nueva sesión)
        btnScan.setOnClickListener {
            viewModel.startScan(appendToExisting = false)
        }

        // Agregar otra página al lote actual
        btnScanMore.setOnClickListener {
            viewModel.scanNextPage()
        }

        // Limpiar todas las páginas
        btnClearPages.setOnClickListener {
            viewModel.clearPages()
            cardPreview.isVisible = false
        }

        // Guardar como imagen(es)
        btnSaveImages.setOnClickListener {
            viewModel.saveAsImages(requireContext())
        }

        // Guardar como PDF
        btnSavePdf.setOnClickListener {
            val pageCount = viewModel.scannedPages.value?.size ?: 0
            val timestamp = System.currentTimeMillis()
            val fileName  = if (pageCount == 1) "scan_$timestamp.pdf"
            else "scan_${pageCount}pag_$timestamp.pdf"
            savePdfLauncher.launch(fileName)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Resalta visualmente el modo seleccionado (Color vs B&N) */
    private fun setColorSelected(colorSelected: Boolean) {
        // Intercambia los estilos: el activo es filled, el inactivo es outlined
        val colorBtn  = btnModeColor  as? com.google.android.material.button.MaterialButton
        val bwBtn     = btnModeBW     as? com.google.android.material.button.MaterialButton

        if (colorSelected) {
            colorBtn?.alpha = 1.0f
            bwBtn?.alpha    = 0.5f
        } else {
            colorBtn?.alpha = 0.5f
            bwBtn?.alpha    = 1.0f
        }
    }
}

// ── Adapter de miniaturas ─────────────────────────────────────────────────────

class ScannedPagesAdapter(
    private val onPageClick: (Int) -> Unit
) : RecyclerView.Adapter<ScannedPagesAdapter.PageViewHolder>() {

    private var pages: List<Bitmap> = emptyList()

    fun submitList(newPages: List<Bitmap>) {
        pages = newPages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = android.widget.FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(240, 320).apply { marginEnd = 8 }
        }
        val imageView = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        // Número de página encima
        val badge = TextView(parent.context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = android.view.Gravity.BOTTOM }
            setBackgroundColor(0xAA000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setPadding(4, 2, 4, 4)
        }
        container.addView(imageView)
        container.addView(badge)
        return PageViewHolder(container, imageView, badge)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.imageView.setImageBitmap(pages[position])
        holder.badge.text = "Pág. ${position + 1}"
        holder.itemView.setOnClickListener { onPageClick(position) }
    }

    override fun getItemCount() = pages.size

    class PageViewHolder(
        itemView: View,
        val imageView: ImageView,
        val badge: TextView
    ) : RecyclerView.ViewHolder(itemView)
}