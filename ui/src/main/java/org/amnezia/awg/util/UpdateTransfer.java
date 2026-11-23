package org.amnezia.awg.util;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Hard deadline includes DNS/redirects; stuck network calls cannot trap the UI coroutine. */
public final class UpdateTransfer {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final ExecutorService NETWORK = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "apk-transfer"); t.setDaemon(true); return t; });
    public interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    public interface Progress { void received(long bytes, long total); }
    public static final class Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Future<?> future;
        private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        public void await(long timeoutMillis) throws Exception {
            try { future.get(timeoutMillis, TimeUnit.MILLISECONDS); }
            catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("Download failed", cause);
            }
        }
        public void awaitResponsive(long timeoutMillis, long stallMillis) throws Exception {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (true) {
                long left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
                if (left <= 0) throw new TimeoutException("Transfer deadline");
                try { await(Math.min(200, Math.max(1, left))); return; }
                catch (TimeoutException timeout) {
                    if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastActivity.get()) >= stallMillis) throw new TimeoutException("No network progress");
                }
            }
        }
        public void cancel() { cancelled.set(true); future.cancel(true); }
    }
    public static Handle start(URL url, File part, Progress progress) {
        return startLimited(url, part, MAX_BYTES, progress);
    }
    public static Handle startLimited(URL url, File part, long maxBytes, Progress progress) {
        return startVia(url, part, maxBytes, progress, target -> (HttpURLConnection) target.openConnection());
    }
    public static Handle startVia(URL url, File part, long maxBytes, Progress progress, ConnectionFactory factory) {
        if (maxBytes <= 0 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("Invalid size limit");
        Handle handle = new Handle();
        handle.future = NETWORK.submit(() -> {
            HttpURLConnection c = null;
            try {
                c = factory.open(url);
                c.setConnectTimeout(8000); c.setReadTimeout(10000);
                c.setUseCaches(false); c.setRequestProperty("Cache-Control", "no-cache");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                long length = c.getContentLengthLong();
                if (length > maxBytes) throw new IOException("APK is too large");
                handle.lastActivity.set(System.nanoTime());
                progress.received(0, length);
                try (InputStream input = c.getInputStream(); OutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[32768]; long total = 0;
                    while (true) {
                        if (handle.cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
                        int n = input.read(buffer); if (n < 0) break;
                        total += n;
                        if (total > maxBytes) throw new IOException("APK is too large");
                        output.write(buffer, 0, n); handle.lastActivity.set(System.nanoTime()); progress.received(total, length);
                    }
                    if (length >= 0 && total != length) throw new EOFException("Incomplete APK");
                }
                if (handle.cancelled.get()) throw new InterruptedIOException("Cancelled");
                return null;
            } finally {
                if (c != null) c.disconnect();
                if (handle.cancelled.get()) part.delete();
            }
        });
        return handle;
    }
    private UpdateTransfer() { }
}
