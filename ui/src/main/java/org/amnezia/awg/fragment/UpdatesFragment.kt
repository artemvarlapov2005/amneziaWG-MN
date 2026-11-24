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
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.view.ContextThemeWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R
import org.amnezia.awg.updater.*
import org.amnezia.awg.util.ChannelRelease
import org.amnezia.awg.util.ChannelGate
import org.amnezia.awg.util.UpdateVersion
import org.amnezia.awg.activity.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import java.util.concurrent.TimeoutException

class UpdatesFragment : Fragment() {
    private var downloadJob: Job? = null
    private lateinit var cancel: MaterialButton
    private var release: ChannelRelease? = null
    private var selection: ChannelGate.Snapshot? = null
    private var checkJob: Job? = null
    private lateinit var channelButton: MaterialButton
    private lateinit var notes: TextView
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
        label(getString(R.string.mn_installed_channel, BuildConfig.VERSION_NAME, if (UpdateVersion.isBeta(BuildConfig.VERSION_NAME)) "Beta" else "Stable"))
        channelButton = button(R.string.mn_channel_label).apply { setOnClickListener { chooseChannel() } }
        updateChannelLabel()
        notes = label("", 14f)
        status = label(getString(R.string.mn_checking))
        check = button(R.string.mn_check).apply { setOnClickListener { checkRelease() } }
        install = button(R.string.mn_install).apply { visibility = View.GONE; setOnClickListener { downloadOrInstall() } }
        cancel = button(R.string.mn_download_cancel).apply {
            visibility = View.GONE
            setOnClickListener {
                UpdateRepository.cancelDownload(requireContext(), release?.sha256, selection ?: UpdateRepository.snapshot(requireContext()))
                downloadJob?.cancel()
            }
        }
        val auto = SwitchMaterial(context).apply {
            setText(R.string.mn_auto_enable); isChecked = UpdateScheduler.enabled(context)
        }
        content.addView(auto)
        label(getString(R.string.mn_auto_interval))
        val intervals = resources.getStringArray(R.array.mn_auto_intervals)
        val popupContext = ContextThemeWrapper(context, R.style.MnSpinnerPopup)
        val popupTheme = popupContext.theme
        val spinner = AppCompatSpinner(context, null, androidx.appcompat.R.attr.spinnerStyle, Spinner.MODE_DROPDOWN, popupTheme).apply {
            setPopupBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(MaterialColors.getColor(popupContext, com.google.android.material.R.attr.colorSurface, 0))
            })
            adapter = object : ArrayAdapter<String>(popupContext, android.R.layout.simple_spinner_dropdown_item, intervals) {
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        val normal = MaterialColors.getColor(popupContext, com.google.android.material.R.attr.colorOnSurface, 0)
                        val selected = MaterialColors.getColor(popupContext, com.google.android.material.R.attr.colorOnSecondaryContainer, normal)
                        // Resolve theme colors before constructing drawables. Some Android builds
                        // inflate a Spinner selector without a Theme (theme=null), which crashes
                        // when XML selector items contain ?attr/colorSecondaryContainer.
                        val highlight = MaterialColors.getColor(popupContext, com.google.android.material.R.attr.colorSecondaryContainer, 0)
                        background = StateListDrawable().apply {
                            for (state in intArrayOf(android.R.attr.state_pressed, android.R.attr.state_selected, android.R.attr.state_focused, android.R.attr.state_activated)) {
                                addState(intArrayOf(state), ColorDrawable(highlight))
                            }
                            addState(intArrayOf(), ColorDrawable(android.graphics.Color.TRANSPARENT))
                        }
                        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_selected), intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_activated), intArrayOf()), intArrayOf(selected, selected, selected, selected, normal)))
                    }
                }
            }
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
    private fun updateChannelLabel() {
        channelButton.text = getString(R.string.mn_channel_selected, if (UpdateRepository.channel(requireContext()) == "beta") "Beta" else "Stable")
    }
    private fun chooseChannel() {
        val beta = UpdateRepository.channel(requireContext()) == "beta"
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.mn_channel_label)
            .setSingleChoiceItems(arrayOf(getString(R.string.mn_channel_stable), getString(R.string.mn_channel_beta)), if (beta) 1 else 0) { dialog, position ->
                dialog.dismiss()
                val channel = if (position == 1) "beta" else "stable"
                if (channel == UpdateRepository.channel(requireContext())) return@setSingleChoiceItems
                if (channel == "beta") MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.mn_channel_beta)
                    .setMessage(R.string.mn_channel_beta_warning)
                    .setPositiveButton(R.string.mn_channel_enable_beta) { _, _ -> switchChannel(channel) }
                    .setNegativeButton(R.string.cancel, null).show()
                else switchChannel(channel)
            }.setNegativeButton(R.string.cancel, null).show()
    }
    private fun switchChannel(channel: String) {
        downloadJob?.cancel(); checkJob?.cancel()
        UpdateRepository.changeChannel(requireContext(), channel)
        release = null; selection = null; notes.text = ""
        install.isEnabled = true; cancel.visibility = View.GONE
        updateChannelLabel()
        (activity as? MainActivity)?.refreshPendingUpdateIndicator()
        checkRelease()
    }
    private fun checkRelease() {
        checkJob?.cancel()
        val context = requireContext().applicationContext
        val started = UpdateRepository.snapshot(context)
        release = null; selection = null; notes.text = ""
        check.isEnabled = false; install.visibility = View.GONE; status.setText(R.string.mn_checking)
        checkJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { UpdateRepository.latest(context) }
                if (!UpdateRepository.current(context, result.snapshot)) return@launch
                selection = result.snapshot; release = result.candidate
                val chosen = result.candidate
                if (chosen == null) {
                    status.setText(if (result.waitingForStable) R.string.mn_channel_wait_stable else R.string.mn_current)
                } else {
                    val ready = withContext(Dispatchers.IO) { UpdateRepository.withReady(context) { it.sha256 == chosen.sha256 } ?: false }
                    if (!UpdateRepository.current(context, result.snapshot)) return@launch
                    notes.text = chosen.notes.take(12000)
                    status.text = getString(if (ready) R.string.mn_auto_ready_body else R.string.mn_available, chosen.versionName)
                    install.setText(if (ready) R.string.mn_auto_install else R.string.mn_install)
                    install.visibility = View.VISIBLE
                }
            } catch (_: UpdateRepository.ChannelChangedException) { }
              catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) {
                if (UpdateRepository.current(context, started)) {
                    status.setText(R.string.mn_channel_check_failed)
                    if (withContext(Dispatchers.IO) { UpdateRepository.valid(context) } && UpdateRepository.current(context, started)) {
                        install.setText(R.string.mn_auto_install); install.visibility = View.VISIBLE
                    }
                }
            } finally { if (UpdateRepository.current(context, started)) check.isEnabled = true }
        }
    }
    private fun downloadOrInstall() {
        if (downloadJob?.isActive == true) return
        install.isEnabled = false; check.isEnabled = false
        cancel.visibility = View.VISIBLE
        status.setText(R.string.mn_downloading)
        val context = requireContext().applicationContext
        val expected = selection ?: UpdateRepository.snapshot(context)
        val chosen = release
        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val operation = coroutineContext[Job]
            val observer = launch {
                UpdateRepository.downloadProgress.state.collect { active ->
                    if (!UpdateRepository.current(context, expected) || operation?.isActive != true) return@collect
                    if (active == null || active.generation != expected.generation || (chosen != null && active.artifact != chosen.sha256)) {
                        status.setText(R.string.mn_download_waiting)
                    } else {
                        status.text = when (active.phase) {
                            UpdateDownloadProgress.Phase.CONNECTING -> if (active.route.isEmpty()) getString(R.string.mn_download_waiting) else getString(R.string.mn_download_connecting, active.route, active.attempt)
                            UpdateDownloadProgress.Phase.VERIFYING -> getString(R.string.mn_download_verifying)
                            UpdateDownloadProgress.Phase.DOWNLOADING -> {
                                val mb = String.format(Locale.getDefault(), "%.1f", active.bytes / 1048576.0)
                                if (active.total > 0) getString(R.string.mn_download_progress, mb,
                                    String.format(Locale.getDefault(), "%.1f", active.total / 1048576.0), (active.bytes * 100 / active.total).toInt())
                                else getString(R.string.mn_download_bytes, mb)
                            }
                        }
                    }
                }
            }
            try {
                withTimeout(130000) { withContext(Dispatchers.IO) {
                    if (chosen != null) UpdateRepository.download(context, chosen, expected)
                    require(UpdateRepository.valid(context))
                } }
                if (UpdateRepository.current(context, expected)) startActivity(Intent(requireContext(), UpdateInstallActivity::class.java))
            } catch (_: RoutedUpdateTransfer.TransferAbortedException) { if (UpdateRepository.current(context, expected)) status.setText(R.string.mn_download_cancelled) }
              catch (_: UpdateRepository.ChannelChangedException) { }
              catch (_: TimeoutCancellationException) { if (UpdateRepository.current(context, expected)) status.setText(R.string.mn_download_timeout) }
              catch (cancelled: CancellationException) {
                if (UpdateRepository.current(context, expected)) status.setText(R.string.mn_download_cancelled)
                throw cancelled
            } catch (_: TimeoutException) { if (UpdateRepository.current(context, expected)) status.setText(R.string.mn_download_timeout) }
              catch (_: java.net.SocketTimeoutException) { if (UpdateRepository.current(context, expected)) status.setText(R.string.mn_download_timeout) }
              catch (_: Exception) { if (UpdateRepository.current(context, expected)) status.setText(R.string.mn_download_failed) }
            finally {
                observer.cancel()
                if (UpdateRepository.current(context, expected)) { install.isEnabled = true; check.isEnabled = true; cancel.visibility = View.GONE }
            }
        }
    }
}
