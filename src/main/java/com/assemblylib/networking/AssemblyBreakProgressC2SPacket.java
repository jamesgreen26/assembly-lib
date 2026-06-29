package com.assemblylib.networking;

import com.assemblylib.AssemblyLib;
import com.assemblylib.blockentity.ServoMotorBlockEntity;
import com.assemblylib.assembly.AssemblyPath;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent by a mining player as the crack stage of a assembly block changes
 * ({@code stage} 0-9, or -1 to clear). The server rebroadcasts it to nearby
 * players (except the sender) so they see the crack progress too.
 */
public record AssemblyBreakProgressC2SPacket(AssemblyPath path, BlockPos localPos, int stage)
        implements CustomPacketPayload {
    public static final Type<AssemblyBreakProgressC2SPacket> TYPE =
            new Type<>(AssemblyLib.resource("assembly_break_progress"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyBreakProgressC2SPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblyBreakProgressC2SPacket decode(RegistryFriendlyByteBuf buffer) {
            return new AssemblyBreakProgressC2SPacket(AssemblyPath.STREAM_CODEC.decode(buffer),
                    buffer.readBlockPos(), buffer.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, AssemblyBreakProgressC2SPacket packet) {
            AssemblyPath.STREAM_CODEC.encode(buffer, packet.path);
            buffer.writeBlockPos(packet.localPos);
            buffer.writeVarInt(packet.stage);
        }
    };

    private static final double BROADCAST_RANGE_SQR = 64.0 * 64.0;

    public static void handle(AssemblyBreakProgressC2SPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer sender = (ServerPlayer) context.player();
            ServerLevel level = sender.serverLevel();
            if (packet.path.resolve(level) == null) return;

            Vec3 motorCenter = Vec3.atCenterOf(packet.path.rootMotorPos());
            AssemblyDestroyStageS2CPacket s2c =
                    new AssemblyDestroyStageS2CPacket(packet.path, packet.localPos, sender.getId(), packet.stage);
            for (ServerPlayer player : level.players()) {
                if (player == sender) continue;
                if (player.position().distanceToSqr(motorCenter) > BROADCAST_RANGE_SQR) continue;
                PacketDistributor.sendToPlayer(player, s2c);
            }
        });
    }

    @Override
    public Type<AssemblyBreakProgressC2SPacket> type() {
        return TYPE;
    }
}
