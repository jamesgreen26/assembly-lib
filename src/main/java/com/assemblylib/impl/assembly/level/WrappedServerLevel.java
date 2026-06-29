package com.assemblylib.impl.assembly.level;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import com.assemblylib.impl.mixin.BiomeManagerAccessor;
import com.assemblylib.impl.mixin.EntityAccessor;
import com.assemblylib.impl.mixin.MinecraftServerAccessor;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.TickPriority;

/**
 * A genuine {@link ServerLevel} that wraps a real server level: its constructor reuses the real
 * level's server, storage, level data, dimension and random sequences (with a dummy chunk source and
 * progress listener), so the engine treats it as a real {@code ServerLevel} while a subclass overlays
 * its own block storage. This is what lets the assembly actually run native redstone / scheduled
 * ticks against its own blocks (see {@code AssemblySimServerLevel}).
 *
 * <p>Clean-room port of catnip's {@code WrappedServerLevel} (previously pulled in via Ponder), so the
 * assembly sim levels no longer depend on create/flywheel/catnip. The only local adaptations are
 * using assembly-lib's own mixin accessors ({@link MinecraftServerAccessor}, {@link BiomeManagerAccessor},
 * {@link EntityAccessor}) in place of catnip's.
 */
public class WrappedServerLevel extends ServerLevel {

	protected ServerLevel level;

	public WrappedServerLevel(ServerLevel level) {
		super(level.getServer(), Util.backgroundExecutor(),
			((MinecraftServerAccessor) level.getServer()).zps$getStorageSource(),
			(ServerLevelData) level.getLevelData(), level.dimension(),
			new LevelStem(level.dimensionTypeRegistration(), level.getChunkSource().getGenerator()),
			new DummyStatusListener(), level.isDebug(),
			((BiomeManagerAccessor) level.getBiomeManager()).zps$getBiomeZoomSeed(), Collections.emptyList(), false,
			level.getRandomSequences());
		this.level = level;
	}

	@Override
	public float getSunAngle(float partialTick) {
		return 0.0F;
	}

	@Override
	public int getMaxLocalRawBrightness(BlockPos pos) {
		return 15;
	}

	@Override
	public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
		this.level.sendBlockUpdated(pos, oldState, newState, flags);
	}

	@Override
	public LevelTicks<Block> getBlockTicks() {
		return super.getBlockTicks();
	}

	@Override
	public LevelTicks<Fluid> getFluidTicks() {
		return super.getFluidTicks();
	}

	@Override
	public void scheduleTick(BlockPos pos, Block block, int delay) {
	}

	@Override
	public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {
	}

	@Override
	public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {
	}

	@Override
	public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {
	}

	@Override
	public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
	}

	@Override
	public List<ServerPlayer> players() {
		return Collections.emptyList();
	}

	@Override
	public void playSound(@Nullable Player player, double x, double y, double z, SoundEvent sound, SoundSource category,
		float volume, float pitch) {
	}

	@Override
	public void playSound(@Nullable Player player, Entity entity, SoundEvent sound, SoundSource category, float volume,
		float pitch) {
	}

	@Override
	public Entity getEntity(int id) {
		return null;
	}

	@Nullable
	@Override
	public MapItemSavedData getMapData(MapId mapId) {
		return null;
	}

	@Override
	public boolean addFreshEntity(Entity entity) {
		((EntityAccessor) entity).zps$setLevel(this.level);
		return this.level.addFreshEntity(entity);
	}

	@Override
	public void setMapData(MapId mapId, MapItemSavedData mapData) {
	}

	@Override
	public MapId getFreeMapId() {
		return new MapId(0);
	}

	@Override
	public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
	}

	@Override
	public RecipeManager getRecipeManager() {
		return this.level.getRecipeManager();
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
		return this.level.getUncachedNoiseBiome(x, y, z);
	}
}
