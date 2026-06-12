package com.example.epsonprintapp.ui.notifications

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.epsonprintapp.appContainer
import com.example.epsonprintapp.database.entities.NotificationEntity
import kotlinx.coroutines.launch

/**
 * NotificationsViewModel — Gestiona la lista de notificaciones persistentes.
 *
 * Todas las notificaciones se guardan en Room cuando ocurren eventos
 * (impresión exitosa, error, tinta baja, etc.). Este ViewModel las expone
 * como LiveData para que el Fragment las muestre en un RecyclerView.
 */
class NotificationsViewModel(application: Application) : AndroidViewModel(application) {

    private val notificationDao = application.appContainer.database.notificationDao()

    // ── LiveData expuesto al Fragment ───────────────────────────────────────────

    /** Lista completa de notificaciones (más recientes primero) */
    val notifications: LiveData<List<NotificationEntity>> =
        notificationDao.getAllNotifications().asLiveData()

    /** Conteo de notificaciones no leídas (para el badge) */
    val unreadCount: LiveData<Int> =
        notificationDao.getUnreadCount().asLiveData()

    /** Mensaje de estado para operaciones de borrado/marcado */
    private val _statusMessage = MutableLiveData<String?>()
    val statusMessage: LiveData<String?> = _statusMessage

    // ── Acciones del usuario ────────────────────────────────────────────────────

    /** Marcar todas como leídas (al abrir la pantalla de notificaciones). */
    fun markAllAsRead() {
        viewModelScope.launch {
            notificationDao.markAllAsRead()
        }
    }

    /** Marcar una notificación individual como leída. */
    fun markAsRead(notificationId: Long) {
        viewModelScope.launch {
            notificationDao.markAsRead(notificationId)
        }
    }

    /** Eliminar una notificación por su ID. */
    fun deleteNotification(notificationId: Long) {
        viewModelScope.launch {
            notificationDao.deleteById(notificationId)
            _statusMessage.value = "Notificación eliminada"
        }
    }

    /** Limpiar todas las notificaciones. */
    fun clearAll() {
        viewModelScope.launch {
            notificationDao.clearAll()
            _statusMessage.value = "Historial borrado"
        }
    }

    /** Limpiar mensaje de estado después de mostrarlo */
    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
