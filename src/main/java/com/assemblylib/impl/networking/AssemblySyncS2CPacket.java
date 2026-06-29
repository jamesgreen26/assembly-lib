package com.assemblylib.impl.networking;

import com.assemblylib.AssemblyLib;
import com.assemblylib.api.AssemblyHost;
import com.assemblylib.impl.assembly.AssemblyPath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Pushes an assembly's state to watching clients on its own channel, instead of piggybacking on the
 * host's block-entity update / entity-data sync. {@code path} names the (possibly nested) host whose
 * assembly this is; {@code state} is the controller's serialized structure ({@link
 * com.assemblylib.impl.assembly.AssemblyController#writeState}). Sent on every edit to a root host's
 * trackers and once to each player as they start tracking the host.
 */
public record AssemblySyncS2CPacket(AssemblyPath path, CompoundTag state) implements CustomPacketPayload {
    public static final Type<AssemblySyncS2CPacket> TYPE = new Type<>(AssemblyLib.resource("assembly_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblySyncS2CPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblySyncS2CPacket decode(RegistryFriendlyByteBuf buffer) {
            AssemblyPath path = AssemblyPath.STREAM_CODEC.decode(buffer);
            CompoundTag state = buffer.readNbt();
            return new AssemblySyncS2CPacket(path, state == null ? new CompoundTag() : state);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, AssemblySyncS2CPacket packet) {
            AssemblyPath.STREAM_CODEC.encode(buffer, packet.path);
            buffer.writeNbt(packet.state);
        }
    };

    public static void handle(AssemblySyncS2CPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            AssemblyHost host = packet.path.resolve(player.level());
            if (host == null)
                return;
            host.getAssemblyController().readState(packet.state, player.registryAccess());
        });
    }

    @Override
    public Type<AssemblySyncS2CPacket> type() {
        return TYPE;
    }
}
