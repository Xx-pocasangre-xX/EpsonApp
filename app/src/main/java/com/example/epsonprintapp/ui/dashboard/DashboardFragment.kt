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
import com.example.epsonprintapp.printer.PrinterState

/**
 * DashboardFragment - Pantalla principal de la aplicación
 *
 * DISEÑO DE LA PANTALLA:
 * ======================
 * ┌────────────────────────────────┐
 * │ 🖨️ EPSON EcoTank L3560         │  ← Nombre de impresora
 * │ 🟢 Lista | 192.168.1.10        │  ← Estado + IP
 * ├────────────────────────────────┤
 * │ NIVELES DE TINTA               │
 * │ 🔵 Cyan    ████████░░ 80%      │
 * │ 🔴 Magenta █████░░░░░ 50%      │
 * │ 🟡 Yellow  ███████░░░ 70%      │
 * │ ⚫ Black   ██████████ 90%      │
 * ├────────────────────────────────┤
 * │ 📄 Papel: Con papel            │
 * ├────────────────────────────────┤
 * │ [🖨️ IMPRIMIR] [🔍 ESCANEAR]    │
 * │      [🔔 NOTIFICACIONES]       │
 * └────────────────────────────────┘
 *
 * OBSERVACIÓN DE LIVEDATA:
 * La UI "observa" los datos del ViewModel.
 * Cuando el ViewModel actualiza un valor, la UI reacciona automáticamente.
 * No hay polling manual ni callbacks anidados.
 */
class DashboardFragment : Fragment() {

    // ViewBinding: evita findViewById y garantiza type-safety
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    // ViewModel con la lógica de negocio
    // 'by viewModels()' crea el VM con el scope del Fragment
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

    // =========================================================================
    // OBSERVERS - Reaccionar a cambios en el ViewModel
    // =========================================================================

    private fun setupObservers() {

        // Observar estado de carga
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.isVisible = isLoading
            binding.swipeRefresh.isRefreshing = isLoading
        }

        // Observar si hay WiFi
        viewModel.isWifiConnected.observe(viewLifecycleOwner) { isConnected ->
            binding.wifiWarning.isVisible = !isConnected
            binding.mainContent.isVisible = isConnected
        }

        // Observar impresora actual
        viewModel.currentPrinter.observe(viewLifecycleOwner) { printer ->
            if (printer != null) {
                binding.textPrinterName.text = printer.name
                binding.textPrinterModel.text = printer.model ?: "Epson EcoTank"
                binding.textPrinterIp.text = "📡 ${printer.ipAddress}:${printer.ippPort}"
                binding.noPrinterLayout.isVisible = false
                binding.printerInfoCard.isVisible = true
            } else {
                binding.noPrinterLayout.isVisible = true
                binding.printerInfoCard.isVisible = false
            }
        }

        // Observar estado detallado de la impresora
        viewModel.printerStatus.observe(viewLifecycleOwner) { status ->
            if (status != null) {
                updatePrinterStatusUI(status)
            } else {
                showOfflineState()
            }
        }

        // Observar proceso de descubrimiento
        viewModel.isDiscovering.observe(viewLifecycleOwner) { isDiscovering ->
            binding.discoveryProgressBar.isVisible = isDiscovering
            binding.textDiscovering.isVisible = isDiscovering
            if (isDiscovering) {
                binding.textDiscovering.text = "🔍 Buscando impresoras en la red..."
            }
        }

        // Observar contador de notificaciones no leídas (badge)
        viewModel.unreadNotificationCount.observe(viewLifecycleOwner) { count ->
            if (count > 0) {
                binding.badgeNotifications.isVisible = true
                binding.badgeNotifications.text = if (count > 99) "99+" else count.toString()
            } else {
                binding.badgeNotifications.isVisible = false
            }
        }

        // Observar mensajes de error
        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }

    // =========================================================================
    // CLICK LISTENERS
    // =========================================================================

    private fun setupClickListeners() {
        // Botón IMPRIMIR → navegar a PrintFragment
        binding.buttonPrint.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_print)
        }

        // Botón ESCANEAR → navegar a ScanFragment
        binding.buttonScan.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_scan)
        }

        // Botón NOTIFICACIONES → navegar a NotificationsFragment
        binding.buttonNotifications.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_notifications)
        }

        // Pull-to-refresh → actualizar estado de impresora
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshPrinterStatus()
        }

        // Botón buscar impresoras (cuando no hay ninguna)
        binding.buttonSearchPrinter.setOnClickListener {
            viewModel.discoverPrinters()
        }

        // Toque en la tarjeta de impresora → ver detalles
        binding.printerInfoCard.setOnClickListener {
            // TODO: Navegar a pantalla de detalles de impresora
        }
    }

    // =========================================================================
    // ACTUALIZACIÓN DE UI
    // =========================================================================

    /**
     * Actualizar toda la UI de estado de impresora
     *
     * Esta función es el corazón del dashboard:
     * Traduce el estado técnico de la impresora en una UI visual clara.
     */
    private fun updatePrinterStatusUI(status: com.example.epsonprintapp.printer.PrinterStatus) {

        // ===== ESTADO PRINCIPAL =====
        binding.textPrinterStatus.text = status.displayStatus

        // Color del indicador de estado
        val statusColor = when (status.state) {
            PrinterState.IDLE -> {
                if (status.hasPaper) {
                    ContextCompat.getColor(requireContext(), R.color.status_ready)   // Verde
                } else {
                    ContextCompat.getColor(requireContext(), R.color.status_warning) // Naranja
                }
            }
            PrinterState.PROCESSING -> ContextCompat.getColor(requireContext(), R.color.status_printing) // Azul
            PrinterState.STOPPED -> ContextCompat.getColor(requireContext(), R.color.status_error)       // Rojo
            PrinterState.UNKNOWN -> ContextCompat.getColor(requireContext(), R.color.status_offline)     // Gris
        }
        binding.statusIndicator.setBackgroundColor(statusColor)

        // ===== ESTADO DEL PAPEL =====
        if (status.hasPaper) {
            binding.textPaperStatus.text = "📄 Con papel"
            binding.textPaperStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_success)
            )
        } else {
            binding.textPaperStatus.text = "⚠️ Sin papel"
            binding.textPaperStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_warning)
            )
        }

        // ===== NIVELES DE TINTA =====
        val inkLevels = status.inkLevels
        if (inkLevels.isAvailable) {
            binding.inkLevelsCard.isVisible = true

            // Función auxiliar para actualizar cada barra de tinta
            fun updateInkBar(level: Int,
                             progressBar: android.widget.ProgressBar,
                             textLevel: android.widget.TextView,
                             colorRes: Int) {
                if (level >= 0) {
                    progressBar.progress = level
                    textLevel.text = "$level%"
                    // Cambiar color según nivel: rojo si < 20%, verde si > 50%
                    val barColor = when {
                        level < 20 -> ContextCompat.getColor(requireContext(), R.color.ink_critical)
                        level < 50 -> ContextCompat.getColor(requireContext(), R.color.ink_low)
                        else -> colorRes
                    }
                    progressBar.progressTintList =
                        android.content.res.ColorStateList.valueOf(barColor)
                    progressBar.isVisible = true
                    textLevel.isVisible = true
                } else {
                    progressBar.isVisible = false
                    textLevel.isVisible = false
                }
            }

            // Actualizar cada color de tinta
            updateInkBar(
                inkLevels.cyan,
                binding.progressCyan,
                binding.textCyanLevel,
                ContextCompat.getColor(requireContext(), R.color.ink_cyan)
            )
            updateInkBar(
                inkLevels.magenta,
                binding.progressMagenta,
                binding.textMagentaLevel,
                ContextCompat.getColor(requireContext(), R.color.ink_magenta)
            )
            updateInkBar(
                inkLevels.yellow,
                binding.progressYellow,
                binding.textYellowLevel,
                ContextCompat.getColor(requireContext(), R.color.ink_yellow)
            )
            updateInkBar(
                inkLevels.black,
                binding.progressBlack,
                binding.textBlackLevel,
                ContextCompat.getColor(requireContext(), R.color.ink_black)
            )

        } else {
            // La impresora no reporta niveles de tinta via IPP
            binding.inkLevelsCard.isVisible = false
            binding.inkNotAvailable.isVisible = true
        }

        // ===== HABILITAR/DESHABILITAR BOTONES =====
        val canPrint = status.state == PrinterState.IDLE && status.hasPaper
        val canScan = status.state != PrinterState.STOPPED

        binding.buttonPrint.isEnabled = canPrint
        binding.buttonPrint.alpha = if (canPrint) 1.0f else 0.5f

        binding.buttonScan.isEnabled = canScan
        binding.buttonScan.alpha = if (canScan) 1.0f else 0.5f
    }

    /**
     * Mostrar estado offline cuando no se puede conectar a la impresora
     */
    private fun showOfflineState() {
        binding.textPrinterStatus.text = "Desconectada"
        binding.statusIndicator.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.status_offline)
        )
        binding.textPaperStatus.text = "Estado desconocido"
        binding.inkLevelsCard.isVisible = false
        binding.buttonPrint.isEnabled = false
        binding.buttonScan.isEnabled = false
    }

    // =========================================================================
    // CICLO DE VIDA
    // =========================================================================

    override fun onDestroyView() {
        super.onDestroyView()
        // IMPORTANTE: Limpiar binding para evitar memory leaks
        // El Fragment puede destruirse pero el ViewModel sobrevive
        _binding = null
    }
}
