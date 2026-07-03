package com.assemblylib.impl.vchunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Constructs the hand-built, minimal {@link LevelChunk}s that back assembly-space.
 *
 * <p>Unlike a normal chunk, these are <em>never</em> registered with {@code ChunkMap}/{@code
 * ChunkHolder}, never given a ticket, never run through world-gen, and never auto-saved as region
 * files. They are owned entirely by {@link AssemblyManager} and handed to vanilla block code only
 * via the chunk-read interception mixins ({@code AssemblyServerChunkCacheMixin}).
 *
 * <p>For Milestone 1 an assembly chunk is simply an empty {@link LevelChunk}: empty sections, no
 * biome / heightmap / structure generation. Blocks are added lazily via the normal
 * {@code ServerLevel.setBlock} path, which routes through the interception mixin to this chunk.
 */
public final class AssemblyChunkSource {

    private AssemblyChunkSource() {}

    /**
     * Build a fresh empty assembly chunk at {@code pos}. Uses the plain {@code LevelChunk(Level,
     * ChunkPos)} constructor (empty {@code LevelChunkSection[]}, FULL persisted status, default
     * heightmaps).
     *
     * <p>We deliberately do <strong>not</strong> call {@code setLoaded(true)}: that would register the
     * chunk's block entities into the real level's ticking list, where vanilla would try (and, lacking
     * a ticking {@code ChunkHolder} and loaded entity sections, fail) to tick them. Assembly block
     * entities are instead driven explicitly by {@link AssemblyManager#tickAssemblies()}, and remain
     * stored in the chunk's own block-entity map (populated by {@code setBlockState}) so
     * {@code getBlockEntity} resolves them normally.
     */
    public static LevelChunk buildEmptyChunk(ServerLevel level, ChunkPos pos) {
        return new LevelChunk(level, pos);
    }
}
