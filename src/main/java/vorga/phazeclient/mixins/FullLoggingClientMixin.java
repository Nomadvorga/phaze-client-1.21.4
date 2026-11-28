package vorga.phazeclient.mixins;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.ReportType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vorga.phazeclient.api.system.logging.FullLog;
import vorga.phazeclient.api.system.logging.FullLogGui;
import vorga.phazeclient.api.system.logging.FullLogRuntime;

import java.io.File;

@Mixin(MinecraftClient.class)
public abstract class FullLoggingClientMixin {
    @Inject(method = "doAttack", at = @At("HEAD"), require = 1)
    private void phaze$fullLogSwing(CallbackInfoReturnable<Boolean> cir) {
        if (FullLog.isEnabled()) FullLog.event("ATTACK_INPUT", FullLogRuntime.target(((MinecraftClient) (Object) this).crosshairTarget));
    }

    @Inject(method = {"doItemUse", "doItemPick"}, at = @At("HEAD"), require = 1)
    private void phaze$fullLogItemInput(CallbackInfo ci) {
        if (FullLog.isEnabled()) FullLog.event("ITEM_INPUT", "method=" + ci.getId() + " "
                + FullLogRuntime.target(((MinecraftClient) (Object) this).crosshairTarget));
    }

    @Inject(method = "setScreen", at = @At("HEAD"), require = 1)
    private void phaze$fullLogScreen(Screen next, CallbackInfo ci) {
        if (!FullLog.isEnabled()) return;
        Screen current = ((MinecraftClient) (Object) this).currentScreen;
        if (FullLogGui.isPhazeTarget(current)) FullLog.event("PHAZE_GUI", "CLOSE screen=" + current.getClass().getSimpleName());
        if (FullLogGui.isPhazeTarget(next)) FullLog.event("PHAZE_GUI", "OPEN screen=" + next.getClass().getSimpleName());
    }

    @Inject(method = "printCrashReport(Lnet/minecraft/client/MinecraftClient;Ljava/io/File;Lnet/minecraft/util/crash/CrashReport;)V",
            at = @At("HEAD"), require = 1)
    private static void phaze$fullLogCrash(MinecraftClient client, File directory, CrashReport report, CallbackInfo ci) {
        if (FullLog.isEnabled()) {
            FullLog.critical("CRASH_REPORT", report.asString(ReportType.MINECRAFT_CRASH_REPORT));
            FullLog.stop();
        }
    }
}
