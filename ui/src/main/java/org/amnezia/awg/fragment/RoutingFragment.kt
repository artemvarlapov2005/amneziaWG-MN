package org.amnezia.awg.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.*
import org.amnezia.awg.Application
import org.amnezia.awg.R
import org.amnezia.awg.model.ObservableTunnel
import org.amnezia.awg.util.AppRouting
import org.amnezia.awg.util.ErrorMessages

class RoutingFragment : Fragment() {
    private data class App(val name: String, val pkg: String, val icon: Drawable?)
    private var apps = emptyList<App>()
    private var shown = emptyList<App>()
    private val selected = linkedSetOf<String>()
    private var tunnels = emptyList<ObservableTunnel>()
    private var current: ObservableTunnel? = null
    private var loadJob: Job? = null
    private var loading = false
    private var saving = false
    private var mode = 0
    private var query = ""
    private var restored: Bundle? = null
    private lateinit var status: TextView
    private lateinit var save: MaterialButton
    private lateinit var profiles: Spinner
    private lateinit var modes: RadioGroup
    private lateinit var adapter: BaseAdapter
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        restored = state
        val context = requireContext()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), 0) }
        fun label(text: String, size: Float = 14f) = TextView(context).apply { this.text = text; textSize = size; root.addView(this) }
        val title = label(getString(R.string.mn_routing), 24f)
        profiles = Spinner(context).also { root.addView(it, LinearLayout.LayoutParams(-1, dp(48))) }
        modes = RadioGroup(context).apply {
            listOf(R.string.mn_route_all, R.string.mn_route_include, R.string.mn_route_exclude).forEachIndexed { i, text ->
                addView(RadioButton(context).apply { id = View.generateViewId(); tag = i; setText(text); minHeight = dp(44) })
            }
            setOnCheckedChangeListener { group, id ->
                mode = group.findViewById<View>(id)?.tag as? Int ?: 0
                refreshStatus()
            }
        }
        root.addView(modes)
        val input = TextInputEditText(context).apply {
            setSingleLine(); hint = getString(R.string.mn_app_search)
            doAfterTextChanged { query = it.toString(); filter() }
        }
        root.addView(TextInputLayout(context).apply { addView(input) })
        status = label("")
        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64)
                    addView(ImageView(context), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(context).apply { textSize = 15f })
                        addView(TextView(context).apply { textSize = 11f })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(MaterialCheckBox(context).apply { isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
                }
                val app = shown[position]
                (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
                val labels = row.getChildAt(1) as LinearLayout
                (labels.getChildAt(0) as TextView).text = app.name
                (labels.getChildAt(1) as TextView).text = app.pkg
                (row.getChildAt(2) as MaterialCheckBox).isChecked = app.pkg in selected
                row.alpha = if (mode == 0) .45f else 1f
                row.contentDescription = "${app.name}, ${if (app.pkg in selected) getString(R.string.mn_selected) else getString(R.string.mn_not_selected)}"
                row.setOnClickListener {
                    if (mode != 0 && !saving && !loading) {
                        if (!selected.add(app.pkg)) selected.remove(app.pkg)
                        notifyDataSetChanged(); refreshStatus()
                    }
                }
                return row
            }
        }
        root.addView(ListView(context).apply { this.adapter = this@RoutingFragment.adapter; divider = null }, LinearLayout.LayoutParams(-1, 0, 1f))
        save = MaterialButton(context).apply { setText(R.string.mn_route_apply); setOnClickListener { applyRouting() } }
        root.addView(save, LinearLayout.LayoutParams(-1, dp(56)))
        val hint = label(getString(R.string.mn_route_reconnect), 12f)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val visibility = if (insets.isVisible(WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
            title.visibility = visibility; profiles.visibility = visibility
            modes.visibility = visibility; hint.visibility = visibility
            insets
        }
        ViewCompat.requestApplyInsets(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                tunnels = Application.getTunnelManager().getTunnels().toList()
                profiles.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, tunnels.map { it.name })
                profiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { load(tunnels[position]) }
                }
                val index = tunnels.indexOfFirst { it.name == restored?.getString("profile") }.coerceAtLeast(0)
                if (tunnels.isNotEmpty()) { profiles.setSelection(index); load(tunnels[index]) }
                else { status.setText(R.string.mn_route_no_profiles); save.isEnabled = false }
                apps = withContext(Dispatchers.IO) {
                    val pm = requireContext().applicationContext.packageManager
                    @Suppress("DEPRECATION")
                    val installed = pm.getPackagesHoldingPermissions(arrayOf(Manifest.permission.INTERNET), 0)
                    installed.map { p -> App(p.applicationInfo?.loadLabel(pm)?.toString() ?: p.packageName, p.packageName, p.applicationInfo?.loadIcon(pm)) }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                }
                filter()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (e: Exception) { status.text = ErrorMessages[e]; save.isEnabled = false }
        }
    }

    private fun load(tunnel: ObservableTunnel) {
        if (current === tunnel) return
        loadJob?.cancel(); current = tunnel; loading = true; save.isEnabled = false
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val config = tunnel.getConfigAsync()
                selected.clear()
                val iface = config.`interface`
                mode = if (iface.includedApplications.isNotEmpty()) 1 else if (iface.excludedApplications.isNotEmpty()) 2 else 0
                selected.addAll(if (mode == 1) iface.includedApplications else iface.excludedApplications)
                restored?.takeIf { it.getString("profile") == tunnel.name }?.let {
                    mode = it.getInt("mode"); selected.clear(); selected.addAll(it.getStringArrayList("apps") ?: emptyList())
                }
                restored = null
                modes.check(modes.getChildAt(mode).id)
                loading = false; filter()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (e: Exception) { status.text = ErrorMessages[e] }
        }
    }

    private fun filter() {
        if (!::adapter.isInitialized) return
        shown = apps.filter { it.name.contains(query, true) || it.pkg.contains(query, true) }
        adapter.notifyDataSetChanged(); refreshStatus()
    }
    private fun refreshStatus() {
        if (!::status.isInitialized || !::save.isInitialized) return
        save.isEnabled = current != null && !loading && !saving && (mode == 0 || selected.isNotEmpty())
        status.text = if (current == null) getString(R.string.mn_route_no_profiles) else if (mode != 0 && selected.isEmpty()) getString(R.string.mn_route_choose_apps) else getString(R.string.mn_route_count, selected.size)
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }
    private fun applyRouting() {
        val tunnel = current ?: return
        if (saving || loading || (mode != 0 && selected.isEmpty())) return
        val chosen = selected.toList(); val chosenMode = mode
        saving = true; profiles.isEnabled = false; modes.isEnabled = false
        for (i in 0 until modes.childCount) modes.getChildAt(i).isEnabled = false
        refreshStatus()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val config = tunnel.getConfigAsync()
                tunnel.setConfigAsync(AppRouting.apply(config, chosenMode, chosen))
                Toast.makeText(requireContext(), R.string.mn_route_saved, Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (e: Exception) { Toast.makeText(requireContext(), ErrorMessages[e], Toast.LENGTH_LONG).show() }
            finally {
                saving = false; profiles.isEnabled = true; modes.isEnabled = true
                for (i in 0 until modes.childCount) modes.getChildAt(i).isEnabled = true
                refreshStatus()
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("profile", current?.name); outState.putInt("mode", mode); outState.putStringArrayList("apps", ArrayList(selected))
        super.onSaveInstanceState(outState)
    }
    
}
