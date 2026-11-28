package vorga.phazeclient.implement.features.modules.other;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.IdentityHashMap;
import net.minecraft.client.model.ModelPart;

/**
 * 3D Skins renderer that operates in pure additive/subtractive mode:
 * 1. preRender: Hides the vanilla 2D second layers on the model so vanilla does not draw them.
 * 2. postRender: Renders clean extruded 3D voxels attached directly to the model's limb transforms.
 *
 * This completely avoids mutating ModelPart.cuboids lists, guaranteeing 100% compatibility
 * with Sodium, Iris, ImmediatelyFast, and Entity Culling.
 */
public final class ThreeDSkinsHelper {

    private static boolean activeForCurrentEntity = false;
    private static ThreeDSkinsVoxelizer.BakedSkinLayers currentBaked = null;
    private static boolean currentIsSlim = false;
    private static final IdentityHashMap<ModelPart, Boolean> originalVisibility = new IdentityHashMap<>();

    private ThreeDSkinsHelper() {}

    public static void preRender(PlayerEntityModel model, PlayerEntityRenderState state) {
        activeForCurrentEntity = false;
        currentBaked = null;
        originalVisibility.clear();

        if (model == null || state == null) return;
        if (state.invisible && state.invisibleToPlayer && !state.hasOutline) return;

        ThreeDSkins module = ThreeDSkins.getInstance();
        if (module == null || !module.isEnabled()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        boolean isLocal = player != null && state.id == player.getId();
        if (!isLocal && !module.showOthers.isValue()) return;

        // Distance LOD check
        if (player != null && !isLocal) {
            double dx = player.getX() - state.x;
            double dy = player.getY() - state.y;
            double dz = player.getZ() - state.z;
            double maxDist = module.maxDistance.getValue();
            if (dx * dx + dy * dy + dz * dz > maxDist * maxDist) return;
        }

        Identifier skinId = state.skinTextures != null ? state.skinTextures.texture() : null;
        if (skinId == null) return;

        ThreeDSkinsVoxelizer.BakedSkinLayers baked = ThreeDSkinsVoxelizer.get(skinId);
        if (baked == null || !baked.hasAny()) return;

        activeForCurrentEntity = true;
        currentBaked = baked;
        currentIsSlim = state.skinTextures != null && state.skinTextures.model() == SkinTextures.Model.SLIM;

        // Hide vanilla flat 2D layers that are replaced by 3D voxels
        if (module.hat3D.isValue() && model.head.visible && model.hat.visible && !baked.hat.isEmpty()) {
            hide(model.hat);
        }
        // Vanilla spectators have only a head, even if their skin toggles enable body layers.
        if (state.spectator) return;
        if (module.jacket3D.isValue() && model.body.visible && model.jacket.visible && !baked.jacket.isEmpty()) {
            hide(model.jacket);
        }
        if (module.sleeves3D.isValue()) {
            List<ThreeDSkinsVoxelizer.VoxelQuad> leftSleeve = currentIsSlim ? baked.leftSleeveSlim : baked.leftSleeveWide;
            List<ThreeDSkinsVoxelizer.VoxelQuad> rightSleeve = currentIsSlim ? baked.rightSleeveSlim : baked.rightSleeveWide;
            if (model.leftArm.visible && model.leftSleeve.visible && leftSleeve != null && !leftSleeve.isEmpty()) {
                hide(model.leftSleeve);
            }
            if (model.rightArm.visible && model.rightSleeve.visible && rightSleeve != null && !rightSleeve.isEmpty()) {
                hide(model.rightSleeve);
            }
        }
        if (module.pants3D.isValue()) {
            if (model.leftLeg.visible && model.leftPants.visible && !baked.leftPants.isEmpty()) {
                hide(model.leftPants);
            }
            if (model.rightLeg.visible && model.rightPants.visible && !baked.rightPants.isEmpty()) {
                hide(model.rightPants);
            }
        }
    }

    public static void postRender(
            PlayerEntityModel model,
            PlayerEntityRenderState state,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            int overlay
    ) {
        if (!activeForCurrentEntity || currentBaked == null || model == null || state == null) {
            restoreVisibility();
            activeForCurrentEntity = false;
            currentBaked = null;
            return;
        }

        ThreeDSkins module = ThreeDSkins.getInstance();
        if (module == null || !module.isEnabled()) {
            restoreVisibility();
            activeForCurrentEntity = false;
            currentBaked = null;
            return;
        }

        Identifier skinId = state.skinTextures != null ? state.skinTextures.texture() : null;
        if (skinId == null) {
            restoreVisibility();
            activeForCurrentEntity = false;
            currentBaked = null;
            return;
        }

        // Match vanilla's ghost-head alpha and render layer for visible invisible players.
        // Keep the normal skin path in the cutout pipeline.
        boolean translucent = state.invisible && !state.invisibleToPlayer;
        RenderLayer layer = translucent ? RenderLayer.getItemEntityTranslucentCull(skinId)
                : state.invisible ? (state.hasOutline ? RenderLayer.getOutline(skinId) : null)
                : RenderLayer.getEntityCutoutNoCull(skinId);
        if (layer == null) {
            restoreVisibility();
            activeForCurrentEntity = false;
            currentBaked = null;
            return;
        }
        int color = translucent ? 0x26FFFFFF : 0xFFFFFFFF;
        VertexConsumer consumer = vertexConsumers.getBuffer(layer);

        // 1. Hat (attached to head)
        if (module.hat3D.isValue() && Boolean.TRUE.equals(originalVisibility.get(model.hat)) && !currentBaked.hat.isEmpty()) {
            matrices.push();
            transformPart(matrices, model.head);
            matrices.scale(1.0f / 16.0f, 1.0f / 16.0f, 1.0f / 16.0f);
            renderQuads(consumer, matrices, currentBaked.hat, light, overlay, color);
            matrices.pop();
        }

        // 2. Jacket (attached to body)
        if (!state.spectator && module.jacket3D.isValue() && Boolean.TRUE.equals(originalVisibility.get(model.jacket)) && !currentBaked.jacket.isEmpty()) {
            matrices.push();
            transformPart(matrices, model.body);
            matrices.scale(1.0f / 16.0f, 1.0f / 16.0f, 1.0f / 16.0f);
            renderQuads(consumer, matrices, currentBaked.jacket, light, overlay, color);
            matrices.pop();
        }

        // 3. Sleeves (attached to rightArm and leftArm)
        if (!state.spectator && module.sleeves3D.isValue()) {
            List<ThreeDSkinsVoxelizer.VoxelQuad> rightSleeve = currentIsSlim ? currentBaked.rightSleeveSlim : currentBaked.rightSleeveWide;
            if (Boolean.TRUE.equals(originalVisibility.get(model.rightSleeve)) && rightSleeve != null && !rightSleeve.isEmpty()) {
                matrices.push();
                model.rightArm.rotate(matrices);
                matrices.scale(1.0f / 16.0f, 1.0f / 16.0f, 1.0f / 16.0f);
                renderQuads(consumer, matrices, rightSleeve, light, overlay, color);
                matrices.pop();
            }

            List<ThreeDSkinsVoxelizer.VoxelQuad> leftSleeve = currentIsSlim ? currentBaked.leftSleeveSlim : currentBaked.leftSleeveWide;
            if (Boolean.TRUE.equals(originalVisibility.get(model.leftSleeve)) && leftSleeve != null && !leftSleeve.isEmpty()) {
                matrices.push();
                model.leftArm.rotate(matrices);
                matrices.scale(1.0f / 16.0f, 1.0f / 16.0f, 1.0f / 16.0f);
                renderQuads(consumer, matrices, leftSleeve, light, overlay, color);
                matrices.pop();
            }
        }

        // 4. Pants (attached to rightLeg and leftLeg)
        if (!state.spectator && module.pants3D.isValue()) {
            if (Boolean.TRUE.equals(originalVisibility.get(model.rightPants)) && !currentBaked.rightPants.isEmpty()) {
                matrices.push();
                model.rightLeg.rotate(matrices);
                matrices.scale(1.0f / 16.0f, 1.0f / 16.0f, 1.0f / 16.0f);
                renderQuads(consumer, matrices, currentBaked.rightPants, light, overlay, color);
                matrices.pop();
            }

            if (Boolean.TRUE.equals(originalVisibility.get(model.leftPants)) && !currentBaked.leftPants.isEmpty()) {
                matrices.push();
                model.leftLeg.rotate(matrices);
                matrices.scale(1.0f / 16.0f, 1.0f / 16.0f, 1.0f / 16.0f);
                renderQuads(consumer, matrices, currentBaked.leftPants, light, overlay, color);
                matrices.pop();
            }
        }

        restoreVisibility();
        activeForCurrentEntity = false;
        currentBaked = null;
    }

    private static void hide(ModelPart part) {
        originalVisibility.putIfAbsent(part, part.visible);
        part.visible = false;
    }

    private static void restoreVisibility() {
        originalVisibility.forEach((part, visible) -> part.visible = visible);
        originalVisibility.clear();
    }

    /** ModelPart#rotate applies only orientation; vanilla render also applies
     * the part pivot. Missing that translation was the source of the visible
     * gap between the baked layer and the player model. */
    private static void transformPart(MatrixStack matrices, ModelPart part) {
        // MatrixStack is already in world/block units here. The voxel
        // coordinates are converted to blocks by the scale that follows, so
        // the model pivot must be converted independently. Using raw model
        // units made arms and legs jump several blocks away.
        matrices.translate(part.pivotX / 16.0f, part.pivotY / 16.0f, part.pivotZ / 16.0f);
        part.rotate(matrices);
    }

    private static void renderQuads(
            VertexConsumer consumer,
            MatrixStack matrices,
            List<ThreeDSkinsVoxelizer.VoxelQuad> quads,
            int light,
            int overlay,
            int color
    ) {
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f posMatrix = entry.getPositionMatrix();

        for (ThreeDSkinsVoxelizer.VoxelQuad quad : quads) {
            Vector3f normal = quad.normal();
            for (ThreeDSkinsVoxelizer.VoxelVertex v : quad.vertices()) {
                consumer.vertex(posMatrix, v.x(), v.y(), v.z())
                        .color(color)
                        .texture(v.u(), v.v())
                        .overlay(overlay)
                        .light(light)
                        .normal(entry, normal.x(), normal.y(), normal.z());
            }
        }
    }
}
