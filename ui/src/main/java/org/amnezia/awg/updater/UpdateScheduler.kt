package org.amnezia.awg.updater

import android.app.*
import android.app.job.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.amnezia.awg.R

object UpdateScheduler {
    private const val JOB = 42010
    const val NOTIFICATION = 42011
    private const val CHANNEL = "app_updates"
    val intervals = longArrayOf(15, 60, 360, 720, 1440)
    fun prefs(c: Context) = c.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", true)
    fun minutes(c: Context) = prefs(c).getLong("minutes", 1440).takeIf { it in intervals } ?: 1440
    fun configure(c: Context, enabled: Boolean, minutes: Long) {
        require(minutes in intervals)
        prefs(c).edit().putBoolean("enabled", enabled).putLong("minutes", minutes).apply()
        schedule(c)
        if (!enabled) c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }
    fun schedule(c: Context) {
        val jobs = c.getSystemService(JobScheduler::class.java)
        if (!enabled(c)) { jobs.cancel(JOB); return }
        val interval = minutes(c) * 60000
        if (jobs.getPendingJob(JOB)?.intervalMillis == interval) return
        jobs.schedule(JobInfo.Builder(JOB, ComponentName(c, UpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setPeriodic(interval).setBackoffCriteria(30 * 60000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    fun notifyReady(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.mn_auto_channel), NotificationManager.IMPORTANCE_DEFAULT))
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        val tag = prefs(c).getString("downloaded", "") ?: ""
        if (prefs(c).getString("notified", null) == tag) return
        val intent = Intent(c, UpdateInstallActivity::class.java)
        val pending = PendingIntent.getActivity(c, NOTIFICATION, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_mn_updates)
            .setContentTitle(c.getString(R.string.mn_auto_ready_title))
            .setContentText(c.getString(R.string.mn_auto_ready_body, tag.removePrefix("v")))
            .setContentIntent(pending).setAutoCancel(true).build()
        try { nm.notify(NOTIFICATION, notice); prefs(c).edit().putString("notified", tag).apply() }
        catch (_: SecurityException) { /* User can still install from Updates. */ }
    }
}
