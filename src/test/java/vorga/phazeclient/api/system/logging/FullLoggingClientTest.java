package vorga.phazeclient.api.system.logging;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Difficulty;
import vorga.phazeclient.implement.features.modules.client.Settings;
import vorga.phazeclient.implement.features.modules.hud.FpsHud;
import vorga.phazeclient.implement.menu.MenuScreen;
import vorga.phazeclient.implement.menu.MainMenuScreen;
import vorga.phazeclient.base.util.Lang;
import vorga.phazeclient.implement.menu.components.implement.settings.CheckboxComponent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Real Mixin/Fabric smoke test, running entirely in build/full-logs-client-test. */
public final class FullLoggingClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitFor(client -> client.getOverlay() == null);
        Path logs = context.computeOnClient(client -> client.runDirectory.toPath().resolve("Phaze/logs"));
        SavedState saved = context.computeOnClient(client -> new SavedState(
                Settings.getInstance().fullLogs.isValue(), Settings.getInstance().renderOtherPlayerCosmetics.isValue(),
                FpsHud.getInstance().getHudX(), FpsHud.getInstance().getHudY()));
        try {
            runChecks(context, logs);
        } finally {
            context.runOnClient(client -> {
                Settings.getInstance().renderOtherPlayerCosmetics.setValue(saved.cosmetics());
                FpsHud.getInstance().setHudX(saved.hudX());
                FpsHud.getInstance().setHudY(saved.hudY());
                Settings.getInstance().fullLogs.setValue(saved.logging());
                MenuScreen.INSTANCE.closeModuleDetail();
                client.setScreen(new TitleScreen());
            });
        }
        context.waitForScreen(TitleScreen.class);
        System.out.println("Full logging client checks PASSED");
    }

    private static void runChecks(ClientGameTestContext context, Path logs) {
        String marker = "SMOKE_SESSION_" + java.util.UUID.randomUUID();
        context.runOnClient(client -> {
            Settings settings = Settings.getInstance();
            if (settings.fullLogs.isValue()) {
                check(FullLog.isEnabled(), "Persisted preference starts logging before test enables it");
            }
            settings.fullLogs.setValue(false);
            settings.fullLogs.setValue(true);
            check(FullLog.isEnabled(), "Full logs setting starts writer");
            FullLog.event(marker, "BEGIN");
            client.setScreen(new MainMenuScreen());
            client.currentScreen.children().stream()
                    .filter(child -> child instanceof ButtonWidget button
                            && button.getMessage().getString().equals(Lang.translate("Singleplayer")))
                    .map(child -> (ButtonWidget) child).findFirst().orElseThrow().onPress();
            MenuScreen.INSTANCE.openGui();
            MenuScreen.INSTANCE.openModuleDetail(settings);
        });
        context.waitTicks(5);
        context.runOnClient(client -> {
            // This must go through a transformed Phaze component, not a direct logger call.
            CheckboxComponent component = (CheckboxComponent) MenuScreen.INSTANCE.getModuleDetailComponent()
                    .getSettingComponents().stream()
                    .filter(row -> row.getSetting() == Settings.getInstance().renderOtherPlayerCosmetics)
                    .findFirst().orElseThrow();
            double x = component.x + component.width - 20;
            double y = component.y + component.height / 2;
            check(component.mouseClicked(x, y, 0), "Rendered Phaze toggle handles the click");
            component.mouseReleased(x, y, 0);
            FpsHud.getInstance().setHudX(88.0F);
            FpsHud.getInstance().setHudY(99.0F);
            FullLog.error("SMOKE_EXPECTED_ERROR", new IllegalStateException("full-log-test-stack"));
            FullLog.event("VANILLA_SECTION_BEGIN", "marker");
            client.setScreen(new OptionsScreen(client.currentScreen, client.options));
        });
        context.getInput().pressMouse(0);
        context.getInput().releaseMouse(0);
        context.waitTicks(2);
        context.runOnClient(client -> {
            FullLog.event("VANILLA_SECTION_END", "marker");
            client.setScreen(new TitleScreen());
        });
        gameplay(context);
        context.runOnClient(client -> {
            Settings.getInstance().fullLogs.setValue(false);
            check(!FullLog.isEnabled(), "Full logs setting stops writer");
        });
        try {
            StringBuilder text = new StringBuilder();
            try (var files = Files.list(logs)) {
                for (Path path : files.filter(path -> path.getFileName().toString().startsWith("full-summary-"))
                        .sorted().toList()) text.append(Files.readString(path));
            }
            String all = text.toString();
            String log = all.substring(all.indexOf("[" + marker + "]"));
            check(log.contains("CheckboxComponent") && log.contains("Render Other Player Cosmetics"), "GUI log identifies exact component and setting");
            check(log.contains("CLICK_HANDLED") && log.contains(" x=") && log.contains(" y="), "GUI click handled with coordinates");
            check(log.contains("BUTTON_PRESS") && log.contains("label=" + Lang.translate("Singleplayer")), "Custom main menu button is identified by label");
            check(log.contains("PHAZE_HUD_TRANSFORM") && log.contains("hudX=88.0") && log.contains("hudY=99.0"), "HUD movement captured");
            check(log.contains("SMOKE_EXPECTED_ERROR") && log.contains("full-log-test-stack") && log.contains("FullLoggingClientTest"), "Error includes full stack");
            check(all.contains("APPLIED_OK") && all.contains("tracedMethods="), "Actual mixin lifecycle recorded");
            check(log.contains("[MIXIN_STATUS] status=OK") && log.contains("RETURN_OK="), "Runtime mixin summaries are active");
            for (String category : new String[]{"PLAYER_TICK", "CROSSHAIR", "BLOCK_ACTION", "INTERACT_ITEM",
                    "INVENTORY_ACTION", "GAMEPLAY_MOUSE", "PACKET_SEND", "PACKET_RECEIVE"}) {
                check(log.contains("[" + category + "]"), "Actual gameplay hook recorded: " + category);
            }
            String vanillaSection = log.substring(log.indexOf("[VANILLA_SECTION_BEGIN]"), log.indexOf("[VANILLA_SECTION_END]"));
            check(!vanillaSection.contains("SCREEN_CLICK") && !vanillaSection.contains("CLICK_DISPATCH")
                    && !vanillaSection.contains("CLICK_HANDLED") && !vanillaSection.contains("GAMEPLAY_MOUSE"), "Vanilla buttons generate no GUI click logs");
        } catch (Exception error) {
            throw new AssertionError("Could not inspect full log files", error);
        }
    }

    private record SavedState(boolean logging, boolean cosmetics, float hudX, float hudY) {}

    private static void gameplay(ClientGameTestContext context) {
        try (var world = context.worldBuilder().adjustSettings(creator -> {
            creator.setWorldName("Phaze Full Logs");
            creator.setGameMode(WorldCreator.Mode.CREATIVE);
            creator.setDifficulty(Difficulty.PEACEFUL);
        }).create()) {
            context.waitFor(client -> client.player != null && client.world != null);
            context.runOnClient(client -> {
                client.setScreen(null);
                client.interactionManager.attackBlock(client.player.getBlockPos().down(), Direction.UP);
                client.interactionManager.cancelBlockBreaking();
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, 36, 0,
                        SlotActionType.PICKUP, client.player);
            });
            context.getInput().pressMouse(0);
            context.getInput().releaseMouse(0);
            context.getInput().holdKey(options -> options.forwardKey);
            context.waitTicks(30);
            context.getInput().releaseKey(options -> options.forwardKey);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
