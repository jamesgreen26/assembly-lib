package com.assemblylib.impl.vchunk.net;

import java.util.ArrayList;
import java.util.List;

import com.assemblylib.impl.vchunk.Assembly;
import com.assemblylib.impl.vchunk.AssemblyManager;
import com.assemblylib.impl.vchunk.AssemblySpace;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side broadcast helpers for the assembly engine. Since assembly-space chunks bypass
 * vanilla's distance-based player-chunk tracking entirely, the engine owns its own sync. Milestone 1
 * syncs every assembly to every player (acceptable at small scale); proximity-based tracking is a
 * Milestone 5 concern.
 */
public final class AssemblyNetwork {

    private AssemblyNetwork() {}

    /** Collect an assembly's non-air blocks (and any block entities') at LOCAL positions into a snapshot. */
    public static AssemblySnapshotS2CPacket buildSnapshot(AssemblyManager manager, Assembly assembly) {
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());
        List<BlockPos> positions = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        List<BlockPos> bePositions = new ArrayList<>();
        List<CompoundTag> beTags = new ArrayList<>();
        manager.forEachNonAirBlock(assembly.slot(), (abs, state) -> {
            BlockPos local = abs.subtract(origin);
            positions.add(local);
            states.add(state);
            if (state.hasBlockEntity()) {
                LevelChunk chunk = manager.getResidentChunk(new ChunkPos(abs));
                BlockEntity be = chunk == null ? null : chunk.getBlockEntity(abs);
                if (be != null) {
                    bePositions.add(local);
                    beTags.add(be.saveWithFullMetadata(manager.level().registryAccess()));
                }
            }
        });
        return new AssemblySnapshotS2CPacket(assembly.id(), assembly.currentTransform(), positions, states, bePositions, beTags);
    }

    /**
     * Build a per-block diff for the given changed LOCAL positions: current state per position (air =
     * removal on the client) plus block-entity NBT where present.
     */
    public static AssemblyBlockDiffS2CPacket buildDiff(AssemblyManager manager, Assembly assembly,
            java.util.Collection<BlockPos> changedLocal) {
        List<BlockPos> positions = new ArrayList<>(changedLocal.size());
        List<BlockState> states = new ArrayList<>(changedLocal.size());
        List<BlockPos> bePositions = new ArrayList<>();
        List<CompoundTag> beTags = new ArrayList<>();
        for (BlockPos local : changedLocal) {
            BlockPos abs = AssemblySpace.localToAbsolute(assembly.slot(), local);
            LevelChunk chunk = manager.getResidentChunk(new ChunkPos(abs));
            BlockState state = chunk == null
                ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : chunk.getBlockState(abs);
            positions.add(local);
            states.add(state);
            if (chunk != null && state.hasBlockEntity()) {
                BlockEntity be = chunk.getBlockEntity(abs);
                if (be != null) {
                    bePositions.add(local);
                    beTags.add(be.saveWithFullMetadata(manager.level().registryAccess()));
                }
            }
        }
        return new AssemblyBlockDiffS2CPacket(assembly.id().handle(), positions, states, bePositions, beTags);
    }

    public static void broadcastDiff(AssemblyManager manager, Assembly assembly, java.util.Collection<BlockPos> changedLocal) {
        PacketDistributor.sendToAllPlayers(buildDiff(manager, assembly, changedLocal));
    }

    public static void broadcastSnapshot(AssemblyManager manager, Assembly assembly) {
        PacketDistributor.sendToAllPlayers(buildSnapshot(manager, assembly));
    }

    /** Push every currently-LOADED assembly to a player (e.g. on join). Unloaded ones sync once loaded. */
    public static void sendAllTo(ServerPlayer player, AssemblyManager manager) {
        for (Assembly assembly : manager.assemblies()) {
            if (assembly.isLoaded()) {
                PacketDistributor.sendToPlayer(player, buildSnapshot(manager, assembly));
            }
        }
    }

    public static void broadcastTransform(Assembly assembly) {
        PacketDistributor.sendToAllPlayers(new AssemblyTransformS2CPacket(assembly.id().handle(), assembly.currentTransform()));
    }

    public static void broadcastRemove(Assembly assembly) {
        PacketDistributor.sendToAllPlayers(new AssemblyRemoveS2CPacket(assembly.id().handle()));
    }
}
