package com.assemblylib.impl.client;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.assemblylib.impl.client.renderer.assembly.AssemblyRenderWorld;
import com.assemblylib.impl.assembly.Assembly;
import com.assemblylib.impl.assembly.AssemblyBlockGetter;
import com.assemblylib.api.AssemblyHost;
import com.assemblylib.impl.assembly.AssemblyGroundEntity;
import com.assemblylib.impl.assembly.AssemblyHosts;
import com.assemblylib.impl.assembly.AssemblyPath;
import com.assemblylib.impl.assembly.AssemblyPlacementUtil;
import com.assemblylib.impl.assembly.AssemblyPlaceContext;
import com.assemblylib.impl.assembly.AssemblySimLevel;
import com.assemblylib.impl.assembly.AssemblyTransform;
import com.assemblylib.impl.assembly.collision.AssemblyCollider;
import com.assemblylib.impl.assembly.util.AssemblyMath;
import com.assemblylib.impl.networking.AssemblyBreakC2SPacket;
import com.assemblylib.impl.networking.AssemblyBreakProgressC2SPacket;
import com.assemblylib.impl.networking.AssemblyPlaceC2SPacket;
import com.assemblylib.impl.networking.AssemblyLibPackets;
import com.assemblylib.impl.mixin.MinecraftAccessor;
import com.assemblylib.impl.mixin.MultiPlayerGameModeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client-side targeting, input handling and rendering for breaking and placing
 * blocks on an assembled assembly. The player's look ray is transformed into
 * assembly-local space and clipped against the captured blocks; if a hit is
 * nearer than the world target, vanilla interaction is suppressed (via
 * {@link com.assemblylib.impl.mixin.MinecraftMixin}) and routed here instead, while the
 * selection box and breaking crack are drawn under the assembly's pose.
 */
public final class AssemblyInteractionClient {

	private AssemblyInteractionClient() {}

	/** Cooldown ticks after a break/place, mirroring vanilla's destroy/use delays. */
	private static final int ACTION_DELAY = 5;
	private static final int OUTLINE_ARGB_ALPHA = 102; // ~0.4

	private record Hit(AssemblyHost motor, BlockPos localPos, Direction localFace, Vec3 localHit,
		Vec3 worldHit, AssemblyTransform transform) {}

	private record RemoteBreak(AssemblyPath path, BlockPos localPos, int stage) {}

	private static Hit currentHit;

	private static AssemblyHost breakMotor;
	private static BlockPos breakLocal;
	private static float destroyProgress;
	private static int lastSentStage = -1;

	private static final Map<Integer, RemoteBreak> remoteBreaks = new HashMap<>();

	// The assembly the local player is currently standing on (Y-axis only), and the
	// platform's interpolated angle last frame, so we can turn the player exactly in step
	// with the rendered rotation each frame (smooth; a per-tick turn would step at 20 Hz).
	private static AssemblyHost ridingMotor;
	private static float lastPlatformAngle;

	// region input (called from MinecraftMixin)

	public static boolean hasTarget() {
		return currentHit != null;
	}

	public static Vec3 maybeBackOffFromAssemblyEdge(Player player, Vec3 movement, float maxUpStep) {
		if (player == null || player.level() == null || AssemblyHosts.ACTIVE_CLIENT.isEmpty())
			return null;
		AssemblyHost supportMotor = findSupportingAssembly(player, maxUpStep);
		if (supportMotor == null)
			return null;

		AssemblyTransform support = AssemblyTransform.ofCurrent(supportMotor);
		float yaw = support.hasVerticalRotation() ? 0.0F : support.yawDegrees();
		Vec3 localMovement = AssemblyMath.rotate(new Vec3(movement.x, 0.0D, movement.z), -yaw, Direction.Axis.Y);
		double xMovement = localMovement.x;
		double zMovement = localMovement.z;
		double step = 0.05D;
		double signedXStep = Math.signum(xMovement) * step;
		double signedZStep = Math.signum(zMovement) * step;

		while (xMovement != 0.0D && wouldSlideOff(player, xMovement, 0.0D, maxUpStep, yaw)) {
			if (Math.abs(xMovement) <= step) {
				xMovement = 0.0D;
				break;
			}
			xMovement -= signedXStep;
		}
		while (zMovement != 0.0D && wouldSlideOff(player, 0.0D, zMovement, maxUpStep, yaw)) {
			if (Math.abs(zMovement) <= step) {
				zMovement = 0.0D;
				break;
			}
			zMovement -= signedZStep;
		}
		while (xMovement != 0.0D && zMovement != 0.0D && wouldSlideOff(player, xMovement, zMovement, maxUpStep, yaw)) {
			if (Math.abs(xMovement) <= step)
				xMovement = 0.0D;
			else
				xMovement -= signedXStep;
			if (Math.abs(zMovement) <= step)
				zMovement = 0.0D;
			else
				zMovement -= signedZStep;
		}

		Vec3 worldMovement = AssemblyMath.rotate(new Vec3(xMovement, 0.0D, zMovement), yaw, Direction.Axis.Y);
		return new Vec3(worldMovement.x, movement.y, worldMovement.z);
	}

	public static boolean isAboveAssemblyGround(Player player, float maxUpStep) {
		return player != null && player.level() != null && findSupportingAssembly(player, maxUpStep) != null;
	}

	private static boolean wouldSlideOff(Player player, double localXMovement, double localZMovement, float fallDistance,
		float yaw) {
		Vec3 worldMovement = AssemblyMath.rotate(new Vec3(localXMovement, 0.0D, localZMovement), yaw,
			Direction.Axis.Y);
		AABB supportBox = supportBox(player, worldMovement.x, worldMovement.z, fallDistance);
		return player.level().noCollision(player, supportBox) && !intersectsAnyAssembly(player, supportBox);
	}

	private static AssemblyHost findSupportingAssembly(Player player, float fallDistance) {
		AABB supportBox = supportBox(player, 0.0D, 0.0D, fallDistance);
		for (AssemblyHost motor : AssemblyHosts.ACTIVE_CLIENT) {
			if (motor.assemblyLevel() != player.level())
				continue;
			Assembly assembly = motor.getAssembly();
			if (assembly == null || assembly.isEmpty())
				continue;
			AssemblyTransform transform = AssemblyTransform.ofCurrent(motor);
			AABB localBox = worldBoxToLocalAabb(shrinkSupportBox(supportBox), transform);
			if (AssemblyCollider.intersectsAssembly(player.level(), assembly, localBox,
				transform.worldToLocalRotationNoWorldYaw()))
				return motor;
		}
		return null;
	}

	private static AABB supportBox(Player player, double xMovement, double zMovement, float fallDistance) {
		AABB bounds = player.getBoundingBox();
		return new AABB(bounds.minX + xMovement, bounds.minY - (double) fallDistance - 1.0E-5D,
			bounds.minZ + zMovement, bounds.maxX + xMovement, bounds.minY, bounds.maxZ + zMovement);
	}

	private static AABB shrinkSupportBox(AABB supportBox) {
		return supportBox.getXsize() > 0.1D && supportBox.getZsize() > 0.1D
			? supportBox.inflate(-0.05D, 0.0D, -0.05D)
			: supportBox;
	}

	private static boolean intersectsAnyAssembly(Player player, AABB worldBox) {
		for (AssemblyHost motor : AssemblyHosts.ACTIVE_CLIENT) {
			if (motor.assemblyLevel() != player.level())
				continue;
			Assembly assembly = motor.getAssembly();
			if (assembly == null || assembly.isEmpty())
				continue;
			AssemblyTransform transform = AssemblyTransform.ofCurrent(motor);
			AABB localBox = worldBoxToLocalAabb(shrinkSupportBox(worldBox), transform);
			if (AssemblyCollider.intersectsAssembly(player.level(), assembly, localBox,
				transform.worldToLocalRotationNoWorldYaw()))
				return true;
		}
		return false;
	}

	private static AABB worldBoxToLocalAabb(AABB worldBox, AssemblyTransform transform) {
		Vec3 localCenter = transform.worldToLocal(worldBox.getCenter());
		return new AABB(localCenter, localCenter)
			.inflate(worldBox.getXsize() / 2.0D, worldBox.getYsize() / 2.0D, worldBox.getZsize() / 2.0D);
	}

	public static boolean handleStartAttack() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		pick(partialTick());
		if (currentHit == null) return false;
		// A fresh click acts immediately, mirroring vanilla's startAttack (only the held
		// path below is throttled by the shared vanilla mining cooldown).
		progressMining(true);
		mc.player.swing(InteractionHand.MAIN_HAND);
		return true;
	}

	public static boolean handleContinueAttack(boolean leftDown) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		pick(partialTick());
		if (!leftDown || currentHit == null) {
			resetBreak();
			return false;
		}
		progressMining(false);
		mc.player.swing(InteractionHand.MAIN_HAND);
		return true;
	}

	public static boolean handleStartUseItem() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) return false;
		pick(partialTick());
		if (currentHit == null) return false;

		InteractionHand hand = null;
		if (player.getMainHandItem().getItem() instanceof BlockItem) hand = InteractionHand.MAIN_HAND;
		else if (player.getOffhandItem().getItem() instanceof BlockItem) hand = InteractionHand.OFF_HAND;
		// Not holding a placeable block: assembly blocks can't be used/activated, so let vanilla handle
		// the right-click normally.
		if (hand == null)
			return false;

		// Predict the placement locally so the block appears immediately; the authoritative server sync
		// replaces it a round-trip later (and corrects it if the server disagrees). Block-entity blocks
		// (chests, …) are NOT predicted: the lightweight client sim can't reproduce the server's
		// block-entity/neighbour side-effects (e.g. a chest pairing flips the adjacent chest's TYPE), so a
		// predicted state would render a wrong transient until the sync lands. Just send and let the
		// authoritative sync show it.
		boolean placingBlockEntity = ((BlockItem) player.getItemInHand(hand).getItem()).getBlock() instanceof EntityBlock;
		if (!placingBlockEntity && !predictPlacement(player, currentHit, hand))
			return true;
		AssemblyLibPackets.sendToServer(new AssemblyPlaceC2SPacket(AssemblyPath.of(currentHit.motor()),
			currentHit.localPos(), currentHit.localFace(), currentHit.localHit(), hand));
		player.swing(hand);
		// Throttle only the held auto-repeat: vanilla re-fires startUseItem when
		// rightClickDelay hits 0, while a fresh keyUse.consumeClick() bypasses it.
		((MinecraftAccessor) mc).setRightClickDelay(ACTION_DELAY);
		return true;
	}

	/**
	 * Middle-click pick-block on a assembly: resolve the targeted block to its item
	 * and select/add it just like vanilla {@code Minecraft.pickBlock}. Returns false when
	 * no assembly block is targeted so vanilla can pick the world block instead.
	 */
	public static boolean handlePickBlock() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) return false;
		pick(partialTick());
		if (currentHit == null) return false;

		Assembly assembly = currentHit.motor().getAssembly();
		if (assembly == null) return false;
		StructureBlockInfo info = assembly.getBlocks().get(currentHit.localPos());
		if (info == null) return false;
		BlockState state = info.state();
		if (state.isAir()) return false;

		boolean creative = player.getAbilities().instabuild;
		AssemblySimLevel sim = new AssemblySimLevel(mc.level, assembly);
		ItemStack stack = state.getBlock().getCloneItemStack(sim, currentHit.localPos(), state);
		if (stack.isEmpty()) return false;

		// Ctrl+pick in creative copies the block entity data, mirroring vanilla.
		if (creative && Screen.hasControlDown() && state.hasBlockEntity() && info.nbt() != null) {
			BlockEntity be = BlockEntity.loadStatic(currentHit.localPos(), state, info.nbt(), mc.level.registryAccess());
			if (be != null) addCustomNbtData(stack, be, mc.level);
		}

		Inventory inventory = player.getInventory();
		int slot = inventory.findSlotMatchingItem(stack);
		if (creative) {
			inventory.setPickedItem(stack);
			mc.gameMode.handleCreativeModeItemAdd(player.getItemInHand(InteractionHand.MAIN_HAND), 36 + inventory.selected);
		} else if (slot != -1) {
			if (Inventory.isHotbarSlot(slot)) inventory.selected = slot;
			else mc.gameMode.handlePickItem(slot);
		}
		return true;
	}

	/** Inlined copy of the private {@code Minecraft.addCustomNbtData} (block entity → item). */
	private static void addCustomNbtData(ItemStack stack, BlockEntity blockEntity, net.minecraft.world.level.Level level) {
		CompoundTag tag = blockEntity.saveCustomAndMetadata(level.registryAccess());
		blockEntity.removeComponentsFromTag(tag);
		BlockItem.setBlockEntityData(stack, blockEntity.getType(), tag);
		stack.applyComponents(blockEntity.collectComponents());
	}

	private static boolean predictPlacement(LocalPlayer player, Hit hit, InteractionHand hand) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return false;
		AssemblyHost motor = hit.motor();
		Assembly assembly = motor.getAssembly();
		if (assembly == null) return false;

		ItemStack stack = player.getItemInHand(hand);
		AssemblyTransform transform = AssemblyTransform.ofCurrent(motor);
		Assembly predicted = assembly.copy();
		AssemblySimLevel sim = new AssemblySimLevel(mc.level, predicted);
		AssemblyPlaceContext.Placed placed = AssemblyPlaceContext.resolve(sim, player, hand, stack,
			hit.localPos(), hit.localFace(), hit.localHit(), transform);
		if (placed == null) return false;
		if (!AssemblyPlacementUtil.isUnobstructed(mc.level, sim, player, placed.pos(), placed.state(), transform))
			return false;

		if (!AssemblyPlacementUtil.placeBlock(mc.level, sim, predicted, player, stack, placed))
			return false;
		motor.getAssemblyController().setAssemblyClient(predicted);
		return true;
	}

	private static void predictBreak(AssemblyHost motor, BlockPos local) {
		Assembly assembly = motor.getAssembly();
		if (assembly == null) return;
		Assembly predicted = assembly.copy();
		predicted.removeBlock(local);
		motor.getAssemblyController().setAssemblyClient(predicted);
	}

	private static void progressMining(boolean fresh) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || currentHit == null || mc.gameMode == null) {
			resetBreak();
			return;
		}
		// Share vanilla's held-mining cooldown so assembly and world breaks throttle each other
		// exactly like two regular blocks would: a held break waits ACTION_DELAY ticks after any
		// break, a fresh click always acts. We count it down here because while we're handling the
		// assembly vanilla's continueDestroyBlock (which normally decrements it) is suppressed.
		MultiPlayerGameModeAccessor gameMode = (MultiPlayerGameModeAccessor) mc.gameMode;
		if (!fresh) {
			int delay = gameMode.getDestroyDelay();
			if (delay > 0) {
				gameMode.setDestroyDelay(delay - 1);
				return;
			}
		}

		AssemblyHost motor = currentHit.motor();
		BlockPos local = currentHit.localPos();
		Assembly assembly = motor.getAssembly();
		if (assembly == null) {
			resetBreak();
			return;
		}
		StructureBlockInfo info = assembly.getBlocks().get(local);
		if (info == null) {
			resetBreak();
			return;
		}

		if (motor != breakMotor || !local.equals(breakLocal)) {
			resetBreak();
			breakMotor = motor;
			breakLocal = local;
		}

		if (player.getAbilities().instabuild) {
			sendBreak(motor, local);
			predictBreak(motor, local);
			gameMode.setDestroyDelay(ACTION_DELAY);
			resetBreak();
			return;
		}

		destroyProgress += info.state().getDestroyProgress(player, new AssemblyBlockGetter(assembly), local);
		int stage = Math.min(9, (int) (destroyProgress * 10.0f));
		if (stage != lastSentStage) {
			lastSentStage = stage;
			AssemblyLibPackets.sendToServer(new AssemblyBreakProgressC2SPacket(AssemblyPath.of(motor), local, stage));
		}
		if (destroyProgress >= 1.0f) {
			sendBreak(motor, local);
			predictBreak(motor, local);
			gameMode.setDestroyDelay(ACTION_DELAY);
			resetBreak();
		}
	}

	private static void sendBreak(AssemblyHost motor, BlockPos local) {
		AssemblyLibPackets.sendToServer(new AssemblyBreakC2SPacket(AssemblyPath.of(motor), local));
	}

	private static void resetBreak() {
		if (breakMotor != null && breakLocal != null && lastSentStage >= 0)
			AssemblyLibPackets.sendToServer(new AssemblyBreakProgressC2SPacket(AssemblyPath.of(breakMotor), breakLocal, -1));
		breakMotor = null;
		breakLocal = null;
		destroyProgress = 0;
		lastSentStage = -1;
	}

	// endregion

	// region events

	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			currentHit = null;
			remoteBreaks.clear();
			breakMotor = null;
			breakLocal = null;
			destroyProgress = 0;
			lastSentStage = -1;
			return;
		}
		// Resolve the local player against every assembled assembly (client drives
		// this because player movement is client-authoritative).
		if (mc.player != null) {
			// Recomputed by the collision sweep below; clear it first so stepping off an assembly
			// (no surface collision this tick) correctly reverts to vanilla onGround behaviour.
			((AssemblyGroundEntity) mc.player).zps$setOnAssemblyGround(false);
			for (AssemblyHost be : AssemblyHosts.ACTIVE_CLIENT) {
				if (inClientWorld(be, mc.level))
					be.getAssemblyController().collideWithPlayer(mc.player);
			}
			// Vanilla stops creative flight on landing in LocalPlayer#aiStep, but that runs before our
			// collision (which sets onGround) and is undone next tick when vanilla move() clears onGround
			// — the assembly isn't a real block. Re-apply the same stop here once we've landed on one.
			if (mc.player.onGround() && mc.player.getAbilities().flying && !mc.gameMode.isAlwaysFlying()) {
				mc.player.getAbilities().flying = false;
				mc.player.onUpdateAbilities();
			}
		}
	}

	/** Whether {@code be} belongs to {@code clientLevel}. */
	private static boolean inClientWorld(AssemblyHost be, net.minecraft.world.level.Level clientLevel) {
		return be.assemblyLevel() == clientLevel;
	}

	/**
	 * Per-frame: if the local player is standing on a assembly spinning about Y, turn
	 * their look to follow it, tracking the platform's interpolated angle exactly so the
	 * camera stays in lockstep with the rendered rotation (frame-perfect, no 20 Hz step).
	 */
	public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			ridingMotor = null;
			return;
		}
		float partialTick = (float) event.getPartialTick();
		AssemblyHost motor = findRidingMotor(player, partialTick);
		if (motor == null) {
			ridingMotor = null;
			return;
		}

		float current = AssemblyTransform.ofInterpolated(motor, partialTick).yawDegrees();
		if (motor != ridingMotor) {
			// Just stepped on: establish the baseline without an initial jump.
			ridingMotor = motor;
			lastPlatformAngle = current;
			return;
		}

		float platformDelta = AssemblyMath.getShortestAngleDiff(lastPlatformAngle, current);
		lastPlatformAngle = current;
		if (platformDelta == 0)
			return;

		// Platform turns +Z toward +X (AssemblyMath); Minecraft yaw runs the other way.
		float yawDelta = -platformDelta;
		float newYaw = player.getYRot() + yawDelta;
		player.setYRot(newYaw);
		player.yRotO = newYaw;
		player.setYHeadRot(player.getYHeadRot() + yawDelta);
		player.yHeadRotO = player.getYHeadRot();
		player.yBodyRot += yawDelta;
		player.yBodyRotO = player.yBodyRot;
		event.setYaw(newYaw);
	}

	@org.jetbrains.annotations.Nullable
	private static AssemblyHost findRidingMotor(LocalPlayer player, float partialTick) {
		for (AssemblyHost be : AssemblyHosts.ACTIVE_CLIENT) {
			if (be.assemblyLevel() != player.level())
				continue;
			Assembly assembly = be.getAssembly();
			if (assembly == null || assembly.isEmpty())
				continue;
			AssemblyTransform transform = AssemblyTransform.ofInterpolated(be, partialTick);
			// Only a (horizontal) Y-axis turntable turns the rider; a tilted platform does not.
			if (transform.hasVerticalRotation())
				continue;
			// Block supporting the player's feet, in assembly-local space.
			Vec3 local = transform.worldToLocal(player.position());
			BlockPos support = BlockPos.containing(local.x, local.y - 0.05, local.z);
			StructureBlockInfo info = assembly.getBlocks().get(support);
			if (info != null
				&& !info.state().getCollisionShape(new AssemblyBlockGetter(assembly), support).isEmpty())
				return be;
		}
		return null;
	}

	public static void onRenderHighlight(RenderHighlightEvent.Block event) {
		// Our assembly block is the nearer target: suppress the wrong world outline.
		if (currentHit != null)
			event.setCanceled(true);
	}

	public static void onRemoteDestroyStage(AssemblyPath path, BlockPos localPos, int breakerId, int stage) {
		if (stage < 0) remoteBreaks.remove(breakerId);
		else remoteBreaks.put(breakerId, new RemoteBreak(path, localPos, stage));
	}

	public static void onRenderLevelStage(RenderLevelStageEvent event) {
		RenderLevelStageEvent.Stage stage = event.getStage();
		if (stage == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
			pick(event.getPartialTick().getGameTimeDeltaPartialTick(false));
			return;
		}
		if (stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Vec3 cam = event.getCamera().getPosition();
		PoseStack pose = event.getPoseStack();

		if (currentHit != null)
			renderOutline(pose, mc.renderBuffers().bufferSource(), cam, currentHit);

		boolean anyCrack = (breakMotor != null && breakLocal != null && destroyProgress > 0) || !remoteBreaks.isEmpty();
		if (!anyCrack) return;

		MultiBufferSource.BufferSource crumbling = mc.renderBuffers().crumblingBufferSource();
		if (breakMotor != null && breakLocal != null && destroyProgress > 0)
			renderCrack(pose, cam, breakMotor, breakLocal, Math.min(9, (int) (destroyProgress * 10f)), crumbling);
		for (RemoteBreak rb : remoteBreaks.values()) {
			if (rb.stage() < 0) continue;
			AssemblyHost m = rb.path().resolve(mc.level);
			if (m != null && m.getAssembly() != null)
				renderCrack(pose, cam, m, rb.localPos(), Math.min(9, rb.stage()), crumbling);
		}
		crumbling.endBatch();
	}

	// endregion

	// region raytrace + rendering

	private static float partialTick() {
		return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
	}

	private static void pick(float partialTick) {
		currentHit = null;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || AssemblyHosts.ACTIVE_CLIENT.isEmpty()) return;

		double reach = player.blockInteractionRange();
		Vec3 eye = player.getEyePosition(partialTick);
		Vec3 look = player.getViewVector(partialTick);
		Vec3 end = eye.add(look.x * reach, look.y * reach, look.z * reach);

		double bestDist = reach;
		if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS)
			bestDist = mc.hitResult.getLocation().distanceTo(eye);

		Hit best = null;
		for (AssemblyHost be : AssemblyHosts.ACTIVE_CLIENT) {
			if (!inClientWorld(be, mc.level)) continue;
			Assembly assembly = be.getAssembly();
			if (assembly == null || assembly.isEmpty()) continue;

			AssemblyTransform transform = AssemblyTransform.ofInterpolated(be, partialTick);
			Vec3 localStart = transform.worldToLocal(eye);
			Vec3 localEnd = transform.worldToLocal(end);
			BlockHitResult hit = new AssemblyBlockGetter(assembly).clip(
				new ClipContext(localStart, localEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
			if (hit.getType() == HitResult.Type.MISS) continue;

			Vec3 worldHit = transform.localToWorld(hit.getLocation());
			double dist = worldHit.distanceTo(eye);
			if (dist < bestDist) {
				bestDist = dist;
				best = new Hit(be, hit.getBlockPos(), hit.getDirection(), hit.getLocation(), worldHit, transform);
			}
		}
		currentHit = best;
	}

	private static void renderOutline(PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 cam, Hit hit) {
		Assembly assembly = hit.motor().getAssembly();
		if (assembly == null) return;
		StructureBlockInfo info = assembly.getBlocks().get(hit.localPos());
		if (info == null) return;
		VoxelShape shape = info.state().getShape(new AssemblyBlockGetter(assembly), hit.localPos());
		if (shape.isEmpty()) return;

		pose.pushPose();
		pose.mulPose(hit.transform().renderPose(cam));
		pose.translate(hit.localPos().getX(), hit.localPos().getY(), hit.localPos().getZ());
		VertexConsumer vc = buffers.getBuffer(RenderType.lines());
		PoseStack.Pose last = pose.last();
		shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
			float dx = (float) (x2 - x1);
			float dy = (float) (y2 - y1);
			float dz = (float) (z2 - z1);
			float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
			if (len < 1.0e-5f) return;
			dx /= len;
			dy /= len;
			dz /= len;
			vc.addVertex(last, (float) x1, (float) y1, (float) z1).setColor(0, 0, 0, OUTLINE_ARGB_ALPHA)
				.setNormal(last, dx, dy, dz);
			vc.addVertex(last, (float) x2, (float) y2, (float) z2).setColor(0, 0, 0, OUTLINE_ARGB_ALPHA)
				.setNormal(last, dx, dy, dz);
		});
		pose.popPose();
		buffers.endBatch(RenderType.lines());
	}

	private static void renderCrack(PoseStack pose, Vec3 cam, AssemblyHost motor, BlockPos local, int stage,
		MultiBufferSource.BufferSource crumbling) {
		Assembly assembly = motor.getAssembly();
		if (assembly == null) return;
		StructureBlockInfo info = assembly.getBlocks().get(local);
		if (info == null) return;

		AssemblyTransform transform = AssemblyTransform.ofInterpolated(motor, partialTick());
		pose.pushPose();
		pose.mulPose(transform.renderPose(cam));
		pose.translate(local.getX(), local.getY(), local.getZ());
		RenderType crackType = ModelBakery.DESTROY_TYPES.get(stage);
		VertexConsumer vc = new SheetedDecalTextureGenerator(crumbling.getBuffer(crackType), pose.last(), 1.0f);
		Minecraft.getInstance().getBlockRenderer().renderBreakingTexture(info.state(), local,
			new AssemblyRenderWorld(motor.assemblyLevel(), assembly), pose, vc);
		pose.popPose();
	}

	// endregion
}
