package com.assemblylib.impl.vchunk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Single source of truth for the real-coordinate assembly-space coordinate scheme (a "plotgrid",
 * modelled on Sable's sub-level plot system).
 *
 * <p>Assemblies are stored as real blocks at real, far-away coordinates inside the same
 * {@code ServerLevel}. The plotgrid is a square grid of fixed-size <em>plots</em> placed
 * <strong>past 30,000,000 on both X and Z</strong> — which is where vanilla's world border /
 * {@code isInWorldBounds} stop, so no natural terrain, players, or vanilla chunk-tracking ever reach
 * it. (Because it is past the border, {@code AssemblyWorldBorderMixin} re-enables ticking there.)
 *
 * <p>Coordinate limits: {@link BlockPos#asLong} packs X/Z into signed 26-bit fields (±33,554,431), so
 * the grid sits in {@code [30,000,128, ~33.5M)} on both axes. Each plot is {@code 1<<LOG_PLOT_SIZE}
 * chunks on a side; the grid is {@code 1<<LOG_SIDE_LENGTH} plots on a side. A ship's "slot" is its
 * flat index into that grid.
 */
public final class AssemblySpace {

    private AssemblySpace() {}

    /** log2 of a plot's side length in chunks (5 → 32 chunks → 512 blocks per plot side). */
    public static final int LOG_PLOT_SIZE = 5;
    /** log2 of the grid's side length in plots (7 → 128 plots → 16,384 slots total). */
    public static final int LOG_SIDE_LENGTH = 7;

    public static final int PLOT_SIZE_CHUNKS = 1 << LOG_PLOT_SIZE;      // 32
    public static final int PLOT_SIZE_BLOCKS = PLOT_SIZE_CHUNKS << 4;    // 512
    public static final int GRID_SIDE = 1 << LOG_SIDE_LENGTH;           // 128
    public static final int MAX_SLOTS = GRID_SIDE * GRID_SIDE;          // 16,384

    /**
     * Grid origin in <em>plot</em> coordinates, chosen so the first plot's block origin is just past
     * 30,000,000: {@code ceil(30_000_000 / PLOT_SIZE_BLOCKS)} = 58594 → block 30,000,128.
     */
    public static final int ORIGIN_PLOT = 58594;

    /** Origin of the whole band in chunk coordinates (inclusive lower bound on both X and Z). */
    public static final int ORIGIN_CHUNK = ORIGIN_PLOT << LOG_PLOT_SIZE;              // 1,875,008
    /** Exclusive upper chunk bound of the band on both axes. */
    public static final int END_CHUNK = (ORIGIN_PLOT + GRID_SIDE) << LOG_PLOT_SIZE;   // 1,879,104
    /** Origin block X/Z of the band (≈ 30,000,128). */
    public static final int ORIGIN_BLOCK = ORIGIN_CHUNK << 4;

    // ---- membership tests ----

    public static boolean isAssemblyChunk(int chunkX, int chunkZ) {
        return chunkX >= ORIGIN_CHUNK && chunkX < END_CHUNK && chunkZ >= ORIGIN_CHUNK && chunkZ < END_CHUNK;
    }

    public static boolean isAssemblyChunk(ChunkPos pos) {
        return isAssemblyChunk(pos.x, pos.z);
    }

    public static boolean isAssemblySpace(int blockX, int blockZ) {
        return isAssemblyChunk(blockX >> 4, blockZ >> 4);
    }

    public static boolean isAssemblySpace(BlockPos pos) {
        return isAssemblySpace(pos.getX(), pos.getZ());
    }

    // ---- slot <-> plot mapping ----

    /** The flat grid slot owning a chunk, or -1 if the chunk is outside the band. */
    public static int slotForChunk(int chunkX, int chunkZ) {
        if (!isAssemblyChunk(chunkX, chunkZ)) {
            return -1;
        }
        int px = (chunkX >> LOG_PLOT_SIZE) - ORIGIN_PLOT;
        int pz = (chunkZ >> LOG_PLOT_SIZE) - ORIGIN_PLOT;
        return px + (pz << LOG_SIDE_LENGTH);
    }

    public static int slotForChunk(ChunkPos pos) {
        return slotForChunk(pos.x, pos.z);
    }

    /** Origin chunk (minimum corner) of the plot owned by {@code slot}. */
    public static ChunkPos tileOriginChunk(int slot) {
        int px = slot & (GRID_SIDE - 1);
        int pz = slot >> LOG_SIDE_LENGTH;
        return new ChunkPos((ORIGIN_PLOT + px) << LOG_PLOT_SIZE, (ORIGIN_PLOT + pz) << LOG_PLOT_SIZE);
    }

    /** World-block origin (minimum corner) of the plot owned by {@code slot}. */
    public static BlockPos tileOrigin(int slot) {
        ChunkPos c = tileOriginChunk(slot);
        return new BlockPos(c.getMinBlockX(), 0, c.getMinBlockZ());
    }

    /** Convert an assembly-local block position to absolute world coordinates. */
    public static BlockPos localToAbsolute(int slot, BlockPos local) {
        BlockPos origin = tileOrigin(slot);
        return new BlockPos(origin.getX() + local.getX(), origin.getY() + local.getY(), origin.getZ() + local.getZ());
    }

    /** Convert an absolute assembly-space position back to a plot-local position. */
    public static BlockPos absoluteToLocal(int slot, BlockPos absolute) {
        BlockPos origin = tileOrigin(slot);
        return new BlockPos(absolute.getX() - origin.getX(), absolute.getY() - origin.getY(), absolute.getZ() - origin.getZ());
    }
}
