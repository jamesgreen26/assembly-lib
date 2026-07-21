package com.assemblylib.impl.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public record AssemblyBlock(
        BlockPos relativePos,
        BlockState state,
        @Nullable CompoundTag blockEntityData
) {
    public AssemblyBlock(BlockPos relativePos, BlockState state) {
        this(relativePos, state, null);
    }
}