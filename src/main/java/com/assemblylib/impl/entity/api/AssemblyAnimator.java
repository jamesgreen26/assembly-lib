package com.assemblylib.impl.entity.api;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import com.assemblylib.impl.entity.AssemblyEntity;

import java.util.Set;

/**
 * Client-side render hook that lets an assembly animate individual blocks — e.g. a
 * cannon's breech slide sliding out, a piston arm extending, a turret yawing.
 *
 * <p>How it fits the pipeline: an assembly's static blocks are baked once into a frozen
 * vertex buffer for speed. A frozen buffer can't move, so any block you want to animate
 * must be pulled OUT of the bake and redrawn every frame with a live transform. That's
 * what this interface declares:</p>
 *
 * <ul>
 *   <li>{@link #getDynamicBlocks()} — which block positions are animated. These are
 *       excluded from the baked mesh and rendered per-frame instead. This set is read at
 *       <em>bake</em> time, so if it changes you must call
 *       {@link AssemblyEntity#markMeshDirty()} to force a rebake.</li>
 *   <li>{@link #animate} — the per-frame local transform for one dynamic block. Called
 *       every frame; changing the transform here is free (no rebake).</li>
 * </ul>
 *
 * <p>Attach one with {@link AssemblyEntity#setAnimator(AssemblyAnimator)}, or just have
 * your {@code AssemblyBehavior} implement this interface and it will be picked up
 * automatically.</p>
 *
 * <p>Block entities (chests, beds, signs, …) render through their own
 * {@code BlockEntityRenderer} automatically and do not need an animator unless you want
 * to move the whole block.</p>
 */
public interface AssemblyAnimator {

    /**
     * Relative positions of blocks that should be rendered dynamically each frame instead
     * of baked into the static mesh. Defaults to none. Read at bake time — see class docs.
     */
    default Set<BlockPos> getDynamicBlocks() {
        return Set.of();
    }

    /**
     * Apply this frame's transform for one dynamic block. The {@code poseStack} is already
     * positioned at the block's local origin (its lower corner, matching the baked grid),
     * so you transform in block-local space: {@code translate(0, slide, 0)} to slide up,
     * {@code mulPose(Axis.YP.rotation(angle))} to spin, etc. Pivot by translating to the
     * pivot, rotating, then translating back.
     *
     * @param entity      the assembly being rendered
     * @param relativePos the block's position relative to the assembly origin
     * @param state       the block's state
     * @param poseStack   transform stack, pre-translated to the block origin
     * @param partialTick sub-tick interpolation factor for smooth motion
     */
    void animate(AssemblyEntity entity, BlockPos relativePos, BlockState state,
                 PoseStack poseStack, float partialTick);
}
