package com.assemblylib;

import com.assemblylib.debug.block.ModBlocks;
import com.assemblylib.debug.blockentity.ModBlockEntities;
import com.assemblylib.debug.entity.ModEntities;
import com.assemblylib.impl.client.ClientSetup;
import com.assemblylib.debug.gametest.AssemblyCollisionGameTests;
import com.assemblylib.debug.gametest.AssemblyFallingBlockGameTests;
import com.assemblylib.debug.gametest.ServoMotorGameTests;
import com.assemblylib.debug.item.ModCreativeTabs;
import com.assemblylib.debug.item.ModItems;
import com.assemblylib.impl.networking.AssemblyLibPackets;
import com.assemblylib.impl.networking.AssemblySyncEvents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for AssemblyLib: a standalone library mod providing block-entity-hosted assemblys
 * (the Servo Motor and its assembly machinery), extracted from Zero Point Systems.
 */
@Mod(AssemblyLib.MOD_ID)
public final class AssemblyLib {
    public static final String MOD_ID = "assemblylib";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public AssemblyLib(IEventBus modEventBus, Dist dist, ModContainer modContainer) {
        if (!FMLLoader.isProduction()) {
            ModBlocks.BLOCKS.register(modEventBus);
            ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
            ModEntities.ENTITIES.register(modEventBus);
            ModItems.ITEMS.register(modEventBus);
            ModCreativeTabs.TABS.register(modEventBus);
            modEventBus.addListener(ModEntities::registerAttributes);
        }
        modEventBus.addListener(AssemblyLibPackets::register);
        modEventBus.addListener(AssemblyLib::registerGameTests);
        NeoForge.EVENT_BUS.register(AssemblySyncEvents.class);

        if (dist == Dist.CLIENT) {
            modEventBus.register(ClientSetup.class);
        }
    }

    public static ResourceLocation resource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void registerGameTests(RegisterGameTestsEvent event) {
        event.register(ServoMotorGameTests.class);
        event.register(AssemblyFallingBlockGameTests.class);
        event.register(AssemblyCollisionGameTests.class);
    }
}
