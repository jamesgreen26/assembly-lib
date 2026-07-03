package com.assemblylib.impl.vchunk.net;

import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.vchunk.AssemblyInteractionServer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client asks the server to break a block on an assembly (raycast resolved client-side). */
public record AssemblyAttackC2SPacket(int handle, BlockPos local) implements CustomPacketPayload {

    public static final Type<AssemblyAttackC2SPacket> TYPE = new Type<>(AssemblyLib.resource("vchunk_attack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyAttackC2SPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblyAttackC2SPacket decode(RegistryFriendlyByteBuf buf) {
            return new AssemblyAttackC2SPacket(buf.readVarInt(), buf.readBlockPos());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AssemblyAttackC2SPacket packet) {
            buf.writeVarInt(packet.handle);
            buf.writeBlockPos(packet.local);
        }
    };

    public static void handle(AssemblyAttackC2SPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                AssemblyInteractionServer.handleAttack(player, packet.handle, packet.local);
            }
        });
    }

    @Override
    public Type<AssemblyAttackC2SPacket> type() {
        return TYPE;
    }
}
