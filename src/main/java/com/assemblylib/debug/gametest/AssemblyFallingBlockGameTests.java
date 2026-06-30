package com.assemblylib.debug.gametest;

import java.util.List;

import com.assemblylib.AssemblyLib;
import com.assemblylib.debug.block.ModBlocks;
import com.assemblylib.debug.block.ServoMotorBlock;
import com.assemblylib.debug.blockentity.ServoMotorBlockEntity;
import com.assemblylib.impl.assembly.Assembly;
import com.assemblylib.impl.assembly.AssemblySimServerLevel;
import com.assemblylib.impl.assembly.AssemblyTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Verifies the retained physics of an assembly: unsupported falling blocks detach into real
 * {@link FallingBlockEntity}s, and entities a block spawns into the sim level are re-homed to the
 * real world at their rotated pose. Redstone and player interaction are out of scope (not simulated).
 *
 * <p>Operations are driven directly against the server simulation level, advancing scheduled ticks by
 * waiting real game ticks (so world game-time moves) and pumping the motor's tick loop via
 * {@link ServoMotorBlockEntity#serverTick()}.
 */
@GameTestHolder(AssemblyLib.MOD_ID)
@PrefixGameTestTemplate(false)
public class AssemblyFallingBlockGameTests {

	private static final String TEMPLATE = "gametest/flat_7x4x7";
	private static final BlockPos MOTOR_POS = new BlockPos(2, 2, 2);

	// region helpers

	private static ServoMotorBlockEntity setupMotor(GameTestHelper helper) {
		helper.setBlock(MOTOR_POS, ModBlocks.SERVO_MOTOR.get().defaultBlockState()
			.setValue(ServoMotorBlock.FACING, Direction.EAST));
		ServoMotorBlockEntity motor = (ServoMotorBlockEntity) helper.getBlockEntity(MOTOR_POS);
		motor.initAssembly();
		return motor;
	}

	private static AssemblySimServerLevel sim(GameTestHelper helper, Assembly c) {
		return new AssemblySimServerLevel((ServerLevel) helper.getLevel(), c, MOTOR_POS, null, null, null);
	}

	/** Place a block through the sim level so the standard block update fires (mirrors placement). */
	private static void place(GameTestHelper helper, Assembly c, BlockPos local, BlockState state) {
		sim(helper, c).setBlock(local, state, Block.UPDATE_ALL);
	}

	private static BlockState state(Assembly c, BlockPos local) {
		StructureBlockInfo info = c.getBlocks().get(local);
		return info == null ? Blocks.AIR.defaultBlockState() : info.state();
	}

	// endregion

	/** A falling block with no support below it detaches off the assembly as a real falling entity. */
	@GameTest(template = TEMPLATE)
	public static void unsupportedFallingBlockSpawnsEntity(GameTestHelper helper) {
		ServoMotorBlockEntity motor = setupMotor(helper);
		Assembly c = motor.getAssembly();

		// Sand with air directly below it (unsupported), still 26-connected to the head at (-1,0,0).
		BlockPos sand = new BlockPos(0, 1, 0);
		place(helper, c, sand, Blocks.SAND.defaultBlockState());
		if (!state(c, sand).is(Blocks.SAND)) {
			helper.fail("Sand should be present before its scheduled fall tick");
			return;
		}

		helper.runAfterDelay(3, () -> {
			motor.serverTick();
			if (!state(c, sand).isAir()) {
				helper.fail("Unsupported sand should have detached off the assembly");
				return;
			}
			AABB area = AABB.ofSize(Vec3.atCenterOf(helper.absolutePos(MOTOR_POS)), 24, 24, 24);
			List<FallingBlockEntity> falling =
				((ServerLevel) helper.getLevel()).getEntitiesOfClass(FallingBlockEntity.class, area);
			if (falling.isEmpty()) {
				helper.fail("A FallingBlockEntity should have spawned for the detached sand");
				return;
			}
			helper.succeed();
		});
	}

	/** An entity spawned into the sim level is re-homed to the real level at its rotated world pose. */
	@GameTest(template = TEMPLATE)
	public static void spawnedEntityRedirectedToRealLevel(GameTestHelper helper) {
		ServoMotorBlockEntity motor = setupMotor(helper);
		Assembly c = motor.getAssembly();
		ServerLevel level = (ServerLevel) helper.getLevel();

		AssemblySimServerLevel sim = new AssemblySimServerLevel(level, c, MOTOR_POS, null, null,
			() -> AssemblyTransform.ofCurrent(motor));
		Vec3 spawnLocal = Vec3.atCenterOf(new BlockPos(0, 1, 0));
		ItemEntity item = new ItemEntity(sim, spawnLocal.x, spawnLocal.y, spawnLocal.z, new ItemStack(Items.DIAMOND));
		sim.addFreshEntity(item);

		if (item.level() != level) {
			helper.fail("Spawned entity should have been re-homed to the real level");
			return;
		}
		Vec3 expected = AssemblyTransform.ofCurrent(motor).localToWorld(spawnLocal);
		AABB area = new AABB(BlockPos.containing(expected)).inflate(1.5);
		List<ItemEntity> found = level.getEntitiesOfClass(ItemEntity.class, area, e -> e.getItem().is(Items.DIAMOND));
		if (found.isEmpty()) {
			helper.fail("Diamond item entity should be in the real level near " + expected);
			return;
		}
		// The interpolation history must be snapped to the world pose (no visible lerp from the
		// assembly-local origin); moveTo -> setOldPosAndRot guarantees xOld == getX(), etc.
		if (item.xOld != item.getX() || item.yOld != item.getY() || item.zOld != item.getZ()) {
			helper.fail("Spawned entity's old position should be snapped to its world position");
			return;
		}
		helper.succeed();
	}

	/**
	 * A projectile (arrow) spawned into the sim level faces its (rotated) velocity using the projectile
	 * convention, with the rotation history snapped — so it doesn't appear mis-facing on the first
	 * client frame.
	 */
	@GameTest(template = TEMPLATE)
	public static void spawnedArrowFacesVelocity(GameTestHelper helper) {
		ServoMotorBlockEntity motor = setupMotor(helper);
		Assembly c = motor.getAssembly();
		ServerLevel level = (ServerLevel) helper.getLevel();

		AssemblySimServerLevel sim = new AssemblySimServerLevel(level, c, MOTOR_POS, null, null,
			() -> AssemblyTransform.ofCurrent(motor));
		Vec3 spawnLocal = Vec3.atCenterOf(new BlockPos(0, 1, 0));
		Arrow arrow = new Arrow(sim, spawnLocal.x, spawnLocal.y, spawnLocal.z, ItemStack.EMPTY, null);
		arrow.setDeltaMovement(1.0, 0.0, 0.0); // local +X (east)
		arrow.setYRot(0.0F); // bogus initial facing, must be overridden from velocity
		arrow.setXRot(0.0F);
		sim.addFreshEntity(arrow);

		// angle 0 -> rotated velocity is still +X; projectile yaw = atan2(vx, vz) = atan2(1, 0) = 90.
		if (Math.abs(arrow.getYRot() - 90.0F) > 1.0F) {
			helper.fail("Arrow should face its velocity (yaw ~90), got " + arrow.getYRot());
			return;
		}
		if (arrow.yRotO != arrow.getYRot() || arrow.xRotO != arrow.getXRot()) {
			helper.fail("Arrow rotation history should be snapped to its facing");
			return;
		}
		helper.succeed();
	}
}
