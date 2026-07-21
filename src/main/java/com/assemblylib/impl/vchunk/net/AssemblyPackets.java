package com.assemblylib.impl.vchunk.net;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the real-coordinate assembly engine's packets under their own protocol id ("vchunk-1"),
 * separate from the legacy {@code AssemblyLibPackets} channel so the two systems can coexist until
 * cutover.
 */
public final class AssemblyPackets {

    private static final String PROTOCOL_VERSION = "vchunk-1";

    private AssemblyPackets() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(AssemblySnapshotS2CPacket.TYPE, AssemblySnapshotS2CPacket.STREAM_CODEC, AssemblySnapshotS2CPacket::handle);
        registrar.playToClient(AssemblyBlockDiffS2CPacket.TYPE, AssemblyBlockDiffS2CPacket.STREAM_CODEC, AssemblyBlockDiffS2CPacket::handle);
        registrar.playToClient(AssemblyBlockEventS2CPacket.TYPE, AssemblyBlockEventS2CPacket.STREAM_CODEC, AssemblyBlockEventS2CPacket::handle);
        registrar.playToClient(AssemblyTransformS2CPacket.TYPE, AssemblyTransformS2CPacket.STREAM_CODEC, AssemblyTransformS2CPacket::handle);
        registrar.playToClient(AssemblyRemoveS2CPacket.TYPE, AssemblyRemoveS2CPacket.STREAM_CODEC, AssemblyRemoveS2CPacket::handle);
        registrar.playToServer(AssemblyAttackC2SPacket.TYPE, AssemblyAttackC2SPacket.STREAM_CODEC, AssemblyAttackC2SPacket::handle);
        registrar.playToServer(AssemblyUseC2SPacket.TYPE, AssemblyUseC2SPacket.STREAM_CODEC, AssemblyUseC2SPacket::handle);
    }
}
