package vorga.phazeclient.implement.features.modules.other;

/** Tiny Minecraft-independent call target for the instrumentation verifier. */
public final class FullLogProbeFeature {
    private FullLogProbeFeature() {}

    public static long add(long left, long right) {
        return left + right;
    }
}
