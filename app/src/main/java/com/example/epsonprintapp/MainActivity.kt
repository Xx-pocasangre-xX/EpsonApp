package com.example.epsonprintapp

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.epsonprintapp.database.AppDatabase
import com.example.epsonprintapp.util.PermissionHelper
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
    }

    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        // Aplicar insets a la Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, systemBars.top, view.paddingRight, view.paddingBottom)
            val params = view.layoutParams
            params.height = resources.getDimensionPixelSize(
                com.google.android.material.R.dimen.m3_appbar_size_compact
            ) + systemBars.top
            view.layoutParams = params
            insets
        }

        // Aplicar insets al BottomNav
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigationView)
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
            insets
        }

        setSupportActionBar(toolbar)

        // ── Navigation Component ────────────────────────────────────────────
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.dashboardFragment,
                R.id.printFragment,
                R.id.scanFragment,
                R.id.printersFragment      // ← Nueva pestaña
            )
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        bottomNav.setupWithNavController(navController)

        // Badge de notificaciones no leídas
        observeUnreadNotifications(bottomNav)

        // Ocultar BottomNav en pantallas secundarias
        navController.addOnDestinationChangedListener { _, destination, _ ->
            bottomNav.visibility = when (destination.id) {
                R.id.notificationsFragment -> View.GONE
                else                       -> View.VISIBLE
            }
        }

        // ── Solicitar permisos al inicio ───────────────────────────────────
        checkAndRequestPermissions()
    }

    // ── Permisos ──────────────────────────────────────────────────────────────

    /**
     * Verifica si faltan permisos y los solicita mostrando primero
     * un diálogo explicativo para cada grupo.
     */
    private fun checkAndRequestPermissions() {
        val missing = PermissionHelper.getMissingPermissions(this)
        if (missing.isEmpty()) return  // Ya tenemos todo

        // Mostrar diálogo explicativo antes de solicitar
        showPermissionRationaleDialog(missing)
    }

    private fun showPermissionRationaleDialog(permissions: Array<String>) {
        val rationaleText = permissions
            .map { PermissionHelper.getPermissionRationale(it) }
            .distinct()
            .joinToString("\n\n")

        AlertDialog.Builder(this)
            .setTitle("🔐 Permisos necesarios")
            .setMessage(
                "Esta app necesita los siguientes permisos para funcionar correctamente:\n\n" +
                        "$rationaleText\n\n" +
                        "Por favor, otorga los permisos en la siguiente pantalla."
            )
            .setPositiveButton("Entendido") { _, _ ->
                requestPermissions(permissions)
            }
            .setNegativeButton("Ahora no") { _, _ ->
                // Mostrar snackbar indicando que algunas funciones pueden no estar disponibles
                showPermissionWarningSnackbar()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestPermissions(permissions: Array<String>) {
        ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return

        val denied = permissions.filterIndexed { index, _ ->
            grantResults.getOrElse(index) { PackageManager.PERMISSION_DENIED } ==
                    PackageManager.PERMISSION_DENIED
        }

        when {
            denied.isEmpty() -> {
                // Todos los permisos otorgados
                Snackbar.make(
                    findViewById(android.R.id.content),
                    "✅ Permisos otorgados correctamente",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            denied.any { it.contains("LOCATION") } -> {
                // Falta el permiso de ubicación — mDNS no funcionará
                showCriticalPermissionDeniedDialog()
            }
            else -> {
                // Algunos permisos opcionales denegados
                showPermissionWarningSnackbar()
            }
        }
    }

    /**
     * Diálogo cuando el permiso de ubicación (crítico para mDNS) fue denegado.
     */
    private fun showCriticalPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Permiso de ubicación requerido")
            .setMessage(
                "El permiso de ubicación es necesario para descubrir impresoras en la red WiFi.\n\n" +
                        "Sin este permiso, la búsqueda automática de impresoras no funcionará.\n\n" +
                        "Puedes:\n" +
                        "• Ir a Ajustes y otorgar el permiso manualmente\n" +
                        "• Agregar tu impresora manualmente por IP en la pestaña 'Impresoras'"
            )
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                PermissionHelper.openAppSettings(this)
            }
            .setNegativeButton("Agregar IP manualmente") { _, _ ->
                // Navegar a la pantalla de impresoras
                navController.navigate(R.id.printersFragment)
            }
            .show()
    }

    private fun showPermissionWarningSnackbar() {
        Snackbar.make(
            findViewById(android.R.id.content),
            "⚠️ Algunos permisos no otorgados. Funciones limitadas.",
            Snackbar.LENGTH_LONG
        ).setAction("Ajustes") {
            PermissionHelper.openAppSettings(this)
        }.show()
    }

    // ── Badge notificaciones ──────────────────────────────────────────────────

    private fun observeUnreadNotifications(bottomNav: BottomNavigationView) {
        lifecycleScope.launch {
            AppDatabase.getInstance(this@MainActivity)
                .notificationDao()
                .getUnreadCount()
                .collect { count ->
                    val badge = bottomNav.getOrCreateBadge(R.id.dashboardFragment)
                    if (count > 0) {
                        badge.isVisible = true
                        badge.number    = count
                    } else {
                        badge.isVisible = false
                    }
                }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}