package com.assemblylib.impl.vchunk.client;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.assemblylib.impl.vchunk.AssemblyTransform;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws every {@link ClientAssembly} into the world each frame, transformed to wherever its assembly
 * currently appears (interpolated by partial-tick), never reflecting its real far-away storage
 * position. Adapted from the old host-driven {@code AssemblyRenderer}: same baked-mesh draw plus
 * captured-block-entity rendering (chests, furnaces, signs, ...) via their vanilla renderers, but
 * driven from a level-render hook instead of a block-entity/entity renderer (assemblies have no host).
 *
 * <p>Culled to the client's configured render distance (matching vanilla terrain culling): an
 * assembly whose apparent position is farther than that is skipped entirely — no mesh rebuild, no
 * per-block-entity render calls — so distant assemblies cost nothing on the client.
 */
public final class AssemblyRenderer {

    private AssemblyRenderer() {}

    /**
     * Drop every client-side assembly mirror when the player leaves the world.
     * {@link ClientAssemblyManager} holds its mirrors in a {@code static} map, so without this a
     * mirror lingers across a disconnect/reconnect: if it belonged to a now-gone assembly (deleted
     * server-side, a
     * handle the new session never re-broadcasts, or simply a different world), it stays as an
     * INVISIBLE-but-still-colliding ghost — an entity walks into "solid air" where a mesh used to be.
     * The fresh session re-sends snapshots for every assembly that actually exists, so clearing here
     * loses nothing real. (Also fires when switching dimensions/worlds.)
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientAssemblyManager.clear();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (ClientAssemblyManager.all().isEmpty()) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cameraPos = event.getCamera().getPosition();
        Matrix4f projection = event.getProjectionMatrix();
        Matrix3f normal = new Matrix3f();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        BlockEntityRenderDispatcher beDispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();

        double renderDistanceSqr = renderDistanceBlocks();
        renderDistanceSqr *= renderDistanceSqr;

        for (ClientAssembly assembly : ClientAssemblyManager.all()) {
            AssemblyTransform transform = assembly.transform(partialTick);
            Vec3 apparent = transform.translation();
            if (apparent.distanceToSqr(cameraPos) > renderDistanceSqr) {
                continue;
            }

            if (assembly.isMeshDirty()) {
                assembly.mesh().rebuild(assembly.blocks(), assembly.apparentOrigin());
                assembly.clearMeshDirty();
            }

            poseStack.pushPose();
            poseStack.last().pose().mul(transform.renderPose(cameraPos));

            if (assembly.mesh().isBuilt()) {
                assembly.mesh().draw(poseStack, projection, normal);
            }
            renderBlockEntities(assembly, transform, beDispatcher, partialTick, poseStack, buffers);

            poseStack.popPose();
        }
        buffers.endBatch();
    }

    /** Vanilla's render distance in blocks (options are in chunks); assemblies beyond it are skipped. */
    private static double renderDistanceBlocks() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
    }

    private static void renderBlockEntities(ClientAssembly assembly, AssemblyTransform transform,
            BlockEntityRenderDispatcher dispatcher, float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        var realLevel = Minecraft.getInstance().level;
        for (BlockEntity be : assembly.blockEntities()) {
            BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(be);
            if (renderer == null) {
                continue;
            }
            BlockPos local = be.getBlockPos();
            int packedLight = 15728880;
            if (realLevel != null) {
                BlockPos worldPos = BlockPos.containing(transform.localToWorld(Vec3.atCenterOf(local)));
                // LightTexture.pack(blockLight, skyLight) — block first, sky second. Passing them in the
                // other order forces the block-light channel to the sky value (15 outdoors), which is
                // why single chests (whose renderer uses this value directly) rendered full-bright while
                // double chests — which recompute their own light via BrightnessCombiner — looked fine.
                packedLight = LightTexture.pack(realLevel.getBrightness(LightLayer.BLOCK, worldPos),
                    realLevel.getBrightness(LightLayer.SKY, worldPos));
            }
            poseStack.pushPose();
            poseStack.translate(local.getX(), local.getY(), local.getZ());
            try {
                renderer.render(be, partialTick, poseStack, buffers, packedLight, 655360);
            } catch (Exception ignored) {
                // A misbehaving renderer shouldn't crash the frame.
            }
            poseStack.popPose();
        }
    }
}
