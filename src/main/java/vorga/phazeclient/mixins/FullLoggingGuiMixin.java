package vorga.phazeclient.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vorga.phazeclient.api.system.logging.FullLogGui;

/** Phaze-owned components only: no Screen/Widget/vanilla button targets. */
@Mixin(targets = {
        "vorga.phazeclient.implement.menu.components.AbstractComponent",
        "vorga.phazeclient.implement.menu.components.implement.category.CategoryComponent",
        "vorga.phazeclient.implement.menu.components.implement.module.ModuleComponent",
        "vorga.phazeclient.implement.menu.components.implement.other.BackgroundComponent",
        "vorga.phazeclient.implement.menu.components.implement.other.ButtonComponent",
        "vorga.phazeclient.implement.menu.components.implement.other.CategoryContainerComponent",
        "vorga.phazeclient.implement.menu.components.implement.other.CheckComponent",
        "vorga.phazeclient.implement.menu.components.implement.other.ConfigsViewComponent",
        "vorga.phazeclient.implement.menu.components.implement.other.ConfigShareModalComponent",
        "vorga.phazeclient.implement.menu.components.implement.other.CosmeticsViewComponent",
        "vorga.phazeclient.implement.menu.components.implement.other.MessengerViewComponent",
        "vorga.phazeclient.implement.menu.components.implement.other.ModuleDetailComponent",
        "vorga.phazeclient.implement.menu.components.implement.other.SearchComponent",
        "vorga.phazeclient.implement.menu.components.implement.other.SettingComponent",
        "vorga.phazeclient.implement.menu.components.implement.other.WaypointEditorModal",
        "vorga.phazeclient.implement.menu.components.implement.settings.BindComponent",
        "vorga.phazeclient.implement.menu.components.implement.settings.CheckboxComponent",
        "vorga.phazeclient.implement.menu.components.implement.settings.ColorComponent",
        "vorga.phazeclient.implement.menu.components.implement.settings.GroupComponent",
        "vorga.phazeclient.implement.menu.components.implement.settings.ItemPickerComponent",
        "vorga.phazeclient.implement.menu.components.implement.settings.MultiColorComponent",
        "vorga.phazeclient.implement.menu.components.implement.settings.SButtonComponent",
        "vorga.phazeclient.implement.menu.components.implement.settings.SectionComponent",
        "vorga.phazeclient.implement.menu.components.implement.settings.TextComponent",
        "vorga.phazeclient.implement.menu.components.implement.settings.ValueComponent",
        "vorga.phazeclient.implement.menu.components.implement.settings.WaypointListComponent",
        "vorga.phazeclient.implement.menu.components.implement.settings.select.SelectComponent",
        "vorga.phazeclient.implement.menu.components.implement.settings.select.SelectedButton",
        "vorga.phazeclient.implement.menu.components.implement.settings.multiselect.MultiSelectComponent",
        "vorga.phazeclient.implement.menu.components.implement.window.WindowManager",
        "vorga.phazeclient.implement.menu.components.implement.window.AbstractWindow",
        "vorga.phazeclient.implement.menu.components.implement.window.implement.AbstractBindWindow",
        "vorga.phazeclient.implement.menu.components.implement.window.implement.settings.color.ColorWindow",
        "vorga.phazeclient.implement.menu.components.implement.window.implement.settings.color.SettingColorPickerWindow",
        "vorga.phazeclient.implement.menu.components.implement.window.implement.settings.color.ColorPresetButton",
        "vorga.phazeclient.implement.menu.components.implement.window.implement.settings.color.component.AlphaComponent",
        "vorga.phazeclient.implement.menu.components.implement.window.implement.settings.color.component.HueComponent",
        "vorga.phazeclient.implement.menu.components.implement.window.implement.settings.color.component.SaturationComponent",
        "vorga.phazeclient.implement.menu.components.implement.window.implement.settings.color.component.ColorEditorComponent",
        "vorga.phazeclient.implement.menu.components.implement.window.implement.settings.color.component.ColorPresetComponent",
        "vorga.phazeclient.implement.menu.components.implement.window.implement.settings.group.GroupWindow",
        "vorga.phazeclient.implement.menu.components.implement.window.implement.settings.group.WorldColorGroupWindow",
        "vorga.phazeclient.implement.menu.components.implement.window.implement.settings.itempicker.ItemPickerColorWindow"
}, remap = false)
public abstract class FullLoggingGuiMixin {
    @Inject(method = "mouseClicked(DDI)Z", at = @At("HEAD"), require = 0)
    private void phaze$fullLogGuiClick(double x, double y, int button, CallbackInfoReturnable<Boolean> cir) {
        FullLogGui.input(this, "CLICK_DISPATCH", x, y, "button=" + button);
    }

    @Inject(method = "mouseClicked(DDI)Z", at = @At("RETURN"), require = 0)
    private void phaze$fullLogGuiHandled(double x, double y, int button, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) FullLogGui.input(this, "CLICK_HANDLED", x, y, "button=" + button);
    }

    @Inject(method = "mouseReleased(DDI)Z", at = @At("HEAD"), require = 0)
    private void phaze$fullLogGuiRelease(double x, double y, int button, CallbackInfoReturnable<Boolean> cir) {
        FullLogGui.input(this, "RELEASE_BEFORE", x, y, "button=" + button);
    }

    @Inject(method = "mouseReleased(DDI)Z", at = @At("RETURN"), require = 0)
    private void phaze$fullLogGuiReleaseAfter(double x, double y, int button, CallbackInfoReturnable<Boolean> cir) {
        FullLogGui.input(this, "RELEASE_AFTER", x, y, "button=" + button + " handled=" + cir.getReturnValue());
    }

    @Inject(method = "mouseDragged(DDIDD)Z", at = @At("HEAD"), require = 0)
    private void phaze$fullLogGuiDrag(double x, double y, int button, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        FullLogGui.input(this, "DRAG", x, y, "button=" + button + " dx=" + dx + " dy=" + dy);
    }

    @Inject(method = "mouseScrolled(DDD)Z", at = @At("HEAD"), require = 0)
    private void phaze$fullLogGuiScroll(double x, double y, double amount, CallbackInfoReturnable<Boolean> cir) {
        FullLogGui.input(this, "SCROLL", x, y, "amount=" + amount);
    }

    @Inject(method = {"keyPressed(III)Z", "keyReleased(III)Z"}, at = @At("HEAD"), require = 0)
    private void phaze$fullLogGuiKey(int key, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        FullLogGui.input(this, "KEY_DISPATCH", 0, 0, "method=" + cir.getId()
                + " key=" + key + " scanCode=" + scanCode + " modifiers=" + modifiers);
    }
}
