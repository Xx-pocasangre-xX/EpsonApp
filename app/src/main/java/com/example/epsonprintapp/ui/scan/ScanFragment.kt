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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ScanFragment : Fragment() {

    private val viewModel: ScanViewModel by viewModels()

    // Tipos explícitos — antes eran View casteados silenciosamente
    private lateinit var imagePreview:  ImageView
    private lateinit var btnScan:       MaterialButton
    private lateinit var btnScanMore:   MaterialButton
    private lateinit var btnModeColor:  MaterialButton
    private lateinit var btnModeBW:     MaterialButton
    private lateinit var btnSaveImages: MaterialButton
    private lateinit var btnSavePdf:    MaterialButton
    private lateinit var btnClearPages: MaterialButton
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
        uri?.let { viewModel.savePdfToUri(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupPagesAdapter()
        observeViewModel()
        setupClickListeners()
        setColorSelected(true)
    }

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

    private fun setupPagesAdapter() {
        pagesAdapter = ScannedPagesAdapter(
            onPageClick = { position ->
                val thumbs = viewModel.thumbnails.value ?: return@ScannedPagesAdapter
                if (position < thumbs.size) {
                    imagePreview.setImageBitmap(thumbs[position])
                    cardPreview.isVisible = true
                }
            },
            onPageDelete = { position ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Eliminar página")
                    .setMessage("¿Eliminar la página ${position + 1}?")
                    .setPositiveButton("Eliminar") { _, _ -> viewModel.deletePage(position) }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        )
        rvPages.layoutManager = LinearLayoutManager(
            requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvPages.adapter = pagesAdapter
    }

    private fun observeViewModel() {

        viewModel.isScanning.observe(viewLifecycleOwner) { scanning ->
            progressBar.isVisible   = scanning
            btnScan.isEnabled       = !scanning
            val hasData             = viewModel.hasScannedData.value == true
            btnScanMore.isEnabled   = !scanning && hasData
            btnSaveImages.isEnabled = !scanning && hasData
            btnSavePdf.isEnabled    = !scanning && hasData
        }

        viewModel.statusMessage.observe(viewLifecycleOwner) { msg ->
            tvStatus.text = msg ?: "Listo para escanear"
        }

        viewModel.lastPageThumbnail.observe(viewLifecycleOwner) { thumb ->
            if (thumb != null) {
                imagePreview.setImageBitmap(thumb)
                cardPreview.isVisible = true
            }
        }

        viewModel.thumbnails.observe(viewLifecycleOwner) { thumbs ->
            pagesAdapter.submitList(thumbs)
            cardPages.isVisible = thumbs.isNotEmpty()
        }

        viewModel.hasScannedData.observe(viewLifecycleOwner) { hasData ->
            cardSave.isVisible    = hasData
            btnScanMore.isEnabled = hasData && viewModel.isScanning.value == false
        }

        viewModel.pageCount.observe(viewLifecycleOwner) { count ->
            tvPageCount.text = if (count > 0)
                "📄 $count ${if (count == 1) "página" else "páginas"}" else ""
        }

        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            result?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
        }
    }

    private fun setupClickListeners() {
        btnModeColor.setOnClickListener { viewModel.setColorMode(true);  setColorSelected(true) }
        btnModeBW.setOnClickListener    { viewModel.setColorMode(false); setColorSelected(false) }

        btnScan.setOnClickListener { viewModel.startScan(appendToExisting = false) }

        btnScanMore.setOnClickListener { viewModel.scanNextPage() }

        btnClearPages.setOnClickListener {
            val count = viewModel.pageCount.value ?: 0
            if (count == 0) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Borrar escaneos")
                .setMessage("¿Eliminar las $count páginas?")
                .setPositiveButton("Borrar") { _, _ ->
                    viewModel.clearPages()
                    cardPreview.isVisible = false
                    cardPages.isVisible   = false
                    cardSave.isVisible    = false
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        btnSaveImages.setOnClickListener { viewModel.saveAsImages() }

        btnSavePdf.setOnClickListener {
            val count = viewModel.pageCount.value ?: 0
            val ts    = System.currentTimeMillis()
            savePdfLauncher.launch(
                if (count == 1) "scan_$ts.pdf" else "scan_${count}pag_$ts.pdf"
            )
        }
    }

    private fun setColorSelected(colorSelected: Boolean) {
        btnModeColor.alpha = if (colorSelected) 1.0f else 0.5f
        btnModeBW.alpha    = if (colorSelected) 0.5f else 1.0f
    }
}

// ── Adapter con DiffUtil ──────────────────────────────────────────────────────

class ScannedPagesAdapter(
    private val onPageClick:  (Int) -> Unit,
    private val onPageDelete: (Int) -> Unit
) : RecyclerView.Adapter<ScannedPagesAdapter.PageViewHolder>() {

    private var pages: List<Bitmap> = emptyList()

    // submitList con notifyDataSetChanged solo cuando la lista cambia de tamaño
    // Para una implementación completa, migrar a ListAdapter con DiffUtil de Bitmap por referencia
    fun submitList(newPages: List<Bitmap>) {
        pages = newPages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = android.widget.FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(220, 300).apply { marginEnd = 10 }
        }
        val imageView = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        val badge = TextView(parent.context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = android.view.Gravity.BOTTOM }
            setBackgroundColor(0xAA000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f; gravity = android.view.Gravity.CENTER
            setPadding(4, 4, 4, 6)
        }
        val deleteBtn = TextView(parent.context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(32, 32).also {
                it.gravity = android.view.Gravity.TOP or android.view.Gravity.END
                it.topMargin = 4; it.rightMargin = 4
            }
            text = "✕"; textSize = 13f; gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xCCF44336.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
        container.addView(imageView); container.addView(badge); container.addView(deleteBtn)
        return PageViewHolder(container, imageView, badge, deleteBtn)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val bmp = pages.getOrNull(position)
        if (bmp != null && !bmp.isRecycled) holder.imageView.setImageBitmap(bmp)
        else holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
        holder.badge.text = "Pág. ${position + 1}"
        holder.itemView.setOnClickListener  { onPageClick(position) }
        holder.deleteBtn.setOnClickListener { onPageDelete(position) }
    }

    override fun getItemCount() = pages.size

    class PageViewHolder(
        itemView: View, val imageView: ImageView,
        val badge: TextView, val deleteBtn: TextView
    ) : RecyclerView.ViewHolder(itemView)
}