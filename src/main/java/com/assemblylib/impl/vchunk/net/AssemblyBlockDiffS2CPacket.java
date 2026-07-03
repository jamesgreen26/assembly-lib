package com.assemblylib.impl.vchunk.net;

import java.util.ArrayList;
import java.util.List;

import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.vchunk.client.ClientAssemblyNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Incremental per-block sync for one assembly: only the LOCAL positions that changed this tick, with
 * their new states (air = removal) and, where present, block-entity NBT. This is the common case —
 * a furnace lighting up, a door opening, a single placed block — and replaces the Milestone-1
 * "resend the whole assembly on any change" behavior; bulk edits (more than
 * {@code AssemblyManager.DIFF_LIMIT} blocks in one tick) still fall back to a full
 * {@link AssemblySnapshotS2CPacket}.
 */
public record AssemblyBlockDiffS2CPacket(int handle,
        List<BlockPos> positions, List<BlockState> states,
        List<BlockPos> bePositions, List<CompoundTag> beTags) implements CustomPacketPayload {

    public static final Type<AssemblyBlockDiffS2CPacket> TYPE = new Type<>(AssemblyLib.resource("vchunk_block_diff"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyBlockDiffS2CPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblyBlockDiffS2CPacket decode(RegistryFriendlyByteBuf buf) {
            int handle = buf.readVarInt();
            int count = buf.readVarInt();
            List<BlockPos> positions = new ArrayList<>(count);
            List<BlockState> states = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                positions.add(buf.readBlockPos());
                states.add(Block.stateById(buf.readVarInt()));
            }
            int beCount = buf.readVarInt();
            List<BlockPos> bePositions = new ArrayList<>(beCount);
            List<CompoundTag> beTags = new ArrayList<>(beCount);
            for (int i = 0; i < beCount; i++) {
                bePositions.add(buf.readBlockPos());
                CompoundTag tag = buf.readNbt();
                beTags.add(tag == null ? new CompoundTag() : tag);
            }
            return new AssemblyBlockDiffS2CPacket(handle, positions, states, bePositions, beTags);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AssemblyBlockDiffS2CPacket packet) {
            buf.writeVarInt(packet.handle);
            int count = packet.positions.size();
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buf.writeBlockPos(packet.positions.get(i));
                buf.writeVarInt(Block.getId(packet.states.get(i)));
            }
            int beCount = packet.bePositions.size();
            buf.writeVarInt(beCount);
            for (int i = 0; i < beCount; i++) {
                buf.writeBlockPos(packet.bePositions.get(i));
                buf.writeNbt(packet.beTags.get(i));
            }
        }
    };

    public static void handle(AssemblyBlockDiffS2CPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientAssemblyNetwork.handleBlockDiff(packet));
    }

    @Override
    public Type<AssemblyBlockDiffS2CPacket> type() {
        return TYPE;
    }
}
