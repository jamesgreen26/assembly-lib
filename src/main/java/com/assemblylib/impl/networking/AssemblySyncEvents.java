package com.assemblylib.impl.networking;

import com.assemblylib.api.AssemblyHost;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;

/**
 * Sends an assembly to a player the moment they begin tracking its host, replacing the initial state
 * that used to ride along in the host's chunk/entity sync. Entity hosts hook {@link
 * PlayerEvent.StartTracking}; block-entity hosts hook {@link ChunkWatchEvent.Sent} (there is no
 * per-block-entity tracking event, so we scan the freshly-sent chunk). Subsequent edits go out via
 * {@link AssemblySync#broadcast}. Game (NeoForge) event bus.
 */
public final class AssemblySyncEvents {

    private AssemblySyncEvents() {}

    @SubscribeEvent
    public static void onStartTrackingEntity(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof AssemblyHost host && event.getEntity() instanceof ServerPlayer player)
            AssemblySync.sendTo(player, host);
    }

    @SubscribeEvent
    public static void onChunkSent(ChunkWatchEvent.Sent event) {
        ServerPlayer player = event.getPlayer();
        LevelChunk chunk = event.getChunk();
        for (BlockEntity be : chunk.getBlockEntities().values())
            if (be instanceof AssemblyHost host)
                AssemblySync.sendTo(player, host);
    }
}
