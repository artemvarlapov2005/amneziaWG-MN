/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.activity

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBar
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import org.amnezia.awg.R
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.amnezia.awg.updater.UpdateRepository
import org.amnezia.awg.updater.UpdateScheduler
import org.amnezia.awg.updater.UpdateInstallActivity
import org.amnezia.awg.widget.MnBottomBar
import org.amnezia.awg.fragment.RoutingFragment
import org.amnezia.awg.fragment.UpdatesFragment
import org.amnezia.awg.fragment.TunnelDetailFragment
import org.amnezia.awg.fragment.TunnelEditorFragment
import org.amnezia.awg.model.ObservableTunnel

/**
 * CRUD interface for AmneziaWG tunnels. This activity serves as the main entry point to the
 * AmneziaWG application, and contains several fragments for listing, viewing details of, and
 * editing the configuration and interface state of AmneziaWG tunnels.
 */
class MainActivity : BaseActivity(), FragmentManager.OnBackStackChangedListener {
    private val updateNotificationsPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    private var promptedUpdate: String? = null
    private var updatesSelected = false
    private var actionBar: ActionBar? = null
    private var isTwoPaneLayout = false
    private var backPressedCallback: OnBackPressedCallback? = null

    private fun handleBackPressed() {
        if (updatesSelected) { findViewById<MnBottomBar>(R.id.bottom_navigation).selectedItemId = R.id.tab_configs; return }
        val backStackEntries = supportFragmentManager.backStackEntryCount
        // If the two-pane layout does not have an editor open, going back should exit the app.
        if (isTwoPaneLayout && backStackEntries <= 1) {
            finish()
            return
        }

        if (backStackEntries >= 1)
            supportFragmentManager.popBackStack()

        // Deselect the current tunnel on navigating back from the detail pane to the one-pane list.
        if (backStackEntries == 1)
            selectedTunnel = null
    }

    override fun onBackStackChanged() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        backPressedCallback?.isEnabled = updatesSelected || backStackEntries >= 1
        if (actionBar == null) return
        // Do not show the home menu when the two-pane layout is at the detail view (see above).
        val minBackStackEntries = if (isTwoPaneLayout) 2 else 1
        actionBar!!.setDisplayHomeAsUpEnabled(backStackEntries >= minBackStackEntries)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        promptedUpdate = savedInstanceState?.getString("prompted_update")
        setContentView(R.layout.main_activity)
        actionBar = supportActionBar
        isTwoPaneLayout = findViewById<View?>(R.id.master_detail_wrapper) != null
        supportFragmentManager.addOnBackStackChangedListener(this)
        backPressedCallback = onBackPressedDispatcher.addCallback(this) { handleBackPressed() }
        val navigation = findViewById<MnBottomBar>(R.id.bottom_navigation)
        navigation.setOnItemSelectedListener { item ->
            updatesSelected = item.itemId != R.id.tab_configs
            findViewById<View>(if (isTwoPaneLayout) R.id.master_detail_wrapper else R.id.detail_container).visibility = if (updatesSelected) View.GONE else View.VISIBLE
            val routing = item.itemId == R.id.tab_routing
            findViewById<View>(R.id.updates_container).visibility = if (item.itemId == R.id.tab_updates) View.VISIBLE else View.GONE
            findViewById<View>(R.id.routing_container).visibility = if (routing) View.VISIBLE else View.GONE
            val tag = if (routing) "ROUTING" else "UPDATES"
            val container = if (routing) R.id.routing_container else R.id.updates_container
            if (updatesSelected && supportFragmentManager.findFragmentByTag(tag) == null) {
                supportFragmentManager.commit { add(container, if (routing) RoutingFragment() else UpdatesFragment(), tag) }
            }
            invalidateOptionsMenu()
            onBackStackChanged()
            true
        }
        navigation.selectedItemId = savedInstanceState?.getInt("mn_tab", R.id.tab_configs) ?: R.id.tab_configs
        if (android.os.Build.VERSION.SDK_INT >= 33 && UpdateScheduler.enabled(this) &&
            !UpdateScheduler.prefs(this).getBoolean("notification_asked", false) &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            UpdateScheduler.prefs(this).edit().putBoolean("notification_asked", true).apply()
            updateNotificationsPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        onBackStackChanged()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) { UpdateRepository.valid(this@MainActivity) }
            if (isFinishing || supportFragmentManager.isStateSaved || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@launch
            findViewById<MnBottomBar>(R.id.bottom_navigation).showUpdateBadge(ready)
            val tag = UpdateScheduler.prefs(this@MainActivity).getString("downloaded", null)
            if (ready && tag != null && promptedUpdate != tag) {
                promptedUpdate = tag
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.mn_auto_ready_title)
                    .setMessage(getString(R.string.mn_auto_ready_body, tag.removePrefix("v")))
                    .setPositiveButton(R.string.mn_auto_install) { _, _ -> startActivity(Intent(this@MainActivity, UpdateInstallActivity::class.java)) }
                    .setNegativeButton(R.string.mn_auto_later, null).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("prompted_update", promptedUpdate)
        outState.putInt("mn_tab", findViewById<MnBottomBar>(R.id.bottom_navigation).selectedItemId)
        super.onSaveInstanceState(outState)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_action_edit)?.isVisible = !updatesSelected && selectedTunnel != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // The back arrow in the action bar should act the same as the back button.
                onBackPressedDispatcher.onBackPressed()
                true
            }

            R.id.menu_action_edit -> {
                supportFragmentManager.commit {
                    replace(R.id.detail_container, TunnelEditorFragment())
                    setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    addToBackStack(null)
                }
                true
            }
            // This menu item is handled by the editor fragment.
            R.id.menu_action_save -> false
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSelectedTunnelChanged(
        oldTunnel: ObservableTunnel?,
        newTunnel: ObservableTunnel?
    ): Boolean {
        val fragmentManager = supportFragmentManager
        if (fragmentManager.isStateSaved) {
            return false
        }

        val backStackEntries = fragmentManager.backStackEntryCount
        if (newTunnel == null) {
            // Clear everything off the back stack (all editors and detail fragments).
            fragmentManager.popBackStackImmediate(0, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            return true
        }
        if (backStackEntries == 2) {
            // Pop the editor off the back stack to reveal the detail fragment. Use the immediate
            // method to avoid the editor picking up the new tunnel while it is still visible.
            fragmentManager.popBackStackImmediate()
        } else if (backStackEntries == 0) {
            // Create and show a new detail fragment.
            fragmentManager.commit {
                add(R.id.detail_container, TunnelDetailFragment())
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                addToBackStack(null)
            }
        }
        return true
    }
}
