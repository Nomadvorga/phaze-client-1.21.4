package vorga.phazeclient.mixins;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vorga.phazeclient.api.system.logging.FullLog;
import vorga.phazeclient.api.system.logging.FullLogRuntime;

@Mixin(GameRenderer.class)
public abstract class FullLoggingCrosshairMixin {
    @Inject(method = "updateCrosshairTarget", at = @At("RETURN"), require = 1)
    private void phaze$fullLogTarget(float tickDelta, CallbackInfo ci) {
        if (FullLog.isEnabled()) FullLogRuntime.crosshair(MinecraftClient.getInstance());
    }
}
