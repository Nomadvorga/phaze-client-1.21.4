package vorga.phazeclient.api.system.logging;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.nio.file.LinkOption;
import java.util.regex.Pattern;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded queue and one disk owner per diagnostic session. */
final class FullLogWriter implements AutoCloseable {
    private static final Pattern MANAGED_NAME = Pattern.compile("full-summary-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}-[a-z0-9]+-\\d+\\.log");
    private record Entry(String text, boolean flush) {}

    private final ArrayBlockingQueue<Entry> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final Path directory;
    private final String sessionName;
    private final long partLimit;
    private final int maxParts;
    private final Thread worker;
    private volatile boolean accepting = true;
    private volatile IOException failure;
    private OutputStream output;
    private long partBytes;
    private int part;

    FullLogWriter(Path directory) throws IOException {
        this(directory, 4L * 1024 * 1024, 1024, 16);
    }

    FullLogWriter(Path directory, long partLimit, int capacity) throws IOException {
        this(directory, partLimit, capacity, Integer.MAX_VALUE);
    }

    FullLogWriter(Path directory, long partLimit, int capacity, int maxParts) throws IOException {
        this.directory = directory;
        this.partLimit = partLimit;
        this.maxParts = Math.max(1, maxParts);
        this.queue = new ArrayBlockingQueue<>(capacity);
        Files.createDirectories(directory);
        sessionName = "full-summary-" + DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
                .format(LocalDateTime.now()) + "-" + Long.toUnsignedString(System.nanoTime(), 36);
        openPart();
        worker = new Thread(this::run, "phaze-full-log-writer");
        worker.setDaemon(true);
        worker.start();
    }

    boolean isHealthy() {
        return accepting && failure == null;
    }

    synchronized void append(String text, boolean important) {
        if (!isHealthy()) return;
        Entry entry = new Entry(text, important);
        if (!queue.offer(entry)) {
            // Preserve errors in preference to repetitive render/tick traces.
            if (important) {
                for (Entry queued : queue) {
                    if (!queued.flush() && queue.remove(queued)) {
                        dropped.incrementAndGet();
                        break;
                    }
                }
                if (!queue.offer(entry)) dropped.incrementAndGet();
            } else {
                dropped.incrementAndGet();
            }
        }
    }

    private void run() {
        long lastFlush = System.nanoTime();
        try {
            while (accepting || !queue.isEmpty() || dropped.get() != 0) {
                Entry entry = queue.poll(1000, TimeUnit.MILLISECONDS);
                long lost = dropped.getAndSet(0);
                if (lost != 0) write("[WARN] [LOG_BACKPRESSURE] Queue overloaded; dropped=" + lost
                        + "; reduce diagnostic session length/load. No loss is hidden.\n");
                if (entry != null) write(entry.text());
                long now = System.nanoTime();
                if ((entry != null && entry.flush()) || now - lastFlush >= 1_000_000_000L) {
                    output.flush();
                    lastFlush = now;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            failure = error;
            System.err.println("[Phaze] Full logs stopped: " + error);
            error.printStackTrace(System.err);
        } finally {
            accepting = false;
            try {
                output.close();
            } catch (IOException error) {
                failure = error;
                System.err.println("[Phaze] Could not close full log: " + error);
                error.printStackTrace(System.err);
            }
        }
    }

    private void write(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (partBytes > 0 && partBytes + bytes.length > partLimit) {
            output.close();
            part++;
            openPart();
        }
        output.write(bytes);
        partBytes += bytes.length;
    }

    private void openPart() throws IOException {
        Path path = directory.resolve(sessionName + String.format(java.util.Locale.ROOT, "-%03d.log", part));
        output = new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE), 64 * 1024);
        // Only this new, explicitly managed format participates in retention.
        // Existing full-* logs from the verbose logger are never deleted here.
        if (maxParts != Integer.MAX_VALUE) {
            try (var files = Files.list(directory)) {
                var managed = files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                && MANAGED_NAME.matcher(file.getFileName().toString()).matches())
                        .sorted(Comparator.comparing((Path file) -> {
                            String name = file.getFileName().toString();
                            return name.substring(0, name.lastIndexOf('-'));
                        }).thenComparingInt(file -> {
                            String name = file.getFileName().toString();
                            return Integer.parseInt(name.substring(name.lastIndexOf('-') + 1, name.length() - 4));
                        })).toList();
                int remove = managed.size() - maxParts;
                for (Path oldest : managed) {
                    if (remove <= 0) break;
                    if (!oldest.equals(path)) { Files.deleteIfExists(oldest); remove--; }
                }
            }
        }
        partBytes = 0;
    }

    @Override
    public void close() {
        synchronized (this) {
            accepting = false;
        }
        if (Thread.currentThread() == worker) return;
        try {
            worker.join(5000);
            if (worker.isAlive()) {
                System.err.println("[Phaze] Full log is still draining after five seconds: " + directory);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
