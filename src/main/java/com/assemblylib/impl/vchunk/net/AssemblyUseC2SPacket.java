package com.assemblylib.impl.vchunk.net;

import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.vchunk.AssemblyInteractionServer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client asks the server to use/place against a block on an assembly (raycast resolved client-side). */
public record AssemblyUseC2SPacket(int handle, BlockPos local, Direction face, Vec3 localHit, InteractionHand hand)
        implements CustomPacketPayload {

    public static final Type<AssemblyUseC2SPacket> TYPE = new Type<>(AssemblyLib.resource("vchunk_use"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyUseC2SPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblyUseC2SPacket decode(RegistryFriendlyByteBuf buf) {
            int handle = buf.readVarInt();
            BlockPos local = buf.readBlockPos();
            Direction face = buf.readEnum(Direction.class);
            Vec3 hit = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            InteractionHand hand = buf.readEnum(InteractionHand.class);
            return new AssemblyUseC2SPacket(handle, local, face, hit, hand);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AssemblyUseC2SPacket packet) {
            buf.writeVarInt(packet.handle);
            buf.writeBlockPos(packet.local);
            buf.writeEnum(packet.face);
            buf.writeDouble(packet.localHit.x);
            buf.writeDouble(packet.localHit.y);
            buf.writeDouble(packet.localHit.z);
            buf.writeEnum(packet.hand);
        }
    };

    public static void handle(AssemblyUseC2SPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                AssemblyInteractionServer.handleUse(player, packet.handle, packet.local, packet.face, packet.localHit, packet.hand);
            }
        });
    }

    @Override
    public Type<AssemblyUseC2SPacket> type() {
        return TYPE;
    }
}
