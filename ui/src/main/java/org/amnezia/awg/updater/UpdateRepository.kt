package org.amnezia.awg.updater

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.util.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object UpdateRepository {
    const val RELEASES = ChannelRelease.RELEASES
    const val CATALOG = "https://raw.githubusercontent.com/artemvarlapov2005/amneziaWG-MN/master/updates/channels.json"
    class ChannelChangedException : IOException("Update channel changed")
    data class Selection(val snapshot: ChannelGate.Snapshot, val candidate: ChannelRelease?, val waitingForStable: Boolean)
    private val downloadLock = Mutex()
    private val initializing = Any()
    @Volatile private var channelGate: ChannelGate? = null
    private val transfers = ConcurrentHashMap.newKeySet<UpdateTransfer.Handle>()

    private fun gate(c: Context): ChannelGate {
        channelGate?.let { return it }
        return synchronized(initializing) {
            channelGate ?: run {
                val p = UpdateScheduler.prefs(c)
                if (!p.getBoolean("channel_migrated", false)) {
                    file(c).delete()
                    File(c.cacheDir, "updates").deleteRecursively()
                    p.edit().remove("downloaded").remove("notified").remove("pending_release")
                        .putString("update_channel", "stable").putBoolean("channel_migrated", true).apply()
                    c.getSystemService(NotificationManager::class.java).cancel(UpdateScheduler.NOTIFICATION)
                }
                ChannelGate(p.getString("update_channel", "stable").takeIf { it == "beta" } ?: "stable", p.getLong("channel_generation", 0)).also { channelGate = it }
            }
        }
    }
    fun initialize(c: Context) { gate(c) }
    fun snapshot(c: Context): ChannelGate.Snapshot = gate(c).snapshot()
    fun channel(c: Context) = snapshot(c).channel
    fun current(c: Context, s: ChannelGate.Snapshot) = gate(c).current(s)
    private fun requireCurrent(c: Context, s: ChannelGate.Snapshot) { if (!current(c, s)) throw ChannelChangedException() }

    fun changeChannel(c: Context, channel: String) {
        val g = gate(c)
        synchronized(g) {
            if (g.snapshot().channel == channel) return
            val s = g.change(channel)
            UpdateScheduler.prefs(c).edit().putString("update_channel", s.channel).putLong("channel_generation", s.generation).apply()
            transfers.forEach { it.cancel() }
            if (pending(c)?.allowed(channel) != true) discardLocked(c)
        }
        UpdateScheduler.channelChanged(c)
    }
    private fun decode(channel: String, obj: JSONObject): ChannelRelease {
        require(listOf("tag", "versionName", "apkUrl", "sha256", "notes").all { obj.get(it) is String })
        require(obj.get("versionCode") is Number && obj.get("versionCode").toString().matches(Regex("[1-9][0-9]*")))
        return ChannelRelease(channel, obj.getString("tag"), obj.getString("versionName"), obj.getLong("versionCode"),
            obj.getString("apkUrl"), obj.getString("sha256"), obj.getString("notes"))
    }
    private fun encode(r: ChannelRelease) = JSONObject().put("channel", r.channel).put("tag", r.tag).put("versionName", r.versionName)
        .put("versionCode", r.versionCode).put("apkUrl", r.apkUrl).put("sha256", r.sha256).put("notes", r.notes)
    private fun pending(c: Context): ChannelRelease? = try {
        UpdateScheduler.prefs(c).getString("pending_release", null)?.let { val json = JSONObject(it); decode(json.getString("channel"), json) }
    } catch (_: Exception) { null }
    private suspend fun await(c: Context, s: ChannelGate.Snapshot, transfer: UpdateTransfer.Handle, timeout: Long) {
        try { runInterruptible { transfer.await(timeout) } }
        catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            requireCurrent(c, s)
            throw e
        }
        requireCurrent(c, s)
    }
    suspend fun latest(c: Context): Selection {
        val s = snapshot(c)
        val temp = File(c.cacheDir, "channels-${UUID.randomUUID()}.json")
        val handle: UpdateTransfer.Handle
        synchronized(gate(c)) {
            requireCurrent(c, s)
            handle = UpdateTransfer.startLimited(URL(CATALOG), temp, 256L * 1024) { _, _ -> }
            transfers.add(handle)
        }
        try {
            await(c, s, handle, 20000)
            currentCoroutineContext().ensureActive()
            val json = JSONObject(temp.readText(Charsets.UTF_8))
            require(json.get("schemaVersion") == 1) { "Unsupported update catalog" }
            require(json.has("stable") && json.has("beta") && !json.isNull("stable"))
            val stable = if (json.isNull("stable")) null else decode("stable", json.getJSONObject("stable"))
            val beta = if (json.isNull("beta")) null else decode("beta", json.getJSONObject("beta"))
            val chosen = ChannelRelease.select(s.channel, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), stable, beta)
            requireCurrent(c, s)
            return Selection(s, chosen, s.channel == "stable" && UpdateVersion.isBeta(BuildConfig.VERSION_NAME) && chosen == null)
        } finally { transfers.remove(handle); handle.cancel(); temp.delete() }
    }
    fun file(c: Context) = File(c.filesDir, "updates/update.apk")
    fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val b = ByteArray(32768); while (true) { val n = stream.read(b); if (n < 0) break; hash.update(b, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    @Suppress("DEPRECATION")
    private fun verify(c: Context, file: File, release: ChannelRelease): Boolean = try {
        if (!file.isFile || sha256(file) != release.sha256) false else {
            val pm = c.packageManager
            val incoming = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
            val installed = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES)
            fun code(p: android.content.pm.PackageInfo) = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
            fun signers(p: android.content.pm.PackageInfo) = p.signatures?.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
            incoming != null && incoming.versionName == release.versionName && code(incoming) == release.versionCode &&
                UpdatePolicy.accepts(c.packageName, code(installed), signers(installed), incoming.packageName, code(incoming), signers(incoming))
        }
    } catch (_: Exception) { false }
    private fun eligible(c: Context, r: ChannelRelease) = r.allowed(channel(c)) && r.upgrades(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
    fun pendingAllowed(c: Context): Boolean = synchronized(gate(c)) { pending(c)?.let { eligible(c, it) && file(c).isFile } ?: false }
    fun <T> withReady(c: Context, action: (ChannelRelease) -> T): T? = synchronized(gate(c)) {
        val r = pending(c) ?: return@synchronized null
        if (eligible(c, r) && verify(c, file(c), r)) action(r) else null
    }
    fun valid(c: Context) = withReady(c) { true } ?: false
    private fun discardLocked(c: Context) {
        file(c).delete()
        UpdateScheduler.prefs(c).edit().remove("pending_release").remove("downloaded").remove("notified").apply()
        c.getSystemService(NotificationManager::class.java).cancel(UpdateScheduler.NOTIFICATION)
    }
    fun discardInvalid(c: Context) { synchronized(gate(c)) { if (!valid(c)) discardLocked(c) } }
    suspend fun download(c: Context, release: ChannelRelease, expected: ChannelGate.Snapshot,
                         progress: (Long, Long) -> Unit = { _, _ -> }): File = downloadLock.withLock {
        val g = gate(c)
        val target = file(c)
        synchronized(g) {
            requireCurrent(c, expected)
            require(eligible(c, release))
            if (pending(c)?.sha256 == release.sha256 && valid(c)) return@withLock target
            discardLocked(c)
        }
        target.parentFile!!.mkdirs()
        val part = File(target.parentFile, "update-${UUID.randomUUID()}.part")
        val transfer: UpdateTransfer.Handle
        synchronized(g) {
            requireCurrent(c, expected)
            transfer = UpdateTransfer.start(URL(release.apkUrl), part) { bytes, total -> progress(bytes, total) }
            transfers.add(transfer)
        }
        try {
            await(c, expected, transfer, 120000)
            currentCoroutineContext().ensureActive()
            require(verify(c, part, release)) { "APK does not match release metadata/signature" }
            if (!g.commit(expected) {
                check(part.renameTo(target))
                UpdateScheduler.prefs(c).edit().putString("downloaded", release.tag).putString("pending_release", encode(release).toString()).apply()
            }) throw ChannelChangedException()
            target
        } finally { transfers.remove(transfer); transfer.cancel(); part.delete() }
    }
}
