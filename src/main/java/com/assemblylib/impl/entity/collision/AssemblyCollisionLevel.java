package com.assemblylib.impl.entity.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import com.assemblylib.impl.entity.AssemblyBlock;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal BlockAndTintGetter backed by an assembly's block list.
 * Used server-side for collision shape lookups — no client classes, safe on both sides.
 */
public class AssemblyCollisionLevel implements BlockAndTintGetter {

    private final Map<BlockPos, BlockState> blockMap;

    public AssemblyCollisionLevel(List<AssemblyBlock> blocks) {
        this.blockMap = new HashMap<>(blocks.size());
        for (AssemblyBlock block : blocks) {
            blockMap.put(block.relativePos(), block.state());
        }
    }

    public BlockState getBlockStateLocal(BlockPos localPos) {
        return blockMap.getOrDefault(localPos, Blocks.AIR.defaultBlockState());
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return blockMap.getOrDefault(pos, Blocks.AIR.defaultBlockState());
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
        // Collision lookups never actually call this — only rendering does.
        // Returning null is safe here; if something ever does call it we'd
        // rather get a clear NPE than silently return wrong data.
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