package com.assemblylib.impl.vchunk.collision;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * A minimal {@link BlockAndTintGetter} over an {@link AssemblyCollisionSource}, so vanilla's
 * {@code BlockState#getCollisionShape(BlockGetter, BlockPos, CollisionContext)} can be called against
 * an assembly's LOCAL blocks. Ported from VEL's {@code AssemblyCollisionLevel}. Collision lookups
 * never touch lighting or block entities, so those return inert stand-ins.
 */
public final class AssemblyCollisionBlockGetter implements BlockAndTintGetter {

    private final AssemblyCollisionSource source;

    public AssemblyCollisionBlockGetter(AssemblyCollisionSource source) {
        this.source = source;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return source.getLocal(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return 1.0f;
    }

    @Override
    public int getBrightness(LightLayer lightLayer, BlockPos pos) {
        return 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return Math.max(0, 15 - amount);
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        return -1;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        // Collision lookups never actually call this -- only rendering does.
        return null;
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getMinBuildHeight() {
        return -64;
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }
}
