package org.amnezia.awg.util;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class UpdateTransferTest {
    private ServerSocket server(String body, boolean stall) throws Exception {
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            try (Socket socket = listener.accept()) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String line; while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                if (stall) { out.write('a'); out.flush(); Thread.sleep(2000); }
                else { out.write(body.getBytes(StandardCharsets.US_ASCII)); out.flush(); }
            } catch (Exception ignored) { }
        });
        thread.setDaemon(true); thread.start(); return listener;
    }
    @Test public void downloadsCompleteBodyAndReportsProgress() throws Exception {
        File part = File.createTempFile("transfer-test", ".part");
        AtomicLong progress = new AtomicLong();
        try (ServerSocket listener = server("test-apk", false)) {
            UpdateTransfer.Handle h = UpdateTransfer.start(new URL("http://127.0.0.1:" + listener.getLocalPort() + "/apk"), part, (bytes, total) -> progress.set(bytes));
            try { h.await(2000); assertEquals(8, part.length()); assertEquals(8, progress.get()); }
            finally { h.cancel(); }
        } finally { part.delete(); }
    }
    @Test public void totalDeadlineEndsWaitEvenWhenPeerKeepsConnectionOpen() throws Exception {
        File part = File.createTempFile("transfer-stall", ".part");
        try (ServerSocket listener = server("test-apk", true)) {
            UpdateTransfer.Handle h = UpdateTransfer.start(new URL("http://127.0.0.1:" + listener.getLocalPort() + "/apk"), part, (bytes, total) -> { });
            long start = System.nanoTime();
            try { h.await(150); fail("Must time out without waiting for peer"); }
            catch (TimeoutException expected) { assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500); }
            finally { h.cancel(); }
            try { h.await(100); fail("Cancelled wait must end immediately"); }
            catch (CancellationException expected) { }
        } finally { part.delete(); }
    }
    @Test public void catalogSizeLimitRejectsBeforeWritingBody() throws Exception {
        File part = File.createTempFile("catalog-limit", ".json");
        try (ServerSocket listener = server("x".repeat(512), false)) {
            UpdateTransfer.Handle h = UpdateTransfer.startLimited(new URL("http://127.0.0.1:" + listener.getLocalPort() + "/catalog"), part, 32, (bytes, total) -> { });
            try { h.await(2000); fail("Oversized catalog must be rejected"); }
            catch (IOException expected) { assertEquals(0, part.length()); }
            finally { h.cancel(); }
        } finally { part.delete(); }
    }

}
