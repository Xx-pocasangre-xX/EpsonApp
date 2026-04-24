package com.example.epsonprintapp.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.epsonprintapp.R
import com.example.epsonprintapp.databinding.FragmentDashboardBinding
import com.example.epsonprintapp.databinding.ItemInkLevelBinding
import com.example.epsonprintapp.printer.PrinterState

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupObservers()
        setupClickListeners()
    }

    // ── Observers ──────────────────────────────────────────────────────────────

    private fun setupObservers() {

        viewModel.isWifiConnected.observe(viewLifecycleOwner) { isConnected ->
            if (!isConnected) {
                Toast.makeText(requireContext(),
                    "Sin conexión WiFi. Conéctate a la misma red que la impresora.",
                    Toast.LENGTH_LONG).show()
            }
        }

        viewModel.currentPrinter.observe(viewLifecycleOwner) { printer ->
            if (printer != null) {
                binding.tvPrinterName.text = printer.name
                binding.tvPrinterIp.text   = "📡 ${printer.ipAddress}:${printer.ippPort}"
            } else {
                binding.tvPrinterName.text = getString(R.string.printer_no_printer)
                binding.tvPrinterIp.text   = ""
            }
        }

        viewModel.printerStatus.observe(viewLifecycleOwner) { status ->
            if (status != null) updatePrinterStatusUI(status)
            else showOfflineState()
        }

        viewModel.isDiscovering.observe(viewLifecycleOwner) { isDiscovering ->
            binding.layoutDiscovering.isVisible = isDiscovering
        }

        viewModel.unreadNotificationCount.observe(viewLifecycleOwner) { count ->
            val label = if (count > 0) "🔔  Notificaciones ($count)" else "🔔  Notificaciones"
            binding.btnNotifications.text = label
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }

    // ── Click listeners ────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnPrint.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_print)
        }
        binding.btnScan.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_scan)
        }
        binding.btnNotifications.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_notifications)
        }
        binding.btnRefresh.setOnClickListener {
            viewModel.refreshPrinterStatus()
        }
        binding.btnDiscover.setOnClickListener {
            viewModel.discoverPrinters()
        }
    }

    // ── UI update ──────────────────────────────────────────────────────────────

    private fun updatePrinterStatusUI(status: com.example.epsonprintapp.printer.PrinterStatus) {

        binding.tvPrinterState.text = status.displayStatus

        val statusColor = when (status.state) {
            PrinterState.IDLE       -> ContextCompat.getColor(requireContext(),
                if (status.hasPaper) R.color.status_online else R.color.status_warning)
            PrinterState.PROCESSING -> ContextCompat.getColor(requireContext(), R.color.status_processing)
            PrinterState.STOPPED    -> ContextCompat.getColor(requireContext(), R.color.status_error)
            PrinterState.UNKNOWN    -> ContextCompat.getColor(requireContext(), R.color.status_unknown)
        }
        binding.viewStatusIndicator.backgroundTintList =
            android.content.res.ColorStateList.valueOf(statusColor)

        binding.tvPaperStatus.text = if (status.hasPaper) "Con papel ✅" else "Sin papel ⚠️"
        binding.tvPaperStatus.setTextColor(
            ContextCompat.getColor(requireContext(),
                if (status.hasPaper) R.color.status_online else R.color.status_warning)
        )

        val ink = status.inkLevels
        binding.cardInk.isVisible = ink.isAvailable

        if (ink.isAvailable) {
            // FIX: ViewBinding turns each <include> into an ItemInkLevelBinding, not a View
            updateInkRow(binding.inkBlack,   "Negro",    ink.black,   R.color.ink_black)
            updateInkRow(binding.inkCyan,    "Cian",     ink.cyan,    R.color.ink_cyan)
            updateInkRow(binding.inkMagenta, "Magenta",  ink.magenta, R.color.ink_magenta)
            updateInkRow(binding.inkYellow,  "Amarillo", ink.yellow,  R.color.ink_yellow)
        }

        val canPrint = status.state == PrinterState.IDLE && status.hasPaper
        val canScan  = status.state != PrinterState.STOPPED
        binding.btnPrint.isEnabled = canPrint
        binding.btnPrint.alpha     = if (canPrint) 1f else 0.5f
        binding.btnScan.isEnabled  = canScan
        binding.btnScan.alpha      = if (canScan) 1f else 0.5f
    }

    /**
     * When ViewBinding is enabled, <include> tags with an android:id become
     * typed binding objects (ItemInkLevelBinding), not raw Views.
     * Access child views directly through the binding instead of findViewById.
     */
    private fun updateInkRow(
        inkBinding: ItemInkLevelBinding,
        label: String,
        level: Int,
        colorRes: Int
    ) {
        inkBinding.tvInkLabel.text = label

        if (level >= 0) {
            inkBinding.progressInk.progress = level
            inkBinding.tvInkPercent.text    = "$level%"
            val barColor = when {
                level < 20 -> ContextCompat.getColor(requireContext(), R.color.ink_low)
                level < 50 -> ContextCompat.getColor(requireContext(), R.color.ink_medium)
                else       -> ContextCompat.getColor(requireContext(), colorRes)
            }
            inkBinding.progressInk.progressTintList =
                android.content.res.ColorStateList.valueOf(barColor)
            inkBinding.root.isVisible = true
        } else {
            inkBinding.tvInkPercent.text    = "N/D"
            inkBinding.progressInk.progress = 0
        }
    }

    private fun showOfflineState() {
        binding.tvPrinterState.text = getString(R.string.printer_state_offline)
        binding.viewStatusIndicator.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.status_unknown)
            )
        binding.tvPaperStatus.text = getString(R.string.paper_unknown)
        binding.btnPrint.isEnabled = false
        binding.btnScan.isEnabled  = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}