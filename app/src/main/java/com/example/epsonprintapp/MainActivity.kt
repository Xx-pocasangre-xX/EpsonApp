package com.example.epsonprintapp

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // FIX: register the Toolbar as the ActionBar BEFORE calling
        // setupActionBarWithNavController, otherwise it throws
        // "does not have an ActionBar set via setSupportActionBar()"
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
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

        // ── BottomNavigationView ────────────────────────────────────────────
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigationView)
        bottomNav.setupWithNavController(navController)

        // ── Badge de notificaciones no leídas ──────────────────────────────
        observeUnreadNotifications(bottomNav)

        // ── Ocultar BottomNav y Toolbar en pantallas secundarias ────────────
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