package com.assemblylib.impl.vchunk.client;

import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.assemblylib.impl.vchunk.AssemblyTransform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side host {@link Level} for a client assembly's live, reconstructed block entities. Overlays
 * the assembly's synced blocks/block-entities at their LOCAL positions on top of
 * {@link AssemblyWrappedLevel}, and:
 * <ul>
 *   <li>returns the live reconstructed block entity at a LOCAL position, so neighbour-aware
 *       renderers/tickers (double chests, redstone-adjacent visuals) resolve correctly;</li>
 *   <li>samples real-world light and forwards particles at the block's apparent (transformed) world
 *       position, so lit/particle-emitting block entities look right on a moved/rotated assembly.</li>
 * </ul>
 *
 * <p>Owned by {@link ClientAssembly}; the block/block-entity maps and transform supplier are its live
 * ones, so this level never needs to be rebuilt — the underlying maps are mutated in place on sync.
 */
public class AssemblyBlockEntityLevel extends AssemblyWrappedLevel {

    private final Map<BlockPos, BlockState> blocks;
    private final Map<BlockPos, BlockEntity> blockEntities;
    private final Supplier<AssemblyTransform> transform;

    public AssemblyBlockEntityLevel(Level level, Map<BlockPos, BlockState> blocks,
            Map<BlockPos, BlockEntity> blockEntities, Supplier<AssemblyTransform> transform) {
        super(level);
        this.blocks = blocks;
        this.blockEntities = blockEntities;
        this.transform = transform;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = blocks.get(pos);
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return blockEntities.get(pos);
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        Vec3 world = transform.get().localToWorld(Vec3.atCenterOf(pos));
        return getLevel().getBrightness(layer, BlockPos.containing(world));
    }

    @Override
    public void addParticle(ParticleOptions options, double x, double y, double z, double xs, double ys, double zs) {
        forwardParticle(options, x, y, z, xs, ys, zs);
    }

    @Override
    public void addParticle(ParticleOptions options, boolean force, double x, double y, double z, double xs, double ys,
            double zs) {
        forwardParticle(options, x, y, z, xs, ys, zs);
    }

    @Override
    public void addAlwaysVisibleParticle(ParticleOptions options, double x, double y, double z, double xs, double ys,
            double zs) {
        forwardParticle(options, x, y, z, xs, ys, zs);
    }

    @Override
    public void addAlwaysVisibleParticle(ParticleOptions options, boolean ignoreRange, double x, double y, double z,
            double xs, double ys, double zs) {
        forwardParticle(options, x, y, z, xs, ys, zs);
    }

    /** Emit a particle (given in assembly-local space) onto the real level at its apparent world pose. */
    private void forwardParticle(ParticleOptions options, double x, double y, double z, double xs, double ys, double zs) {
        AssemblyTransform t = transform.get();
        Vec3 world = t.localToWorld(new Vec3(x, y, z));
        Vec3 vel = t.localDirToWorld(new Vec3(xs, ys, zs));
        getLevel().addParticle(options, world.x, world.y, world.z, vel.x, vel.y, vel.z);
    }
}
