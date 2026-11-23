package org.amnezia.awg.updater

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class UpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        if (!UpdateScheduler.enabled(this)) return false
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                val result = UpdateRepository.latest(this@UpdateJobService)
                result.candidate?.let { release ->
                    UpdateRepository.download(this@UpdateJobService, release, result.snapshot)
                    ensureActive()
                    if (UpdateScheduler.enabled(this@UpdateJobService) && UpdateRepository.current(this@UpdateJobService, result.snapshot)) UpdateScheduler.notifyReady(this@UpdateJobService)
                } ?: UpdateRepository.discardInvalid(this@UpdateJobService)
            } catch (_: UpdateRepository.ChannelChangedException) { }
              catch (cancelled: CancellationException) { throw cancelled }
              catch (_: IllegalArgumentException) { }
              catch (_: Exception) { retry = true }
            val running = coroutineContext[Job]
            if (isActive) withContext(Dispatchers.Main) { tasks.remove(params.jobId, running); jobFinished(params, retry) }
        }
        tasks.put(params.jobId, task)?.cancel(); task.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        tasks.remove(params.jobId)?.cancel()
        return UpdateScheduler.enabled(this)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
