package vorga.phazeclient.implement.features.modules.client;

/**
 * Source-compatible facade for callers that used the former Theme module API.
 * Settings is the registered module; its stable module ID remains "themes".
 */
@Deprecated
public final class Theme {
    private Theme() {
    }

    public static Settings getInstance() {
        return Settings.getInstance();
    }
}
