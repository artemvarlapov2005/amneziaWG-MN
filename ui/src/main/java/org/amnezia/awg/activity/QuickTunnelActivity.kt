package org.amnezia.awg.activity

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.amnezia.awg.Application
import org.amnezia.awg.R
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection

class QuickTunnelActivity : AppCompatActivity() {
    private data class Invite(val token: String, val name: String)
    private class InviteUnavailable : IOException()

    private var readClipboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (intent.action == Intent.ACTION_VIEW) {
            confirm(intent.dataString.orEmpty())
        } else {
            readClipboard = true
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !readClipboard) return
        readClipboard = false
        val clipboard = getSystemService(ClipboardManager::class.java)
        val link = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.let { it.text?.toString() ?: it.uri?.toString() }
        if (link.isNullOrBlank()) {
            confirm("")
            showStatus(R.string.qtun_clipboard_empty)
        } else {
            confirm(link)
        }
    }

    private lateinit var statusView: TextView
    private lateinit var progressView: View

    private fun confirm(link: String) {
        val invite = parse(link)
        supportActionBar?.hide()
        setContentView(R.layout.quick_tunnel_import)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.qtun_scroll)) { view, insets ->
            val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        statusView = findViewById(R.id.qtun_status)
        progressView = findViewById(R.id.qtun_progress)
        val nameLayout = findViewById<TextInputLayout>(R.id.qtun_name_layout)
        val name = findViewById<EditText>(R.id.qtun_name_input)
        val submit = findViewById<MaterialButton>(R.id.qtun_submit)
        findViewById<MaterialButton>(R.id.qtun_cancel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.qtun_subtitle).text = getString(R.string.qtun_confirm, HOST)
        if (invite == null) {
            nameLayout.visibility = View.GONE
            submit.visibility = View.GONE
            showStatus(R.string.qtun_invalid)
            return
        }
        name.setText(invite.name)
        submit.setOnClickListener {
            val chosen = name.text.toString().trim()
            if (Tunnel.isNameInvalid(chosen)) {
                nameLayout.error = getString(R.string.tunnel_error_invalid_name)
                return@setOnClickListener
            }
            nameLayout.error = null
            submit.isEnabled = false
            name.isEnabled = false
            progressView.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    showStatus(R.string.qtun_fetching)
                    importInvite(invite, chosen)
                } finally {
                    if (!isFinishing) {
                        progressView.visibility = View.GONE
                        submit.isEnabled = true
                        name.isEnabled = true
                    }
                }
            }
        }
    }

    private fun showStatus(message: Int) {
        statusView.setText(message)
        statusView.visibility = View.VISIBLE
    }

    private suspend fun importInvite(invite: Invite, name: String) = importLock.withLock {
        val key = digest(invite.token)
        val prefs = getSharedPreferences("quick_tunnel", MODE_PRIVATE)
        val manager = Application.getTunnelManager()
        var stage = "prepare"
        try {
            if (prefs.getBoolean("$key.complete", false)) {
                showStatus(R.string.qtun_already_used)
                return@withLock
            }
            val attempt = prefs.getString("$key.attempt", null) ?: newSecret()
            val pendingName = prefs.getString("$key.name", null)
            val pendingDigest = prefs.getString("$key.config", null)
            val tunnels = manager.getTunnels()
            val pending = pendingName?.let { prior -> tunnels.firstOrNull { it.name == prior } }
            val alreadySaved = pending != null && pendingDigest != null &&
                digest(pending.getConfigAsync().toAwgQuickString()) == pendingDigest
            if (!alreadySaved) {
                if (tunnels.any { it.name == name }) {
                    showStatus(R.string.qtun_name_taken)
                    return@withLock
                }
                withContext(Dispatchers.IO) {
                    check(prefs.edit().putString("$key.attempt", attempt).commit())
                }
                stage = "download"
                val bytes = withContext(Dispatchers.IO) { checkNotNull(request(invite.token, attempt, "redeem")) }
                showStatus(R.string.qtun_validating)
                stage = "parse"
                val config = withContext(Dispatchers.IO) { bytes.inputStream().use { Config.parse(it) } }
                showStatus(R.string.qtun_saving)
                stage = "save"
                val configDigest = digest(config.toAwgQuickString())
                withContext(Dispatchers.IO) {
                    check(prefs.edit().putString("$key.name", name).putString("$key.config", configDigest).commit())
                }
                manager.create(name, config)
            }
            showStatus(R.string.qtun_confirming)
            stage = "complete"
            try {
                withContext(Dispatchers.IO) { request(invite.token, attempt, "complete") }
            } catch (_: InviteUnavailable) {
                // Expired or revoked after local save. It can no longer be redeemed.
            } catch (_: IOException) {
                showStatus(R.string.qtun_saved_retry)
                return@withLock
            }
            withContext(Dispatchers.IO) {
                check(prefs.edit().putBoolean("$key.complete", true).remove("$key.attempt")
                    .remove("$key.name").remove("$key.config").commit())
            }
            report(R.string.qtun_saved)
        } catch (e: CancellationException) {
            throw e
        } catch (_: InviteUnavailable) {
            showStatus(R.string.qtun_unavailable)
        } catch (e: BadConfigException) {
            Log.w(TAG, "Profile rejected: ${e.section.name}/${e.location.name}/${e.reason.name}")
            showStatus(R.string.qtun_bad_profile)
        } catch (e: Exception) {
            Log.w(TAG, "Import failed at $stage: ${e.javaClass.simpleName}")
            showStatus(R.string.qtun_failed)
        }
    }

    private fun report(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun request(token: String, attempt: String, operation: String): ByteArray? {
        val connection = URL("https://$HOST/account/api/qtun/v1/$operation").openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val body = """{"attempt":"$attempt"}""".toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status == 404) throw InviteUnavailable()
            if (operation == "complete" && status == 204) return null
            val mediaType = connection.getHeaderField("Content-Type")?.substringBefore(';')?.trim()
            if (operation != "redeem" || status != 200 || !mediaType.equals("application/octet-stream", ignoreCase = true)) {
                Log.w(TAG, "Unexpected response: operation=$operation status=$status")
                throw IOException("Quick Tunnel request rejected")
            }
            val result = ByteArrayOutputStream()
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > 128 * 1024 || android.os.SystemClock.elapsedRealtime() > deadline) {
                        throw IOException("Quick Tunnel response exceeds limits")
                    }
                    result.write(buffer, 0, count)
                }
            }
            return result.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(link: String): Invite? {
        val value = link.trim()
        if (value.length > 512) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val token = uri.rawUserInfo ?: return null
        if (uri.scheme != "qtun" || uri.host != HOST || uri.port != -1 || uri.rawPath != "" ||
            uri.rawQuery != null || !TOKEN.matches(token) || uri.rawAuthority != "$token@$HOST") return null
        val suggested = uri.fragment?.takeUnless { Tunnel.isNameInvalid(it) } ?: "QuickTunnel"
        return Invite(token, suggested)
    }

    private fun newSecret(): String = Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun digest(value: String): String = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    private companion object {
        const val TAG = "AmneziaWG/QuickTunnel"
        const val HOST = "portal.sanysunny.online"
        val TOKEN = Regex("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
        val importLock = Mutex()
    }
}
