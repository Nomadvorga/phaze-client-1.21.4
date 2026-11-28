package vorga.phazeclient.api.system.logging;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.api.EnvType;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the existing preference without initializing Settings or Minecraft. */
public final class FullLogBootstrap implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return;
        Path game = FabricLoader.getInstance().getGameDir();
        if (!savedPreference(game)) return;
        try {
            FullLog.start(game.resolve("Phaze/logs"));
            FullLogConsole.attach();
            FullLog.event("BOOTSTRAP", "Full logs enabled before Minecraft initialization from saved Full Logs preference");
        } catch (IOException | RuntimeException error) {
            System.err.println("[Phaze] Could not start early full logs: " + error);
            error.printStackTrace(System.err);
        }
    }

    static boolean savedPreference(Path game) {
        try {
            JsonObject pointer = readWithBackup(game.resolve("Phaze/files/current_config.json"));
            String name = pointer != null && pointer.has("current_config") ? pointer.get("current_config").getAsString() : "default";
            Path configs = game.resolve("Phaze/configs").toAbsolutePath().normalize();
            Path selected = configs.resolve(name + ".Phaze").normalize();
            if (!selected.startsWith(configs)) return false;
            JsonObject config = readWithBackup(selected);
            if (config == null || !config.has("modules")) return false;
            JsonObject modules = config.getAsJsonObject("modules");
            if (!modules.has("themes")) return false;
            JsonObject settings = modules.getAsJsonObject("themes").getAsJsonObject("settings");
            return settings != null && settings.has("Full Logs") && settings.get("Full Logs").getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static JsonObject readWithBackup(Path path) {
        JsonObject value = read(path);
        return value != null ? value : read(path.resolveSibling(path.getFileName() + ".bak"));
    }

    private static JsonObject read(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try (Reader reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}
