package com.assemblylib.impl.networking;

import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.client.AssemblyInteractionClient;
import com.assemblylib.impl.assembly.AssemblyPath;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Broadcast to nearby players so they render the crack overlay another player is
 * making on a assembly block (possibly nested). {@code breakerId} is the mining player's entity
 * id (vanilla semantics, so concurrent breakers don't clobber each other);
 * {@code stage} is 0-9, or -1 to clear.
 */
public record AssemblyDestroyStageS2CPacket(AssemblyPath path, BlockPos localPos, int breakerId, int stage)
        implements CustomPacketPayload {
    public static final Type<AssemblyDestroyStageS2CPacket> TYPE =
            new Type<>(AssemblyLib.resource("assembly_destroy_stage"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyDestroyStageS2CPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblyDestroyStageS2CPacket decode(RegistryFriendlyByteBuf buffer) {
            return new AssemblyDestroyStageS2CPacket(AssemblyPath.STREAM_CODEC.decode(buffer),
                    buffer.readBlockPos(), buffer.readVarInt(), buffer.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, AssemblyDestroyStageS2CPacket packet) {
            AssemblyPath.STREAM_CODEC.encode(buffer, packet.path);
            buffer.writeBlockPos(packet.localPos);
            buffer.writeVarInt(packet.breakerId);
            buffer.writeVarInt(packet.stage);
        }
    };

    public static void handle(AssemblyDestroyStageS2CPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                AssemblyInteractionClient.onRemoteDestroyStage(packet.path, packet.localPos, packet.breakerId, packet.stage));
    }

    @Override
    public Type<AssemblyDestroyStageS2CPacket> type() {
        return TYPE;
    }
}
