package com.assemblylib.impl.entity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.assemblylib.impl.entity.api.AssemblyAnimator;
import com.assemblylib.impl.entity.AssemblyBlock;
import com.assemblylib.impl.entity.AssemblyEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-frame render pass for things a frozen mesh can't handle:
 * <ol>
 *   <li><b>Block entities</b> (chests, beds, signs, …) — drawn through their
 *       {@code BlockEntityRenderer} every frame, exactly as vanilla does in the world.</li>
 *   <li><b>Animated blocks</b> — blocks an {@link AssemblyAnimator} pulled out of the bake,
 *       redrawn each frame with the animator's live transform.</li>
 * </ol>
 *
 * Lives on the client side of an {@link AssemblyEntity}. Block-entity instances are built
 * once and reused; rebuild when the assembly's blocks change.
 */
@OnlyIn(Dist.CLIENT)
public class AssemblyDynamicRenderer {

    /** relativePos -> a live BlockEntity instance for blocks that have one. */
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    private boolean built = false;

    /**
     * (Re)create BlockEntity instances from the assembly's blocks. Each BE is given the
     * real client level (so tint/biome lookups resolve) and its relative position as its
     * BlockPos. We feed light explicitly at render time, so the BE's world position only
     * needs to be stable, not "correct" in the world.
     */
    public void rebuild(java.util.List<AssemblyBlock> blocks) {
        blockEntities.clear();
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;

        for (AssemblyBlock block : blocks) {
            BlockState state = block.state();
            if (!(state.getBlock() instanceof EntityBlock entityBlock)) continue;

            BlockPos pos = block.relativePos();
            BlockEntity be = entityBlock.newBlockEntity(pos, state);
            if (be == null) continue;

            be.setLevel(mc.level);
            if (block.blockEntityData() != null) {
                be.loadWithComponents(block.blockEntityData(), mc.level.registryAccess());
            }
            blockEntities.put(pos, be);
        }
        built = true;
    }

    /**
     * Draw the dynamic pass. Call inside the assembly's transformed pose (after rotation /
     * scale / pivot are applied), so block entities and animated parts ride the assembly.
     *
     * @param entityPos the assembly entity's world position, used to align blocks with the
     *                  baked grid (same fractional offset {@link AssemblyBakedMesh} uses).
     */
    public void render(AssemblyEntity entity, java.util.List<AssemblyBlock> blocks,
                       PoseStack poseStack, MultiBufferSource buffer,
                       float partialTick, int packedLight, Vec3 entityPos) {
        if (!built) rebuild(blocks);

        // Let a behavior/animator drive per-frame BE animations (one-shot triggers, partialTick
        // motion) before they render. Tick-rate advances live in tickBlockEntities() instead.
        var driver = entity.getBlockEntityDriver();
        if (driver != null) driver.driveBlockEntities(blockEntities, partialTick);

        BlockPos entityBlockPos = BlockPos.containing(entityPos);
        double fracX = entityPos.x - entityBlockPos.getX();
        double fracY = entityPos.y - entityBlockPos.getY();
        double fracZ = entityPos.z - entityBlockPos.getZ();

        AssemblyAnimator animator = entity.getAnimator();
        Set<BlockPos> dynamic = (animator != null) ? animator.getDynamicBlocks() : Set.of();

        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        var blockRenderer = Minecraft.getInstance().getBlockRenderer();
        var random = net.minecraft.util.RandomSource.create();

        for (AssemblyBlock block : blocks) {
            BlockPos pos = block.relativePos();
            double localX = pos.getX() - fracX;
            double localY = pos.getY() - fracY;
            double localZ = pos.getZ() - fracZ;

            // 1) Animated blocks: rendered here with the animator's per-frame transform,
            //    because they were excluded from the static bake.
            if (animator != null && dynamic.contains(pos)) {
                poseStack.pushPose();
                poseStack.translate(localX, localY, localZ);
                animator.animate(entity, pos, block.state(), poseStack, partialTick);
                blockRenderer.renderSingleBlock(
                        block.state(), poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY,
                        net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
                poseStack.popPose();
            }

            // 2) Block entities: run their BlockEntityRenderer (chest lid, sign text, …).
            BlockEntity be = blockEntities.get(pos);
            if (be != null) {
                BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(be);
                if (renderer != null) {
                    poseStack.pushPose();
                    poseStack.translate(localX, localY, localZ);
                    // If this BE block is also animated, apply the same transform so the BER moves with it.
                    if (animator != null && dynamic.contains(pos)) {
                        animator.animate(entity, pos, block.state(), poseStack, partialTick);
                    }
                    renderer.render(be, partialTick, poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY);
                    poseStack.popPose();
                }
            }
        }
    }

    /** Live map of relativePos -> render-only BlockEntity. Mutations stick; used by BlockEntityDriver. */
    public Map<BlockPos, BlockEntity> getBlockEntities() {
        return blockEntities;
    }

    public void dispose() {
        blockEntities.clear();
        built = false;
    }
}
