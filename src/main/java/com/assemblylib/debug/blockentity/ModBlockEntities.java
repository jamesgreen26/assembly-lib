package com.assemblylib.debug.blockentity;

import com.assemblylib.AssemblyLib;
import com.assemblylib.debug.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@SuppressWarnings("ConstantConditions")
public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AssemblyLib.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ServoMotorBlockEntity>> SERVO_MOTOR =
        BLOCK_ENTITIES.register("servo_motor",
            () -> BlockEntityType.Builder.of(ServoMotorBlockEntity::new,
                ModBlocks.SERVO_MOTOR.get()).build(null));
}
