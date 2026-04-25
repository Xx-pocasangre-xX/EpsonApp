package com.example.epsonprintapp

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
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
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        // Forzar siempre tema claro, ignorar modo oscuro del sistema
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)

        // Permitir que el contenido se dibuje detrás de la status bar y nav bar
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_main)

        // Aplicar insets: la Toolbar recibe el padding top de la status bar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                view.paddingBottom
            )
            // Ajustar altura del toolbar para incluir la status bar
            val params = view.layoutParams
            params.height = resources.getDimensionPixelSize(
                com.google.android.material.R.dimen.m3_appbar_size_compact
            ) + systemBars.top
            view.layoutParams = params
            insets
        }

        // La BottomNav recibe padding bottom de la navigation bar
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigationView)
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                systemBars.bottom
            )
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
                R.id.scanFragment
            )
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        bottomNav.setupWithNavController(navController)

        // ── Badge de notificaciones no leídas ──────────────────────────────
        observeUnreadNotifications(bottomNav)

        // ── Ocultar BottomNav en pantallas secundarias ──────────────────────
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.notificationsFragment -> {
                    bottomNav.visibility = View.GONE
                }
                else -> {
                    bottomNav.visibility = View.VISIBLE
                }
            }
        }
    }

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