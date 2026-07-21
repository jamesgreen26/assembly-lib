package com.assemblylib;

import com.assemblylib.debug.vchunk.AssemblyDebugCommand;
import com.assemblylib.debug.vchunk.AssemblyVChunkGameTests;
import com.assemblylib.impl.entity.AssemblyEntityTypes;
import com.assemblylib.impl.entity.api.behavior.AssemblyBehaviors;
import com.assemblylib.impl.entity.net.AssemblyEntityNetwork;
import com.assemblylib.impl.vchunk.AssemblyEvents;
import com.assemblylib.impl.vchunk.AssemblyServerConfig;
import com.assemblylib.impl.vchunk.net.AssemblyPackets;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for AssemblyLib: the real-coordinate virtual-chunk ("vchunk") assembly engine —
 * assemblies are real blocks living in dedicated far-away chunks, redirected to wherever they appear
 * via a handful of deep vanilla chokepoint mixins.
 */
@Mod(AssemblyLib.MOD_ID)
public final class AssemblyLib {
    public static final String MOD_ID = "assemblylib";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public AssemblyLib(IEventBus modEventBus, Dist dist, ModContainer modContainer) {
        modEventBus.addListener(AssemblyPackets::register);
        modEventBus.addListener(AssemblyLib::registerGameTests);

        // Entity-based assembly engine (com.assemblylib.impl.entity).
        AssemblyEntityTypes.register(modEventBus);
        AssemblyBehaviors.register(modEventBus);
        AssemblyEntityNetwork.register(modEventBus);

        // Server lifecycle/tick + debug command for the vchunk engine.
        NeoForge.EVENT_BUS.register(AssemblyEvents.class);
        NeoForge.EVENT_BUS.register(AssemblyDebugCommand.class);
        modContainer.registerConfig(ModConfig.Type.SERVER, AssemblyServerConfig.SPEC);

        if (dist == Dist.CLIENT) {
            NeoForge.EVENT_BUS.register(com.assemblylib.impl.vchunk.client.AssemblyRenderer.class);
            NeoForge.EVENT_BUS.register(com.assemblylib.impl.vchunk.client.AssemblyInteractionClient.class);
            NeoForge.EVENT_BUS.register(com.assemblylib.impl.vchunk.client.AssemblyClientBlockEntityTicker.class);
            NeoForge.EVENT_BUS.register(com.assemblylib.impl.vchunk.client.AssemblyCollisionDebugRenderer.class);
        }
    }

    public static ResourceLocation resource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void registerGameTests(RegisterGameTestsEvent event) {
        event.register(AssemblyVChunkGameTests.class);
    }
}
