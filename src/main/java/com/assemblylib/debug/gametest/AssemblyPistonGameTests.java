package com.assemblylib.debug.gametest;

import com.assemblylib.AssemblyLib;
import com.assemblylib.api.AssemblyHost;
import com.assemblylib.debug.block.ModBlocks;
import com.assemblylib.debug.block.ServoMotorBlock;
import com.assemblylib.debug.blockentity.ServoMotorBlockEntity;
import com.assemblylib.impl.assembly.Assembly;
import com.assemblylib.impl.assembly.AssemblySimServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Verifies that vanilla pistons push and pull blocks inside an assembly. Because the server-side
 * {@link AssemblySimServerLevel} is a real {@code ServerLevel}, the engine's piston machinery runs
 * natively — the only additions are routing the piston's {@code blockEvent} through the assembly's
 * own queue (drained each tick by the motor) and landing the {@code PistonMovingBlockEntity} that
 * {@code moveBlocks} creates in the sim's cache so it ticks and finalises.
 *
 * <p>Like {@link AssemblyRedstoneGameTests}, blocks are laid out directly against the simulation
 * level and the assembly is advanced by pumping {@link ServoMotorBlockEntity#serverTick()}. A piston
 * placed into an already-powered neighbourhood fires {@code onPlace -> checkIfExtend} (the freshly
 * placed piston has no block entity yet), queuing the extend event for the next tick's drain.
 */
@GameTestHolder(AssemblyLib.MOD_ID)
@PrefixGameTestTemplate(false)
public class AssemblyPistonGameTests {

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
		AssemblyHost host = (AssemblyHost) helper.getBlockEntity(MOTOR_POS);
		return new AssemblySimServerLevel((ServerLevel) helper.getLevel(), c, MOTOR_POS, host, null, null, null);
	}

	/** Insert a block into the structure without firing updates (builds the inert layout). */
	private static void put(Assembly c, BlockPos local, BlockState state) {
		c.putBlock(local, state, null, null);
	}

	/** Place a block through the sim level so neighbour/redstone updates (and onPlace) fire. */
	private static void place(GameTestHelper helper, Assembly c, BlockPos local, BlockState state) {
		sim(helper, c).setBlock(local, state, Block.UPDATE_ALL);
	}

	/** Pump the motor's server tick {@code n} times (drains block events, ticks the moving piston BE). */
	private static void pump(ServoMotorBlockEntity motor, int n) {
		for (int i = 0; i < n; i++)
			motor.serverTick();
	}

	private static BlockState state(Assembly c, BlockPos local) {
		StructureBlockInfo info = c.getBlocks().get(local);
		return info == null ? Blocks.AIR.defaultBlockState() : info.state();
	}

	private static boolean extended(Assembly c, BlockPos piston) {
		BlockState s = state(c, piston);
		return s.hasProperty(BlockStateProperties.EXTENDED) && s.getValue(BlockStateProperties.EXTENDED);
	}

	// endregion

	/**
	 * A powered piston pushes the block in front of it one cell. While the slide is in flight the
	 * destination cell holds a {@code MOVING_PISTON} (drawn by the moving block entity), and once the
	 * animation finishes the pushed block occupies the destination, a piston head sits where the block
	 * was, and the piston reads {@code EXTENDED}.
	 */
	@GameTest(template = TEMPLATE)
	public static void pistonPushesBlockOneCell(GameTestHelper helper) {
		ServoMotorBlockEntity motor = setupMotor(helper);
		Assembly c = motor.getAssembly();

		BlockPos piston = new BlockPos(1, 1, 0);
		BlockPos pushed = new BlockPos(2, 1, 0);       // block in front of the (east-facing) piston
		BlockPos destination = new BlockPos(3, 1, 0);  // where the pushed block ends up
		BlockPos power = new BlockPos(0, 1, 0);        // redstone block behind (west of) the piston

		put(c, pushed, Blocks.STONE.defaultBlockState());
		put(c, power, Blocks.REDSTONE_BLOCK.defaultBlockState());
		// Placing the piston into the already-powered neighbourhood queues its extend event.
		place(helper, c, piston, Blocks.PISTON.defaultBlockState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));

		// First tick: the event is dispatched and the slide begins — the destination is now a MOVING_PISTON.
		pump(motor, 1);
		if (!state(c, destination).is(Blocks.MOVING_PISTON)) {
			helper.fail("Destination cell should hold a MOVING_PISTON mid-slide; was " + state(c, destination));
			return;
		}

		// After enough ticks the slide resolves to the final blocks.
		pump(motor, 5);
		if (!state(c, destination).is(Blocks.STONE)) {
			helper.fail("Pushed stone should occupy the destination after the push; was " + state(c, destination));
			return;
		}
		if (!state(c, pushed).is(Blocks.PISTON_HEAD)) {
			helper.fail("A piston head should sit where the pushed block was; was " + state(c, pushed));
			return;
		}
		if (!extended(c, piston)) {
			helper.fail("Piston should read EXTENDED after pushing");
			return;
		}
		helper.succeed();
	}

	/**
	 * A piston that extended retracts when it loses power: the head is removed and the piston reads
	 * un-extended again. (A plain piston leaves the pushed block where it was shoved.)
	 */
	@GameTest(template = TEMPLATE)
	public static void pistonRetractsWhenUnpowered(GameTestHelper helper) {
		ServoMotorBlockEntity motor = setupMotor(helper);
		Assembly c = motor.getAssembly();

		BlockPos piston = new BlockPos(1, 1, 0);
		BlockPos pushed = new BlockPos(2, 1, 0);
		BlockPos head = new BlockPos(2, 1, 0); // the head ends up where the pushed block was
		BlockPos power = new BlockPos(0, 1, 0);

		put(c, pushed, Blocks.STONE.defaultBlockState());
		put(c, power, Blocks.REDSTONE_BLOCK.defaultBlockState());
		place(helper, c, piston, Blocks.PISTON.defaultBlockState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));
		pump(motor, 6);
		if (!extended(c, piston) || !state(c, head).is(Blocks.PISTON_HEAD)) {
			helper.fail("Piston should be extended with a head before retracting");
			return;
		}

		// Cut the power: the neighbour update reaches the piston, which queues its retract event.
		place(helper, c, power, Blocks.AIR.defaultBlockState());
		pump(motor, 6);
		if (extended(c, piston)) {
			helper.fail("Piston should read un-extended after losing power");
			return;
		}
		if (state(c, head).is(Blocks.PISTON_HEAD)) {
			helper.fail("Piston head should be gone after retracting; was " + state(c, head));
			return;
		}
		helper.succeed();
	}

	/**
	 * A sticky piston pulls its block back on retraction: after extending (pushing the block to the
	 * destination, head where the block was), cutting power retracts the head and drags the stuck block
	 * back into the cell the head vacated.
	 */
	@GameTest(template = TEMPLATE)
	public static void stickyPistonPullsBlockBack(GameTestHelper helper) {
		ServoMotorBlockEntity motor = setupMotor(helper);
		Assembly c = motor.getAssembly();

		BlockPos piston = new BlockPos(1, 1, 0);
		BlockPos front = new BlockPos(2, 1, 0);        // block in front; also where the head extends to
		BlockPos destination = new BlockPos(3, 1, 0);  // where the block is pushed on extend
		BlockPos power = new BlockPos(0, 1, 0);

		put(c, front, Blocks.STONE.defaultBlockState());
		put(c, power, Blocks.REDSTONE_BLOCK.defaultBlockState());
		place(helper, c, piston, Blocks.STICKY_PISTON.defaultBlockState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));
		pump(motor, 6);
		if (!extended(c, piston) || !state(c, destination).is(Blocks.STONE)) {
			helper.fail("Sticky piston should have pushed the block to the destination before retracting");
			return;
		}

		place(helper, c, power, Blocks.AIR.defaultBlockState());
		pump(motor, 6);
		if (extended(c, piston)) {
			helper.fail("Sticky piston should read un-extended after losing power");
			return;
		}
		if (!state(c, front).is(Blocks.STONE)) {
			helper.fail("Sticky piston should have pulled the block back into the head's cell; was "
				+ state(c, front));
			return;
		}
		if (!state(c, destination).isAir()) {
			helper.fail("The destination cell should be empty after the block was pulled back; was "
				+ state(c, destination));
			return;
		}
		helper.succeed();
	}

	/**
	 * The piston's {@code blockEvent} survives being queued by a throwaway sim instance: it is stored on
	 * the {@link Assembly} (like the block-tick queue), so the event placed via one sim is drained by the
	 * motor's own sim on the next tick. Guards the queue's ownership.
	 */
	@GameTest(template = TEMPLATE)
	public static void pistonBlockEventOutlivesTransientSim(GameTestHelper helper) {
		ServoMotorBlockEntity motor = setupMotor(helper);
		Assembly c = motor.getAssembly();

		BlockPos piston = new BlockPos(1, 1, 0);
		put(c, new BlockPos(2, 1, 0), Blocks.STONE.defaultBlockState());
		put(c, new BlockPos(0, 1, 0), Blocks.REDSTONE_BLOCK.defaultBlockState());
		// place() builds a fresh sim, runs onPlace -> checkIfExtend, and enqueues onto the assembly.
		place(helper, c, piston, Blocks.PISTON.defaultBlockState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));

		// The motor's own (separate) sim instance drains the same assembly-owned queue.
		pump(motor, 6);
		if (!extended(c, piston)) {
			helper.fail("Event queued through a transient sim should have been drained by the motor tick");
			return;
		}
		helper.succeed();
	}
}
