package com.assemblylib.impl.vchunk.net;

import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.vchunk.AssemblyTransform;
import com.assemblylib.impl.vchunk.client.ClientAssemblyNetwork;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Per-tick transform update for one assembly (cheap, small payload). The client shifts its current
 * transform to previous and adopts this as the new current, then interpolates between them by
 * partial-tick — exactly like vanilla entity position sync.
 */
public record AssemblyTransformS2CPacket(int handle, AssemblyTransform transform) implements CustomPacketPayload {

    public static final Type<AssemblyTransformS2CPacket> TYPE = new Type<>(AssemblyLib.resource("vchunk_transform"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyTransformS2CPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblyTransformS2CPacket decode(RegistryFriendlyByteBuf buf) {
            int handle = buf.readVarInt();
            AssemblyTransform transform = AssemblyTransform.STREAM_CODEC.decode(buf);
            return new AssemblyTransformS2CPacket(handle, transform);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AssemblyTransformS2CPacket packet) {
            buf.writeVarInt(packet.handle);
            AssemblyTransform.STREAM_CODEC.encode(buf, packet.transform);
        }
    };

    public static void handle(AssemblyTransformS2CPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientAssemblyNetwork.handleTransform(packet));
    }

    @Override
    public Type<AssemblyTransformS2CPacket> type() {
        return TYPE;
    }
}
