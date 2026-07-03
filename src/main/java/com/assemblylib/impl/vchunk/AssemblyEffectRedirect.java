package com.assemblylib.impl.vchunk;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Shared chokepoint for redirecting vanilla's block-position-based side effects (sounds, canned
 * "level events" like door/piston/block-break sound+particle bundles) from a block's REAL far-away
 * storage position to wherever it currently APPEARS.
 *
 * <p>This is the "mixin deep, not wide" primitive: instead of patching every individual vanilla
 * behavior that plays a sound or effect at a block position (buttons, doors, furnaces, note blocks,
 * dozens of {@code Block#playSound}/{@code Level#levelEvent} call sites throughout vanilla), a
 * handful of mixins intercept the small number of chokepoint methods those calls all funnel through
 * ({@code Level#playSound(Player, BlockPos, ...)}, {@code LevelAccessor#levelEvent(int, BlockPos,
 * int)}) and rewrite the position once, before vanilla's own downstream logic (proximity-based packet
 * broadcast, particle spawning, sound falloff) runs — completely unmodified — against a now-ordinary,
 * nearby coordinate. Adding support for a new vanilla behavior that happens to route through one of
 * these chokepoints requires zero new mixins.
 */
public final class AssemblyEffectRedirect {

    private AssemblyEffectRedirect() {}

    /** The apparent (transformed) world position of a real assembly-space block position, or null. */
    @Nullable
    public static Vec3 apparentCenter(ServerLevel level, BlockPos pos) {
        if (!AssemblySpace.isAssemblySpace(pos)) {
            return null;
        }
        AssemblyManager manager = AssemblyManager.active(level);
        if (manager == null) {
            return null;
        }
        Assembly assembly = manager.assemblyForChunk(new ChunkPos(pos));
        if (assembly == null) {
            return null;
        }
        BlockPos local = AssemblySpace.absoluteToLocal(assembly.slot(), pos);
        return guardApparent(assembly.currentTransform().localToWorld(Vec3.atCenterOf(local)));
    }

    /**
     * A freshly-created assembly's transform is identity AT its own plot origin (blocks "appear"
     * exactly where they are stored until it first moves) — so the apparent position can itself be
     * in assembly-space, and a redirect mixin that re-enters the vanilla method with it would recurse
     * forever. Treat that case as "no redirect needed": the effect stays at the real position, which
     * IS the apparent position.
     */
    @Nullable
    private static Vec3 guardApparent(Vec3 apparent) {
        return AssemblySpace.isAssemblySpace((int) Math.floor(apparent.x), (int) Math.floor(apparent.z))
            ? null : apparent;
    }

    /**
     * Exact-point variant of {@link #apparentCenter} for chokepoints that take doubles rather than a
     * {@link BlockPos} (particle spawns, game events): the fractional offset within the block is part
     * of the effect (a particle spawned at a furnace's smoke hole, a vibration's exact origin), so it
     * must survive the transform instead of being snapped to a block center.
     */
    @Nullable
    public static Vec3 apparentPoint(ServerLevel level, double x, double y, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!AssemblySpace.isAssemblySpace(blockX, blockZ)) {
            return null;
        }
        AssemblyManager manager = AssemblyManager.active(level);
        if (manager == null) {
            return null;
        }
        Assembly assembly = manager.assemblyForChunk(new ChunkPos(blockX >> 4, blockZ >> 4));
        if (assembly == null) {
            return null;
        }
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());
        return guardApparent(assembly.currentTransform()
            .localToWorld(new Vec3(x - origin.getX(), y - origin.getY(), z - origin.getZ())));
    }
}
