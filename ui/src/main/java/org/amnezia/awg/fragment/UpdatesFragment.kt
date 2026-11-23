package org.amnezia.awg.fragment

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.*
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R
import org.amnezia.awg.updater.*

class UpdatesFragment : Fragment() {
    private var release: UpdateRepository.Release? = null
    private lateinit var status: TextView
    private lateinit var install: MaterialButton
    private lateinit var check: MaterialButton
    private lateinit var notificationHint: MaterialButton
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshNotifications() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        fun label(text: String, size: Float = 16f) = TextView(context).apply {
            this.text = text; textSize = size; setPadding(0, 0, 0, dp(12)); content.addView(this)
        }
        fun button(text: Int) = MaterialButton(context).also {
            it.setText(text); content.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        label(getString(R.string.mn_updates_heading), 26f)
        label(getString(R.string.mn_installed, BuildConfig.VERSION_NAME))
        status = label(getString(R.string.mn_checking))
        check = button(R.string.mn_check).apply { setOnClickListener { checkRelease() } }
        install = button(R.string.mn_install).apply { visibility = View.GONE; setOnClickListener { downloadOrInstall() } }
        val auto = SwitchMaterial(context).apply {
            setText(R.string.mn_auto_enable); isChecked = UpdateScheduler.enabled(context)
        }
        content.addView(auto)
        label(getString(R.string.mn_auto_interval))
        val intervals = resources.getStringArray(R.array.mn_auto_intervals)
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, intervals)
            setSelection(UpdateScheduler.intervals.indexOf(UpdateScheduler.minutes(context)).coerceAtLeast(0))
            isEnabled = auto.isChecked
        }
        content.addView(spinner, LinearLayout.LayoutParams(-1, dp(48)))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                UpdateScheduler.configure(context, auto.isChecked, UpdateScheduler.intervals[position])
            }
        }
        auto.setOnCheckedChangeListener { _, enabled ->
            spinner.isEnabled = enabled
            UpdateScheduler.configure(context, enabled, UpdateScheduler.intervals[spinner.selectedItemPosition.coerceAtLeast(0)])
            if (enabled) askNotifications()
        }
        label(getString(R.string.mn_auto_explanation), 13f)
        notificationHint = button(R.string.mn_auto_notifications).apply {
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) askNotifications()
                else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
        button(R.string.mn_releases).setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateRepository.RELEASES))) }
        return ScrollView(context).apply { addView(content) }
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        checkRelease()
        if (UpdateScheduler.enabled(requireContext()) && !UpdateScheduler.prefs(requireContext()).getBoolean("notification_asked", false)) askNotifications()
    }
    override fun onResume() { super.onResume(); refreshNotifications() }
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            UpdateScheduler.prefs(requireContext()).edit().putBoolean("notification_asked", true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun refreshNotifications() {
        if (::notificationHint.isInitialized) notificationHint.visibility = if (NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) View.GONE else View.VISIBLE
    }
    private fun checkRelease() {
        check.isEnabled = false; install.visibility = View.GONE; status.setText(R.string.mn_checking)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                release = withContext(Dispatchers.IO) { UpdateRepository.latest() }
                val chosen = release!!
                if (!UpdateRepository.newer(chosen)) status.setText(R.string.mn_current)
                else {
                    val ready = withContext(Dispatchers.IO) { UpdateRepository.valid(requireContext()) && UpdateScheduler.prefs(requireContext()).getString("downloaded", null) == chosen.tag }
                    status.text = getString(if (ready) R.string.mn_auto_ready_body else R.string.mn_available, chosen.version)
                    install.setText(if (ready) R.string.mn_auto_install else R.string.mn_install)
                    install.visibility = View.VISIBLE
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) {
                status.setText(R.string.mn_update_error)
                if (withContext(Dispatchers.IO) { UpdateRepository.valid(requireContext()) }) {
                    install.setText(R.string.mn_auto_install); install.visibility = View.VISIBLE
                }
            } finally { check.isEnabled = true }
        }
    }
    private fun downloadOrInstall() {
        install.isEnabled = false; check.isEnabled = false
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val chosen = release
                    if (chosen != null && UpdateRepository.newer(chosen)) UpdateRepository.download(context, chosen)
                    require(UpdateRepository.valid(context))
                }
                startActivity(Intent(requireContext(), UpdateInstallActivity::class.java))
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { status.setText(R.string.mn_update_error) }
            finally { install.isEnabled = true; check.isEnabled = true }
        }
    }
}
