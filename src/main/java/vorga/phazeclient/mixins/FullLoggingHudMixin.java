package vorga.phazeclient.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vorga.phazeclient.api.system.logging.FullLog;
import vorga.phazeclient.api.system.logging.FullLogGui;

@Mixin(targets = {
        "vorga.phazeclient.implement.features.modules.hud.RectHudModule",
        "vorga.phazeclient.implement.features.modules.hud.ArmorHud"
}, remap = false)
public abstract class FullLoggingHudMixin {
    @Inject(method = {"setHudX", "setHudY", "setHudScale", "setHudXRatio", "setHudYRatio",
            "setHudPosition", "setHudPositionRatio", "resetHudTransform"}, at = @At("RETURN"), require = 0)
    private void phaze$fullLogHudTransform(CallbackInfo ci) {
        if (FullLog.isEnabled()) FullLogGui.hud(this, ci.getId());
    }
}
