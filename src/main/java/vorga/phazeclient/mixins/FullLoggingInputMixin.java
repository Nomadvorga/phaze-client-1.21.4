package vorga.phazeclient.mixins;

import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vorga.phazeclient.api.system.logging.FullLog;

@Mixin(Keyboard.class)
public abstract class FullLoggingInputMixin {
    @Inject(method = "onKey", at = @At("HEAD"), require = 1)
    private void phaze$fullLogGameplayKey(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
        if (FullLog.isEnabled() && MinecraftClient.getInstance().currentScreen == null) {
            FullLog.event("GAMEPLAY_KEY", "key=" + key + " scancode=" + scanCode + " action=" + action + " modifiers=" + modifiers);
        }
    }
}
