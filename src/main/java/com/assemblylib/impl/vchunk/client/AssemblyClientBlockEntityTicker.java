package com.assemblylib.impl.vchunk.client;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client-ticks every reconstructed block entity in every tracked assembly, mirroring the old
 * system's {@code AssemblyRenderState#tick} — chest lids, spawner spin, campfire smoke, etc. animate
 * exactly as they would in the real world.
 */
public final class AssemblyClientBlockEntityTicker {

    private AssemblyClientBlockEntityTicker() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level == null || Minecraft.getInstance().isPaused()) {
            return;
        }
        for (ClientAssembly assembly : ClientAssemblyManager.all()) {
            assembly.tick();
        }
    }
}
