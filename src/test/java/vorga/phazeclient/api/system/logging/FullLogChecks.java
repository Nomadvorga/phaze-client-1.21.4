package vorga.phazeclient.api.system.logging;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Disk round trips and real JVM verification of transformed methods, no game/window needed. */
public final class FullLogChecks {
    private static final String SOURCE = "vorga.phazeclient.mixins.LogProbeFixture";
    private static final String FEATURE = "vorga/phazeclient/implement/features/modules/other/HitColor";
    private static final String CALLED_FEATURE = "vorga/phazeclient/implement/features/modules/other/FullLogProbeFeature";

    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("phaze-full-log-check-");
        try {
            preference(sandbox.resolve("preference"));
            settingValues();
            rotation(sandbox.resolve("rotation"));
            retention(sandbox.resolve("retention"));
            backpressure(sandbox.resolve("backpressure"));
            traceAndTransform(sandbox.resolve("trace"));
            summaryVolume(sandbox.resolve("volume"));
            check(!FullLogGui.isPhazeTarget(new Object()), "Vanilla/foreign UI must not be a Phaze GUI target");
            System.out.println("Full log checks passed: saved preference, setting values, rotation, UTF-8, queue drain/backpressure, feature gates, JVM bytecode, caught/thrown stacks, disable/re-enable");
        } finally {
            FullLog.stop();
            try (var paths = Files.walk(sandbox)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void preference(Path game) throws Exception {
        check(!FullLogBootstrap.savedPreference(game), "Logging defaults to off");
        Path file = game.resolve("Phaze/configs/default.Phaze");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"modules\":{\"themes\":{\"settings\":{\"Full Logs\":true}}}}");
        check(FullLogBootstrap.savedPreference(game), "Read existing Full Logs config key");
        Files.move(file, file.resolveSibling("default.Phaze.bak"));
        Files.writeString(file, "broken JSON");
        check(FullLogBootstrap.savedPreference(game), "Match config manager backup recovery");
        Path pointer = game.resolve("Phaze/files/current_config.json");
        Files.createDirectories(pointer.getParent());
        Files.writeString(pointer, "{\"current_config\":\"../outside\"}");
        check(!FullLogBootstrap.savedPreference(game), "Do not read a profile outside configs");
    }

    private static void settingValues() {
        var group = new vorga.phazeclient.api.feature.module.setting.implement.GroupSetting("group", "");
        check(FullLogRuntime.settingValue(group.setValue(false)).equals("value=false"), "Group disabled state captured");
        check(FullLogRuntime.settingValue(group.setValue(true)).equals("value=true"), "Group enabled state captured");
        var colors = new vorga.phazeclient.api.feature.module.setting.implement.MultiColorSetting("colors", "")
                .colors("first", "second").defaultColors(11, 22);
        check(FullLogRuntime.settingValue(colors).equals("[11, 22]"), "All multi-color values captured");
        var item = new vorga.phazeclient.api.feature.module.setting.implement.ItemPickerSetting("item", "", 0xFF123456);
        check(FullLogRuntime.settingValue(item).contains("color=#FF123456"), "Item picker color captured");
    }

    private static void rotation(Path directory) throws Exception {
        FullLogWriter writer = new FullLogWriter(directory, 160, 2048);
        for (int index = 0; index < 120; index++) writer.append("record=" + index + " Полные логи\n", false);
        writer.append("FINAL_ERROR\n", true);
        writer.close();
        String text = readLogs(directory);
        for (int index = 0; index < 120; index++) check(text.contains("record=" + index + " Полные логи\n"), "Missing drained record " + index);
        check(text.endsWith("FINAL_ERROR\n"), "Error drained on close");
        try (var files = Files.list(directory)) {
            check(files.count() > 1, "Rotate instead of overwriting/truncating the session");
        }
    }

    private static void traceAndTransform(Path directory) throws Exception {
        FullLog.mixinSetup("fixture setup history");
        FullLog.setFeatureStates(Map.of(FEATURE, false));
        FullLog.start(directory);
        check(!FullLog.shouldTrace(FEATURE), "Disabled feature is silent");
        FullLog.mixin(SOURCE, "disabledMarker", FEATURE, "ENTER");
        FullLog.featureCall(FEATURE, "disabledFeatureMarker", SOURCE, "RETURN_OK");
        check(FullLog.shouldTrace(""), "Core hooks still trace");
        FullLog.setFeatureState(FEATURE, true);
        FullLog.mixin(SOURCE, "enabledMarker", FEATURE, "ENTER");
        FullLog.mixin(SOURCE, "enabledMarker", FEATURE, "RETURN_OK");
        FullLog.featureCall(FEATURE, "enabledFeatureMarker", SOURCE, "RETURN_OK");

        String resource = "/" + ProbeFixture.class.getName().replace('.', '/') + ".class";
        ClassNode node = new ClassNode();
        try (InputStream input = FullLogChecks.class.getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("Fixture bytecode missing");
            new ClassReader(input).accept(node, 0);
        }
        int expected = 0;
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("<") || method.name.equals("untouched") || method.name.equals("invokeNumber")) continue;
            AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
            annotation.values = new ArrayList<>(List.of("mixin", SOURCE));
            if (method.visibleAnnotations == null) method.visibleAnnotations = new ArrayList<>();
            method.visibleAnnotations.add(annotation);
            // Mixin creates some merged methods without an exception-handler list.
            if (method.tryCatchBlocks != null && method.tryCatchBlocks.isEmpty()) method.tryCatchBlocks = null;
            expected++;
        }
        check(MixinLogTransformer.instrument(node, SOURCE) == expected, "Trace all merged methods");
        check(MixinLogTransformer.instrument(node, SOURCE) == 0, "Do not instrument twice");
        check(node.methods.stream().filter(method -> method.name.equals("untouched")).noneMatch(method -> {
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.endsWith("/FullLog")) return true;
            return false;
        }), "Leave vanilla/other-mod methods unchanged");
        // Do not recompute frames: this verifies compatibility with existing Mixin stack maps.
        ClassWriter bytes = new ClassWriter(0);
        node.accept(bytes);
        Class<?> fixture = new ClassLoader(FullLogChecks.class.getClassLoader()) {
            Class<?> define() { byte[] data = bytes.toByteArray(); return defineClass(null, data, 0, data.length); }
        }.define();
        FullLog.setFeatureState(CALLED_FEATURE, true);
        check(fixture.getMethod("feature", long.class).invoke(null, 10L).equals(15L), "Feature probe preserves arguments and existing wide stack values");
        check(fixture.getMethod("number", int.class).invoke(null, -5).equals(5), "Branch and int return unchanged");
        check(fixture.getMethod("invokeNumber", int.class).invoke(null, -6).equals(6), "Expected callback probe preserves caller and return value");
        check(fixture.getMethod("wide").invoke(null).equals(42L), "Two-slot return value unchanged");
        check(fixture.getMethod("decimal").invoke(null).equals(1.25), "Double return unchanged");
        check(fixture.getMethod("object").invoke(null).equals("okay"), "Reference return unchanged");
        check(fixture.getMethod("caught").invoke(null).equals("caught"), "Existing catch handler unchanged");
        try {
            fixture.getMethod("thrown").invoke(null);
            throw new AssertionError("Exception must still propagate");
        } catch (InvocationTargetException expectedError) {
            check(expectedError.getCause() instanceof IllegalStateException, "Original exception type preserved");
        }
        FullLog.flushNow();
        FullLog.setFeatureState(CALLED_FEATURE, false);
        FullLog.event("DISABLED_METHODS_BEGIN", "marker");
        check(fixture.getMethod("number", int.class).invoke(null, -7).equals(7), "Disabled feature helper still executes");
        check(fixture.getMethod("feature", long.class).invoke(null, 10L).equals(15L), "Disabled feature call still executes");
        FullLog.flushNow();
        FullLog.event("DISABLED_METHODS_END", "marker");
        FullLog.error("CHECK_ERROR", new RuntimeException("outer", new IOExceptionFixture("nested")));
        FullLog.critical("CRASH_REPORT", "fixture-critical-report");
        FullLog.stop();
        FullLog.event("DISABLED", "must-not-be-written");
        String text = readLogs(directory);
        check(!text.contains("disabledMarker") && !text.contains("disabledFeatureMarker"), "No disabled feature success messages");
        check(text.contains("enabledMarker") && text.contains("enabledFeatureMarker"), "Active feature calls trace");
        check(text.contains("FEATURE_CALL] RETURN_OK FullLogProbeFeature#add"), "Transformed feature call emits success");
        String disabled = text.substring(text.indexOf("[DISABLED_METHODS_BEGIN]"), text.indexOf("[DISABLED_METHODS_END]"));
        check(!disabled.contains("[MIXIN_STATUS]") && !disabled.contains("[FEATURE_CALL]"), "Disabled feature and its helper mixin methods are silent");
        check(text.contains("fixture setup history"), "Keep mixin setup history from before enable");
        check(text.contains("fixture-caught") && text.contains("fixture-thrown"), "Record caught and explicitly thrown errors");
        check(text.contains("Caused by:") && text.contains("nested"), "Full nested error stack");
        check(text.contains("[ERROR]") && text.contains("[CRASH_REPORT] fixture-critical-report"), "Crash reports use priority error records");
        check(!text.contains("must-not-be-written"), "Off means no writing");
        FullLog.start(directory);
        FullLog.event("REENABLED", "fresh session");
        FullLog.stop();
        check(readLogs(directory).contains("fresh session"), "Can re-enable immediately");
    }

    private static void backpressure(Path directory) throws Exception {
        FullLogWriter writer = new FullLogWriter(directory, 1024 * 1024, 8);
        List<Thread> producers = new ArrayList<>();
        for (int producer = 0; producer < 4; producer++) {
            final int id = producer;
            Thread thread = new Thread(() -> {
                for (int index = 0; index < 10_000; index++) writer.append("burst-" + id + "-" + index + "\n", false);
            });
            producers.add(thread);
            thread.start();
        }
        for (Thread producer : producers) producer.join();
        writer.append("FINAL_PRIORITY_ERROR\n", true);
        writer.close();
        String text = readLogs(directory);
        long written = text.lines().filter(line -> line.startsWith("burst-")).count();
        var losses = java.util.regex.Pattern.compile("dropped=(\\d+)").matcher(text);
        long dropped = 0;
        while (losses.find()) dropped += Long.parseLong(losses.group(1));
        check(written + dropped == 40_000, "Concurrent queue overload reports every lost record, including final losses");
        check(text.endsWith("FINAL_PRIORITY_ERROR\n"), "Final priority error survives overloaded trace queue and close");
    }

    private static void retention(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path old = directory.resolve("full-previous-session-000.log");
        Files.writeString(old, "preserve-existing-session");
        FullLogWriter writer = new FullLogWriter(directory, 96, 64, 3);
        for (int index = 0; index < 30; index++) writer.append("retention-record=" + index + "\n", false);
        writer.close();
        try (var files = Files.list(directory)) {
            check(files.count() == 4, "Retain at most three parts in this session plus the untouched previous session");
        }
        check(Files.readString(old).equals("preserve-existing-session"), "Do not delete the user's existing logs");
        check(readLogs(directory).contains("retention-record=29"), "Rolling session keeps the latest records");
        FullLogWriter nextSession = new FullLogWriter(directory, 96, 64, 3);
        for (int index = 0; index < 12; index++) nextSession.append("next-session-record=" + index + "\n", false);
        nextSession.close();
        try (var files = Files.list(directory)) {
            check(files.count() == 4, "Retention applies across restarts, not separately to every session");
        }
        check(readLogs(directory).contains("next-session-record=11") && Files.exists(old),
                "Cross-session retention keeps latest records and preserves legacy logs");
    }

    private static void summaryVolume(Path directory) throws Exception {
        check(FullLog.INTERVAL_MILLIS == 1000, "Disk summaries are scheduled once per second");
        FullLog.start(directory);
        long start = System.nanoTime();
        RuntimeException repeated = new RuntimeException("repeated-render-error");
        for (int index = 0; index < 100_000; index++) {
            FullLog.mixin("VolumeFixture", "render()V", "", "ENTER");
            FullLog.mixin("VolumeFixture", "render()V", "", "RETURN_OK");
            FullLog.event("PACKET_SEND", "packet=" + index);
            FullLog.error("REPEATED_ERROR", repeated);
        }
        FullLog.mixin("MissingFixture", "expectedHandler()V", "", "ENTER");
        FullLog.mixin("MissingFixture", "expectedHandler()V", "", "RETURN_OK");
        FullLog.mixinExpected("MissingFixture", "expectedHandler()V", "");
        FullLog.setFeatureState(FEATURE, true);
        FullLog.mixinExpected("DisabledMissingFixture", "expectedHandler()V", FEATURE);
        FullLog.setFeatureState(FEATURE, false);
        FullLog.publish("WARN", "SAME_LOGGER", "warning-before-error", false);
        FullLog.publish("ERROR", "SAME_LOGGER", "priority-error-after-warning", true);
        long now = System.nanoTime();
        FullLog.flushAt(now);
        FullLog.flushAt(now + 1_100_000_000L);
        FullLog.stop();
        long intervals = (System.nanoTime() - start) / 1_000_000_000L + 4;
        String log = readLogs(directory);
        check(log.lines().filter(line -> line.contains("mixin=VolumeFixture")).count() <= intervals,
                "100,000 render calls produce only one status per interval");
        check(log.lines().filter(line -> line.contains("[PACKET_SEND]")).count() <= intervals,
                "100,000 packets produce only one summary per interval");
        check(log.split("repeated-render-error", -1).length - 1 <= intervals,
                "Repeated errors keep one stack per interval with a repeat count");
        check(log.contains("status=ERROR mixin=MissingFixture") && log.contains("EXPECTED_CALLBACK_NOT_CALLED"),
                "A past unrelated entry cannot hide a later expected callback that never enters");
        check(!log.contains("mixin=DisabledMissingFixture"), "A disabled feature cannot produce a stale missing-callback error");
        check(log.contains("[ERROR] [main] [SAME_LOGGER] priority-error-after-warning"),
                "An earlier warning from the same logger cannot hide an error's severity");
        check(log.length() < intervals * 16_384, "High-frequency diagnostic traffic stays bounded in size");
        System.out.println("100000 render calls + packets + errors: " + log.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " log bytes");
    }

    private static String readLogs(Path directory) throws Exception {
        StringBuilder text = new StringBuilder();
        try (var paths = Files.list(directory)) {
            for (Path file : paths.sorted().toList()) text.append(Files.readString(file));
        }
        return text.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static class ProbeFixture {
        public static int number(int value) { return value < 0 ? -value : value; }
        public static int invokeNumber(int value) { return number(value); }
        public static long wide() { return 42L; }
        public static double decimal() { return 1.25; }
        public static Object object() { return "okay"; }
        public static long feature(long value) {
            return 3L + vorga.phazeclient.implement.features.modules.other.FullLogProbeFeature.add(value, 2L);
        }
        public static String untouched() { return "untouched"; }
        public static String caught() {
            try { throw new IllegalArgumentException("fixture-caught"); }
            catch (IllegalArgumentException error) { return "caught"; }
        }
        public static void thrown() { throw new IllegalStateException("fixture-thrown"); }
    }

    private static final class IOExceptionFixture extends Exception {
        private IOExceptionFixture(String text) { super(text); }
    }
}
