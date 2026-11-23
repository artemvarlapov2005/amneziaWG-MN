package org.amnezia.awg.updater

import kotlinx.coroutines.*
import org.amnezia.awg.util.UpdateTransfer
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Route retry policy is JVM-testable; Android supplies per-Network connection factories. */
object RoutedUpdateTransfer {
    class TransferAbortedException : IOException("Download cancelled")
    data class Route(val id: String, val label: String, val open: UpdateTransfer.ConnectionFactory)
    suspend fun fetch(url: URL, target: File, maxBytes: Long, totalMs: Long, perRouteMs: Long, stallMs: Long,
                      routes: () -> List<Route>, checkCurrent: () -> Unit,
                      registered: (UpdateTransfer.Handle) -> Unit, removed: (UpdateTransfer.Handle) -> Unit,
                      onRoute: (String, Int) -> Unit, progress: (Long, Long) -> Unit,
                      onFailure: (String, Exception) -> Unit = { _, _ -> }): File {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalMs)
        var last: Exception = IOException("No usable network")
        var attempt = 0
        repeat(2) { wave ->
            for (route in routes().distinctBy { it.id }.take(4)) {
                currentCoroutineContext().ensureActive(); checkCurrent()
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) throw TimeoutException("All network attempts timed out")
                val part = File(target.parentFile, "route-${UUID.randomUUID()}.part")
                onRoute(route.label, ++attempt)
                var handle: UpdateTransfer.Handle? = null
                try {
                    val active = UpdateTransfer.startVia(url, part, maxBytes, { bytes, total -> progress(bytes, total) }, route.open)
                    handle = active
                    registered(active)
                    runInterruptible { active.awaitResponsive(minOf(left, perRouteMs), stallMs) }
                    currentCoroutineContext().ensureActive(); checkCurrent()
                    check(part.renameTo(target))
                    return target
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive(); checkCurrent(); throw TransferAbortedException()
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive(); checkCurrent(); last = failure; onFailure(route.label, failure)
                } finally {
                    handle?.let { removed(it); it.cancel() }
                    part.delete()
                }
            }
            if (wave == 0) delay(300) // Refresh network identities after a VPN/Wi-Fi handover.
        }
        throw IOException("All available update routes failed", last)
    }
}
