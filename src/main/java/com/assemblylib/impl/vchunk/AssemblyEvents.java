package com.assemblylib.impl.vchunk;

import com.assemblylib.impl.vchunk.net.AssemblyNetwork;
import com.assemblylib.impl.vchunk.net.AssemblyRemoveS2CPacket;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side lifecycle + per-tick driver for the assembly engine. Registered on the NeoForge game
 * event bus.
 *
 * <p>Only <em>loaded</em> assemblies (within {@link AssemblyServerConfig#loadDistance()} of a player)
 * are ticked (motion, collision) or synced — an assembly with no player nearby costs nothing beyond
 * its id/transform sitting in memory. Load/unload state is re-evaluated every
 * {@link AssemblyServerConfig#checkIntervalTicks()} ticks, a cheap distance check.
 */
public final class AssemblyEvents {

    private AssemblyEvents() {}

    /** Make a level's {@link AssemblyManager} active (and reachable by the chunk-interception mixin). */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            AssemblyManager.get(serverLevel);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            AssemblyManager.deactivate(serverLevel);
        }
    }

    /** Push every currently-loaded assembly to a joining player; unloaded ones sync once they load. */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AssemblyManager manager = AssemblyManager.active(player.serverLevel());
            if (manager != null) {
                AssemblyNetwork.sendAllTo(player, manager);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        long gameTime = event.getServer().overworld().getGameTime();
        boolean checkLoadState = gameTime % AssemblyServerConfig.checkIntervalTicks() == 0;

        for (ServerLevel level : event.getServer().getAllLevels()) {
            AssemblyManager manager = AssemblyManager.active(level);
            if (manager == null) {
                continue;
            }

            if (checkLoadState) {
                manager.updateLoadState(level.players(), AssemblyServerConfig.loadDistance(), AssemblyServerConfig.unloadDistance());
                for (int handle : manager.drainJustUnloaded()) {
                    PacketDistributor.sendToAllPlayers(new AssemblyRemoveS2CPacket(handle));
                }
            }

            // Random ticking (crop growth, leaf decay, fire spread, sapling growth, ...) is driven by
            // vanilla's OWN ServerLevel#tickChunk, but ServerChunkCache only ever calls it for chunks
            // discovered via chunkMap.getChunks() (real ChunkHolders) — assembly chunks have none, so
            // without this call random ticking silently never runs on assemblies at all (not merely
            // reduced — the game rule and section-level "isRandomlyTicking" gating never fire).
            // Calling vanilla's own method directly reuses its exact block/fluid random-tick logic
            // (and, incidentally, its weather/lightning-strike logic — a lightning bolt spawned this
            // way is correctly repositioned onto the assembly by AssemblyEntitySpawnMixin).
            int randomTickSpeed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
            if (randomTickSpeed > 0) {
                for (LevelChunk chunk : manager.allResidentChunks()) {
                    if (level.shouldTickBlocksAt(chunk.getPos().toLong())) {
                        level.tickChunk(chunk, randomTickSpeed);
                    }
                }
            }

            for (Assembly assembly : manager.assemblies()) {
                if (!assembly.isLoaded()) {
                    continue;
                }

                // Advance test-driver motion and broadcast changed transforms. On an idle tick,
                // collapse previous -> current (like a vanilla entity's xo = x) so a stopped assembly
                // never keeps a stale previous pose that the collision substep loop would smear its
                // block boxes across (phantom "air" collision trailing the assembly). See
                // Assembly#settleTransform.
                if (assembly.tickMotion()) {
                    AssemblyNetwork.broadcastTransform(assembly);
                    // Persist the new pose: the transform is written from a.save() only if this
                    // SavedData is dirty, so a moved-then-parked assembly would otherwise reload at its
                    // last-saved position.
                    manager.setDirty();
                } else {
                    assembly.settleTransform();
                }

                // Entity <-> assembly collision no longer runs from a tick loop here: it's resolved
                // per-entity from AssemblyEntityMoveMixin (mixed into Entity.move() itself), the same
                // chokepoint vanilla's own world collision runs through. That covers every entity on
                // both sides uniformly (including the local player, which used to need its own
                // separate client-tick loop) and merges correctly with vanilla's collision flags
                // instead of pushing entities around after the fact.
            }

            // Incremental sync: per-block diffs for small changes (the common case — one furnace
            // lighting up used to resend the whole assembly), full snapshot only for bulk edits
            // (null position set) or fresh loads.
            for (var entry : manager.drainDirtyContent().entrySet()) {
                Assembly assembly = manager.byHandle(entry.getKey());
                if (assembly == null || !assembly.isLoaded()) {
                    continue;
                }
                if (entry.getValue() == null) {
                    AssemblyNetwork.broadcastSnapshot(manager, assembly);
                } else {
                    AssemblyNetwork.broadcastDiff(manager, assembly, entry.getValue());
                }
            }
        }
    }
}
