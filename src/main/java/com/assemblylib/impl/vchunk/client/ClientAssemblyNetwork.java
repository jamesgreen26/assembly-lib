package com.assemblylib.impl.vchunk.client;

import com.assemblylib.impl.vchunk.net.AssemblyBlockDiffS2CPacket;
import com.assemblylib.impl.vchunk.net.AssemblyBlockEventS2CPacket;
import com.assemblylib.impl.vchunk.net.AssemblyRemoveS2CPacket;
import com.assemblylib.impl.vchunk.net.AssemblySnapshotS2CPacket;
import com.assemblylib.impl.vchunk.net.AssemblyTransformS2CPacket;

/**
 * Client-only handlers for the assembly engine's S2C packets. Referenced from the (common) packet
 * classes only via {@code enqueueWork(() -> ...)}; since the server never receives an S2C packet,
 * this class is never loaded on a dedicated server.
 */
public final class ClientAssemblyNetwork {

    private ClientAssemblyNetwork() {}

    public static void handleSnapshot(AssemblySnapshotS2CPacket packet) {
        int handle = packet.id().handle();
        ClientAssembly assembly = ClientAssemblyManager.get(handle);
        if (assembly == null) {
            assembly = new ClientAssembly(packet.id(), packet.transform());
            ClientAssemblyManager.put(handle, assembly);
        }
        assembly.setSnapshot(packet.transform(), packet.positions(), packet.states(), packet.bePositions(), packet.beTags());
    }

    public static void handleBlockDiff(AssemblyBlockDiffS2CPacket packet) {
        ClientAssembly assembly = ClientAssemblyManager.get(packet.handle());
        if (assembly != null) {
            assembly.applyDiff(packet.positions(), packet.states(), packet.bePositions(), packet.beTags());
        }
        // If the assembly is unknown (diff raced ahead of the initial snapshot), drop it — the full
        // snapshot that must still be in flight carries the complete state anyway.
    }

    public static void handleBlockEvent(AssemblyBlockEventS2CPacket packet) {
        ClientAssembly assembly = ClientAssemblyManager.get(packet.handle());
        if (assembly != null) {
            assembly.applyBlockEvent(packet.localPos(), packet.eventId(), packet.param());
        }
        // Unknown assembly (event raced ahead of the initial snapshot): drop it. A lid event is
        // transient anyway, and the snapshot still in flight carries the block entity's saved state.
    }

    public static void handleTransform(AssemblyTransformS2CPacket packet) {
        ClientAssembly assembly = ClientAssemblyManager.get(packet.handle());
        if (assembly != null) {
            assembly.updateTransform(packet.transform());
        }
    }

    public static void handleRemove(AssemblyRemoveS2CPacket packet) {
        ClientAssemblyManager.remove(packet.handle());
    }
}
