package com.assemblylib.impl.vchunk.collision;

import javax.annotation.Nullable;

import com.assemblylib.impl.vchunk.collision.drag.AssemblyDraggingProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Resolves "the assembly block an entity is standing on / in" for the effect chokepoints — step
 * sounds, sprint particles, block speed factor — that vanilla drives from
 * {@code level().getBlockState(blockBelow)}. An entity on an assembly is physically at the assembly's
 * APPARENT world position, but the block it's resting on lives at the assembly's real (far-away)
 * coordinates, so that world query returns air and every block-below-derived effect silently no-ops.
 *
 * <p>The entity-collision broad phase already records which assembly an entity is riding (its
 * {@code trackingAssemblyHandle}); this maps the world position vanilla was about to query into that
 * assembly's LOCAL space and returns the real block there. Works identically on both sides through
 * {@link AssemblyCollisionSource}, exactly like the collider itself.
 */
public final class AssemblyStandingBlock {

    private AssemblyStandingBlock() {}

    /** Vanilla's {@code getOnPos} drop below the feet — the block an entity is considered to stand on. */
    private static final double FEET_DROP = 0.2;

    /**
     * The non-air assembly block the {@code entity} is standing on, or {@code null} if it isn't on an
     * assembly (or there's no block there). Callers substitute this for a vanilla air result so the
     * block-below effect fires.
     *
     * <p>Resolved from the entity's CONTINUOUS feet position mapped into the assembly's local space,
     * NOT from the world-grid {@code BlockPos} vanilla was about to query. That distinction is the
     * whole fix: when the assembly sits at a fractional offset its local block grid doesn't line up
     * with the world grid, so snapping to a world block first and re-flooring in local space lands a
     * cell off — usually the air directly above the real surface block — which is why footsteps only
     * worked when the assembly happened to rest on whole-number coordinates. Mapping the raw position
     * and applying the feet drop in local space finds the surface block at any offset (and any yaw).
     */
    @Nullable
    public static BlockState below(Entity entity) {
        if (!(entity instanceof AssemblyDraggingProvider provider)) {
            return null;
        }
        int handle = provider.assemblylib$getDraggingInfo().getTrackingAssemblyHandle();
        AssemblyCollisionSource source = AssemblyCollisionCandidates.forHandle(entity.level(), handle);
        if (source == null) {
            return null;
        }
        Vec3 localFeet = source.currentTransform().worldToLocal(entity.position());
        BlockPos localBelow = BlockPos.containing(localFeet.x, localFeet.y - FEET_DROP, localFeet.z);
        BlockState state = source.getLocal(localBelow);
        return state.isAir() ? null : state;
    }
}
