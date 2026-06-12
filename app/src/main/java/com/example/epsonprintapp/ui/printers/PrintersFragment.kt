package com.example.epsonprintapp.ui.printers

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.epsonprintapp.R
import com.example.epsonprintapp.database.entities.PrinterEntity
import com.example.epsonprintapp.printer.PrinterStatus
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PrintersFragment — Pantalla de gestión de múltiples impresoras.
 */
class PrintersFragment : Fragment() {

    // viewModels() funciona porque PrintersViewModel(Application) hereda de AndroidViewModel
    private val viewModel: PrintersViewModel by viewModels()

    private lateinit var recyclerView:    RecyclerView
    private lateinit var emptyView:       View
    private lateinit var progressBar:     ProgressBar
    private lateinit var tvStatus:        TextView
    private lateinit var btnDiscover:     MaterialButton
    private lateinit var fabAddByIp:      ExtendedFloatingActionButton
    private lateinit var cardNetworkInfo: MaterialCardView
    private lateinit var tvDeviceIp:      TextView
    private lateinit var tvNetworkStatus: TextView

    private val adapter = PrintersAdapter(
        onSetDefault = { printer -> viewModel.setDefaultPrinter(printer.id) },
        onRename     = { printer -> showRenameDialog(printer) },
        onDelete     = { printer -> showDeleteConfirmation(printer) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_printers, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupRecyclerView()
        observeViewModel()
        setupClickListeners()
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        recyclerView    = view.findViewById(R.id.rvPrinters)
        emptyView       = view.findViewById(R.id.layoutEmpty)
        progressBar     = view.findViewById(R.id.progressDiscovery)
        tvStatus        = view.findViewById(R.id.tvDiscoveryStatus)
        btnDiscover     = view.findViewById(R.id.btnDiscover)
        fabAddByIp      = view.findViewById(R.id.fabAddByIp)
        cardNetworkInfo = view.findViewById(R.id.cardNetworkInfo)
        tvDeviceIp      = view.findViewById(R.id.tvDeviceIp)
        tvNetworkStatus = view.findViewById(R.id.tvNetworkStatus)
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter       = adapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos     = vh.bindingAdapterPosition
                val printer = adapter.getCurrentItems().getOrNull(pos)
                if (printer != null) showDeleteConfirmation(printer)
                adapter.notifyItemChanged(pos)
            }
        }).attachToRecyclerView(recyclerView)
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeViewModel() {

        viewModel.savedPrinters.observe(viewLifecycleOwner) { printers ->
            val statuses = viewModel.printerStatuses.value ?: emptyMap()
            adapter.submitList(printers, statuses)
            emptyView.isVisible    = printers.isEmpty()
            recyclerView.isVisible = printers.isNotEmpty()
        }

        viewModel.printerStatuses.observe(viewLifecycleOwner) { statuses ->
            val printers = viewModel.savedPrinters.value ?: emptyList()
            adapter.submitList(printers, statuses)
        }

        viewModel.isDiscovering.observe(viewLifecycleOwner) { discovering ->
            progressBar.isVisible = discovering
            btnDiscover.isEnabled = !discovering
            btnDiscover.text      = if (discovering) "⏳ Buscando…" else "🔎 Buscar en red"
        }

        viewModel.isAddingByIp.observe(viewLifecycleOwner) { adding ->
            fabAddByIp.isEnabled = !adding
        }

        viewModel.discoveryStatus.observe(viewLifecycleOwner) { status ->
            val hasText    = !status.isNullOrBlank()
            tvStatus.isVisible = hasText
            tvStatus.text      = status ?: ""
        }

        viewModel.networkInfo.observe(viewLifecycleOwner) { info ->
            if (info == null) return@observe
            tvDeviceIp.text = "📱 IP del dispositivo: ${info.deviceIp}"
            when {
                info.hasLocalNet -> {
                    tvNetworkStatus.text = "✅ Red local detectada (${info.networkPrefix}.x)"
                    tvNetworkStatus.setTextColor(0xFF2E7D32.toInt())
                }
                info.hasAnyNet -> {
                    tvNetworkStatus.text = "⚠️ Conectado sin red local (VPN/Datos)"
                    tvNetworkStatus.setTextColor(0xFFF57F17.toInt())
                }
                else -> {
                    tvNetworkStatus.text = "❌ Sin conexión de red"
                    tvNetworkStatus.setTextColor(0xFFC62828.toInt())
                }
            }
        }

        viewModel.actionResult.observe(viewLifecycleOwner) { result ->
            if (result == null) return@observe
            val message: String
            val bgColor: Int
            when (result) {
                is ActionResult.Success -> {
                    message = result.message
                    bgColor = 0xFF2E7D32.toInt()
                }
                is ActionResult.Info -> {
                    message = result.message
                    bgColor = 0xFF1565C0.toInt()
                }
                is ActionResult.Warning -> {
                    message = result.message
                    bgColor = 0xFFF57F17.toInt()
                }
                is ActionResult.Error -> {
                    message = result.message
                    bgColor = 0xFFC62828.toInt()
                }
            }
            Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
                .setBackgroundTint(bgColor)
                .setTextColor(0xFFFFFFFF.toInt())
                .show()
            viewModel.clearActionResult()
        }
    }

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        btnDiscover.setOnClickListener {
            viewModel.refreshNetworkInfo()
            viewModel.startDiscovery()
        }
        fabAddByIp.setOnClickListener {
            showAddByIpDialog()
        }
        cardNetworkInfo.setOnClickListener {
            viewModel.refreshNetworkInfo()
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showAddByIpDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_printer_ip, null)

        val etIp   = dialogView.findViewById<TextInputEditText>(R.id.etIpAddress)
        val etPort = dialogView.findViewById<TextInputEditText>(R.id.etPort)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etPrinterName)
        val tilIp  = dialogView.findViewById<TextInputLayout>(R.id.tilIpAddress)

        // Pre-llenar con el prefijo de red detectado
        viewModel.networkInfo.value?.networkPrefix?.let { prefix ->
            etIp.setText("$prefix.")
            etIp.setSelection(etIp.text?.length ?: 0)
        }
        etPort.setText("631")

        AlertDialog.Builder(requireContext())
            .setTitle("➕ Agregar impresora por IP")
            .setView(dialogView)
            .setPositiveButton("Conectar") { _, _ ->
                val ip   = etIp.text?.toString()?.trim() ?: ""
                val port = etPort.text?.toString()?.trim()?.toIntOrNull() ?: 631
                val name = etName.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

                if (ip.isEmpty()) {
                    tilIp.error = "Ingresa una dirección IP"
                    return@setPositiveButton
                }
                viewModel.addPrinterByIp(ip, name, port)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showRenameDialog(printer: PrinterEntity) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(printer.name)
            selectAll()
            setPadding(48, 24, 48, 12)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("✏️ Renombrar impresora")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) viewModel.renamePrinter(printer, newName)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showDeleteConfirmation(printer: PrinterEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle("🗑️ Eliminar impresora")
            .setMessage(
                "¿Eliminar '${printer.name}' (${printer.ipAddress}) del registro?\n\n" +
                        "Esto no afecta a la impresora física."
            )
            .setPositiveButton("Eliminar") { _, _ ->
                viewModel.deletePrinter(printer)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

// ── Adapter ───────────────────────────────────────────────────────────────────

class PrintersAdapter(
    private val onSetDefault: (PrinterEntity) -> Unit,
    private val onRename:     (PrinterEntity) -> Unit,
    private val onDelete:     (PrinterEntity) -> Unit,
) : RecyclerView.Adapter<PrintersAdapter.ViewHolder>() {

    private var items:    List<PrinterEntity>    = emptyList()
    private var statuses: Map<Long, PrinterStatus?> = emptyMap()

    fun submitList(
        newItems:    List<PrinterEntity>,
        newStatuses: Map<Long, PrinterStatus?>
    ) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize()                     = items.size
            override fun getNewListSize()                     = newItems.size
            override fun areItemsTheSame(o: Int, n: Int)     = items[o].id == newItems[n].id
            override fun areContentsTheSame(o: Int, n: Int)  =
                items[o] == newItems[n] &&
                        statuses[items[o].id] == newStatuses[newItems[n].id]
        })
        items    = newItems
        statuses = newStatuses
        diff.dispatchUpdatesTo(this)
    }

    fun getCurrentItems(): List<PrinterEntity> = items

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_printer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], statuses[items[position].id])
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName:       TextView    = itemView.findViewById(R.id.tvPrinterName)
        private val tvIp:         TextView    = itemView.findViewById(R.id.tvPrinterIp)
        private val tvModel:      TextView    = itemView.findViewById(R.id.tvPrinterModel)
        private val tvStatus:     TextView    = itemView.findViewById(R.id.tvPrinterStatus)
        private val tvLastSeen:   TextView    = itemView.findViewById(R.id.tvLastSeen)
        private val viewOnline:   View        = itemView.findViewById(R.id.viewOnlineIndicator)
        private val badgeDefault: TextView    = itemView.findViewById(R.id.badgeDefault)
        private val btnMenu:      ImageButton = itemView.findViewById(R.id.btnPrinterMenu)
        private val tvInkSummary: TextView    = itemView.findViewById(R.id.tvInkSummary)

        fun bind(printer: PrinterEntity, status: PrinterStatus?) {
            tvName.text  = printer.name
            tvIp.text    = "📡 ${printer.ipAddress}:${printer.ippPort}"
            tvModel.text = printer.model?.takeIf { it.isNotBlank() } ?: "Modelo desconocido"

            badgeDefault.isVisible = printer.isDefault
            tvLastSeen.text        = "Visto: ${formatTimestamp(printer.lastSeen)}"

            // Estado online / offline
            if (printer.isOnline) {
                viewOnline.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
                tvStatus.text = status?.displayStatus ?: "Online"
                tvStatus.setTextColor(0xFF2E7D32.toInt())
            } else {
                viewOnline.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF9E9E9E.toInt())
                tvStatus.text = "Fuera de línea"
                tvStatus.setTextColor(0xFF9E9E9E.toInt())
            }

            // Resumen de tinta
            val ink = status?.inkLevels
            if (ink != null && ink.isAvailable) {
                val parts = mutableListOf<String>()
                if (ink.black   == -2) parts.add("⬛∞")
                else if (ink.black   >= 0) parts.add("⬛${ink.black}%")
                if (ink.cyan    == -2) parts.add("🔵∞")
                else if (ink.cyan    >= 0) parts.add("🔵${ink.cyan}%")
                if (ink.magenta == -2) parts.add("🔴∞")
                else if (ink.magenta >= 0) parts.add("🔴${ink.magenta}%")
                if (ink.yellow  == -2) parts.add("🟡∞")
                else if (ink.yellow  >= 0) parts.add("🟡${ink.yellow}%")

                tvInkSummary.text      = parts.joinToString("  ")
                tvInkSummary.isVisible = parts.isNotEmpty()
            } else {
                tvInkSummary.isVisible = false
            }

            // Menú contextual
            btnMenu.setOnClickListener { view ->
                val popup = android.widget.PopupMenu(view.context, view)
                popup.inflate(R.menu.menu_printer_item)
                popup.menu.findItem(R.id.action_set_default)?.isEnabled = !printer.isDefault
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_set_default -> { onSetDefault(printer); true }
                        R.id.action_rename      -> { onRename(printer);     true }
                        R.id.action_delete      -> { onDelete(printer);     true }
                        else                    -> false
                    }
                }
                popup.show()
            }

            // Long press → predeterminar
            itemView.setOnLongClickListener {
                if (!printer.isDefault) onSetDefault(printer)
                true
            }
        }

        private fun formatTimestamp(ts: Long): String {
            if (ts == 0L) return "nunca"
            val diff = System.currentTimeMillis() - ts
            return when {
                diff < 60_000L     -> "hace unos segundos"
                diff < 3_600_000L  -> "hace ${diff / 60_000} min"
                diff < 86_400_000L -> "hace ${diff / 3_600_000} h"
                else               -> SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                    .format(Date(ts))
            }
        }
    }
}