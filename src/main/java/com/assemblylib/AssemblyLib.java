package com.assemblylib;

import com.assemblylib.block.ModBlocks;
import com.assemblylib.blockentity.ModBlockEntities;
import com.assemblylib.client.ClientSetup;
import com.assemblylib.gametest.ContraptionNestingGameTests;
import com.assemblylib.gametest.ContraptionRedstoneGameTests;
import com.assemblylib.gametest.ServoMotorGameTests;
import com.assemblylib.item.ModCreativeTabs;
import com.assemblylib.item.ModItems;
import com.assemblylib.networking.AssemblyLibPackets;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for AssemblyLib: a standalone library mod providing block-entity-hosted contraptions
 * (the Servo Motor and its nested-contraption machinery), extracted from Zero Point Systems.
 */
@Mod(AssemblyLib.MOD_ID)
public final class AssemblyLib {
    public static final String MOD_ID = "assemblylib";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public AssemblyLib(IEventBus modEventBus, Dist dist, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        modEventBus.addListener(AssemblyLibPackets::register);
        modEventBus.addListener(AssemblyLib::registerGameTests);

        if (dist == Dist.CLIENT) {
            modEventBus.register(ClientSetup.class);
        }
    }

    public static ResourceLocation resource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void registerGameTests(RegisterGameTestsEvent event) {
        event.register(ServoMotorGameTests.class);
        event.register(ContraptionRedstoneGameTests.class);
        event.register(ContraptionNestingGameTests.class);
    }
}
