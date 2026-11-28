package vorga.phazeclient.api.system.logging;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import vorga.phazeclient.api.feature.module.Module;
import vorga.phazeclient.api.feature.module.setting.Setting;
import vorga.phazeclient.api.feature.module.setting.implement.*;
import vorga.phazeclient.core.Main;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minecraft-aware side of full diagnostics; never loaded by the mixin plugin. */
public final class FullLogRuntime {
    private static final Map<Setting, String> SETTING_VALUES = new IdentityHashMap<>();
    private static boolean active;
    private static Object lastWorld;
    private static long tick;
    private static long nextPlayerSample;
    private static long nextCrosshairSample;

    private FullLogRuntime() {}

    public static synchronized void setEnabled(boolean enabled) {
        if (!enabled) {
            FullLog.stop();
            FullLogConsole.detach();
            active = false;
            SETTING_VALUES.clear();
            return;
        }
        if (active && FullLog.isEnabled()) return;
        try {
            refreshFeatures();
            FullLog.start(FabricLoader.getInstance().getGameDir().resolve("Phaze").resolve("logs"));
            FullLogConsole.attach();
            active = true;
            tick = 0;
            nextPlayerSample = nextCrosshairSample = 0;
            lastWorld = null;
            FullLog.event("ENVIRONMENT", "minecraft=" + FabricLoader.getInstance().getModContainer("minecraft")
                    .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown")
                    + " phaze=" + FabricLoader.getInstance().getModContainer("phaze")
                    .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown"));
            for (Module module : Main.getInstance().getModuleProvider().getModules()) {
                for (Setting setting : module.settings()) snapshotSetting(setting);
            }
            FullLog.event("LOG_MODE", "One summary per second; repeated mixin/feature/input/packet events are counted, not written individually");
        } catch (IOException | RuntimeException error) {
            FullLog.error("LOGGER_START", error);
            FullLog.stop();
            FullLogConsole.detach();
            active = false;
            LogManager.getLogger("Phaze").error("Could not enable full logs in Phaze/logs", error);
        }
    }

    public static void refreshFeatures() {
        Map<String, Boolean> states = new LinkedHashMap<>();
        for (Module module : Main.getInstance().getModuleProvider().getModules()) {
            states.put(module.getClass().getName().replace('.', '/'), module.isEnabled());
        }
        FullLog.setFeatureStates(states);
    }

    public static void moduleChanged(Module module) {
        if (!FullLog.isEnabled()) return;
        boolean enabled = module.isEnabled();
        FullLog.setFeatureState(module.getClass().getName().replace('.', '/'), enabled);
        FullLog.event("MODULE", "module:" + module.getIdentifier(), "id=" + module.getIdentifier() + " name=" + module.getVisibleName()
                + " requested=" + module.isState() + " effective=" + enabled + " bind=" + module.getKey());
    }

    public static synchronized void settingChanged(Setting setting) {
        if (!FullLog.isEnabled()) return;
        String value = settingValue(setting);
        String previous = SETTING_VALUES.put(setting, value);
        FullLog.event("SETTING", "setting:" + setting.getModuleContext() + '/' + setting.getRawName(), "module=" + setting.getModuleContext() + " name=" + setting.getRawName()
                + " old=" + previous + " new=" + value);
    }

    private static synchronized void snapshotSetting(Setting setting) {
        String value = settingValue(setting);
        SETTING_VALUES.put(setting, value);
        if (setting instanceof GroupSetting group) {
            for (Setting child : group.getSubSettings()) snapshotSetting(child);
        } else if (setting instanceof MultiColorSetting colors) {
            for (ColorSetting child : colors.getColors()) snapshotSetting(child);
        }
    }

    static String settingValue(Setting setting) {
        return switch (setting) {
            case BooleanSetting value -> "value=" + value.isValue() + ",bind=" + value.getKey();
            case GroupSetting value -> "value=" + value.isValue();
            case ValueSetting value -> Float.toString(value.getValue());
            case SelectSetting value -> value.getSelected();
            case MultiSelectSetting value -> String.valueOf(value.getSelected());
            case ColorSetting value -> String.format(java.util.Locale.ROOT, "#%08X", value.getColor());
            case MultiColorSetting value -> java.util.Arrays.toString(value.getColorValues());
            case ItemPickerSetting value -> "active=" + value.isActive() + ",enabled=" + value.isEnabled()
                    + ",item=" + value.getItemId() + ",displayName=" + value.getDisplayName()
                    + ",matchName=" + value.getMatchName() + ",partialMatch=" + value.isPartialMatch()
                    + ",color=" + String.format(java.util.Locale.ROOT, "#%08X", value.getHighlightColor());
            case BindSetting value -> Integer.toString(value.getKey());
            case TextSetting value -> value.getText();
            default -> setting.getClass().getSimpleName();
        };
    }

    public static void tick(MinecraftClient client) {
        if (!FullLog.isEnabled()) return;
        long now = System.nanoTime();
        if (now < nextPlayerSample) return;
        nextPlayerSample = now + 1_000_000_000L;
        try {
            refreshFeatures();
            if (lastWorld != client.world) {
                lastWorld = client.world;
                FullLog.event("WORLD", client.world == null ? "LEAVE" : "JOIN dimension="
                        + client.world.getRegistryKey().getValue() + " singleplayer=" + client.isInSingleplayer());
            }
            tick++;
            if (client.player == null) return;
            var player = client.player;
            FullLog.event("PLAYER_TICK", "tick=" + tick + " pos=" + player.getPos()
                    + " velocity=" + player.getVelocity() + " yaw=" + player.getYaw() + " pitch=" + player.getPitch()
                    + " pose=" + player.getPose() + " onGround=" + player.isOnGround()
                    + " sprinting=" + player.isSprinting() + " sneaking=" + player.isSneaking()
                    + " swimming=" + player.isSwimming() + " flying=" + player.getAbilities().flying
                    + " usingItem=" + player.isUsingItem() + " health=" + player.getHealth()
                    + " hunger=" + player.getHungerManager().getFoodLevel()
                    + " selectedSlot=" + player.getInventory().selectedSlot
                    + " mainHand=" + item(player.getMainHandStack()) + " offHand=" + item(player.getOffHandStack()));
            crosshair(client);
        } catch (RuntimeException error) {
            FullLog.error("PLAYER_DIAGNOSTICS", error);
        }
    }

    public static void crosshair(MinecraftClient client) {
        if (!FullLog.isEnabled() || client.player == null || client.currentScreen != null) return;
        long now = System.nanoTime();
        if (now < nextCrosshairSample) return;
        nextCrosshairSample = now + 1_000_000_000L;
        FullLog.event("CROSSHAIR", target(client.crosshairTarget));
    }

    public static String target(HitResult hit) {
        if (hit == null) return "target=null";
        if (hit instanceof BlockHitResult block) {
            return "target=BLOCK " + block(block.getBlockPos()) + " side=" + block.getSide()
                    + " hit=" + block.getPos() + " inside=" + block.isInsideBlock();
        }
        if (hit instanceof EntityHitResult entity) return "target=ENTITY " + entity(entity.getEntity()) + " hit=" + hit.getPos();
        return "target=" + hit.getType() + " hit=" + hit.getPos();
    }

    public static String block(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        return "pos=" + pos.toShortString() + " state=" + (client.world == null ? "no_world" : client.world.getBlockState(pos));
    }

    public static String entity(Entity entity) {
        return entity == null ? "entity=null" : "entity=" + Registries.ENTITY_TYPE.getId(entity.getType())
                + " id=" + entity.getId() + " uuid=" + entity.getUuidAsString() + " pos=" + entity.getPos();
    }

    public static String item(ItemStack stack) {
        return stack.isEmpty() ? "empty" : Registries.ITEM.getId(stack.getItem()) + " x" + stack.getCount()
                + " damage=" + stack.getDamage() + "/" + stack.getMaxDamage();
    }
}
