package com.example.epsonprintapp.ui.notifications

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.*
import com.example.epsonprintapp.R
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.database.entities.NotificationEntity
import com.example.epsonprintapp.ui.ViewModelFactory
import java.text.SimpleDateFormat
import java.util.*

/**
 * NotificationsFragment — Muestra el historial de notificaciones persistentes.
 *
 * Características:
 * - RecyclerView con todas las notificaciones (Room → LiveData → Adapter)
 * - Swipe-to-delete en cada ítem
 * - Botón "Limpiar todo" en el menú de opciones
 * - Badge de no leídas se actualiza en tiempo real
 * - Al abrir la pantalla se marcan todas como leídas
 */
class NotificationsFragment : Fragment() {

    private val viewModel: NotificationsViewModel by viewModels {
        ViewModelFactory(database = AppDatabase.getInstance(requireContext()))
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvUnreadCount: TextView
    private val adapter = NotificationAdapter(
        onDelete = { notification -> viewModel.deleteNotification(notification.id) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_notifications, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView   = view.findViewById(R.id.recyclerNotifications)
        tvEmpty        = view.findViewById(R.id.tvEmpty)
        tvUnreadCount  = view.findViewById(R.id.tvUnreadCount)

        // Configurar RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Swipe-to-delete
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val notification = adapter.currentList[viewHolder.adapterPosition]
                viewModel.deleteNotification(notification.id)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView)

        // Observar datos
        viewModel.notifications.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            tvEmpty.visibility       = if (list.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility  = if (list.isEmpty()) View.GONE   else View.VISIBLE
        }

        viewModel.unreadCount.observe(viewLifecycleOwner) { count ->
            tvUnreadCount.text       = if (count > 0) "$count sin leer" else "Todo leído"
            tvUnreadCount.visibility = View.VISIBLE
        }

        viewModel.statusMessage.observe(viewLifecycleOwner) { msg ->
            msg?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                viewModel.clearStatusMessage()
            }
        }

        // Al abrir esta pantalla → marcar todas como leídas
        viewModel.markAllAsRead()
    }

    // ── Menú opciones ────────────────────────────────────────────────────────────
    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_notifications, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_all -> {
                AlertDialog.Builder(requireContext())
                    .setTitle("Borrar historial")
                    .setMessage("¿Eliminar todas las notificaciones?")
                    .setPositiveButton("Borrar") { _, _ -> viewModel.clearAll() }
                    .setNegativeButton("Cancelar", null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// RecyclerView Adapter
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Adapter para la lista de notificaciones.
 * Usa ListAdapter + DiffUtil para actualizaciones eficientes.
 */
class NotificationAdapter(
    private val onDelete: (NotificationEntity) -> Unit
) : ListAdapter<NotificationEntity, NotificationAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvIcon:           TextView = itemView.findViewById(R.id.tvNotifIcon)
        private val tvTitle:          TextView = itemView.findViewById(R.id.tvNotifTitle)
        private val tvMessage:        TextView = itemView.findViewById(R.id.tvNotifMessage)
        private val tvRecommendation: TextView = itemView.findViewById(R.id.tvNotifRecommendation)
        private val tvDate:           TextView = itemView.findViewById(R.id.tvNotifDate)
        private val viewUnread:       View     = itemView.findViewById(R.id.viewUnreadDot)

        fun bind(notification: NotificationEntity) {
            // Icono según tipo (emoji como texto)
            tvIcon.text = notification.iconName

            tvTitle.text   = notification.title
            tvMessage.text = notification.message

            // Mostrar recomendación solo si existe
            if (notification.recommendation.isNullOrBlank()) {
                tvRecommendation.visibility = View.GONE
            } else {
                tvRecommendation.visibility = View.VISIBLE
                tvRecommendation.text       = "💡 ${notification.recommendation}"
            }

            // Formatear fecha
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            tvDate.text = sdf.format(notification.createdAt)

            // Punto de no leído
            viewUnread.visibility = if (notification.isRead) View.GONE else View.VISIBLE

            // Color de fondo según severidad
            val bgColor = when (notification.severity) {
                "ERROR"   -> 0xFFFFEBEE.toInt() // Rojo claro
                "WARNING" -> 0xFFFFF8E1.toInt() // Amarillo claro
                else      -> 0xFFF1F8E9.toInt() // Verde claro (éxito/info)
            }
            itemView.setBackgroundColor(bgColor)

            // Botón eliminar
            itemView.findViewById<ImageButton>(R.id.btnDeleteNotif).setOnClickListener {
                onDelete(notification)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<NotificationEntity>() {
        override fun areItemsTheSame(old: NotificationEntity, new: NotificationEntity) =
            old.id == new.id
        override fun areContentsTheSame(old: NotificationEntity, new: NotificationEntity) =
            old == new
    }
}
