package com.assemblylib.assembly;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.assemblylib.AssemblyLib;
import com.assemblylib.assembly.collision.AssemblyCollider;
import com.assemblylib.assembly.util.AssemblyMath;
import com.assemblylib.client.renderer.assembly.AssemblyRenderState;
import com.assemblylib.mixin.FallingBlockEntityInvoker;
import com.assemblylib.mixin.ServerGamePacketListenerImplAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Holds an {@link Assembly}'s mutable state and all of its behaviour — server/client ticking,
 * collision, in-flight break/place/use, falling-block detach, NBT save/sync and render bounds —
 * independently of what hosts it. An {@link AssemblyHost} (a BlockEntity, an Entity, …) owns one of
 * these and supplies its environment through the host interface, so the same machinery drives any
 * host. This was previously inlined in {@code ServoMotorBlockEntity}.
 */
public final class AssemblyController {

	/** Rotation speed in degrees per tick (~16 RPM at 2°/t). */
	public static final float DEGREES_PER_TICK = 2f;

	/** Max squared distance from a player to an assembly block for break/place. */
	private static final double INTERACT_RANGE_SQR = 7.0 * 7.0;

	private final AssemblyHost host;

	@Nullable
	private Assembly assembly;
	private boolean running;
	/** Set while we tear the host down ourselves so {@link #onHostRemoved} doesn't double-drop. */
	private boolean suppressRemovalDrops;
	private Axis rotationAxis = Axis.Y;

	private float angle;
	private float prevAngle;

	/** Client-only cache of reconstructed block entities for rendering. */
	@Nullable
	private AssemblyRenderState renderState;

	/** Server-only cached simulation level (see {@link #simLevel()}); rebuilt when the assembly changes. */
	@Nullable
	private AssemblySimServerLevel simLevel;

	/** Set when a scheduled block tick mutates the structure, so {@link #serverTick()} re-syncs once. */
	private boolean simNeedsSync;

	public AssemblyController(AssemblyHost host) {
		this.host = host;
	}

	// region accessors

	@Nullable
	public Assembly getAssembly() {
		return assembly;
	}

	/**
	 * Client-only: replace the assembly with a predicted version (after a local break/place) so it
	 * renders/collides immediately, before the authoritative server sync arrives and replaces it. A
	 * fresh instance triggers the renderer and render-state caches to rebuild (they key on identity).
	 */
	public void setAssemblyClient(Assembly predicted) {
		Level level = host.assemblyLevel();
		if (level != null && level.isClientSide)
			assembly = predicted;
	}

	public boolean isRunning() {
		return running;
	}

	/** Number of blocks in the assembly (head included), at least 1, for break-speed scaling. */
	public int getAssemblyBlockCount() {
		return assembly == null ? 1 : Math.max(1, assembly.getBlocks().size());
	}

	public float getInterpolatedAngle(float partialTick) {
		return AssemblyMath.angleLerp(partialTick, prevAngle, angle);
	}

	/** Raw current rotation angle in degrees (server-authoritative). */
	public float getAngle() {
		return angle;
	}

	/** The intended rotation this tick (used to project carried/release velocity forward). */
	public float getIntendedSpin() {
		return running ? DEGREES_PER_TICK : 0f;
	}

	public Axis getRotationAxis() {
		return rotationAxis;
	}

	/**
	 * Client-only: the live reconstructed block entities of the captured structure. The render state
	 * persists across syncs and reconciles its instances in place (the client builds a fresh assembly
	 * every sync), so block-entity renderers keep their animation state. Returns null when nothing is
	 * assembled or off-client.
	 */
	@Nullable
	public AssemblyRenderState getRenderState() {
		Level level = host.assemblyLevel();
		if (level == null || assembly == null || !level.isClientSide) {
			renderState = null;
			return null;
		}
		if (renderState == null)
			renderState = new AssemblyRenderState(level, assembly, () -> AssemblyTransform.ofCurrent(host), host);
		else
			renderState.update(assembly);
		return renderState;
	}

	// endregion

	// region ticking

	public void serverTick() {
		prevAngle = angle;

		// Pre-assembled on placement; lazily seed the head for hosts created another way
		// (e.g. /setblock) so the assembly always exists.
		if (assembly == null)
			initAssembly();

		// Spins only while powered; the assembly is permanent either way.
		boolean powered = host.isAssemblyPowered();
		if (powered != running) {
			running = powered;
			host.markAssemblyChanged();
			host.syncAssemblyToClients();
		}

		if (assembly == null)
			return;

		// Drive the assembly's own block-tick queue (falling blocks, and redstone components
		// like repeaters/observers/button-release), kept separate from the outer world's tick queue.
		tickAssemblyBlocks();
		// Tick live block entities (furnaces smelt, hoppers move items, …). Any structural change
		// they make flips simNeedsSync via the sim level's onNeedsSync callback.
		tickAssemblyBlockEntities();
		if (simNeedsSync) {
			simNeedsSync = false;
			host.syncAssemblyToClients();
		}

		if (running)
			angle = (angle + DEGREES_PER_TICK) % 360;
		// Resolve non-player entities here (players are resolved client-side). A stopped
		// assembly is still solid; only the carry motion goes to zero.
		collide(entity -> !(entity instanceof Player));
		// Riders stand on blocks that aren't in the world, so keep the server from
		// kicking them for "floating".
		keepRidersAfloat();
	}

	public void clientTick() {
		prevAngle = angle;
		// Targeting/building/breaking work even while stopped, so track any host that has an
		// assembly — not just spinning ones.
		if (assembly == null) {
			AssemblyHosts.ACTIVE_CLIENT.remove(host);
			return;
		}
		AssemblyHosts.ACTIVE_CLIENT.add(host);

		if (running)
			angle = (angle + DEGREES_PER_TICK) % 360;

		// Client-tick the captured block entities so their renderers animate like normal world BEs
		// (chest lids, spawner spin, campfire smoke, conduit frames, …).
		AssemblyRenderState rs = getRenderState();
		if (rs != null)
			rs.tick();

		// Local-player collision is driven from AssemblyInteractionClient (client-only)
		// so this class never references client types and stays dist-safe.

		// Most entities interpolate toward their server-synced position on the client
		// (Entity#baseTick), so the server's authoritative collision result is what the
		// client renders — smooth. FallingBlockEntity is the exception: its tick runs raw
		// physics every tick and never calls super.tick()/interpolates, so on the client it
		// free-falls straight through the assembly (whose blocks aren't real world blocks)
		// and only snaps back when a position packet lands — which reads as jitter. Resolve
		// it here too so the client prediction stays glued to the platform, matching the
		// server. (Create gets this for free: its assembly is an entity that runs
		// collideEntities on both sides.)
		collide(entity -> entity instanceof FallingBlockEntity || entity instanceof ItemEntity);
	}

	/** Remove this host from the client-active registry (called when the host unloads/removes). */
	public void onClientUnload() {
		AssemblyHosts.ACTIVE_CLIENT.remove(host);
	}

	/**
	 * The server simulation level bound to this assembly: writes/ticks mutate the assembly and mark the
	 * host dirty, and — because it is a real {@link ServerLevel} ({@link AssemblySimServerLevel}) —
	 * scheduled ticks and redstone run natively. Callers batch a single {@link AssemblyHost#syncAssemblyToClients()}
	 * after an operation, so a multi-block change is one network re-sync. Cached (constructing it builds
	 * a full ServerLevel) and rebuilt when the assembly identity changes. Server-only.
	 */
	private AssemblySimServerLevel simLevel() {
		if (simLevel == null || simLevel.getAssembly() != assembly)
			simLevel = new AssemblySimServerLevel((ServerLevel) host.assemblyLevel(), assembly,
				host.assemblyHostBlockPos(), host::markAssemblyChanged, () -> simNeedsSync = true,
				() -> AssemblyTransform.ofCurrent(host));
		return simLevel;
	}

	/**
	 * The live (server-side) block entity at an assembly-local position, or {@code null} if there is
	 * none. Lazily created and cached by the simulation level. Server-only.
	 */
	@Nullable
	public BlockEntity getAssemblyBlockEntity(BlockPos local) {
		if (assembly == null || !(host.assemblyLevel() instanceof ServerLevel))
			return null;
		return simLevel().getBlockEntity(local);
	}

	/**
	 * The live host reconstructed at assembly-local cell {@code local}, when this host nests another
	 * assembly there — or {@code null} if that cell holds no host. Dist-aware: the server uses the sim
	 * level's live block entity, the client the render state's. Used to walk an {@link AssemblyPath}
	 * down to an innermost nested host.
	 */
	@Nullable
	public AssemblyHost getNestedHost(BlockPos local) {
		Level level = host.assemblyLevel();
		BlockEntity be;
		if (level != null && level.isClientSide) {
			AssemblyRenderState rs = getRenderState();
			be = rs == null ? null : rs.getBlockEntity(local);
		} else {
			be = getAssemblyBlockEntity(local);
		}
		return be instanceof AssemblyHost h ? h : null;
	}

	/** Run the assembly's own block-tick queue once (server-side). */
	private void tickAssemblyBlocks() {
		if (!(host.assemblyLevel() instanceof ServerLevel serverLevel))
			return;
		AssemblySimServerLevel sim = simLevel();
		assembly.getBlockTicks().tick(serverLevel.getGameTime(), 65536,
			(local, block) -> onAssemblyBlockTick(local, block, sim, serverLevel));
	}

	/**
	 * Tick the assembly's live block entities once (server-side), so furnaces smelt, hoppers move
	 * items, brewing stands brew, etc. A misbehaving BE must not kill the host tick, so each tick is
	 * isolated. Block-entity-driven structural changes (e.g. a furnace toggling LIT via setBlock) flip
	 * {@code simNeedsSync} through the sim level, re-syncing the assembly to clients.
	 */
	private void tickAssemblyBlockEntities() {
		if (!(host.assemblyLevel() instanceof ServerLevel))
			return;
		AssemblySimServerLevel sim = simLevel();
		for (AssemblySimServerLevel.TickingBE ticking : sim.getTickingBlockEntities()) {
			BlockPos local = ticking.pos();
			BlockEntity be = ticking.be();
			BlockState state = sim.getBlockState(local);
			if (be.isRemoved() || !be.getType().isValid(state))
				continue;
			try {
				ticking.ticker().tick(sim, local, state, be);
			} catch (Exception e) {
				AssemblyLib.LOGGER.error("Assembly block entity at {} threw while ticking", local, e);
			}
		}
	}

	/**
	 * Ticker callback for a due scheduled block tick: re-validate the cell against the scheduled
	 * block, then either run custom falling-block handling or the block's own scheduled tick.
	 */
	private void onAssemblyBlockTick(BlockPos local, Block block, AssemblySimServerLevel sim,
		ServerLevel serverLevel) {
		StructureBlockInfo info = assembly.getBlocks().get(local);
		if (info == null || !info.state().is(block))
			return;
		// Falling blocks (sand/gravel/concrete powder/anvils) get custom handling: if unsupported they
		// detach into a real FallingBlockEntity that inherits the assembly's rotation and platform
		// velocity (exactly like a group that splits off). We must NOT run vanilla FallingBlock#tick,
		// which would instead spawn a plain entity at the local position and drop the block.
		if (block instanceof FallingBlock) {
			detachFallingBlock(local, info.state());
			return;
		}
		// Everything else runs its real scheduled tick against the (ServerLevel) sim, so
		// repeaters/comparators/observers/redstone-torches/button-release behave as in a normal world.
		info.state().tick(sim, local, serverLevel.getRandom());
		simNeedsSync = true;
	}

	/**
	 * If the falling block at {@code local} has no support directly below it (in local space), detach
	 * it from the structure (spawning a real {@link FallingBlockEntity}) and run a structural update so
	 * the change propagates: it falls in the world and lands on any lower platform via the existing
	 * falling-block collision in {@link #serverTick()}.
	 */
	private void detachFallingBlock(BlockPos local, BlockState state) {
		if (!(host.assemblyLevel() instanceof ServerLevel serverLevel))
			return;
		StructureBlockInfo below = assembly.getBlocks().get(local.below());
		BlockState belowState = below == null ? Blocks.AIR.defaultBlockState() : below.state();
		if (!FallingBlock.isFree(belowState))
			return; // still supported

		AssemblySimServerLevel sim = simLevel();
		detachBlock(local, state, averagePlatformVelocity(List.of(local)), sim, serverLevel);
		applyStructureUpdate(sim, serverLevel);
	}

	/**
	 * Remove the block at {@code local} through the simulation level — so the standard block update
	 * fires — and spawn a real {@link FallingBlockEntity} for it at the block's current rotated world
	 * position, with the assembly's orientation and the given release {@code velocity}. Does NOT
	 * re-sync; callers batch one {@link AssemblyHost#syncAssemblyToClients()}.
	 */
	private void detachBlock(BlockPos local, BlockState state, Vec3 velocity, AssemblySimServerLevel sim,
		ServerLevel serverLevel) {
		sim.setBlock(local, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

		AssemblyTransform transform = AssemblyTransform.ofCurrent(host);
		// Feet at the cell's bottom-centre: local x/z centred, y at the block's bottom face.
		Vec3 feet = transform.localToWorld(new Vec3(local.getX() + 0.5, local.getY(), local.getZ() + 0.5));
		FallingBlockEntity entity = FallingBlockEntityInvoker.zps$create(serverLevel, feet.x, feet.y, feet.z, state);
		// Inherit the assembly's current (composed, for a nested assembly) orientation so the
		// block keeps it while falling.
		((AssemblyRotatedEntity) entity).zps$setAssemblyRotation(transform.localToWorldRotationQuat());
		// Release it with the platform velocity (a spinning detach continues smoothly instead of
		// jerking to a stop); for a multi-block group this is the group average. Sent to clients in
		// the spawn packet's velocity.
		entity.setDeltaMovement(velocity);
		serverLevel.addFreshEntity(entity);
	}

	/**
	 * Structural update after a removal: collapse anything no longer connected to the head, then
	 * re-sync once. The falling-block re-evaluation is handled by the removal itself going through
	 * {@link AssemblySimServerLevel#setBlock} (which runs the standard block update). Re-invoked when a
	 * falling block detaches, so collapses cascade.
	 */
	private void applyStructureUpdate(AssemblySimServerLevel sim, ServerLevel serverLevel) {
		detachDisconnected(sim, serverLevel);
		host.markAssemblyChanged();
		host.syncAssemblyToClients();
	}

	/**
	 * Detach every block no longer connected to the head (26-way: face, edge or corner contact all keep
	 * a block attached) as a falling block entity. Each self-connected sub-group falls off as a
	 * cohesive unit: all its blocks inherit the group's average platform velocity, so the group
	 * translates together instead of shearing apart.
	 */
	private void detachDisconnected(AssemblySimServerLevel sim, ServerLevel serverLevel) {
		var blocks = assembly.getBlocks();
		BlockPos head = host.headLocalPos();
		if (!blocks.containsKey(head))
			return;

		// Everything 26-connected to the head stays attached.
		Set<BlockPos> connected = new HashSet<>();
		connectedGroup(head, blocks.keySet(), connected);
		if (connected.size() == blocks.size())
			return;

		Set<BlockPos> disconnected = new HashSet<>();
		for (BlockPos p : blocks.keySet())
			if (!connected.contains(p))
				disconnected.add(p);

		Set<BlockPos> grouped = new HashSet<>();
		for (BlockPos start : disconnected) {
			List<BlockPos> group = connectedGroup(start, disconnected, grouped);
			if (group.isEmpty())
				continue; // already part of an earlier group
			Vec3 velocity = averagePlatformVelocity(group);
			for (BlockPos p : group) {
				StructureBlockInfo info = blocks.get(p);
				if (info == null)
					continue; // already removed by a cascading shape update
				detachBlock(p, info.state(), velocity, sim, serverLevel);
			}
		}
	}

	/** 26-way connected component of {@code start} within {@code domain}, accumulating into {@code visited}. */
	private static List<BlockPos> connectedGroup(BlockPos start, Set<BlockPos> domain, Set<BlockPos> visited) {
		List<BlockPos> group = new ArrayList<>();
		if (!domain.contains(start) || !visited.add(start))
			return group;
		Deque<BlockPos> stack = new ArrayDeque<>();
		group.add(start);
		stack.push(start);
		while (!stack.isEmpty()) {
			BlockPos p = stack.pop();
			for (int dx = -1; dx <= 1; dx++)
				for (int dy = -1; dy <= 1; dy++)
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0)
							continue;
						BlockPos n = p.offset(dx, dy, dz);
						if (domain.contains(n) && visited.add(n)) {
							group.add(n);
							stack.push(n);
						}
					}
		}
		return group;
	}

	/** Average platform velocity over a group of local cells, for releasing it as a cohesive unit. */
	private Vec3 averagePlatformVelocity(List<BlockPos> group) {
		AssemblyTransform now = AssemblyTransform.ofCurrent(host);
		AssemblyTransform next = AssemblyTransform.ofIntendedNext(host);
		Vec3 sum = Vec3.ZERO;
		for (BlockPos p : group)
			sum = sum.add(next.localBlockCenterToWorld(p).subtract(now.localBlockCenterToWorld(p)));
		return sum.scale(1.0 / group.size());
	}

	// endregion

	// region assembly lifecycle

	/**
	 * Seed the assembly with just the head at the host's own cell (on the rotation axis, so it spins in
	 * place). The rest of the assembly is built outward off the head via in-flight placement. The host
	 * is never disassembled.
	 */
	public void initAssembly() {
		Level level = host.assemblyLevel();
		if (level == null || level.isClientSide || assembly != null)
			return;

		Direction facing = host.assemblyFacing();
		rotationAxis = facing.getAxis();
		BlockPos anchor = host.assemblyHostBlockPos().relative(facing);
		Assembly next = new Assembly();
		next.setAnchor(anchor);
		next.putBlock(host.headLocalPos(), host.createHeadBlockState(), null, null);

		assembly = next;
		angle = 0;
		prevAngle = 0;
		host.markAssemblyChanged();
		host.syncAssemblyToClients();
	}

	/** Called when the host is broken: drop every assembly block as items. */
	public void onHostRemoved() {
		Level level = host.assemblyLevel();
		if (level == null || level.isClientSide)
			return;
		if (!suppressRemovalDrops && assembly != null)
			dropAssemblyLoot(ItemStack.EMPTY);
		assembly = null;
		running = false;
	}

	// endregion

	// region in-flight editing (break / place / use)

	/** True if {@code player} is close enough to the rotated world position of a local block. */
	private boolean inReach(BlockPos local, ServerPlayer player, AssemblyTransform transform) {
		return player.position().distanceToSqr(transform.localBlockCenterToWorld(local)) <= INTERACT_RANGE_SQR;
	}

	/** Mine a single block out of the structure: drops, particles/sound, and structure update. */
	public void breakAssemblyBlock(BlockPos local, ServerPlayer player) {
		Level level = host.assemblyLevel();
		if (level == null || level.isClientSide || assembly == null)
			return;
		StructureBlockInfo info = assembly.getBlocks().get(local);
		if (info == null)
			return;
		ServerLevel serverLevel = (ServerLevel) level;
		AssemblyTransform transform = AssemblyTransform.ofCurrent(host);
		if (!inReach(local, player, transform))
			return;

		// The head coincides with the in-world host; breaking it tears down the whole host.
		if (local.equals(host.headLocalPos())) {
			host.breakWholeHost(player);
			return;
		}

		BlockState state = info.state();
		BlockPos worldPos = BlockPos.containing(transform.localBlockCenterToWorld(local));
		ItemStack tool = player.getMainHandItem();
		AssemblySimServerLevel sim = simLevel();

		if (!player.isCreative() && serverLevel.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
			popBlockLoot(serverLevel, state, liveBlockEntityNbt(sim, local, info), worldPos, player, tool);
			state.spawnAfterBreak(serverLevel, worldPos, tool, true);
		}

		spawnAssemblyBreakEffects(serverLevel, local, state, transform);
		// Remove through the sim level so neighbours get the standard block update (unsupported
		// falling blocks fall), then collapse anything no longer connected to the head. Re-syncs once.
		sim.setBlock(local, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		applyStructureUpdate(sim, serverLevel);
	}

	private void spawnAssemblyBreakEffects(ServerLevel serverLevel, BlockPos local, BlockState state,
		AssemblyTransform transform) {
		if (assembly == null || state.isAir())
			return;

		Vec3 worldCenter = transform.localBlockCenterToWorld(local);
		SoundType sound = state.getSoundType();
		serverLevel.playSound(null, worldCenter.x, worldCenter.y, worldCenter.z, sound.getBreakSound(),
			SoundSource.BLOCKS, (sound.getVolume() + 1.0f) / 2.0f, sound.getPitch() * 0.8f);

		BlockParticleOption particle = new BlockParticleOption(ParticleTypes.BLOCK, state);
		VoxelShape shape = state.getShape(new AssemblyBlockGetter(assembly), local);
		shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
			double sizeX = Math.min(1.0D, maxX - minX);
			double sizeY = Math.min(1.0D, maxY - minY);
			double sizeZ = Math.min(1.0D, maxZ - minZ);
			int countX = Math.max(2, Mth.ceil(sizeX / 0.25D));
			int countY = Math.max(2, Mth.ceil(sizeY / 0.25D));
			int countZ = Math.max(2, Mth.ceil(sizeZ / 0.25D));

			for (int x = 0; x < countX; ++x) {
				for (int y = 0; y < countY; ++y) {
					for (int z = 0; z < countZ; ++z) {
						double relX = ((double) x + 0.5D) / (double) countX;
						double relY = ((double) y + 0.5D) / (double) countY;
						double relZ = ((double) z + 0.5D) / (double) countZ;
						Vec3 localParticle = new Vec3(
							local.getX() + relX * sizeX + minX,
							local.getY() + relY * sizeY + minY,
							local.getZ() + relZ * sizeZ + minZ);
						Vec3 worldParticle = transform.localToWorld(localParticle);
						Vec3 velocity = AssemblyMath.rotate(new Vec3(relX - 0.5D, relY - 0.5D, relZ - 0.5D),
							transform.angle(), transform.axis());
						serverLevel.sendParticles(particle, worldParticle.x, worldParticle.y, worldParticle.z, 0,
							velocity.x, velocity.y, velocity.z, 1.0D);
					}
				}
			}
		});
	}

	/** Drop the loot of every assembly block except the (unobtainable) head. */
	private void dropAssemblyLoot(ItemStack tool) {
		Level level = host.assemblyLevel();
		if (level == null || level.isClientSide || assembly == null)
			return;
		ServerLevel serverLevel = (ServerLevel) level;
		if (!serverLevel.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS))
			return;

		BlockPos headLocal = host.headLocalPos();
		BlockPos worldPos = host.assemblyHostBlockPos();
		AssemblySimServerLevel sim = simLevel();
		for (var entry : assembly.getBlocks().entrySet()) {
			if (entry.getKey().equals(headLocal))
				continue;
			popBlockLoot(serverLevel, entry.getValue().state(),
				liveBlockEntityNbt(sim, entry.getKey(), entry.getValue()), worldPos, null, tool);
		}
	}

	/**
	 * The freshest block-entity NBT for an assembly cell: the live BE's current state if one is loaded
	 * (so a broken chest drops its current contents), else the captured {@code beNbt}.
	 */
	@Nullable
	private CompoundTag liveBlockEntityNbt(AssemblySimServerLevel sim, BlockPos local, StructureBlockInfo info) {
		BlockEntity live = sim.getBlockEntity(local);
		Level level = host.assemblyLevel();
		if (live != null && level != null)
			return live.saveWithFullMetadata(level.registryAccess());
		return info.nbt();
	}

	/** Reconstruct any block entity from {@code nbt} and pop the block's drops at {@code worldPos}. */
	private void popBlockLoot(ServerLevel serverLevel, BlockState state, @Nullable CompoundTag nbt, BlockPos worldPos,
		@Nullable ServerPlayer player, ItemStack tool) {
		BlockEntity be = null;
		if (nbt != null && state.getBlock() instanceof EntityBlock entityBlock) {
			be = entityBlock.newBlockEntity(worldPos, state);
			if (be != null) {
				be.setLevel(serverLevel);
				be.loadWithComponents(nbt, serverLevel.registryAccess());
			}
		}
		for (ItemStack drop : Block.getDrops(state, serverLevel, worldPos, be, player, tool))
			Block.popResource(serverLevel, worldPos, drop);
	}

	/** Place the player's held block into the structure with full vanilla placement context. */
	public boolean placeAssemblyBlock(BlockPos local, Direction localFace, Vec3 localHit, ServerPlayer player,
		InteractionHand hand) {
		Level level = host.assemblyLevel();
		if (level == null || level.isClientSide || assembly == null)
			return false;
		if (!assembly.getBlocks().containsKey(local))
			return false;
		ItemStack stack = player.getItemInHand(hand);
		if (!(stack.getItem() instanceof BlockItem))
			return false;

		ServerLevel serverLevel = (ServerLevel) level;
		AssemblyTransform transform = AssemblyTransform.ofCurrent(host);
		if (!inReach(local, player, transform))
			return false;

		// Overlay exposing the assembly's blocks at their local positions, so
		// getStateForPlacement sees in-structure neighbours (fences/walls/redstone/stairs)
		// and in-structure side-effects (FallingBlock#onPlace) read/write the assembly.
		AssemblySimServerLevel sim = simLevel();
		AssemblyPlaceContext.Placed placed =
			AssemblyPlaceContext.resolve(sim, player, hand, stack, local, localFace, localHit, transform);
		if (placed == null)
			return false;

		BlockPos placePos = placed.pos();
		BlockState placeState = placed.state();
		if (!AssemblyPlacementUtil.isUnobstructed(serverLevel, sim, player, placePos, placeState, transform))
			return false;

		if (!AssemblyPlacementUtil.placeBlock(serverLevel, sim, assembly, player, stack, placed))
			return false;
		placeState = sim.getBlockState(placePos);
		// Placement side-effects (e.g. a FallingBlock scheduling its fall, redstone components
		// notifying neighbours) now run inside AssemblySimServerLevel#setBlock via the block
		// lifecycle, so no explicit onPlace is needed here.
		if (!player.isCreative())
			stack.shrink(1);

		Vec3 worldCenter = transform.localBlockCenterToWorld(placePos);
		SoundType sound = placeState.getSoundType();
		serverLevel.playSound(null, BlockPos.containing(worldCenter), sound.getPlaceSound(), SoundSource.BLOCKS,
			(sound.getVolume() + 1.0f) / 2.0f, sound.getPitch() * 0.8f);
		host.markAssemblyChanged();
		host.syncAssemblyToClients();
		return true;
	}

	/**
	 * Right-click a block in the structure (buttons, levers, doors, trapdoors, pressure plates, …).
	 * Runs the block's vanilla use logic against the server simulation level, so its side-effects —
	 * toggling {@code OPEN}/{@code POWERED}, scheduling the button-release tick, redstone neighbour
	 * updates, sounds — all happen against the assembly. Mirrors vanilla's right-click order
	 * (held-item block interaction first, then the block's own use), then re-syncs once.
	 */
	public boolean useAssemblyBlock(BlockPos local, Direction localFace, Vec3 localHit, ServerPlayer player,
		InteractionHand hand) {
		Level level = host.assemblyLevel();
		if (level == null || level.isClientSide || assembly == null)
			return false;
		StructureBlockInfo info = assembly.getBlocks().get(local);
		if (info == null)
			return false;

		AssemblyTransform transform = AssemblyTransform.ofCurrent(host);
		if (!inReach(local, player, transform))
			return false;

		AssemblySimServerLevel sim = simLevel();
		BlockState state = info.state();
		BlockHitResult hit = new BlockHitResult(localHit, localFace, local, false);
		ItemStack stack = player.getItemInHand(hand);

		if (!stack.isEmpty()) {
			ItemInteractionResult itemResult = state.useItemOn(stack, sim, player, hand, hit);
			if (itemResult != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
				if (itemResult.consumesAction()) {
					host.markAssemblyChanged();
					host.syncAssemblyToClients();
				}
				return itemResult.consumesAction();
			}
		}
		if (state.useWithoutItem(sim, player, hit).consumesAction()) {
			host.markAssemblyChanged();
			host.syncAssemblyToClients();
			return true;
		}
		return false;
	}

	// endregion

	// region collision

	private void collide(Predicate<Entity> shouldCollide) {
		AssemblyTransform transform = AssemblyTransform.ofCurrent(host);
		AABB worldBounds = computeWorldBounds(transform);
		AssemblyCollider.collideEntities(rootRealLevel(), transform, assembly, worldBounds,
			this::getContactPointMotion, shouldCollide, this::captureLandedBlock);
	}

	/**
	 * The genuine outer world the assembly's entities live in. For a root host this is its level; for a
	 * nested host it walks up the host chain (whose intermediate levels are sim/render wrappers with no
	 * entities of their own). Dist-safe — uses {@link AssemblyHost#assemblyParentHost()}, never a
	 * wrapper type.
	 */
	private Level rootRealLevel() {
		AssemblyHost m = host;
		AssemblyHost parent;
		while ((parent = m.assemblyParentHost()) != null)
			m = parent;
		return m.assemblyLevel();
	}

	/**
	 * Capture a {@link FallingBlockEntity} that has landed on the assembly into the structure at
	 * {@code localCell}, so it becomes part of the assembly (the inverse of {@link #detachFallingBlock}).
	 * Server-side; the collider only invokes this when the cell is empty and supported below.
	 */
	private void captureLandedBlock(Entity entity, BlockPos localCell) {
		if (assembly == null || !(host.assemblyLevel() instanceof ServerLevel)
			|| !(entity instanceof FallingBlockEntity falling))
			return;
		BlockState state = falling.getBlockState();
		if (state.isAir() || assembly.getBlocks().size() >= Assembly.MAX_BLOCKS)
			return;

		// Block-entity data from the falling block is dropped for now (sand/gravel/anvils have none).
		assembly.putBlock(localCell, state, null, null);
		falling.discard();

		Vec3 worldCenter = AssemblyTransform.ofCurrent(host).localBlockCenterToWorld(localCell);
		SoundType sound = state.getSoundType();
		// Emit on the real outer world (a nested host's own level is a sim wrapper that would
		// re-transform the already-world position).
		rootRealLevel().playSound(null, BlockPos.containing(worldCenter), sound.getPlaceSound(), SoundSource.BLOCKS,
			(sound.getVolume() + 1.0f) / 2.0f, sound.getPitch() * 0.8f);
		host.markAssemblyChanged();
		host.syncAssemblyToClients();
	}

	/**
	 * Resolve a single player against this assembly (their movement is client-authoritative, so the
	 * client drives this for its local player).
	 */
	public void collideWithPlayer(Player player) {
		if (assembly == null || player == null)
			return;
		collide(entity -> entity == player);
	}

	private void keepRidersAfloat() {
		AABB bounds = computeWorldBounds(AssemblyTransform.ofCurrent(host));
		for (Player player : rootRealLevel().getEntitiesOfClass(Player.class, bounds)) {
			if (player instanceof ServerPlayer serverPlayer) {
				ServerGamePacketListenerImplAccessor connection =
					(ServerGamePacketListenerImplAccessor) serverPlayer.connection;
				connection.setAboveGroundTickCount(0);
				connection.setAboveGroundVehicleTickCount(0);
			}
		}
	}

	/** Velocity of the rotating platform at a world point, for carrying riders. */
	private Vec3 getContactPointMotion(Vec3 worldPos) {
		// Carry the rider to where this platform-fixed point will actually be after this tick's
		// rotation (project the composed pose forward by each host's intended spin), so it lands
		// exactly on its circle instead of stepping along the tangent and spiralling outward. The
		// composition makes a nested rider inherit its parent's angular velocity as well as its own.
		AssemblyTransform now = AssemblyTransform.ofCurrent(host);
		AssemblyTransform next = AssemblyTransform.ofIntendedNext(host);
		return next.localToWorld(now.worldToLocal(worldPos)).subtract(worldPos);
	}

	private AABB computeWorldBounds(AssemblyTransform transform) {
		AABB local = assembly == null ? new AABB(BlockPos.ZERO) : assembly.getBounds();
		// Enclose the rotating (possibly nested) structure: transform all 8 local corners to world.
		double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
		double[] xs = { local.minX, local.maxX };
		double[] ys = { local.minY, local.maxY };
		double[] zs = { local.minZ, local.maxZ };
		for (double x : xs)
			for (double y : ys)
				for (double z : zs) {
					Vec3 w = transform.localToWorld(new Vec3(x, y, z));
					minX = Math.min(minX, w.x);
					minY = Math.min(minY, w.y);
					minZ = Math.min(minZ, w.z);
					maxX = Math.max(maxX, w.x);
					maxY = Math.max(maxY, w.y);
					maxZ = Math.max(maxZ, w.z);
				}
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(1);
	}

	public AABB getRenderBoundingBox() {
		if (assembly == null)
			return new AABB(host.assemblyHostBlockPos());
		return computeWorldBounds(AssemblyTransform.ofCurrent(host));
	}

	// endregion

	// region sync + persistence

	public void writeState(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putBoolean("Running", running);
		tag.putInt("Axis", rotationAxis.ordinal());
		tag.putFloat("Angle", angle);
		// Flush live block entities back into the assembly first, so the serialized structure
		// reflects their current state (smelt progress, container contents, …) for both save and sync.
		if (simLevel != null && simLevel.getAssembly() == assembly)
			simLevel.flushAllBlockEntityData();
		Level level = host.assemblyLevel();
		if (assembly != null)
			tag.put("Assembly", assembly.writeNBT(level == null ? 0L : level.getGameTime()));
	}

	public void readState(CompoundTag tag, HolderLookup.Provider registries) {
		Level level = host.assemblyLevel();
		// A structure edit (break/place) re-syncs the whole host while it keeps spinning. The
		// client advances the angle deterministically every tick, so adopting the packet's
		// (already stale) angle here would snap the rotation. Only adopt the synced angle on
		// the initial load; otherwise keep the client's free-running angle.
		boolean wasRunningWithAssembly = running && assembly != null;
		float clientAngle = angle;
		float clientPrevAngle = prevAngle;

		running = tag.getBoolean("Running");
		rotationAxis = Axis.values()[tag.getInt("Axis")];

		if (level != null && level.isClientSide && wasRunningWithAssembly && running) {
			angle = clientAngle;
			prevAngle = clientPrevAngle;
		} else {
			angle = tag.getFloat("Angle");
			prevAngle = angle;
		}

		if (tag.contains("Assembly")) {
			assembly = new Assembly();
			assembly.readNBT(registries, tag.getCompound("Assembly"), level == null ? 0L : level.getGameTime());
		} else {
			assembly = null;
		}
	}

	// endregion
}
