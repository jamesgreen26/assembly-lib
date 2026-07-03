package com.assemblylib.impl.vchunk.net;

import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.vchunk.client.ClientAssemblyNetwork;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Tells clients an assembly was deleted; they drop its mirror and dispose its baked mesh. */
public record AssemblyRemoveS2CPacket(int handle) implements CustomPacketPayload {

    public static final Type<AssemblyRemoveS2CPacket> TYPE = new Type<>(AssemblyLib.resource("vchunk_remove"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyRemoveS2CPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblyRemoveS2CPacket decode(RegistryFriendlyByteBuf buf) {
            return new AssemblyRemoveS2CPacket(buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AssemblyRemoveS2CPacket packet) {
            buf.writeVarInt(packet.handle);
        }
    };

    public static void handle(AssemblyRemoveS2CPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientAssemblyNetwork.handleRemove(packet));
    }

    @Override
    public Type<AssemblyRemoveS2CPacket> type() {
        return TYPE;
    }
}
