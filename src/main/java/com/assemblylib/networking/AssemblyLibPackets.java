package com.assemblylib.networking;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class AssemblyLibPackets {
    private static final String PROTOCOL_VERSION = "1";

    private AssemblyLibPackets() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(AssemblyBreakC2SPacket.TYPE, AssemblyBreakC2SPacket.STREAM_CODEC, AssemblyBreakC2SPacket::handle);
        registrar.playToServer(AssemblyPlaceC2SPacket.TYPE, AssemblyPlaceC2SPacket.STREAM_CODEC, AssemblyPlaceC2SPacket::handle);
        registrar.playToServer(AssemblyUseC2SPacket.TYPE, AssemblyUseC2SPacket.STREAM_CODEC, AssemblyUseC2SPacket::handle);
        registrar.playToServer(AssemblyBreakProgressC2SPacket.TYPE, AssemblyBreakProgressC2SPacket.STREAM_CODEC, AssemblyBreakProgressC2SPacket::handle);
        registrar.playToClient(AssemblyDestroyStageS2CPacket.TYPE, AssemblyDestroyStageS2CPacket.STREAM_CODEC, AssemblyDestroyStageS2CPacket::handle);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
