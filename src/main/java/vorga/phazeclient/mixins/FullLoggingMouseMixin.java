package vorga.phazeclient.mixins;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vorga.phazeclient.api.system.logging.FullLog;

@Mixin(Mouse.class)
public abstract class FullLoggingMouseMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"), require = 1)
    private void phaze$fullLogButton(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (FullLog.isEnabled() && MinecraftClient.getInstance().currentScreen == null) {
            FullLog.event("GAMEPLAY_MOUSE", "button=" + button + " action=" + action + " modifiers=" + modifiers);
        }
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), require = 1)
    private void phaze$fullLogScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (FullLog.isEnabled() && MinecraftClient.getInstance().currentScreen == null) {
            FullLog.event("GAMEPLAY_SCROLL", "horizontal=" + horizontal + " vertical=" + vertical);
        }
    }
}
