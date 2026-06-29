package com.assemblylib.debug.block;

import com.assemblylib.AssemblyLib;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

@SuppressWarnings("unused")
public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AssemblyLib.MOD_ID);

    public static final DeferredBlock<Block> SERVO_MOTOR = BLOCKS.register("servo_motor",
            () -> new ServoMotorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .strength(3.0f)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    // Unobtainable: no BlockItem, no creative tab, no loot table (like the piston head).
    public static final DeferredBlock<Block> SERVO_MOTOR_HEAD = BLOCKS.register("servo_motor_head",
            () -> new ServoMotorHeadBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .strength(3.0f)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
                    .noLootTable()));
}
