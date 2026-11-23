package org.amnezia.awg.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.amnezia.awg.util.UpdateTransfer
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.util.UpdatePolicy
import org.amnezia.awg.util.UpdateVersion
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object UpdateRepository {
    const val RELEASES = "https://github.com/artemvarlapov2005/amneziaWG-MN/releases"
    private val lock = Mutex()
    data class Release(val tag: String, val version: String, val url: String)
    fun latest(): Release {
        val c = URL("$RELEASES/latest").openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.connectTimeout = 15000; c.readTimeout = 15000
        try {
            check(c.responseCode == 200)
            val prefix = "$RELEASES/tag/"
            require(c.url.toString().startsWith(prefix))
            val tag = c.url.toString().removePrefix(prefix)
            require(Regex("v?[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?").matches(tag))
            val version = tag.removePrefix("v")
            return Release(tag, version, "$RELEASES/download/$tag/AmneziaWG-MN-$version-arm64.apk")
        } finally { c.disconnect() }
    }
    fun newer(release: Release) = UpdateVersion.isNewer(release.version, BuildConfig.VERSION_NAME)
    fun file(context: Context) = File(context.filesDir, "updates/update.apk")
    @Suppress("DEPRECATION")
    fun valid(context: Context, file: File = file(context)): Boolean {
        if (!file.isFile) return false
        return try {
            val pm = context.packageManager
            val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES) ?: return false
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            fun signers(p: android.content.pm.PackageInfo) = p.signatures?.map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }
            fun code(p: android.content.pm.PackageInfo) = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
            UpdatePolicy.accepts(context.packageName, code(installed), signers(installed), archive.packageName, code(archive), signers(archive))
        } catch (_: Exception) { false }
    }
    suspend fun download(context: Context, release: Release, progress: (Long, Long) -> Unit = { _, _ -> }): File = lock.withLock {
        val target = file(context)
        val prefs = UpdateScheduler.prefs(context)
        if (prefs.getString("downloaded", null) == release.tag && valid(context, target)) return@withLock target
        if (prefs.getString("downloaded", null) != release.tag) {
            target.delete()
            prefs.edit().remove("downloaded").remove("notified").apply()
            context.getSystemService(android.app.NotificationManager::class.java).cancel(UpdateScheduler.NOTIFICATION)
        }
        target.parentFile!!.mkdirs()
        val part = File(target.parentFile, "update-${UUID.randomUUID()}.part")
        val transfer = UpdateTransfer.start(URL(release.url), part) { bytes, total -> progress(bytes, total) }
        try {
            runInterruptible { transfer.await(120000) }
            currentCoroutineContext().ensureActive()
            require(valid(context, part)) { "APK identity/version verification failed" }
            check(part.renameTo(target))
            prefs.edit().putString("downloaded", release.tag).apply()
            target
        } finally { transfer.cancel(); part.delete() }
    }
}
