package com.example.epsonprintapp

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.epsonprintapp.database.AppDatabase
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

/**
 * MainActivity — Actividad principal de la aplicación.
 *
 * Arquitectura de navegación:
 * - Navigation Component con un NavHostFragment como contenedor
 * - BottomNavigationView para las tres secciones principales:
 *   Dashboard → Imprimir → Escanear
 * - Las Notificaciones se acceden desde el Dashboard (botón dedicado)
 *
 * El badge de notificaciones no leídas se actualiza en tiempo real
 * observando el Flow de Room.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ── Configurar Navigation Component ────────────────────────────────────
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Top-level destinations: no mostrar botón "atrás" en estos fragments
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.dashboardFragment,
                R.id.printFragment,
                R.id.scanFragment
            )
        )

        // ── ActionBar con Navigation ────────────────────────────────────────────
        setupActionBarWithNavController(navController, appBarConfiguration)

        // ── BottomNavigationView ────────────────────────────────────────────────
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigationView)
        bottomNav.setupWithNavController(navController)

        // ── Badge de notificaciones no leídas ──────────────────────────────────
        observeUnreadNotifications(bottomNav)

        // ── Ocultar BottomNav en pantallas secundarias ──────────────────────────
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.notificationsFragment -> bottomNav.visibility = View.GONE
                else                       -> bottomNav.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Observa el conteo de notificaciones no leídas y actualiza el badge
     * del BottomNavigationView en tiempo real.
     */
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

    // ── Manejar botón "atrás" de la ActionBar ───────────────────────────────────
    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}
