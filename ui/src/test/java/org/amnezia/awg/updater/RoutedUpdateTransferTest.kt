package org.amnezia.awg.updater

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.amnezia.awg.util.UpdateTransfer
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class RoutedUpdateTransferTest {
    private val url = URL("https://example.invalid/apk")
    private fun connection(input: InputStream, size: Long = 8) = object : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getContentLengthLong() = size
        override fun getInputStream() = input
    }
    private fun good(id: String) = RoutedUpdateTransfer.Route(id, id, UpdateTransfer.ConnectionFactory { connection(ByteArrayInputStream("test-apk".toByteArray())) })
    private fun bad(id: String) = RoutedUpdateTransfer.Route(id, id, UpdateTransfer.ConnectionFactory { throw IOException("network lost") })
    private suspend fun fetch(target: File, routes: () -> List<RoutedUpdateTransfer.Route>, attempts: MutableList<String>,
                              registered: (UpdateTransfer.Handle) -> Unit = { }, check: () -> Unit = { }) =
        RoutedUpdateTransfer.fetch(url, target, 1024, 3000, 1000, 100, routes, check, registered, { },
            { route, _ -> attempts.add(route) }, { _, _ -> })
    @Test fun failedVpnFallsBackToDirectWithoutChangingProcessNetwork() = runBlocking {
        val dir = Files.createTempDirectory("route-fallback").toFile()
        try {
            val attempts = mutableListOf<String>()
            val file = fetch(File(dir, "result"), { listOf(bad("vpn"), good("direct")) }, attempts)
            assertEquals(listOf("vpn", "direct"), attempts)
            assertEquals("test-apk", file.readText())
            assertEquals(listOf("result"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }
    @Test fun directFailureCanFallBackToVpn() = runBlocking {
        val dir = Files.createTempDirectory("route-reverse").toFile()
        try {
            val attempts = mutableListOf<String>()
            fetch(File(dir,"result"), { listOf(bad("direct"), good("vpn")) }, attempts)
            assertEquals(listOf("direct", "vpn"), attempts)
        } finally { dir.deleteRecursively() }
    }
    @Test fun stalledConnectionFallsBackBeforeWholeDeadline() = runBlocking {
        val dir = Files.createTempDirectory("route-stall").toFile()
        try {
            val stall = RoutedUpdateTransfer.Route("vpn","vpn", UpdateTransfer.ConnectionFactory {
                connection(object : InputStream() {
                    override fun read(): Int { try { Thread.sleep(2000) } catch (_: InterruptedException) { throw InterruptedIOException() }; return -1 }
                })
            })
            val attempts = mutableListOf<String>()
            val started = System.nanoTime()
            fetch(File(dir,"result"), { listOf(stall, good("direct")) }, attempts)
            assertTrue((System.nanoTime() - started) / 1000000 < 1800)
            assertEquals(listOf("vpn", "direct"), attempts)
        } finally { dir.deleteRecursively() }
    }
    @Test fun refreshedNetworkAfterHandoverIsRetried() = runBlocking {
        val dir = Files.createTempDirectory("route-refresh").toFile()
        try {
            val calls = AtomicInteger(); val attempts = mutableListOf<String>()
            fetch(File(dir,"result"), { if (calls.getAndIncrement() == 0) listOf(bad("old-vpn")) else listOf(good("new-mobile")) }, attempts)
            assertEquals(listOf("old-vpn", "new-mobile"), attempts)
        } finally { dir.deleteRecursively() }
    }
    @Test fun cancellationDoesNotTryAnotherRoute() = runBlocking {
        val dir = Files.createTempDirectory("route-cancel").toFile()
        try {
            val slow = RoutedUpdateTransfer.Route("vpn","vpn", UpdateTransfer.ConnectionFactory {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { throw InterruptedIOException() }
                connection(ByteArrayInputStream("test-apk".toByteArray()))
            })
            val attempts = mutableListOf<String>()
            try { fetch(File(dir,"result"), { listOf(slow, good("direct")) }, attempts, { it.cancel() }); fail("must cancel") }
            catch (_: RoutedUpdateTransfer.TransferAbortedException) { }
            assertEquals(listOf("vpn"), attempts); assertFalse(File(dir,"result").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun channelChangeDoesNotRetryOrCommit() = runBlocking {
        val dir = Files.createTempDirectory("route-channel").toFile()
        try {
            var current = true; val attempts = mutableListOf<String>()
            try {
                fetch(File(dir,"result"), { listOf(bad("vpn"), good("direct")) }, attempts,
                    { current = false; it.cancel() }, { if (!current) throw IOException("channel changed") })
                fail("must invalidate")
            } catch (_: IOException) { }
            assertEquals(listOf("vpn"), attempts); assertFalse(File(dir,"result").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun manualWaiterSeesBackgroundProgressBeforeMutexIsReleased() = runBlocking {
        val tracker = UpdateDownloadProgress(); val mutex = Mutex()
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val background = async(Dispatchers.Default) {
            mutex.withLock {
                val token = tracker.begin("same-apk", 1)
                tracker.route(token,"vpn",1); tracker.bytes(token,64,128)
                started.complete(Unit); finish.await(); tracker.finish(token)
            }
        }
        started.await()
        val manual = async(Dispatchers.Default) { mutex.withLock { "already downloaded" } }
        try {
            val value = withTimeout(1000) { tracker.state.filterNotNull().first() }
            assertEquals(64L,value.bytes); assertEquals(128L,value.total)
            assertFalse(manual.isCompleted)
        } finally { finish.complete(Unit) }
        background.await(); assertEquals("already downloaded",manual.await()); assertNull(tracker.state.value)
    }
    @Test fun staleProgressCannotResurrectCancelledAttempt() {
        val tracker = UpdateDownloadProgress()
        val old = tracker.begin("old",1); tracker.finish(old)
        val fresh = tracker.begin("new",2)
        tracker.bytes(old,100,100); tracker.finish(old)
        assertEquals(fresh,tracker.state.value!!.token)
        tracker.bytes(fresh,3,8); assertEquals(3L,tracker.state.value!!.bytes)
    }
    @Test fun lateBytesFromCancelledRouteCannotOverwriteNewRouteProgress() = runBlocking {
        val dir = Files.createTempDirectory("route-late-bytes").toFile()
        val directStarted = java.util.concurrent.CountDownLatch(1)
        val oldClosed = java.util.concurrent.CountDownLatch(1)
        try {
            val currentRoute = java.util.concurrent.atomic.AtomicReference("")
            val updates = java.util.Collections.synchronizedList(mutableListOf<Pair<String,Long>>())
            val old = RoutedUpdateTransfer.Route("vpn","vpn", UpdateTransfer.ConnectionFactory {
                connection(object : InputStream() {
                    var sent = false
                    override fun read(): Int {
                        if (sent) return -1
                        while (directStarted.count > 0) { try { directStarted.await(100,java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { } }
                        sent = true; return 1
                    }
                    override fun close() { oldClosed.countDown() }
                })
            })
            val direct = RoutedUpdateTransfer.Route("direct","direct", UpdateTransfer.ConnectionFactory {
                directStarted.countDown(); connection(ByteArrayInputStream("test-apk".toByteArray()))
            })
            RoutedUpdateTransfer.fetch(url, File(dir,"result"), 1024, 3000, 1000, 100,
                { listOf(old,direct) }, { }, { }, { }, { name, _ -> currentRoute.set(name) },
                { bytes, _ -> updates.add(currentRoute.get() to bytes) })
            assertTrue(oldClosed.await(1000,java.util.concurrent.TimeUnit.MILLISECONDS))
            assertFalse(updates.any { it.first == "direct" && it.second == 1L })
            assertEquals("test-apk",File(dir,"result").readText())
        } finally { directStarted.countDown(); dir.deleteRecursively() }
    }

}
