package com.assemblylib.impl.vchunk.net;

import java.util.ArrayList;
import java.util.List;

import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.vchunk.AssemblyId;
import com.assemblylib.impl.vchunk.AssemblyTransform;
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
 * A full snapshot of one assembly: its id, current transform, every non-air block (at assembly LOCAL
 * positions), and the full NBT of every block entity among them (so the client can reconstruct live,
 * ticking, renderable block entities — chests, furnaces, signs, etc. — exactly like the real world).
 * Sent on creation, on content change, and to each player as they begin tracking the assembly.
 * Milestone 1 resends the whole snapshot on any change; incremental per-block diffing is a Milestone
 * 5 concern.
 */
public record AssemblySnapshotS2CPacket(AssemblyId id, AssemblyTransform transform,
        List<BlockPos> positions, List<BlockState> states,
        List<BlockPos> bePositions, List<CompoundTag> beTags) implements CustomPacketPayload {

    public static final Type<AssemblySnapshotS2CPacket> TYPE = new Type<>(AssemblyLib.resource("vchunk_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblySnapshotS2CPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblySnapshotS2CPacket decode(RegistryFriendlyByteBuf buf) {
            AssemblyId id = AssemblyId.STREAM_CODEC.decode(buf);
            AssemblyTransform transform = AssemblyTransform.STREAM_CODEC.decode(buf);
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
            return new AssemblySnapshotS2CPacket(id, transform, positions, states, bePositions, beTags);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AssemblySnapshotS2CPacket packet) {
            AssemblyId.STREAM_CODEC.encode(buf, packet.id);
            AssemblyTransform.STREAM_CODEC.encode(buf, packet.transform);
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

    public static void handle(AssemblySnapshotS2CPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientAssemblyNetwork.handleSnapshot(packet));
    }

    @Override
    public Type<AssemblySnapshotS2CPacket> type() {
        return TYPE;
    }
}
