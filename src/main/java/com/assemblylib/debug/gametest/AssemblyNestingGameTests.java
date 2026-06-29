package com.assemblylib.debug.gametest;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.assemblylib.AssemblyLib;
import com.assemblylib.api.AssemblyHost;
import com.assemblylib.debug.block.ModBlocks;
import com.assemblylib.debug.block.ServoMotorBlock;
import com.assemblylib.debug.blockentity.ServoMotorBlockEntity;
import com.assemblylib.debug.entity.AssemblyHostEntity;
import com.assemblylib.debug.entity.ModEntities;
import com.assemblylib.impl.assembly.Assembly;
import com.assemblylib.impl.assembly.AssemblyPath;
import com.assemblylib.impl.assembly.AssemblyRotatedEntity;
import com.assemblylib.impl.assembly.AssemblySimServerLevel;
import com.assemblylib.impl.assembly.AssemblyTransform;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Tests for nested assemblys (a Servo Motor mounted on another assembly). Phase A covers the
 * {@link AssemblyTransform} composition algebra that everything else builds on: a nested
 * transform must map points exactly as applying the inner then the parent transform, and remain
 * invertible (so collision/raytrace round-trips hold for arbitrary axis combinations).
 */
@GameTestHolder(AssemblyLib.MOD_ID)
@PrefixGameTestTemplate(false)
public class AssemblyNestingGameTests {

	private static final String TEMPLATE = "gametest/flat_7x4x7";

	private static boolean near(Vec3 a, Vec3 b) {
		return a.distanceTo(b) < 1e-4;
	}

	/**
	 * {@code inner.andThen(parent)} must equal first applying {@code inner} (into the parent's local
	 * space) and then {@code parent} (into world) — for several points and a mix of rotation axes.
	 */
	@GameTest(template = TEMPLATE)
	public static void composedTransformMatchesManualComposition(GameTestHelper helper) {
		AssemblyTransform parent = AssemblyTransform.leaf(new Vec3(10, 5, 10), 30f, Direction.Axis.Y);
		AssemblyTransform inner = AssemblyTransform.leaf(new Vec3(2, 0, 0), 45f, Direction.Axis.X);
		AssemblyTransform composed = inner.andThen(parent);

		for (Vec3 p : new Vec3[] { new Vec3(0, 0, 0), new Vec3(1.3, 0.7, -0.4), new Vec3(-2, 3, 1.5) }) {
			Vec3 manual = parent.localToWorld(inner.localToWorld(p));
			if (!near(composed.localToWorld(p), manual)) {
				helper.fail("Composed localToWorld " + composed.localToWorld(p) + " != manual " + manual + " for " + p);
				return;
			}
		}
		helper.succeed();
	}

	/** A composed (nested) transform must round-trip: {@code worldToLocal(localToWorld(p)) == p}. */
	@GameTest(template = TEMPLATE)
	public static void composedTransformRoundTrips(GameTestHelper helper) {
		AssemblyTransform composed = AssemblyTransform.leaf(new Vec3(7, 2, -3), 90f, Direction.Axis.Z)
			.andThen(AssemblyTransform.leaf(new Vec3(10, 5, 10), 30f, Direction.Axis.Y));

		Vec3 p = new Vec3(1.25, -0.5, 2.75);
		Vec3 back = composed.worldToLocal(composed.localToWorld(p));
		if (!near(back, p)) {
			helper.fail("Round-trip failed: " + back + " != " + p);
			return;
		}
		// Direction round-trip too (rotation only), since collision/raytrace rotate normals.
		Vec3 d = new Vec3(0.2, 0.8, -0.5);
		if (!near(composed.worldDirToLocal(composed.localDirToWorld(d)), d)) {
			helper.fail("Direction round-trip failed");
			return;
		}
		helper.succeed();
	}

	/**
	 * A pure Y-axis turntable reports no vertical rotation; composing a tilt (X/Z) onto it does. Guards
	 * the collider's "is the platform tilted" decision that replaces {@code AssemblyRotationState}.
	 */
	@GameTest(template = TEMPLATE)
	public static void verticalRotationDetection(GameTestHelper helper) {
		AssemblyTransform flat = AssemblyTransform.leaf(Vec3.ZERO, 47f, Direction.Axis.Y);
		AssemblyTransform tilted = AssemblyTransform.leaf(Vec3.ZERO, 20f, Direction.Axis.X)
			.andThen(AssemblyTransform.leaf(Vec3.ZERO, 47f, Direction.Axis.Y));
		if (flat.hasVerticalRotation()) {
			helper.fail("A Y-axis turntable should report no vertical rotation");
			return;
		}
		if (!tilted.hasVerticalRotation()) {
			helper.fail("A composed X-tilt should report vertical rotation");
			return;
		}
		helper.succeed();
	}

	/**
	 * A platform point on a nested assembly that is <em>not</em> spinning is still carried by the
	 * parent's rotation: differencing the composed current/next-tick poses yields a non-zero velocity.
	 * This is the core of "collisions + carried motion" for nested assemblys.
	 */
	@GameTest(template = TEMPLATE)
	public static void carriedVelocityIncludesParentSpin(GameTestHelper helper) {
		AssemblyTransform parentNow = AssemblyTransform.leaf(new Vec3(10, 5, 10), 0f, Direction.Axis.Y);
		AssemblyTransform parentNext = AssemblyTransform.leaf(new Vec3(10, 5, 10), 2f, Direction.Axis.Y);
		AssemblyTransform nested = AssemblyTransform.leaf(new Vec3(3, 0, 0), 0f, Direction.Axis.Y);

		AssemblyTransform now = nested.andThen(parentNow);
		AssemblyTransform next = nested.andThen(parentNext);
		Vec3 p = new Vec3(1, 0, 0); // a point fixed on the (stationary) nested platform
		Vec3 carried = next.localToWorld(p).subtract(now.localToWorld(p));
		if (carried.length() < 1e-3) {
			helper.fail("A non-spinning nested platform point should still be carried by the parent's spin");
			return;
		}
		helper.succeed();
	}

	/**
	 * The quaternion handed to a detached falling block (a nested assembly's composed orientation)
	 * rotates vectors identically to the transform's local-&gt;world rotation matrix.
	 */
	@GameTest(template = TEMPLATE)
	public static void composedRotationQuaternionMatchesMatrix(GameTestHelper helper) {
		AssemblyTransform t = AssemblyTransform.leaf(Vec3.ZERO, 35f, Direction.Axis.Z)
			.andThen(AssemblyTransform.leaf(Vec3.ZERO, 50f, Direction.Axis.Y));
		Quaternionf q = t.localToWorldRotationQuat();
		Vec3 d = new Vec3(0.3, 0.6, -0.4);
		Vector3f viaQuat = q.transform(new Vector3f((float) d.x, (float) d.y, (float) d.z));
		Vec3 viaMatrix = t.localDirToWorld(d);
		Vec3 quat = new Vec3(viaQuat.x, viaQuat.y, viaQuat.z);
		if (quat.distanceTo(viaMatrix) > 1e-4) {
			helper.fail("Quaternion rotation " + quat + " != matrix rotation " + viaMatrix);
			return;
		}
		helper.succeed();
	}

	private static final BlockPos MOTOR_POS = new BlockPos(2, 2, 2);

	/**
	 * A Servo Motor placed on another assembly is reconstructed + ticked as a live nested motor;
	 * it discovers its host, and a {@link AssemblyPath} round-trips (built by walking up, resolved
	 * by walking down) to the same live instance — the identity mechanism interaction packets rely on.
	 */
	@GameTest(template = TEMPLATE)
	public static void nestedMotorPathResolves(GameTestHelper helper) {
		ServerLevel level = (ServerLevel) helper.getLevel();
		helper.setBlock(MOTOR_POS, ModBlocks.SERVO_MOTOR.get().defaultBlockState()
			.setValue(ServoMotorBlock.FACING, Direction.EAST));
		ServoMotorBlockEntity parent = (ServoMotorBlockEntity) helper.getBlockEntity(MOTOR_POS);
		parent.initAssembly();

		// A servo motor sitting on the parent assembly (a nested motor).
		Assembly pc = parent.getAssembly();
		BlockPos nestedCell = new BlockPos(1, 0, 0);
		pc.putBlock(nestedCell, ModBlocks.SERVO_MOTOR.get().defaultBlockState()
			.setValue(ServoMotorBlock.FACING, Direction.UP), null, null);

		// Ticking the parent reconstructs the nested motor as a live block entity and ticks it, so it
		// seeds its own assembly (head).
		parent.serverTick();

		ServoMotorBlockEntity nested = parent.getNestedMotor(nestedCell);
		if (nested == null) {
			helper.fail("Nested motor should be reconstructed as a live block entity");
			return;
		}
		if (nested.hostMotor() != parent) {
			helper.fail("Nested motor's host should be the parent motor");
			return;
		}
		if (nested.getAssembly() == null) {
			helper.fail("Nested motor should have initialised its own assembly");
			return;
		}

		AssemblyPath path = AssemblyPath.of(nested);
		boolean rootMatches = path.root() instanceof AssemblyPath.BlockPosRoot root
			&& root.pos().equals(parent.getBlockPos());
		if (!rootMatches || path.nestedCells().size() != 1
			|| !path.nestedCells().get(0).equals(nestedCell)) {
			helper.fail("Path should be (parentPos, [nestedCell]) but was " + path);
			return;
		}
		if (path.resolve(level) != nested) {
			helper.fail("Resolving the path should return the same live nested motor instance");
			return;
		}
		helper.succeed();
	}

	/**
	 * A nested motor's own assembly must reach the client, or the player can't target or build on it.
	 * The client rebuilds a nested host purely from the per-cell update tag embedded in its parent's
	 * structure sync ({@code AssemblyRenderState#reconstruct} -> {@code handleUpdateTag}); the dedicated
	 * assembly-sync channel only reaches root hosts. Regression guard: the nested cell's update tag must
	 * carry the nested assembly, so a BE rebuilt from it alone comes back with its structure intact.
	 */
	@GameTest(template = TEMPLATE)
	public static void nestedMotorAssemblyReachesClientViaUpdateTag(GameTestHelper helper) {
		ServerLevel level = (ServerLevel) helper.getLevel();
		helper.setBlock(MOTOR_POS, ModBlocks.SERVO_MOTOR.get().defaultBlockState()
			.setValue(ServoMotorBlock.FACING, Direction.EAST));
		ServoMotorBlockEntity parent = (ServoMotorBlockEntity) helper.getBlockEntity(MOTOR_POS);
		parent.initAssembly();

		BlockPos nestedCell = new BlockPos(1, 0, 0);
		parent.getAssembly().putBlock(nestedCell, ModBlocks.SERVO_MOTOR.get().defaultBlockState()
			.setValue(ServoMotorBlock.FACING, Direction.UP), null, null);

		// Tick the parent so the nested motor becomes a live BE and seeds its own (head) assembly.
		parent.serverTick();
		ServoMotorBlockEntity nested = parent.getNestedMotor(nestedCell);
		if (nested == null || nested.getAssembly() == null) {
			helper.fail("Nested motor should be live with its own assembly");
			return;
		}
		int nestedBlocks = nested.getAssembly().getBlocks().size();

		// Serialize the parent the way a save/sync does, then round-trip it back as a client would: the
		// nested cell's update tag is what the client reconstructs the nested motor from.
		CompoundTag parentState = new CompoundTag();
		parent.getAssemblyController().writeState(parentState, level.registryAccess());
		Assembly parentSynced = new Assembly();
		parentSynced.readNBT(level.registryAccess(), parentState.getCompound("Assembly"), level.getGameTime());
		CompoundTag nestedUpdateTag = parentSynced.getUpdateTags().get(nestedCell);
		if (nestedUpdateTag == null) {
			helper.fail("Parent's synced structure should carry a client update tag for the nested motor cell");
			return;
		}

		// Mirror the client: rebuild a fresh BE from only that update tag.
		ServoMotorBlockEntity rebuilt = new ServoMotorBlockEntity(nestedCell,
			ModBlocks.SERVO_MOTOR.get().defaultBlockState().setValue(ServoMotorBlock.FACING, Direction.UP));
		rebuilt.handleUpdateTag(nestedUpdateTag, level.registryAccess());

		if (rebuilt.getAssembly() == null) {
			helper.fail("Rebuilt nested motor should have its assembly from the update tag (client can't target it otherwise)");
			return;
		}
		if (rebuilt.getAssembly().getBlocks().size() != nestedBlocks) {
			helper.fail("Rebuilt nested assembly should match the live nested assembly (" + nestedBlocks
				+ " blocks), got " + rebuilt.getAssembly().getBlocks().size());
			return;
		}
		helper.succeed();
	}

	/**
	 * Same as above but with an <em>entity</em> root host. The server resolves a nested host's parent
	 * through its sim level; that lookup must work for an entity host (which has no block entity at its
	 * position), or the nested host never discovers its parent and its assembly is never embedded into
	 * the parent's client update tag — leaving it untargetable on the client. Regression guard for the
	 * entity-root nesting path.
	 */
	@GameTest(template = TEMPLATE)
	public static void nestedMotorOnEntityRootReachesClientViaUpdateTag(GameTestHelper helper) {
		ServerLevel level = (ServerLevel) helper.getLevel();
		AssemblyHostEntity entity = helper.spawn(ModEntities.ASSEMBLY_HOST.get(), new BlockPos(3, 2, 3));
		if (entity.getAssembly() == null) {
			helper.fail("Entity host should have seeded its assembly on spawn");
			return;
		}

		BlockPos nestedCell = new BlockPos(0, 1, 1);
		entity.getAssembly().putBlock(nestedCell, ModBlocks.SERVO_MOTOR.get().defaultBlockState()
			.setValue(ServoMotorBlock.FACING, Direction.UP), null, null);

		// Tick the entity so the nested motor becomes a live BE and seeds its own assembly.
		entity.serverTick();
		AssemblyHost nested = entity.getNestedHost(nestedCell);
		if (nested == null || nested.getAssembly() == null) {
			helper.fail("Nested motor on an entity root should be live with its own assembly");
			return;
		}
		// The core of the entity-root regression: the nested host must discover its entity parent.
		if (nested.assemblyParentHost() != entity) {
			helper.fail("Nested host should resolve its parent to the entity root, was " + nested.assemblyParentHost());
			return;
		}
		int nestedBlocks = nested.getAssembly().getBlocks().size();

		CompoundTag parentState = new CompoundTag();
		entity.getAssemblyController().writeState(parentState, level.registryAccess());
		Assembly parentSynced = new Assembly();
		parentSynced.readNBT(level.registryAccess(), parentState.getCompound("Assembly"), level.getGameTime());
		CompoundTag nestedUpdateTag = parentSynced.getUpdateTags().get(nestedCell);
		if (nestedUpdateTag == null || !nestedUpdateTag.contains("Assembly")) {
			helper.fail("Entity root's synced structure should carry the nested motor's assembly in its update tag");
			return;
		}

		ServoMotorBlockEntity rebuilt = new ServoMotorBlockEntity(nestedCell,
			ModBlocks.SERVO_MOTOR.get().defaultBlockState().setValue(ServoMotorBlock.FACING, Direction.UP));
		rebuilt.handleUpdateTag(nestedUpdateTag, level.registryAccess());
		if (rebuilt.getAssembly() == null || rebuilt.getAssembly().getBlocks().size() != nestedBlocks) {
			helper.fail("Rebuilt nested motor (entity root) should have its assembly from the update tag");
			return;
		}
		helper.succeed();
	}

	/** Set up a parent servo (root) with a nested servo, ticked so the nested is live with its assembly. */
	private static ServoMotorBlockEntity setupNested(GameTestHelper helper, BlockPos nestedCell) {
		helper.setBlock(MOTOR_POS, ModBlocks.SERVO_MOTOR.get().defaultBlockState()
			.setValue(ServoMotorBlock.FACING, Direction.EAST));
		ServoMotorBlockEntity parent = (ServoMotorBlockEntity) helper.getBlockEntity(MOTOR_POS);
		parent.initAssembly();
		parent.getAssembly().putBlock(nestedCell, ModBlocks.SERVO_MOTOR.get().defaultBlockState()
			.setValue(ServoMotorBlock.FACING, Direction.EAST), null, null);
		parent.serverTick();
		return parent;
	}

	/**
	 * A falling block detached from a <em>nested</em> assembly must spawn into the genuine root world at
	 * the assembly's composed (root-relative) world position — not into the parent sim level, where the
	 * pose would be transformed a second time. Places an unsupported sand block in the nested assembly,
	 * lets its scheduled fall fire, and asserts the entity lands at {@code ofCurrent(nested).localToWorld}.
	 */
	@GameTest(template = TEMPLATE)
	public static void nestedDetachedFallingBlockUsesRootWorldPosition(GameTestHelper helper) {
		ServerLevel level = (ServerLevel) helper.getLevel();
		BlockPos nestedCell = new BlockPos(1, 0, 0);
		ServoMotorBlockEntity parent = setupNested(helper, nestedCell);
		ServoMotorBlockEntity nested = parent.getNestedMotor(nestedCell);
		if (nested == null || nested.getAssembly() == null) {
			helper.fail("Nested motor should be live with its own assembly");
			return;
		}

		// Unsupported sand (air directly below), 26-connected to the nested head: it falls rather than
		// being treated as a disconnected group. Placed through a sim so FallingBlock schedules its fall.
		BlockPos sandCell = new BlockPos(0, 1, 0);
		AssemblySimServerLevel nestedSim = new AssemblySimServerLevel((ServerLevel) nested.assemblyLevel(),
			nested.getAssembly(), nestedCell, nested, null, null, () -> AssemblyTransform.ofCurrent(nested));
		nestedSim.setBlock(sandCell, Blocks.SAND.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);

		// The expected detach pose: the cell's bottom-centre through the full composed (root) transform.
		Vec3 feet = new Vec3(sandCell.getX() + 0.5, sandCell.getY(), sandCell.getZ() + 0.5);
		Vec3 expected = AssemblyTransform.ofCurrent(nested).localToWorld(feet);

		helper.runAfterDelay(3, () -> {
			parent.serverTick(); // ticks the nested motor → the sand's scheduled fall → detach
			AABB area = new AABB(BlockPos.containing(expected)).inflate(8.0);
			List<FallingBlockEntity> falling = level.getEntitiesOfClass(FallingBlockEntity.class, area);
			if (falling.isEmpty()) {
				helper.fail("A FallingBlockEntity should have detached from the nested assembly into the root world");
				return;
			}
			FallingBlockEntity fb = falling.get(0);
			// A double transform (spawning into the parent sim) would offset this by the parent's whole
			// translation — many blocks — so a tight tolerance distinguishes the correct root pose.
			if (fb.position().distanceTo(expected) > 0.5) {
				helper.fail("Detached block should be at the composed root position " + expected
					+ " but was at " + fb.position());
				return;
			}
			helper.succeed();
		});
	}

	/**
	 * When the parent assembly is spinning, a block detached from the nested assembly must inherit the
	 * <em>composed</em> root-world orientation and carried velocity (the parent's spin sweeping the nested
	 * platform), not just the nested assembly's own (here identity) pose. Powers the parent so it spins,
	 * then detaches a nested block and checks the entity carries a non-identity rotation and a non-zero
	 * velocity, while still landing at the composed root position.
	 */
	@GameTest(template = TEMPLATE)
	public static void nestedDetachedFallingBlockInheritsComposedSpin(GameTestHelper helper) {
		ServerLevel level = (ServerLevel) helper.getLevel();
		BlockPos nestedCell = new BlockPos(1, 0, 0);
		ServoMotorBlockEntity parent = setupNested(helper, nestedCell);
		// Power the parent so it spins about its facing (X) axis; the nested platform is swept along.
		helper.setBlock(MOTOR_POS.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
		for (int i = 0; i < 5; i++)
			parent.serverTick();

		ServoMotorBlockEntity nested = parent.getNestedMotor(nestedCell);
		if (nested == null || nested.getAssembly() == null) {
			helper.fail("Nested motor should be live with its own assembly");
			return;
		}

		BlockPos sandCell = new BlockPos(0, 1, 0);
		AssemblySimServerLevel nestedSim = new AssemblySimServerLevel((ServerLevel) nested.assemblyLevel(),
			nested.getAssembly(), nestedCell, nested, null, null, () -> AssemblyTransform.ofCurrent(nested));
		nestedSim.setBlock(sandCell, Blocks.SAND.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);

		helper.runAfterDelay(3, () -> {
			parent.serverTick(); // advances the spin, then detaches the sand at that pose
			// The gametest world ticks the parent block entity itself, so the exact spin angle at detach
			// is timing-dependent; assert the qualitative composed-root behaviour rather than an exact angle.
			Vec3 expected = AssemblyTransform.ofCurrent(nested)
				.localToWorld(new Vec3(sandCell.getX() + 0.5, sandCell.getY(), sandCell.getZ() + 0.5));

			AABB area = new AABB(BlockPos.containing(expected)).inflate(8.0);
			List<FallingBlockEntity> falling = level.getEntitiesOfClass(FallingBlockEntity.class, area);
			if (falling.isEmpty()) {
				helper.fail("A FallingBlockEntity should have detached from the spinning nested assembly");
				return;
			}
			FallingBlockEntity fb = falling.get(0);
			// Root-world position (a few ticks of spin at the cell radius stays well within tolerance; a
			// double transform into the parent sim would be off by the parent's whole translation).
			if (fb.position().distanceTo(expected) > 1.0) {
				helper.fail("Detached block should land near the composed root position " + expected
					+ " but was at " + fb.position());
				return;
			}
			// Inherits the parent's spin: a non-identity orientation purely about the parent's facing (X)
			// axis — not the nested assembly's own (identity) pose.
			Quaternionf rot = ((AssemblyRotatedEntity) fb).zps$getAssemblyRotation();
			if (Math.abs(rot.w) > 0.9999) {
				helper.fail("Detached block should inherit the parent's (non-identity) spin orientation, got " + rot);
				return;
			}
			if (Math.abs(rot.y) > 1.0e-4 || Math.abs(rot.z) > 1.0e-4) {
				helper.fail("Inherited orientation should be about the parent's X axis, got " + rot);
				return;
			}
			// Carried velocity from the parent sweeping the nested platform (root-world, non-zero).
			if (fb.getDeltaMovement().length() < 1.0e-4) {
				helper.fail("Detached block from a spinning parent should carry a non-zero root-world velocity");
				return;
			}
			helper.succeed();
		});
	}
}
