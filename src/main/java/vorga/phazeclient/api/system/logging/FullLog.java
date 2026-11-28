package vorga.phazeclient.api.system.logging;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Safe to reference from transformed vanilla classes before Minecraft/Main exist.
 * In particular, do not introduce references to Minecraft, Settings or Module here.
 */
public final class FullLog {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ArrayDeque<String> MIXIN_HISTORY = new ArrayDeque<>();
    private static final ConcurrentHashMap<String, String[]> OWNERS = new ConcurrentHashMap<>();
    static final long INTERVAL_MILLIS = 1000;
    private static final int MAX_PENDING = 512;
    private static final Object BUFFER_LOCK = new Object();
    private static final Map<String, Pending> PENDING = new LinkedHashMap<>();
    private static final ConcurrentHashMap<String, MixinCounter> MIXINS = new ConcurrentHashMap<>();
    private static long suppressed;
    private static volatile Map<String, Boolean> featureStates = Map.of();
    private static volatile FullLogWriter writer;
    private static ScheduledExecutorService summaries;

    private static final class Pending {
        final String level, category, first;
        final boolean important;
        String last, thread;
        long count = 1;

        Pending(String level, String category, String detail, boolean important) {
            this.level = level;
            this.category = category;
            this.first = this.last = detail;
            this.thread = Thread.currentThread().getName();
            this.important = important;
        }
    }

    private static final class MethodCounter {
        long expected, entered, calls, returns, errors, missingSince;
        String owners = "";
    }

    private static final class MixinCounter {
        final Map<String, MethodCounter> methods = new LinkedHashMap<>();
        String owners = "";

        synchronized void record(String method, String owners, String phase) {
            if (!owners.isEmpty()) this.owners = owners;
            MethodCounter counter = methods.computeIfAbsent(method, ignored -> new MethodCounter());
            if (!owners.isEmpty()) counter.owners = owners;
            switch (phase) {
                case "EXPECTED" -> counter.expected++;
                case "ENTER" -> {
                    if (counter.entered < counter.expected) counter.entered++;
                    counter.calls++;
                }
                case "RETURN_OK" -> counter.returns++;
                case "ERROR" -> counter.errors++;
                default -> { }
            }
        }

        synchronized String summary(String source, long now) {
            long calls = 0, returns = 0, errors = 0, expected = 0;
            boolean missing = false;
            StringBuilder invoked = new StringBuilder();
            for (var entry : methods.entrySet()) {
                MethodCounter counter = entry.getValue();
                if (!shouldTrace(counter.owners)) {
                    counter.expected = counter.entered = counter.missingSince = 0;
                    counter.calls = counter.returns = 0;
                }
                calls += counter.calls;
                returns += counter.returns;
                errors += counter.errors;
                if (counter.expected > counter.entered) {
                    if (counter.missingSince == 0) counter.missingSince = now;
                    if (now - counter.missingSince >= TimeUnit.MILLISECONDS.toNanos(INTERVAL_MILLIS)) {
                        missing = true;
                        if (!invoked.isEmpty()) invoked.append(", ");
                        invoked.append(entry.getKey()).append("=EXPECTED_CALLBACK_NOT_CALLED");
                    }
                } else counter.missingSince = 0;
                expected += counter.expected;
                if (counter.calls > 0) {
                    if (!invoked.isEmpty()) invoked.append(", ");
                    invoked.append(entry.getKey()).append('=').append(counter.calls);
                }
                counter.calls = counter.returns = counter.errors = 0;
            }
            if (calls == 0 && returns == 0 && errors == 0 && !missing) return null;
            return "status=" + (missing || errors > 0 ? "ERROR" : "OK") + " mixin=" + source
                    + " calls=" + calls + " RETURN_OK=" + returns + " errors=" + errors
                    + " expectedTotal=" + expected + " features=" + enabledOwners(owners)
                    + " methods={" + invoked + '}';
        }
    }

    private FullLog() {}

    public static boolean isEnabled() {
        FullLogWriter current = writer;
        return current != null && current.isHealthy();
    }

    public static synchronized void start(Path directory) throws IOException {
        if (isEnabled()) return;
        if (writer != null) stop();
        synchronized (BUFFER_LOCK) { PENDING.clear(); suppressed = 0; }
        MIXINS.clear();
        writer = new FullLogWriter(directory);
        event("SESSION", "START directory=" + directory.toAbsolutePath()
                + " summaryIntervalMs=" + INTERVAL_MILLIS + " maxManagedLogsMiB=64"
                + " java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " processors=" + Runtime.getRuntime().availableProcessors()
                + " maxMemory=" + Runtime.getRuntime().maxMemory());
        for (String applied : MIXIN_HISTORY) event("MIXIN_SETUP", applied, applied);
        summaries = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "phaze-full-log-summary");
            thread.setDaemon(true);
            return thread;
        });
        summaries.scheduleAtFixedRate(FullLog::flushNow, INTERVAL_MILLIS, INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    public static synchronized void stop() {
        FullLogWriter current = writer;
        if (current == null) return;
        if (summaries != null) { summaries.shutdown(); summaries = null; }
        event("SESSION", "STOP; draining queued records");
        flushNow();
        writer = null;
        current.close();
    }

    public static void event(String category, String detail) {
        publish("TRACE", category, detail, false);
    }

    public static void event(String category, String key, String detail) {
        buffer("TRACE", category, key, detail, false);
    }

    public static void critical(String category, String detail) {
        publish("ERROR", category, detail, true);
    }

    public static void error(String context, Throwable error) {
        if (!isEnabled()) return;
        String key = context + ':' + (error == null ? "null" : error.getClass().getName() + ':' + error.getMessage()
                + ':' + (error.getStackTrace().length == 0 ? "" : error.getStackTrace()[0]));
        synchronized (BUFFER_LOCK) {
            Pending existing = PENDING.get(recordKey("ERROR", context, key));
            if (existing != null) { existing.count++; return; }
            StringWriter stack = new StringWriter();
            if (error != null) error.printStackTrace(new PrintWriter(stack));
            buffer("ERROR", context, key, stack.toString(), true);
        }
    }

    static void publish(String level, String category, String detail, boolean important) {
        buffer(level, category, category, detail, important);
    }

    private static void buffer(String level, String category, String key, String detail, boolean important) {
        if (!isEnabled()) return;
        synchronized (BUFFER_LOCK) {
            key = recordKey(level, category, key);
            Pending existing = PENDING.get(key);
            String bounded = limit(detail, important ? 256 * 1024 : 2048);
            if (existing != null) {
                existing.count++;
                existing.last = bounded;
                existing.thread = Thread.currentThread().getName();
                return;
            }
            if (PENDING.size() >= MAX_PENDING) {
                if (important) {
                    var candidates = PENDING.entrySet().iterator();
                    while (candidates.hasNext()) {
                        if (!candidates.next().getValue().important) { candidates.remove(); suppressed++; break; }
                    }
                }
                if (PENDING.size() >= MAX_PENDING) { suppressed++; return; }
            }
            PENDING.put(key, new Pending(level, category, bounded, important));
        }
    }

    private static String recordKey(String level, String category, String key) {
        return level + '\u0000' + category + '\u0000' + key;
    }

    private static String limit(String text, int length) {
        if (text == null) return "null";
        return text.length() <= length ? text : text.substring(0, length) + " [TRUNCATED]";
    }

    /** The timer is the only normal disk publisher; stopping drains one final partial interval. */
    static synchronized void flushNow() {
        flushAt(System.nanoTime());
    }

    static synchronized void flushAt(long now) {
        if (!isEnabled()) return;
        List<Pending> batch;
        long skipped;
        synchronized (BUFFER_LOCK) {
            batch = new ArrayList<>(PENDING.values());
            PENDING.clear();
            skipped = suppressed;
            suppressed = 0;
        }
        for (Pending record : batch) {
            String detail = record.last;
            if (record.count > 1) detail += " count=" + record.count
                    + (record.first.equals(record.last) ? "" : " first={" + record.first + '}');
            write(record.level, record.category, record.thread, detail, record.important);
        }
        MIXINS.forEach((source, counter) -> {
            String detail = counter.summary(source, now);
            if (detail != null) {
                boolean error = detail.startsWith("status=ERROR");
                write(error ? "ERROR" : "INFO", "MIXIN_STATUS", "summary", detail, error);
            }
        });
        if (skipped > 0) write("WARN", "LOG_AGGREGATION_LIMIT", "summary",
                "Unique records suppressed=" + skipped + " within 1s interval", true);
    }

    private static void write(String level, String category, String thread, String detail, boolean important) {
        FullLogWriter current = writer;
        if (current == null || !current.isHealthy()) return;
        current.append(Instant.now() + " #" + SEQUENCE.incrementAndGet() + " [" + level + "] ["
                + thread + "] [" + category + "] " + detail + "\n", important);
    }

    public static void setFeatureStates(Map<String, Boolean> states) {
        featureStates = Map.copyOf(states);
    }

    public static synchronized void setFeatureState(String owner, boolean enabled) {
        Map<String, Boolean> updated = new LinkedHashMap<>(featureStates);
        updated.put(owner, enabled);
        featureStates = Map.copyOf(updated);
    }

    /** No singleton lookup, reflection, or isEnabled() calls inside transformed render code. */
    public static boolean shouldTrace(String owners) {
        if (!isEnabled()) return false;
        if (owners.isEmpty()) return true;
        boolean knownFeature = false;
        Map<String, Boolean> states = featureStates;
        if (states.isEmpty()) return false; // Module state has not been restored yet during early startup.
        for (String owner : OWNERS.computeIfAbsent(owners, value -> value.split(";"))) {
            Boolean state = states.get(owner);
            if (state == null) continue; // A static helper, not a toggleable module.
            knownFeature = true;
            if (state) return true;
        }
        return !knownFeature;
    }

    public static void mixin(String source, String method, String owners, String phase) {
        if (shouldTrace(owners)) {
            MIXINS.computeIfAbsent(source, ignored -> new MixinCounter()).record(method, owners, phase);
        }
    }

    public static void mixinExpected(String source, String method, String owners) {
        if (shouldTrace(owners)) {
            MIXINS.computeIfAbsent(source, ignored -> new MixinCounter()).record(method, owners, "EXPECTED");
        }
    }

    private static String enabledOwners(String owners) {
        if (owners.isEmpty()) return "core";
        StringBuilder names = new StringBuilder();
        Map<String, Boolean> states = featureStates;
        for (String owner : OWNERS.computeIfAbsent(owners, value -> value.split(";"))) {
            if (!Boolean.TRUE.equals(states.get(owner))) continue;
            if (!names.isEmpty()) names.append(',');
            names.append(owner.substring(owner.lastIndexOf('/') + 1));
        }
        return names.isEmpty() ? "core/helper" : names.toString();
    }

    public static void mixinError(Throwable error, String source, String method) {
        if (isEnabled()) MIXINS.computeIfAbsent(source, ignored -> new MixinCounter()).record(method, "", "ERROR");
        error("MIXIN " + source + "#" + method, error);
    }

    public static void featureCall(String owner, String method, String source, String phase) {
        if (isEnabled() && Boolean.TRUE.equals(featureStates.get(owner))) {
            event("FEATURE_CALL", "feature:" + owner, phase + " " + owner.substring(owner.lastIndexOf('/') + 1)
                    + "#" + method + " via=" + source);
        }
    }

    public static synchronized void mixinSetup(String detail) {
        if (!detail.startsWith("APPLY_BEGIN ")) {
            if (MIXIN_HISTORY.size() == 2048) MIXIN_HISTORY.removeFirst();
            MIXIN_HISTORY.addLast(detail);
        }
        if (isEnabled()) event("MIXIN_SETUP", detail, detail);
    }
}
