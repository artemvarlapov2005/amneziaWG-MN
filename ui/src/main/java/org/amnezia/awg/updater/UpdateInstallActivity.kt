package org.amnezia.awg.updater

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.R

/** Notification lands in our app; only a verified file can reach Android's installer. */
class UpdateInstallActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var button: MaterialButton
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) install(false)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(R.string.mn_updates_heading)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }
        status = TextView(this).apply { textSize = 18f; setText(R.string.mn_auto_ready_title) }
        button = MaterialButton(this).apply { setText(R.string.mn_auto_install); setOnClickListener { install(true) } }
        content.addView(status); content.addView(button); setContentView(content)
        if (state == null) install(true)
    }
    private fun install(askPermission: Boolean) {
        button.isEnabled = false
        lifecycleScope.launch {
            val valid = withContext(Dispatchers.IO) { UpdateRepository.valid(this@UpdateInstallActivity) }
            if (!valid || !UpdateRepository.pendingAllowed(this@UpdateInstallActivity)) { status.setText(R.string.mn_auto_no_file); return@launch }
            button.isEnabled = true
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                status.setText(R.string.mn_installer_permission)
                if (askPermission) permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@UpdateInstallActivity, "$packageName.updates", UpdateRepository.file(this@UpdateInstallActivity))
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                status.setText(R.string.mn_auto_confirm)
            } catch (_: Exception) { status.setText(R.string.mn_update_error) }
        }
    }
}
