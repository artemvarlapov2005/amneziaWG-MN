package org.amnezia.awg.updater

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

class UpdateJobService : JobService() {
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (!UpdateScheduler.enabled(this)) return false
        task = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var retry = false
            try {
                val release = UpdateRepository.latest()
                if (UpdateRepository.newer(release)) {
                    UpdateRepository.download(this@UpdateJobService, release)
                    ensureActive()
                    if (UpdateScheduler.enabled(this@UpdateJobService)) UpdateScheduler.notifyReady(this@UpdateJobService)
                } else if (!UpdateRepository.valid(this@UpdateJobService)) {
                    UpdateRepository.file(this@UpdateJobService).delete()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: IllegalArgumentException) { /* Invalid APK/version must not trigger a retry loop. */ }
              catch (_: Exception) { retry = true }
            if (isActive) withContext(Dispatchers.Main) { jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        task?.cancel(); task = null
        return UpdateScheduler.enabled(this)
    }
    override fun onDestroy() { task?.cancel(); super.onDestroy() }
}
