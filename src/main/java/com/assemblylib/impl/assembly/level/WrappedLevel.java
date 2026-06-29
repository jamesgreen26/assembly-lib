package com.assemblylib.impl.assembly.level;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.assemblylib.impl.mixin.EntityAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * A {@link Level} that wraps a real level and delegates the long tail of its methods to it while
 * stubbing out the side-effecting ones (sounds, level events, game events, particles/break progress,
 * day-time), so a subclass can overlay its own block storage on top. This is the base for the
 * assembly simulation levels ({@code AssemblySimLevel}).
 *
 * <p>Clean-room port of catnip's {@code WrappedLevel} (previously pulled in via the Ponder
 * dependency), so the assembly sim levels no longer depend on create/flywheel/catnip. The only local
 * adaptation is using assembly-lib's own {@link EntityAccessor} to re-home a spawned entity onto the
 * wrapped level.
 */
public class WrappedLevel extends Level {

	protected Level level;
	@Nullable
	protected ChunkSource chunkSource;
	protected LevelEntityGetter<Entity> entityGetter = new DummyLevelEntityGetter<>();

	public WrappedLevel(Level level) {
		super((WritableLevelData) level.getLevelData(), level.dimension(), level.registryAccess(),
			level.dimensionTypeRegistration(), level::getProfiler, level.isClientSide, level.isDebug(), 0L, 0);
		this.level = level;
	}

	public void setChunkSource(ChunkSource source) {
		this.chunkSource = source;
	}

	public Level getLevel() {
		return this.level;
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return this.level.getLightEngine();
	}

	@Override
	public BlockState getBlockState(@Nullable BlockPos pos) {
		return this.level.getBlockState(pos);
	}

	@Override
	public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
		return this.level.isStateAtPosition(pos, predicate);
	}

	@Nullable
	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return this.level.getBlockEntity(pos);
	}

	@Override
	public boolean setBlock(BlockPos pos, BlockState newState, int flags) {
		return this.level.setBlock(pos, newState, flags);
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
	public LevelTickAccess<Block> getBlockTicks() {
		return this.level.getBlockTicks();
	}

	@Override
	public LevelTickAccess<Fluid> getFluidTicks() {
		return this.level.getFluidTicks();
	}

	@Override
	public ChunkSource getChunkSource() {
		return this.chunkSource != null ? this.chunkSource : this.level.getChunkSource();
	}

	@Override
	public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
	}

	@Override
	public List<? extends Player> players() {
		return Collections.emptyList();
	}

	@Override
	public void playSeededSound(@Nullable Player player, double x, double y, double z, Holder<SoundEvent> sound,
		SoundSource source, float volume, float pitch, long seed) {
	}

	@Override
	public void playSeededSound(@Nullable Player player, Entity entity, Holder<SoundEvent> sound, SoundSource category,
		float volume, float pitch, long seed) {
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

	@Override
	public TickRateManager tickRateManager() {
		return this.level.tickRateManager();
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
		return this.level.getFreeMapId();
	}

	@Override
	public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
	}

	@Override
	public Scoreboard getScoreboard() {
		return this.level.getScoreboard();
	}

	@Override
	public RecipeManager getRecipeManager() {
		return this.level.getRecipeManager();
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
		return this.level.getUncachedNoiseBiome(x, y, z);
	}

	@Override
	public RegistryAccess registryAccess() {
		return this.level.registryAccess();
	}

	@Override
	public PotionBrewing potionBrewing() {
		return this.level.potionBrewing();
	}

	@Override
	public float getShade(Direction direction, boolean shade) {
		return this.level.getShade(direction, shade);
	}

	@Override
	public void updateNeighbourForOutputSignal(BlockPos pos, Block block) {
	}

	@Override
	public void gameEvent(@Nullable Entity entity, Holder<GameEvent> gameEvent, Vec3 pos) {
	}

	@Override
	public void gameEvent(Holder<GameEvent> gameEvent, Vec3 pos, GameEvent.Context context) {
	}

	@Override
	public String gatherChunkSourceStats() {
		return this.level.gatherChunkSourceStats();
	}

	@Override
	protected LevelEntityGetter<Entity> getEntities() {
		return this.entityGetter;
	}

	@Override
	public int getMaxBuildHeight() {
		return this.getMinBuildHeight() + this.getHeight();
	}

	@Override
	public int getSectionsCount() {
		return this.getMaxSection() - this.getMinSection();
	}

	@Override
	public int getMinSection() {
		return SectionPos.blockToSectionCoord(this.getMinBuildHeight());
	}

	@Override
	public int getMaxSection() {
		return SectionPos.blockToSectionCoord(this.getMaxBuildHeight() - 1) + 1;
	}

	@Override
	public boolean isOutsideBuildHeight(BlockPos pos) {
		return this.isOutsideBuildHeight(pos.getY());
	}

	@Override
	public boolean isOutsideBuildHeight(int y) {
		return y < this.getMinBuildHeight() || y >= this.getMaxBuildHeight();
	}

	@Override
	public int getSectionIndex(int y) {
		return this.getSectionIndexFromSectionY(SectionPos.blockToSectionCoord(y));
	}

	@Override
	public int getSectionIndexFromSectionY(int sectionY) {
		return sectionY - this.getMinSection();
	}

	@Override
	public int getSectionYFromSectionIndex(int sectionIndex) {
		return sectionIndex + this.getMinSection();
	}

	@Override
	public FeatureFlagSet enabledFeatures() {
		return this.level.enabledFeatures();
	}

	@Override
	public void setDayTimeFraction(float fraction) {
	}

	@Override
	public float getDayTimeFraction() {
		return 0.0F;
	}

	@Override
	public float getDayTimePerTick() {
		return 0.0F;
	}

	@Override
	public void setDayTimePerTick(float perTick) {
	}
}
