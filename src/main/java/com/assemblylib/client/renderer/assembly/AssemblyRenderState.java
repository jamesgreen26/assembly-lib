package com.assemblylib.client.renderer.assembly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.assemblylib.AssemblyLib;
import com.assemblylib.blockentity.ServoMotorBlockEntity;
import com.assemblylib.assembly.Assembly;
import com.assemblylib.assembly.AssemblyTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/**
 * Client-side cache of the block entities captured in a assembly, kept LIVE: the reconstructed
 * block entities are client-ticked every tick (so chest lids, spawner spin, campfire smoke, conduit
 * frames, … animate as they would in the world) and their data is refreshed in place on each sync
 * (so contents/sign-text/progress update without re-creating the instance and resetting animation).
 *
 * <p>The block entities are hosted on a {@link AssemblyRenderLevel} (a wrapped client level that
 * exposes the assembly's own blocks and block entities at their LOCAL positions), so neighbour-
 * aware renderers/tickers resolve correctly and particles emit on the moving structure.
 *
 * <p>The client builds a fresh {@link Assembly} on every sync packet; rather than discard and
 * rebuild everything on each one, {@link #update} reconciles the existing block-entity instances
 * against the new assembly — preserving them where the cell is unchanged (refreshing only their
 * synced data) and adding/removing/rebuilding only where the structure actually changed.
 */
public class AssemblyRenderState {

	/** A reconstructed block entity with its resolved client ticker (mirrors the server sim level). */
	public record TickingBE(BlockPos pos, BlockEntity be, BlockEntityTicker<BlockEntity> ticker) {
	}

	/** Real client level (registry access, wrapped by the render level). */
	private final Level level;
	@Nullable
	private final Supplier<AssemblyTransform> transform;
	/** The motor owning this assembly, handed to the render level so nested motors find their host. */
	@Nullable
	private final ServoMotorBlockEntity hostMotor;

	/** The last assembly reconciled against (identity changes on every sync); used as the no-op gate. */
	@Nullable
	private Assembly assembly;
	/** Host level for the reconstructed block entities; rebuilt only when the structure changes. */
	private AssemblyRenderLevel renderLevel;
	/** The assembly {@link #renderLevel} was built for (so its block reads stay current). */
	@Nullable
	private Assembly levelAssembly;

	private final List<BlockEntity> blockEntities = new ArrayList<>();
	/** Reconstructed block entities keyed by assembly-LOCAL position (also handed to the render level). */
	private final Map<BlockPos, BlockEntity> blockEntitiesByPos = new HashMap<>();

	/** Cached tickers, rebuilt lazily when {@link #tickersDirty} (a block was added/removed/replaced). */
	@Nullable
	private List<TickingBE> tickers;
	private boolean tickersDirty = true;
	/** Bumped whenever the structure (positions or block states) changes; gates Flywheel/structure rebuilds. */
	private long structureRevision;

	public AssemblyRenderState(Level level, Assembly assembly,
		@Nullable Supplier<AssemblyTransform> transform, @Nullable ServoMotorBlockEntity hostMotor) {
		this.level = level;
		this.transform = transform;
		this.hostMotor = hostMotor;
		this.renderLevel = new AssemblyRenderLevel(level, assembly, blockEntitiesByPos, transform, hostMotor);
		this.levelAssembly = assembly;
		reconcile(assembly);
		this.assembly = assembly;
	}

	/**
	 * Reconcile against {@code next} (a freshly synced assembly). A cheap no-op unless the
	 * assembly identity changed since the last reconcile, so it is safe to call on every access.
	 */
	public void update(Assembly next) {
		if (next == assembly)
			return;
		reconcile(next);
		assembly = next;
	}

	/** Client-tick every reconstructed block entity via its client ticker (mirrors the server tick). */
	public void tick() {
		for (TickingBE ticking : getTickers()) {
			BlockEntity be = ticking.be();
			BlockState state = be.getBlockState();
			if (be.isRemoved() || !be.getType().isValid(state))
				continue;
			try {
				ticking.ticker().tick(renderLevel, ticking.pos(), state, be);
			} catch (Exception e) {
				AssemblyLib.LOGGER.error("Assembly client block entity at {} threw while ticking", ticking.pos(), e);
			}
		}
	}

	/** The reconstructed block entity at a assembly-LOCAL position, or {@code null} if there is none. */
	@Nullable
	public BlockEntity getBlockEntity(BlockPos localPos) {
		return blockEntitiesByPos.get(localPos);
	}

	/** The assembly this state was last reconciled against, so callers can detect changes. */
	@Nullable
	public Assembly getAssembly() {
		return assembly;
	}

	public List<BlockEntity> getBlockEntities() {
		return blockEntities;
	}

	/** Counter bumped on every structural change (block added/removed/state-changed); never decreases. */
	public long getStructureRevision() {
		return structureRevision;
	}

	/**
	 * Diff the cached block entities against {@code next}: drop ones whose cell vanished or whose block
	 * changed type, add ones for new cells, and — the load-bearing case — refresh the synced data of an
	 * unchanged cell on its EXISTING instance via {@link BlockEntity#handleUpdateTag}. Refreshing in
	 * place keeps transient animation fields (lid controllers, spin, …) alive; a few block entities may
	 * reset some animation-adjacent state from their update tag, which is acceptable.
	 */
	private void reconcile(Assembly next) {
		Map<BlockPos, StructureBlockInfo> newBlocks = next.getBlocks();
		Map<BlockPos, CompoundTag> newTags = next.getUpdateTags();
		boolean structuralChange = false;

		// 1. Removal pass: drop block entities whose cell vanished, lost its block entity, or whose new
		// block state is no longer valid for the existing instance's type.
		for (Iterator<Map.Entry<BlockPos, BlockEntity>> it = blockEntitiesByPos.entrySet().iterator(); it.hasNext();) {
			Map.Entry<BlockPos, BlockEntity> entry = it.next();
			StructureBlockInfo info = newBlocks.get(entry.getKey());
			if (info == null || !info.state().hasBlockEntity() || !entry.getValue().getType().isValid(info.state())) {
				entry.getValue().setRemoved();
				it.remove();
				structuralChange = true;
			}
		}

		// 2. Add / update pass.
		for (StructureBlockInfo info : newBlocks.values()) {
			BlockState state = info.state();
			if (!state.hasBlockEntity() || !(state.getBlock() instanceof EntityBlock))
				continue;
			BlockPos pos = info.pos();
			BlockEntity existing = blockEntitiesByPos.get(pos);
			CompoundTag tag = newTags.get(pos);
			if (existing == null) {
				BlockEntity be = reconstruct(state, pos, tag);
				if (be != null) {
					blockEntitiesByPos.put(pos, be);
					structuralChange = true;
				}
			} else if (existing.getBlockState() != state) {
				// Block states are interned, so identity inequality means the state actually changed.
				existing.setBlockState(state);
				if (tag != null)
					existing.handleUpdateTag(tag, level.registryAccess());
				structuralChange = true;
			} else if (tag != null) {
				// Same block: refresh live data on the same instance (preserves animation state).
				existing.handleUpdateTag(tag, level.registryAccess());
			}
		}

		if (structuralChange) {
			rebuildBlockEntitiesList();
			tickersDirty = true;
			structureRevision++;
			// Re-point the host level at the new structure so its block reads stay current, then rebind
			// every (preserved and newly added) block entity to it.
			if (levelAssembly != next) {
				renderLevel = new AssemblyRenderLevel(level, next, blockEntitiesByPos, transform, hostMotor);
				levelAssembly = next;
			}
			for (BlockEntity be : blockEntities)
				be.setLevel(renderLevel);
		}
	}

	private void rebuildBlockEntitiesList() {
		blockEntities.clear();
		blockEntities.addAll(blockEntitiesByPos.values());
	}

	private List<TickingBE> getTickers() {
		if (tickers == null || tickersDirty)
			rebuildTickers();
		return tickers;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void rebuildTickers() {
		List<TickingBE> rebuilt = new ArrayList<>();
		for (BlockEntity be : blockEntities) {
			BlockState state = be.getBlockState();
			if (!(state.getBlock() instanceof EntityBlock entityBlock))
				continue;
			BlockEntityTicker<BlockEntity> ticker =
				(BlockEntityTicker<BlockEntity>) entityBlock.getTicker(renderLevel, state, (BlockEntityType) be.getType());
			if (ticker == null)
				continue;
			rebuilt.add(new TickingBE(be.getBlockPos(), be, ticker));
		}
		tickers = rebuilt;
		tickersDirty = false;
	}

	@Nullable
	private BlockEntity reconstruct(BlockState state, BlockPos localPos, @Nullable CompoundTag updateTag) {
		if (!(state.getBlock() instanceof EntityBlock entityBlock))
			return null;
		BlockEntity be = entityBlock.newBlockEntity(localPos, state);
		if (be == null)
			return null;
		be.setLevel(renderLevel);
		be.setBlockState(state);
		if (updateTag != null)
			be.handleUpdateTag(updateTag, level.registryAccess());
		return be;
	}
}
