package com.assemblylib.impl.vchunk.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A read-only view of an assembly's blocks at LOCAL positions, abstracting over the data source so
 * the collider works identically server-side (backed by the real ship-space {@code LevelChunk}s) and
 * client-side (backed by the synced block snapshot map).
 */
public interface AssemblyLocalBlocks {

    /** The block state at a LOCAL position (air if none). */
    BlockState getLocal(BlockPos local);

    /** True if the assembly has no blocks (skip collision entirely). */
    boolean isEmpty();
}
