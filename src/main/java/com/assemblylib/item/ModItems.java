package com.assemblylib.item;

import com.assemblylib.AssemblyLib;
import com.assemblylib.block.ModBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AssemblyLib.MOD_ID);

    public static final DeferredItem<Item> SERVO_MOTOR = ITEMS.register("servo_motor",
            () -> new BlockItem(ModBlocks.SERVO_MOTOR.get(), new Item.Properties()));
}
