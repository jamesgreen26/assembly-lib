package com.assemblylib.impl.vchunk.collision;

import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import com.assemblylib.impl.vchunk.AssemblyTransform;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * One collidable assembly, abstracted over its data source: the real ship-space chunks on the
 * server ({@code Assembly} via {@code AssemblyManager}), or the synced snapshot on the client
 * ({@code ClientAssembly}). {@link AssemblyEntityCollision} is written once against this interface and
 * runs identically on both sides, mirroring how {@code Entity.move()} itself runs on both sides.
 */
public interface AssemblyCollisionSource {

    /** Runtime handle, stable across the network (used to track "which assembly is this entity on"). */
    int handle();

    /** Pose as of the previous tick (substep interpolation start). */
    AssemblyTransform previousTransform();

    /** Pose as of the current tick (substep interpolation end). */
    AssemblyTransform currentTransform();

    /** LOCAL-space AABB enclosing this assembly's non-air blocks, or null if it has none. */
    @Nullable
    AABB localBounds();

    /** The block state at a LOCAL position (air if none). */
    BlockState getLocal(BlockPos local);

    /** Visit every non-air block at its LOCAL position (for building candidate collision boxes). */
    void forEachLocalBlock(BiConsumer<BlockPos, BlockState> visitor);
}
