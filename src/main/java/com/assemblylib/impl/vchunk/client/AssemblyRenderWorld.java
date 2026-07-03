package com.assemblylib.impl.vchunk.client;

import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * A minimal {@link BlockAndTintGetter} exposing a client assembly's blocks at their LOCAL positions,
 * used by {@link AssemblyBakedMesh} while baking the static mesh.
 *
 * <p><b>Lighting (Milestone 6, minimal):</b> when {@link #USE_WORLD_LIGHT} is true, light is sampled
 * from the real level at the block's <em>apparent</em> world position and each block's own emission
 * is honoured — so a docked assembly picks up its surroundings' sky/block light and torches glow.
 * Because the baked mesh is only rebuilt on a content change (not every time the assembly moves),
 * this lighting is captured at bake time and goes stale as the assembly travels; a proper
 * incremental light path across assembly-chunk boundaries is the deferred "dedicated experimentation"
 * the design doc calls for. Set {@code USE_WORLD_LIGHT=false} to fall back to flat full-bright.
 */
public class AssemblyRenderWorld implements BlockAndTintGetter {

    /** Sample real world light at the apparent position (true) vs. flat full-bright (false). */
    public static final boolean USE_WORLD_LIGHT = true;

    private final Map<BlockPos, BlockState> blocks;
    private final BlockPos apparentOrigin;

    public AssemblyRenderWorld(Map<BlockPos, BlockState> blocks, BlockPos apparentOrigin) {
        this.blocks = blocks;
        this.apparentOrigin = apparentOrigin;
    }

    private Level clientLevel() {
        return Minecraft.getInstance().level;
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
        return null;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        Level level = clientLevel();
        return level == null ? 1f : level.getShade(direction, shade);
    }

    @Override
    public int getBrightness(LightLayer lightLayer, BlockPos pos) {
        Level level = clientLevel();
        if (!USE_WORLD_LIGHT || level == null) {
            return lightLayer == LightLayer.SKY ? 15 : 0;
        }
        return level.getBrightness(lightLayer, apparentOrigin.offset(pos));
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        Level level = clientLevel();
        if (!USE_WORLD_LIGHT || level == null) {
            return Math.max(0, 15 - amount);
        }
        return level.getMaxLocalRawBrightness(apparentOrigin.offset(pos));
    }

    @Override
    public int getLightEmission(BlockPos pos) {
        BlockState state = getBlockState(pos);
        if (state.isAir()) {
            return 0;
        }
        // Honour the block's own emission; force a non-zero value in full-bright mode so vanilla takes
        // the flat per-face shading path.
        return USE_WORLD_LIGHT ? state.getLightEmission() : 1;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return clientLevel().getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        Level level = clientLevel();
        return level == null ? -1 : level.getBlockTint(apparentOrigin.offset(pos), colorResolver);
    }

    @Override
    public int getHeight() {
        return clientLevel().getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return clientLevel().getMinBuildHeight();
    }
}
