package com.assemblylib.impl.networking;

import com.assemblylib.AssemblyLib;
import com.assemblylib.api.AssemblyHost;
import com.assemblylib.impl.assembly.AssemblyPath;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Sent when a player finishes mining a block off a assembly (possibly nested). */
public record AssemblyBreakC2SPacket(AssemblyPath path, BlockPos localPos) implements CustomPacketPayload {
    public static final Type<AssemblyBreakC2SPacket> TYPE = new Type<>(AssemblyLib.resource("assembly_break"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyBreakC2SPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblyBreakC2SPacket decode(RegistryFriendlyByteBuf buffer) {
            return new AssemblyBreakC2SPacket(AssemblyPath.STREAM_CODEC.decode(buffer), buffer.readBlockPos());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, AssemblyBreakC2SPacket packet) {
            AssemblyPath.STREAM_CODEC.encode(buffer, packet.path);
            buffer.writeBlockPos(packet.localPos);
        }
    };

    public static void handle(AssemblyBreakC2SPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer sender = (ServerPlayer) context.player();
            ServerLevel level = sender.serverLevel();
            AssemblyHost host = packet.path.resolve(level);
            if (host == null) return;
            host.getAssemblyController().breakAssemblyBlock(packet.localPos, sender);
        });
    }

    @Override
    public Type<AssemblyBreakC2SPacket> type() {
        return TYPE;
    }
}
