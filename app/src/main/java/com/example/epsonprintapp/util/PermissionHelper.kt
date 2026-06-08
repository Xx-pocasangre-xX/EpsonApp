package com.example.epsonprintapp.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * PermissionHelper — Centraliza la lógica de permisos requeridos por la app.
 *
 * Permisos por categoría:
 * - RED: ACCESS_FINE_LOCATION (requerido para mDNS en Android 8+)
 * - ALMACENAMIENTO: READ_MEDIA_IMAGES, READ_MEDIA_VIDEO (Android 13+)
 *                   READ_EXTERNAL_STORAGE (Android ≤ 12)
 * - NOTIFICACIONES: POST_NOTIFICATIONS (Android 13+)
 *
 * Nota sobre ACCESS_FINE_LOCATION:
 * En Android 8.1+ el discovery de mDNS (NsdManager) requiere permiso de
 * ubicación porque las redes WiFi cercanas pueden revelar la ubicación del
 * usuario. Sin este permiso, discoverServices() falla silenciosamente.
 */
object PermissionHelper {

    // ── Permisos requeridos según versión ─────────────────────────────────────

    val NETWORK_PERMISSIONS: Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        else -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    val STORAGE_PERMISSIONS: Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        else -> emptyArray()
    }

    val NOTIFICATION_PERMISSIONS: Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.POST_NOTIFICATIONS
        )
        else -> emptyArray()
    }

    /**
     * Todos los permisos que la app necesita, agrupados para la solicitud inicial.
     */
    fun getAllRequiredPermissions(): Array<String> {
        return (NETWORK_PERMISSIONS + STORAGE_PERMISSIONS + NOTIFICATION_PERMISSIONS)
            .distinct()
            .toTypedArray()
    }

    /**
     * Retorna los permisos que aún no han sido otorgados.
     */
    fun getMissingPermissions(context: Context): Array<String> {
        return getAllRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                    PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    /**
     * ¿Todos los permisos críticos están otorgados?
     */
    fun hasAllPermissions(context: Context): Boolean {
        return getMissingPermissions(context).isEmpty()
    }

    /**
     * ¿El permiso de ubicación (necesario para mDNS) está otorgado?
     */
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * ¿Los permisos de almacenamiento están otorgados?
     */
    fun hasStoragePermissions(context: Context): Boolean {
        return STORAGE_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Intent para abrir la configuración de la app (cuando el usuario
     * denegó un permiso permanentemente).
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Descripción amigable de cada permiso para mostrar al usuario.
     */
    fun getPermissionRationale(permission: String): String = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION ->
            "📍 Ubicación: necesaria para descubrir impresoras en la red WiFi local (requerido por Android para mDNS)"
        Manifest.permission.READ_MEDIA_IMAGES ->
            "🖼️ Imágenes: para seleccionar fotos e imágenes para imprimir"
        Manifest.permission.READ_MEDIA_VIDEO ->
            "🎬 Videos: para acceder a archivos multimedia"
        Manifest.permission.READ_EXTERNAL_STORAGE ->
            "📁 Almacenamiento: para seleccionar archivos PDF e imágenes para imprimir"
        Manifest.permission.POST_NOTIFICATIONS ->
            "🔔 Notificaciones: para avisarte cuando termine de imprimir o escanear"
        else -> "Permiso requerido para el funcionamiento de la app"
    }
}