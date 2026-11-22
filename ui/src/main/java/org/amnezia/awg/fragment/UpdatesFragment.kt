package org.amnezia.awg.fragment

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R
import org.amnezia.awg.util.UpdatePolicy
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdatesFragment : Fragment() {
    private data class Release(val tag: String, val notes: String, val apk: String)
    private var release: Release? = null
    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var install: MaterialButton
    private lateinit var check: MaterialButton
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }
        fun label(text: String, size: Float): TextView = TextView(context).apply {
            this.text = text; textSize = size
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, 0, 0, dp(16))
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        label(getString(R.string.mn_updates_heading), 28f)
        label(getString(R.string.mn_updates_description), 16f)
        val card = MaterialCardView(context).apply { radius = dp(24).toFloat(); cardElevation = 0f }
        val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        status = TextView(context).apply { textSize = 18f; text = getString(R.string.mn_installed, BuildConfig.VERSION_NAME) }
        notes = TextView(context).apply { textSize = 14f; setPadding(0, dp(16), 0, 0) }
        details.addView(status); details.addView(notes); card.addView(details); content.addView(card)
        fun button(title: Int): MaterialButton = MaterialButton(context).also {
            it.setText(title)
            content.addView(it, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(16) })
        }
        check = button(R.string.mn_check).apply { setOnClickListener { checkRelease() } }
        install = button(R.string.mn_install).apply { visibility = View.GONE; setOnClickListener { downloadAndInstall() } }
        button(R.string.mn_releases).setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))) }
        return ScrollView(context).apply { isFillViewport = true; addView(content) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkRelease()
    }

    // Public web redirects do not consume the unauthenticated REST API quota.
    private fun publicRelease(): Release {
        val connection = URL("$RELEASES/latest").openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 15000; connection.readTimeout = 15000
        try {
            check(connection.responseCode == 200)
            val prefix = "$RELEASES/tag/"
            val destination = connection.url.toString()
            require(destination.startsWith(prefix))
            val tag = destination.removePrefix(prefix)
            require(Regex("v?[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?").matches(tag))
            val version = tag.removePrefix("v")
            return Release(tag, "", "$RELEASES/download/$tag/AmneziaWG-MN-$version-arm64.apk")
        } finally { connection.disconnect() }
    }

    private fun checkRelease() {
        check.isEnabled = false
        status.setText(R.string.mn_checking)
        notes.text = ""
        release = null
        install.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                  try {
                    val connection = URL(API).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000; connection.readTimeout = 15000
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("User-Agent", "amneziaWG-MN/${BuildConfig.VERSION_NAME}")
                    try {
                        if (connection.responseCode == 404) return@withContext null
                        check(connection.responseCode == 200)
                        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        val assets = json.getJSONArray("assets")
                        var apk: String? = null
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.getString("name").lowercase()
                            if (name.endsWith(".apk") && ("arm64" in name || "universal" in name)) {
                                val url = asset.getString("browser_download_url")
                                if (url.startsWith("$RELEASES/download/")) { apk = url; break }
                            }
                        }
                        if (apk == null) return@withContext null
                        Release(json.getString("tag_name"), json.optString("body"), apk)
                    } finally { connection.disconnect() }
                  } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { publicRelease() }
                }
                release = result
                if (result == null) {
                    status.setText(R.string.mn_unavailable); notes.text = ""
                } else {
                    notes.text = result.notes.take(12000)
                    val current = result.tag.removePrefix("v") == BuildConfig.VERSION_NAME
                    status.text = if (current) getString(R.string.mn_current) else getString(R.string.mn_available, result.tag)
                    install.visibility = if (current) View.GONE else View.VISIBLE
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { status.setText(R.string.mn_update_error) }
            finally { check.isEnabled = true }
        }
    }

    @Suppress("DEPRECATION")
    private fun downloadAndInstall() {
        val chosen = release ?: return
        val context = requireContext()
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            status.setText(R.string.mn_installer_permission)
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        install.isEnabled = false; check.isEnabled = false
        status.setText(R.string.mn_downloading)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                    val temp = File(dir, "update.part")
                    val target = File(dir, "update.apk")
                    val connection = URL(chosen.apk).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000; connection.readTimeout = 30000
                    try {
                        check(connection.responseCode == 200)
                        connection.inputStream.use { input -> temp.outputStream().use { output ->
                            val buffer = ByteArray(32768); var total = 0L
                            while (true) { val count = input.read(buffer); if (count < 0) break
                                total += count; check(total <= 128L * 1024 * 1024); output.write(buffer, 0, count) }
                        } }
                        val pm = context.packageManager
                        val archive = pm.getPackageArchiveInfo(temp.path, PackageManager.GET_SIGNATURES) ?: error("Invalid APK")
                        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                        fun fingerprints(info: android.content.pm.PackageInfo) = info.signatures?.map {
                            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
                        }?.sorted()
                        val incomingCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
                        val currentCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
                        require(UpdatePolicy.accepts(context.packageName, currentCode, fingerprints(installed),
                            archive.packageName, incomingCode, fingerprints(archive)))
                        check(temp.renameTo(target)); target
                    } finally { connection.disconnect(); temp.delete() }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                status.text = getString(R.string.mn_available, chosen.tag)
            } catch (_: IllegalArgumentException) { status.setText(R.string.mn_bad_apk) }
              catch (_: Exception) { status.setText(R.string.mn_update_error) }
            finally { install.isEnabled = true; check.isEnabled = true }
        }
    }

    companion object {
        private const val RELEASES = "https://github.com/artemvarlapov2005/amneziaWG-MN/releases"
        private const val API = "https://api.github.com/repos/artemvarlapov2005/amneziaWG-MN/releases/latest"
    }
}
