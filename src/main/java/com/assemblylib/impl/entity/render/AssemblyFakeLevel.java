package com.assemblylib.impl.entity.render;

import net.minecraft.client.Minecraft;
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

public class AssemblyFakeLevel implements BlockAndTintGetter {

    private final Map<BlockPos, BlockState> blockMap;
    private final BlockPos entityPos;
    private final net.minecraft.client.multiplayer.ClientLevel level;

    public AssemblyFakeLevel(List<AssemblyBlock> blocks, BlockPos entityPos) {
        this.entityPos = entityPos;
        this.level = Minecraft.getInstance().level;
        this.blockMap = new HashMap<>(blocks.size());
        for (AssemblyBlock block : blocks) {
            blockMap.put(block.relativePos(), block.state());
        }
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
        return this.level.getShade(direction, shade);
    }

    @Override
    public int getBrightness(LightLayer lightLayer, BlockPos pos) {
        return switch (lightLayer) {
            case SKY -> 15;
            case BLOCK -> 0;
        };
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return Math.max(0, 15 - amount);
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        if (level == null) return -1;
        return level.getBlockTint(entityPos.offset(pos), colorResolver);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return level.getLightEngine();
    }

    @Override
    public int getHeight() { return level != null ? level.getHeight() : 256; }

    @Override
    public int getMinBuildHeight() { return level != null ? level.getMinBuildHeight() : 0; }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) { return null; }

    @Override
    public int getLightEmission(BlockPos pos) {
        BlockState state = getBlockState(pos);
        return state.isAir() ? 0 : 1;
    }
}