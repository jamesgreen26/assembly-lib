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
        registrar.playToServer(ContraptionBreakC2SPacket.TYPE, ContraptionBreakC2SPacket.STREAM_CODEC, ContraptionBreakC2SPacket::handle);
        registrar.playToServer(ContraptionPlaceC2SPacket.TYPE, ContraptionPlaceC2SPacket.STREAM_CODEC, ContraptionPlaceC2SPacket::handle);
        registrar.playToServer(ContraptionUseC2SPacket.TYPE, ContraptionUseC2SPacket.STREAM_CODEC, ContraptionUseC2SPacket::handle);
        registrar.playToServer(ContraptionBreakProgressC2SPacket.TYPE, ContraptionBreakProgressC2SPacket.STREAM_CODEC, ContraptionBreakProgressC2SPacket::handle);
        registrar.playToClient(ContraptionDestroyStageS2CPacket.TYPE, ContraptionDestroyStageS2CPacket.STREAM_CODEC, ContraptionDestroyStageS2CPacket::handle);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
