package com.assemblylib.impl.vchunk.net;

import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.vchunk.client.ClientAssemblyNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Forwards a vanilla block event ({@code BlockState#triggerEvent} / {@code Level#blockEvent}) for one
 * assembly cell to the client mirror. Block events drive transient, client-only visual state that is
 * NOT part of a block's saved state — the chest/ender-chest/shulker lid controller, the note-block
 * pling animation, a bell's swing — so they never travel through the assembly's block-state diff sync.
 * Vanilla itself only broadcasts these to players tracking the event's real chunk, which for an
 * assembly plot (tens of millions of blocks from any player) is nobody; the server therefore emits
 * this packet instead, and the client replays {@code triggerEvent} on the mirror block entity so the
 * animation plays wherever the assembly currently appears.
 *
 * @param handle   the assembly handle
 * @param localPos the cell in assembly-LOCAL space
 * @param eventId  the vanilla block-event id (paramA — e.g. 1 for a chest lid)
 * @param param    the vanilla block-event data (paramB — e.g. the open count)
 */
public record AssemblyBlockEventS2CPacket(int handle, BlockPos localPos, int eventId, int param)
        implements CustomPacketPayload {

    public static final Type<AssemblyBlockEventS2CPacket> TYPE = new Type<>(AssemblyLib.resource("vchunk_block_event"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyBlockEventS2CPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblyBlockEventS2CPacket decode(RegistryFriendlyByteBuf buf) {
            int handle = buf.readVarInt();
            BlockPos localPos = buf.readBlockPos();
            int eventId = buf.readVarInt();
            int param = buf.readVarInt();
            return new AssemblyBlockEventS2CPacket(handle, localPos, eventId, param);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AssemblyBlockEventS2CPacket packet) {
            buf.writeVarInt(packet.handle);
            buf.writeBlockPos(packet.localPos);
            buf.writeVarInt(packet.eventId);
            buf.writeVarInt(packet.param);
        }
    };

    public static void handle(AssemblyBlockEventS2CPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientAssemblyNetwork.handleBlockEvent(packet));
    }

    @Override
    public Type<AssemblyBlockEventS2CPacket> type() {
        return TYPE;
    }
}
