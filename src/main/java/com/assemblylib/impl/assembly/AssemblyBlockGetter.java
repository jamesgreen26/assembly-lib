package com.assemblylib.impl.assembly;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.material.FluidState;

/**
 * A bare {@link BlockGetter} exposing a assembly's captured blocks at their
 * LOCAL positions, so the default {@link BlockGetter#clip} DDA raytrace can be
 * run in assembly-local space to find the targeted block.
 */
public record AssemblyBlockGetter(Assembly assembly) implements BlockGetter {

	@Override
	public BlockState getBlockState(BlockPos pos) {
		StructureBlockInfo info = assembly.getBlocks().get(pos);
		return info == null ? Blocks.AIR.defaultBlockState() : info.state();
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Nullable
	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
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
}
