package com.assemblylib.impl.assembly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.assemblylib.api.AssemblyHost;
import com.assemblylib.impl.mixin.EntityAccessor;
import com.assemblylib.impl.assembly.level.WrappedServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

/**
 * Server-side companion to {@link AssemblySimLevel}: a sim level that overlays a
 * assembly's captured blocks at their LOCAL positions (air everywhere else), but
 * backed by catnip's {@link WrappedServerLevel} so it is a <b>genuine {@link ServerLevel}</b>.
 *
 * <p>This is what lets the assembly actually <i>run</i>: because the engine sees a real
 * {@code ServerLevel}, the inherited {@code Level} machinery — {@code updateNeighborsAt}/
 * {@code neighborChanged}, the real {@code CollectingNeighborUpdater}, {@code getSignal}, and
 * {@code BlockState#tick} dispatch — all work natively, reading block state through our
 * {@link #getBlockState} override and scheduling through our {@link #scheduleTick} override.
 * So buttons, levers, doors, pressure plates and non-block-entity redstone behave as in a
 * normal world, with no re-implementation.
 *
 * <p>Reads come from the assembly and writes/ticks are routed into it (never the wrapped
 * real level): {@link #setBlock} mutates the assembly's block map (notifying the owner to
 * re-sync) and, when {@code UPDATE_NEIGHBORS} is set, propagates a redstone neighbour update;
 * {@link #getBlockTicks}/{@link #scheduleTick} use the assembly's own persistent tick queue.
 *
 * <p><b>Live block entities are supported here</b> (unlike the client {@link AssemblySimLevel}):
 * {@link #getBlockEntity} lazily reconstructs a live {@link BlockEntity} from the captured
 * {@code beNbt}, bound to this level at the LOCAL position, and caches it. The owner ticks them
 * ({@link #getTickingBlockEntities}) so furnaces smelt / hoppers move items / comparators read
 * containers, and {@link #flushAllBlockEntityData} writes their live state back into the
 * assembly before save/sync. Pistons remain out of scope.
 *
 * <p>Constructing a {@code WrappedServerLevel} builds a full {@code ServerLevel}, so the owner
 * caches one instance per assembly rather than allocating per tick. Server-only — the client
 * uses the lightweight {@link AssemblySimLevel}.
 */
public class AssemblySimServerLevel extends WrappedServerLevel implements AssemblyHostLevel {

	private static final double RAD_TO_DEG = 180.0 / Math.PI;

	/** The real wrapped level, kept directly because {@link WrappedServerLevel} stubs some methods. */
	private final ServerLevel realLevel;
	private final Assembly assembly;
	/** World position of the host Servo Motor, so bespoke screens can resolve their BE through it. */
	private final BlockPos motorPos;
	/**
	 * The host owning this assembly, so a nested host can find its parent. Held directly (like the client
	 * {@link com.assemblylib.impl.client.renderer.assembly.AssemblyRenderLevel}) because resolving via
	 * {@code getBlockEntity(motorPos)} only works for block-entity hosts — an <em>entity</em> host has no
	 * block entity at that position.
	 */
	private final AssemblyHost host;
	/** Notified after a write so the host BlockEntity can mark itself dirty ({@code setChanged}). */
	@Nullable
	private final Runnable onChanged;
	/**
	 * Notified when a change needs to reach clients (a structural {@link #setBlock} or a block
	 * entity raising {@link #sendBlockUpdated}). The host flips its {@code simNeedsSync} flag so the
	 * whole assembly is re-synced once at the end of the tick. Kept distinct from {@link #onChanged}
	 * (persistence) because {@code blockEntityChanged} fires every hopper/furnace tick and must NOT
	 * trigger a network resync.
	 */
	@Nullable
	private final Runnable onNeedsSync;

	/** Live block entities, keyed by LOCAL position; lazily built from {@code beNbt} in {@link #getBlockEntity}. */
	private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
	/** Cached list of tickable block entities, rebuilt when {@link #tickersDirty} (block add/remove). */
	@Nullable
	private List<TickingBE> tickers;
	private boolean tickersDirty = true;

	/** A live block entity paired with its resolved server ticker. */
	public record TickingBE(BlockPos pos, BlockEntity be, BlockEntityTicker<BlockEntity> ticker) {
	}
	/**
	 * Supplies the assembly's current world pose, used to play block sounds (which the engine
	 * emits at LOCAL block coordinates) at their actual, rotated world position. Null when no pose
	 * is available (e.g. in tests), in which case sounds fall through untransformed.
	 */
	@Nullable
	private final Supplier<AssemblyTransform> transform;
	/** Own (empty) fluid queue so any stray fluid tick never leaks onto the real level. */
	private final LevelTicks<Fluid> fluidTicks = new LevelTicks<>(cp -> true, () -> InactiveProfiler.INSTANCE);

	public AssemblySimServerLevel(ServerLevel level, Assembly assembly, BlockPos motorPos, AssemblyHost host,
		@Nullable Runnable onChanged, @Nullable Runnable onNeedsSync, @Nullable Supplier<AssemblyTransform> transform) {
		super(level);
		this.realLevel = level;
		this.assembly = assembly;
		this.motorPos = motorPos;
		this.host = host;
		this.onChanged = onChanged;
		this.onNeedsSync = onNeedsSync;
		this.transform = transform;
	}

	public Assembly getAssembly() {
		return assembly;
	}

	/** World position of the host Servo Motor. */
	public BlockPos getMotorPos() {
		return motorPos;
	}

	/**
	 * The level this sim wraps — the real {@link ServerLevel} for a root assembly, or the PARENT
	 * sim level when this assembly is itself nested inside another.
	 */
	public ServerLevel getRealLevel() {
		return realLevel;
	}

	/**
	 * The genuine, untransformed {@link ServerLevel} at the bottom of any nested-sim chain. Emissions
	 * already mapped to world space (spawned entities, sounds, level events) must target this — not the
	 * intermediate {@code realLevel}, which for a nested assembly is the PARENT sim and would
	 * re-transform them.
	 */
	public ServerLevel rootRealLevel() {
		ServerLevel l = realLevel;
		while (l instanceof AssemblySimServerLevel sim)
			l = sim.realLevel;
		return l;
	}

	@Override
	public AssemblyHost getAssemblyHost() {
		return host;
	}

	/** A nested host asked to re-sync: nudge the parent to re-broadcast the whole structure. */
	@Override
	public void requestAssemblySync() {
		if (onNeedsSync != null)
			onNeedsSync.run();
	}

	/** The assembly's current world pose, or {@code null} when none is available (e.g. tests). */
	@Nullable
	public AssemblyTransform getTransform() {
		return transform == null ? null : transform.get();
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
		BlockEntity existing = blockEntities.get(pos);
		if (existing != null)
			return existing;
		StructureBlockInfo info = assembly.getBlocks().get(pos);
		if (info == null)
			return null;
		BlockState state = info.state();
		if (!(state.getBlock() instanceof EntityBlock entityBlock))
			return null;
		BlockPos immutable = pos.immutable();
		BlockEntity be = entityBlock.newBlockEntity(immutable, state);
		if (be == null)
			return null;
		be.setLevel(this);
		if (info.nbt() != null)
			be.loadWithComponents(info.nbt(), registryAccess());
		blockEntities.put(immutable, be);
		return be;
	}

	/** Drop a live block entity from the cache (e.g. its host block was removed/replaced). */
	public void removeBlockEntity(BlockPos pos) {
		BlockEntity removed = blockEntities.remove(pos);
		if (removed != null)
			removed.setRemoved();
		tickersDirty = true;
	}

	/**
	 * The assembly's tickable block entities with their resolved server tickers. Rebuilt lazily
	 * whenever a block was added/removed/replaced (so a newly placed furnace starts ticking and a
	 * broken one stops). Driven once per server tick by the Servo Motor.
	 */
	public List<TickingBE> getTickingBlockEntities() {
		if (tickers == null || tickersDirty)
			rebuildTickers();
		return tickers;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void rebuildTickers() {
		List<TickingBE> rebuilt = new ArrayList<>();
		for (StructureBlockInfo info : assembly.getBlocks().values()) {
			BlockState state = info.state();
			if (!(state.getBlock() instanceof EntityBlock entityBlock))
				continue;
			BlockEntity be = getBlockEntity(info.pos());
			if (be == null)
				continue;
			BlockEntityTicker<BlockEntity> ticker =
				(BlockEntityTicker<BlockEntity>) entityBlock.getTicker(this, state, (BlockEntityType) be.getType());
			if (ticker == null)
				continue;
			rebuilt.add(new TickingBE(info.pos(), be, ticker));
		}
		tickers = rebuilt;
		tickersDirty = false;
	}

	/**
	 * Write every live block entity's current state back into the assembly (full {@code beNbt}
	 * for persistence/loot and {@code updateTag} for client rendering). Called by the host before a
	 * save or network sync so the serialized structure reflects live BE state (smelt progress,
	 * container contents, …).
	 */
	public void flushAllBlockEntityData() {
		for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
			BlockPos pos = entry.getKey();
			BlockEntity be = entry.getValue();
			StructureBlockInfo info = assembly.getBlocks().get(pos);
			if (info == null)
				continue;
			assembly.putBlock(pos, info.state(), be.saveWithFullMetadata(registryAccess()),
				be.getUpdateTag(registryAccess()));
		}
	}

	@Override
	public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
		return predicate.test(getBlockState(pos));
	}

	/**
	 * The wrapped level's chunk source is a dummy that reports no loaded chunks, but several redstone
	 * paths gate on {@code hasChunkAt} — notably {@code updateNeighbourForOutputSignal}, which a
	 * container raises so an adjacent comparator recomputes. Reads are served from the assembly map
	 * (see {@link #getBlockState}/{@link #getBlockEntity}), so it is safe to report every position as
	 * present; out-of-structure neighbours simply read as air.
	 */
	@Override
	public boolean hasChunkAt(BlockPos pos) {
		return true;
	}

	@Override
	public boolean setBlock(BlockPos pos, BlockState state, int flags) {
		return setBlock(pos, state, flags, 512);
	}

	@Override
	public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
		BlockState old = getBlockState(pos);
		if (state == old)
			return false;
		if (state.isAir())
			assembly.removeBlock(pos);
		else
			assembly.putBlock(pos, state, null, null);

		// Block-entity lifecycle, mirroring LevelChunk#setBlockState: drop a live BE whose host block
		// no longer supports it (the new one, if any, is created lazily by getBlockEntity). A pure
		// blockstate change on the same BE-bearing block (e.g. a furnace toggling LIT) keeps it.
		BlockEntity liveBE = blockEntities.get(pos);
		if (liveBE != null) {
			if (!liveBE.getType().isValid(state))
				removeBlockEntity(pos);
			else
				// Keep the live BE's cached block state in sync with the structure (mirrors
				// LevelChunk#setBlockState). Without this, a BE that reads its own state during its tick —
				// e.g. a script terminal checking POWERED — would see the pre-change state forever, so a
				// redstone toggle on the assembly would never take effect.
				liveBE.setBlockState(state);
		}
		if (old.hasBlockEntity() || state.hasBlockEntity())
			tickersDirty = true;

		// Block lifecycle, mirroring LevelChunk#setBlockState on the server: old#onRemove then
		// new#onPlace, with the new state already written above. This is essential for redstone:
		// a repeater/comparator toggles POWERED with flag 2 (no neighbour update) and notifies its
		// output purely through onPlace/onRemove -> updateNeighborsInFront. Skipping it left the
		// downstream wire's power un-recomputed.
		old.onRemove(this, pos, state, false);
		state.onPlace(this, pos, old, false);

		// Standard block-update propagation against the assembly (mirrors Level#markAndNotifyBlock,
		// minus the real-world client/chunk path — the host re-syncs the whole assembly).
		if ((flags & Block.UPDATE_KNOWN_SHAPE) == 0 && recursionLeft > 0) {
			int childFlags = flags & ~(Block.UPDATE_NEIGHBORS | Block.UPDATE_SUPPRESS_DROPS);
			old.updateIndirectNeighbourShapes(this, pos, childFlags, recursionLeft - 1);
			state.updateNeighbourShapes(this, pos, childFlags, recursionLeft - 1);
			state.updateIndirectNeighbourShapes(this, pos, childFlags, recursionLeft - 1);
		}
		// Redstone: notify the six neighbours that this cell changed. Safe to call here because
		// we are a real ServerLevel — the inherited CollectingNeighborUpdater queues and bounds
		// the cascade. This is what lights a lamp next to a placed redstone block, etc.
		if ((flags & Block.UPDATE_NEIGHBORS) != 0)
			updateNeighborsAt(pos, state.getBlock());
		if (onChanged != null)
			onChanged.run();
		// A structural change must reach clients (redstone, a furnace toggling LIT, falling blocks).
		if (onNeedsSync != null)
			onNeedsSync.run();
		return true;
	}

	@Override
	public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
		// A shape update can destroy an unsupported block (e.g. a torch). Just remove it from the
		// assembly — skip the real-world drop/effects path, which would spawn entities in the
		// wrapped level at the local position.
		if (getBlockState(pos).isAir())
			return false;
		return setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL, recursionLeft);
	}

	/**
	 * An entity a block/block-entity tick tries to spawn (a dispenser's projectile, dropped items, a
	 * primed TNT, smelting XP, …) is created against THIS sim level at assembly-LOCAL coordinates —
	 * but the sim level isn't ticked or tracked by the server, so it would be orphaned. Redirect it to
	 * the real outer level at the matching rotated WORLD position, with its velocity rotated into world
	 * space, so it behaves like it spawned off the moving structure. Falls back to a plain spawn when
	 * no pose is available (e.g. tests).
	 */
	@Override
	public boolean addFreshEntity(Entity entity) {
		ServerLevel root = rootRealLevel();
		((EntityAccessor) entity).zps$setLevel(root);
		AssemblyTransform t = getTransform();
		if (t != null) {
			Vec3 world = t.localToWorld(entity.position());
			Vec3 velocity = t.localDirToWorld(entity.getDeltaMovement());
			entity.setDeltaMovement(velocity);
			float yaw;
			float pitch;
			if (entity instanceof Projectile) {
				// Projectiles orient to their velocity using the atan2(vx,vz) convention (see Projectile#shoot
				// / AbstractArrow's spawn recompute). Derive yaw/pitch from the ROTATED velocity in that same
				// convention so the client's first frame already matches what the projectile computes itself —
				// the look-vector round-trip below uses the camera convention and would mis-face an arrow.
				double horizontal = velocity.horizontalDistance();
				yaw = (float) (Mth.atan2(velocity.x, velocity.z) * RAD_TO_DEG);
				pitch = (float) (Mth.atan2(velocity.y, horizontal) * RAD_TO_DEG);
			} else {
				// Other entities face via the camera convention: rotate the look vector, read back yaw/pitch
				// (entities have no roll).
				Vec3 look = t.localDirToWorld(Vec3.directionFromRotation(entity.getXRot(), entity.getYRot()));
				double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
				yaw = Mth.wrapDegrees((float) (Mth.atan2(look.z, look.x) * RAD_TO_DEG) - 90.0F);
				pitch = Mth.wrapDegrees((float) (-(Mth.atan2(look.y, horizontal) * RAD_TO_DEG)));
			}
			// moveTo also calls setOldPosAndRot, snapping the interpolation history (xo/xOld/yRotO/…) to
			// the world pose so the client doesn't lerp in from the assembly-local origin (a visible streak).
			entity.moveTo(world.x, world.y, world.z, yaw, pitch);
		} else {
			// No pose (e.g. tests): still snap the history to the spawn pose so there's no lerp from (0,0,0).
			entity.moveTo(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
		}
		return root.addFreshEntity(entity);
	}

	@Override
	public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
		// A block entity raises this on a discrete visual change (sign text, smoker lit, …). Refresh
		// that cell's client render NBT from the live BE and request a single whole-assembly resync.
		BlockEntity be = blockEntities.get(pos);
		if (be != null) {
			StructureBlockInfo info = assembly.getBlocks().get(pos);
			if (info != null)
				assembly.putBlock(pos, info.state(), info.nbt(), be.getUpdateTag(registryAccess()));
		}
		if (onNeedsSync != null)
			onNeedsSync.run();
	}

	@Override
	public void blockEntityChanged(BlockPos pos) {
		// Fires every hopper/furnace tick: only mark the host dirty for persistence; do NOT resync to
		// clients here (sendBlockUpdated handles discrete visual changes; flush handles save/sync).
		if (onChanged != null && blockEntities.containsKey(pos))
			onChanged.run();
	}

	/**
	 * Blocks emit sounds at their (local) coordinates; rotate them into world space so a button
	 * click, door creak, etc. plays where the block actually is on the assembly. All the
	 * positional {@code playSound} overloads (BlockPos / no-seed) funnel through this one.
	 *
	 * <p>The sound is emitted on the <b>real</b> level: {@link WrappedServerLevel} stubs
	 * {@code playSound} to a no-op (catnip suppresses sim-level sounds), so calling {@code super}
	 * would drop it. We pass a {@code null} source player so the interacting player hears it too —
	 * assembly interactions aren't predicted client-side, so nothing plays it locally.
	 */
	@Override
	public void playSound(@Nullable Player player, double x, double y, double z, SoundEvent sound, SoundSource source,
		float volume, float pitch) {
		Vec3 world = transform != null ? transform.get().localToWorld(new Vec3(x, y, z)) : new Vec3(x, y, z);
		rootRealLevel().playSound(null, world.x, world.y, world.z, sound, source, volume, pitch);
	}

	/**
	 * Entity-attached sounds: the entity already lives in world space, so no transform is needed —
	 * just emit on the real level ({@code super} is a no-op as above).
	 */
	@Override
	public void playSound(@Nullable Player player, Entity entity, SoundEvent sound, SoundSource source, float volume,
		float pitch) {
		realLevel.playSound(null, entity, sound, source, volume, pitch);
	}

	/**
	 * Many blocks emit their effects not through {@link #playSound} but through {@code levelEvent} —
	 * a dispenser's dispense/fail click ({@link net.minecraft.world.level.block.LevelEvent#SOUND_DISPENSER_DISPENSE}/
	 * {@code SOUND_DISPENSER_FAIL}), wooden doors/trapdoors/fence gates opening and closing, a jukebox
	 * starting a record, etc. As with {@link #playSound}, {@link WrappedServerLevel} suppresses these on
	 * the sim level, so they never reach clients. Forward them to the <b>real</b> level at the block's
	 * actual, rotated world position so the effect plays where the block is on the assembly.
	 *
	 * <p>The position is a {@link BlockPos}, so we rotate the block's centre into world space and snap
	 * back to a block — exact enough for a positional sound/particle. Direction-encoded {@code data}
	 * (e.g. a dispenser's particle facing) is passed through unchanged: representing the assembly's
	 * arbitrary rotation as one of six {@link net.minecraft.core.Direction}s isn't possible, so the
	 * particle trail keeps its local facing while the sound — the point of this fix — lands correctly.
	 */
	@Override
	public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
		BlockPos worldPos = pos;
		if (transform != null) {
			Vec3 world = transform.get().localToWorld(Vec3.atCenterOf(pos));
			worldPos = BlockPos.containing(world);
		}
		rootRealLevel().levelEvent(null, type, worldPos, data);
	}

	@Override
	public LevelTicks<Block> getBlockTicks() {
		return assembly.getBlockTicks();
	}

	@Override
	public LevelTicks<Fluid> getFluidTicks() {
		return fluidTicks;
	}

	@Override
	public void scheduleTick(BlockPos pos, Block block, int delay) {
		scheduleTick(pos, block, delay, TickPriority.NORMAL);
	}

	@Override
	public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {
		assembly.ensureTickContainer(pos);
		assembly.getBlockTicks().schedule(
			new ScheduledTick<>(block, pos, getLevelData().getGameTime() + delay, priority, nextSubTickCount()));
	}
}
