package com.assemblylib.impl.networking;

import com.assemblylib.api.AssemblyHost;
import com.assemblylib.impl.assembly.AssemblyPath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side dispatch for {@link AssemblySyncS2CPacket}: the assembly's own sync channel. The
 * controller calls {@link #broadcast} on every edit to a root host; the tracking events call
 * {@link #sendTo} once as a player begins tracking a host. A nested host never reaches here — it
 * routes through its parent (see {@code AssemblyController#sync}), which re-broadcasts the whole
 * structure including the nested change.
 */
public final class AssemblySync {

    private AssemblySync() {}

    /** Push {@code host}'s current assembly to every player tracking it (its entity, or its chunk). */
    public static void broadcast(ServerLevel level, AssemblyHost host) {
        AssemblySyncS2CPacket packet = packetFor(host, level);
        if (host instanceof Entity entity)
            PacketDistributor.sendToPlayersTrackingEntity(entity, packet);
        else
            PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(host.assemblyHostBlockPos()), packet);
    }

    /** Send {@code host}'s current assembly to a single player that just started tracking it. */
    public static void sendTo(ServerPlayer player, AssemblyHost host) {
        if (host.assemblyLevel() instanceof ServerLevel level)
            PacketDistributor.sendToPlayer(player, packetFor(host, level));
    }

    private static AssemblySyncS2CPacket packetFor(AssemblyHost host, ServerLevel level) {
        CompoundTag state = new CompoundTag();
        host.getAssemblyController().writeState(state, level.registryAccess());
        return new AssemblySyncS2CPacket(AssemblyPath.of(host), state);
    }
}
