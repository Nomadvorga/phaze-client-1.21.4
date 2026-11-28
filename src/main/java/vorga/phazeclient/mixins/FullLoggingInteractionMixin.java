package vorga.phazeclient.mixins;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vorga.phazeclient.api.system.logging.FullLog;
import vorga.phazeclient.api.system.logging.FullLogRuntime;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class FullLoggingInteractionMixin {
    @Inject(method = "attackEntity", at = @At("HEAD"), require = 1)
    private void phaze$fullLogAttack(PlayerEntity player, Entity target, CallbackInfo ci) {
        if (!FullLog.isEnabled()) return;
        FullLog.event("ATTACK_ENTITY", "BEGIN " + FullLogRuntime.entity(target)
                + " attackerPos=" + player.getPos() + " distance=" + player.distanceTo(target)
                + " weapon=" + FullLogRuntime.item(player.getMainHandStack()));
    }

    @Inject(method = "attackEntity", at = @At("RETURN"), require = 1)
    private void phaze$fullLogAttackReturn(PlayerEntity player, Entity target, CallbackInfo ci) {
        if (FullLog.isEnabled()) FullLog.event("ATTACK_ENTITY", "RETURN " + FullLogRuntime.entity(target)
                + "; local call completed; server damage is not yet confirmed");
    }

    @Inject(method = {"attackBlock", "updateBlockBreakingProgress"}, at = @At("HEAD"), require = 1)
    private void phaze$fullLogMine(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (FullLog.isEnabled()) FullLog.event("BLOCK_ACTION", "BEGIN method=" + cir.getId() + " "
                + FullLogRuntime.block(pos) + " side=" + direction);
    }

    @Inject(method = {"attackBlock", "updateBlockBreakingProgress"}, at = @At("RETURN"), require = 1)
    private void phaze$fullLogMineReturn(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (FullLog.isEnabled()) FullLog.event("BLOCK_ACTION", "RETURN method=" + cir.getId()
                + " result=" + cir.getReturnValue() + " " + FullLogRuntime.block(pos));
    }

    @Inject(method = "breakBlock", at = @At("RETURN"), require = 1)
    private void phaze$fullLogBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (FullLog.isEnabled()) FullLog.event("BREAK_BLOCK", FullLogRuntime.block(pos) + " result=" + cir.getReturnValue());
    }

    @Inject(method = "cancelBlockBreaking", at = @At("HEAD"), require = 1)
    private void phaze$fullLogCancelBreak(CallbackInfo ci) {
        if (FullLog.isEnabled()) FullLog.event("CANCEL_BLOCK_BREAKING", "called");
    }

    @Inject(method = "interactBlock", at = @At("HEAD"), require = 1)
    private void phaze$fullLogUseBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hit,
                                      CallbackInfoReturnable<ActionResult> cir) {
        if (FullLog.isEnabled()) FullLog.event("INTERACT_BLOCK", "BEGIN hand=" + hand + " "
                + FullLogRuntime.target(hit) + " item=" + FullLogRuntime.item(player.getStackInHand(hand)));
    }

    @Inject(method = "interactBlock", at = @At("RETURN"), require = 1)
    private void phaze$fullLogUseBlockReturn(ClientPlayerEntity player, Hand hand, BlockHitResult hit,
                                            CallbackInfoReturnable<ActionResult> cir) {
        if (FullLog.isEnabled()) FullLog.event("INTERACT_BLOCK", "RETURN result=" + cir.getReturnValue()
                + " hand=" + hand + " " + FullLogRuntime.target(hit));
    }

    @Inject(method = "interactItem", at = @At("HEAD"), require = 1)
    private void phaze$fullLogUseItem(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (FullLog.isEnabled()) FullLog.event("INTERACT_ITEM", "BEGIN hand=" + hand
                + " item=" + FullLogRuntime.item(player.getStackInHand(hand)));
    }

    @Inject(method = "interactItem", at = @At("RETURN"), require = 1)
    private void phaze$fullLogUseItemReturn(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (FullLog.isEnabled()) FullLog.event("INTERACT_ITEM", "RETURN hand=" + hand + " result=" + cir.getReturnValue());
    }

    @Inject(method = "interactEntity", at = @At("HEAD"), require = 1)
    private void phaze$fullLogUseEntity(PlayerEntity player, Entity target, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (FullLog.isEnabled()) FullLog.event("INTERACT_ENTITY", "BEGIN hand=" + hand + " " + FullLogRuntime.entity(target));
    }

    @Inject(method = "interactEntityAtLocation", at = @At("HEAD"), require = 1)
    private void phaze$fullLogUseEntityAt(PlayerEntity player, Entity target, EntityHitResult hit, Hand hand,
                                         CallbackInfoReturnable<ActionResult> cir) {
        if (FullLog.isEnabled()) FullLog.event("INTERACT_ENTITY_AT", "BEGIN hand=" + hand + " " + FullLogRuntime.target(hit));
    }

    @Inject(method = "clickSlot", at = @At("HEAD"), require = 1)
    private void phaze$fullLogSlot(int syncId, int slotId, int button, SlotActionType action, PlayerEntity player, CallbackInfo ci) {
        if (FullLog.isEnabled()) FullLog.event("INVENTORY_ACTION", "syncId=" + syncId + " slot=" + slotId
                + " button=" + button + " action=" + action + " cursor=" + FullLogRuntime.item(player.currentScreenHandler.getCursorStack()));
    }

    @Inject(method = "stopUsingItem", at = @At("HEAD"), require = 1)
    private void phaze$fullLogStopUse(PlayerEntity player, CallbackInfo ci) {
        if (FullLog.isEnabled()) FullLog.event("STOP_USING_ITEM", "active=" + FullLogRuntime.item(player.getActiveItem())
                + " using=" + player.isUsingItem());
    }
}
