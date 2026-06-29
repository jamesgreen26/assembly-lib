package com.assemblylib.impl.client.renderer.assembly;

import javax.annotation.Nullable;

import com.assemblylib.impl.AssemblyClientConfig;
import com.assemblylib.impl.assembly.Assembly;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * A minimal {@link net.minecraft.world.level.BlockAndTintGetter} that exposes an assembly's captured
 * blocks at their LOCAL positions, used by {@link AssemblyBakedMesh} while baking the static mesh.
 *
 * <p>Mirrors VEL's {@code AssemblyFakeLevel}: vanilla's directional face shading is delegated to the
 * real level (so it is baked into the mesh), and lighting is baked at full sky-brightness
 * ({@code SKY=15, BLOCK=0}) with a non-zero light emission so the flat-shading path is taken. The
 * mesh therefore tracks day/night skylight at draw time (the lightmap content changes), but does not
 * darken for occlusion — the agreed VEL behaviour. Biome tint is sampled at the assembly's WORLD
 * position so grass/water/foliage colours match the structure's location.
 */
public class AssemblyRenderWorld implements net.minecraft.world.level.BlockAndTintGetter {

	private final Level level;
	private final Assembly assembly;
	/** Assembly world anchor, so biome tint is sampled where the structure actually is. */
	private final BlockPos worldOrigin;

	public AssemblyRenderWorld(Level level, Assembly assembly, BlockPos worldOrigin) {
		this.level = level;
		this.assembly = assembly;
		this.worldOrigin = worldOrigin;
	}

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
	public float getShade(Direction direction, boolean shade) {
		// Flywheel applies its own cardinal GPU shading at draw time, so bake flat shading here to
		// avoid double-shading. The baked-mesh path has no GPU shading, so bake vanilla's real
		// directional face shading instead (matches VEL's AssemblyFakeLevel).
		if (AssemblyClientConfig.useFlywheelRenderer())
			return 1f;
		return level.getShade(direction, shade);
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
	public int getLightEmission(BlockPos pos) {
		// Non-air emission forces vanilla's flat per-face shading path (full sky bake).
		return getBlockState(pos).isAir() ? 0 : 1;
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return level.getLightEngine();
	}

	@Override
	public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
		return level.getBlockTint(worldOrigin.offset(pos), colorResolver);
	}

	@Override
	public int getHeight() {
		return level.getHeight();
	}

	@Override
	public int getMinBuildHeight() {
		return level.getMinBuildHeight();
	}
}
