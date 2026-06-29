package com.assemblylib.networking;

import com.assemblylib.AssemblyLib;
import com.assemblylib.assembly.AssemblyHost;
import com.assemblylib.assembly.AssemblyPath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Sent when a player places a held block onto a assembly face (possibly nested). */
public record AssemblyPlaceC2SPacket(AssemblyPath path, BlockPos localPos, Direction localFace, Vec3 localHit,
        InteractionHand hand) implements CustomPacketPayload {
    public static final Type<AssemblyPlaceC2SPacket> TYPE = new Type<>(AssemblyLib.resource("assembly_place"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyPlaceC2SPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblyPlaceC2SPacket decode(RegistryFriendlyByteBuf buffer) {
            AssemblyPath path = AssemblyPath.STREAM_CODEC.decode(buffer);
            BlockPos localPos = buffer.readBlockPos();
            Direction localFace = buffer.readEnum(Direction.class);
            Vec3 localHit = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            InteractionHand hand = buffer.readEnum(InteractionHand.class);
            return new AssemblyPlaceC2SPacket(path, localPos, localFace, localHit, hand);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, AssemblyPlaceC2SPacket packet) {
            AssemblyPath.STREAM_CODEC.encode(buffer, packet.path);
            buffer.writeBlockPos(packet.localPos);
            buffer.writeEnum(packet.localFace);
            buffer.writeDouble(packet.localHit.x);
            buffer.writeDouble(packet.localHit.y);
            buffer.writeDouble(packet.localHit.z);
            buffer.writeEnum(packet.hand);
        }
    };

    public static void handle(AssemblyPlaceC2SPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer sender = (ServerPlayer) context.player();
            ServerLevel level = sender.serverLevel();
            AssemblyHost host = packet.path.resolve(level);
            if (host == null) return;
            host.placeAssemblyBlock(packet.localPos, packet.localFace, packet.localHit, sender, packet.hand);
        });
    }

    @Override
    public Type<AssemblyPlaceC2SPacket> type() {
        return TYPE;
    }
}
