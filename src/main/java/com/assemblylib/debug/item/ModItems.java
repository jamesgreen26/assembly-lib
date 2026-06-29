package com.assemblylib.debug.item;

import com.assemblylib.AssemblyLib;
import com.assemblylib.debug.block.ModBlocks;
import com.assemblylib.debug.entity.ModEntities;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AssemblyLib.MOD_ID);

    public static final DeferredItem<Item> SERVO_MOTOR = ITEMS.register("servo_motor",
            () -> new BlockItem(ModBlocks.SERVO_MOTOR.get(), new Item.Properties()));

    public static final DeferredItem<Item> ASSEMBLY_HOST_SPAWN_EGG = ITEMS.register("assembly_host_spawn_egg",
            () -> new DeferredSpawnEggItem(ModEntities.ASSEMBLY_HOST, 0x65A84D, 0x8A5B2E, new Item.Properties()));
}
