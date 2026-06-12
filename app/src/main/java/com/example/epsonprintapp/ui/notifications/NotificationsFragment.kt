package com.example.epsonprintapp.ui.notifications

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.epsonprintapp.R
import com.example.epsonprintapp.database.entities.NotificationEntity
import java.text.SimpleDateFormat
import java.util.*

class NotificationsFragment : Fragment() {

    // AndroidViewModel: la factory por defecto resuelve el constructor (Application)
    private val viewModel: NotificationsViewModel by viewModels()

    private lateinit var recyclerView:  RecyclerView
    private lateinit var emptyLayout:   View
    private lateinit var tvUnreadCount: TextView

    private val adapter = NotificationAdapter(
        onDelete = { notification -> viewModel.deleteNotification(notification.id) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_notifications, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView  = view.findViewById(R.id.recyclerNotifications)
        emptyLayout   = view.findViewById(R.id.tvEmpty)
        tvUnreadCount = view.findViewById(R.id.tvUnreadCount)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter       = adapter

        // Menú con MenuProvider (reemplaza API deprecada setHasOptionsMenu)
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                inflater.inflate(R.menu.menu_notifications, menu)
            }
            override fun onMenuItemSelected(item: MenuItem): Boolean {
                if (item.itemId == R.id.action_clear_all) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Borrar historial")
                        .setMessage("¿Eliminar todas las notificaciones?")
                        .setPositiveButton("Borrar") { _, _ -> viewModel.clearAll() }
                        .setNegativeButton("Cancelar", null)
                        .show()
                    return true
                }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        // Swipe-to-delete
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos         = vh.bindingAdapterPosition
                val currentList = adapter.getCurrentItems()
                if (pos >= 0 && pos < currentList.size) {
                    viewModel.deleteNotification(currentList[pos].id)
                }
            }
        }).attachToRecyclerView(recyclerView)

        viewModel.notifications.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            emptyLayout.visibility  = if (list.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (list.isEmpty()) View.GONE   else View.VISIBLE
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

        viewModel.markAllAsRead()
    }
}

// ── Adapter con DiffUtil manual ───────────────────────────────────────────────
//
// Se usa RecyclerView.Adapter directamente con DiffUtil.calculateDiff()
// en lugar de androidx.recyclerview.widget.ListAdapter para evitar la
// ambigüedad con android.widget.ListAdapter que estaba causando los errores.

class NotificationAdapter(
    private val onDelete: (NotificationEntity) -> Unit
) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    private var items: List<NotificationEntity> = emptyList()

    fun submitList(newList: List<NotificationEntity>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize()                              = items.size
            override fun getNewListSize()                              = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int)    = items[oldPos].id == newList[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = items[oldPos] == newList[newPos]
        })
        items = newList
        diff.dispatchUpdatesTo(this)
    }

    fun getCurrentItems(): List<NotificationEntity> = items

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvIcon:           TextView    = itemView.findViewById(R.id.tvNotifIcon)
        private val tvTitle:          TextView    = itemView.findViewById(R.id.tvNotifTitle)
        private val tvMessage:        TextView    = itemView.findViewById(R.id.tvNotifMessage)
        private val tvRecommendation: TextView    = itemView.findViewById(R.id.tvNotifRecommendation)
        private val tvDate:           TextView    = itemView.findViewById(R.id.tvNotifDate)
        private val viewUnread:       View        = itemView.findViewById(R.id.viewUnreadDot)
        private val btnDelete:        ImageButton = itemView.findViewById(R.id.btnDeleteNotif)

        fun bind(n: NotificationEntity) {
            tvIcon.text    = n.iconName
            tvTitle.text   = n.title
            tvMessage.text = n.message

            if (n.recommendation.isNullOrBlank()) {
                tvRecommendation.visibility = View.GONE
            } else {
                tvRecommendation.visibility = View.VISIBLE
                tvRecommendation.text       = "💡 ${n.recommendation}"
            }

            tvDate.text = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                .format(n.createdAt)

            viewUnread.visibility = if (n.isRead) View.GONE else View.VISIBLE

            itemView.setBackgroundColor(when (n.severity) {
                "ERROR"   -> 0xFFFFEBEE.toInt()
                "WARNING" -> 0xFFFFF8E1.toInt()
                else      -> 0xFFF1F8E9.toInt()
            })

            btnDelete.setOnClickListener { onDelete(n) }
        }
    }
}