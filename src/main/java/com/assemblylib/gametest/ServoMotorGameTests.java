package com.assemblylib.gametest;

import com.assemblylib.AssemblyLib;
import com.assemblylib.block.ModBlocks;
import com.assemblylib.block.ServoMotorBlock;
import com.assemblylib.blockentity.ServoMotorBlockEntity;
import com.assemblylib.assembly.Assembly;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AssemblyLib.MOD_ID)
@PrefixGameTestTemplate(false)
public class ServoMotorGameTests {

    private static final String TEMPLATE = "gametest/flat_7x4x7";
    private static final BlockPos MOTOR_POS = new BlockPos(2, 2, 2);
    private static final BlockPos FRONT_POS = MOTOR_POS.east();

    @GameTest(template = TEMPLATE)
    public static void placesPreAssembledWithHeadOnly(GameTestHelper helper) {
        helper.setBlock(MOTOR_POS, ModBlocks.SERVO_MOTOR.get().defaultBlockState()
                .setValue(ServoMotorBlock.FACING, Direction.EAST));
        // A block in front must NOT be captured: there is no flood-fill assembly anymore.
        helper.setBlock(FRONT_POS, Blocks.STONE);

        BlockEntity be = helper.getBlockEntity(MOTOR_POS);
        if (!(be instanceof ServoMotorBlockEntity motor)) {
            helper.fail("Expected a ServoMotorBlockEntity at " + MOTOR_POS + ", got " + be);
            return;
        }

        motor.initAssembly();
        Assembly assembly = motor.getAssembly();
        if (assembly == null) {
            helper.fail("Motor should be pre-assembled with a assembly");
            return;
        }
        if (assembly.getBlocks().size() != 1) {
            helper.fail("Assembly should contain only the head; got " + assembly.getBlocks().size());
            return;
        }
        StructureBlockInfo head = assembly.getBlocks().get(motor.headLocalPos());
        if (head == null || !head.state().is(ModBlocks.SERVO_MOTOR_HEAD.get())) {
            helper.fail("Head should sit at the motor's own cell (local " + motor.headLocalPos() + ")");
            return;
        }
        // The block in front stays in the world untouched.
        helper.assertBlockPresent(Blocks.STONE, FRONT_POS);
        helper.succeed();
    }

    /**
     * Exercises the in-flight editing primitives on the assembly data model used to build
     * outward off the head (the server break/place methods wrap these but need a real
     * ServerPlayer, verified with runClient). Here we assert add/remove keep the block map and
     * bounds consistent.
     */
    @GameTest(template = TEMPLATE)
    public static void assemblyAddAndRemoveBlocks(GameTestHelper helper) {
        helper.setBlock(MOTOR_POS, ModBlocks.SERVO_MOTOR.get().defaultBlockState()
                .setValue(ServoMotorBlock.FACING, Direction.EAST));
        ServoMotorBlockEntity motor = (ServoMotorBlockEntity) helper.getBlockEntity(MOTOR_POS);
        motor.initAssembly();

        Assembly assembly = motor.getAssembly();
        if (assembly == null) {
            helper.fail("Expected a assembly after placement");
            return;
        }

        // Use a local position far from anything else so the bounds effect is unambiguous.
        BlockPos far = new BlockPos(0, 10, 0); // block AABB spans y in [10, 11]
        int before = assembly.getBlocks().size();

        assembly.putBlock(far, Blocks.OAK_PLANKS.defaultBlockState(), null, null);
        if (assembly.getBlocks().size() != before + 1
                || !assembly.getBlocks().get(far).state().is(Blocks.OAK_PLANKS)) {
            helper.fail("putBlock should have added the plank at " + far);
            return;
        }
        if (assembly.getBounds().maxY < 11.0) {
            helper.fail("Bounds should have grown to include y=10; got " + assembly.getBounds());
            return;
        }

        if (assembly.removeBlock(far) == null) {
            helper.fail("removeBlock should have returned the removed plank");
            return;
        }
        if (assembly.getBlocks().size() != before || assembly.getBlocks().containsKey(far)) {
            helper.fail("removeBlock should have removed the plank at " + far);
            return;
        }
        // Bounds recomputed from scratch must no longer reach y=10.
        if (assembly.getBounds().maxY >= 11.0) {
            helper.fail("Bounds should have been recomputed to exclude y=10; got " + assembly.getBounds());
            return;
        }

        helper.succeed();
    }
}
