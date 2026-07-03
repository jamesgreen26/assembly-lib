package com.assemblylib.impl.vchunk;

import java.util.UUID;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Stable identity for an assembly.
 *
 * <p>The {@link #uuid} is the persistent identity (survives slot reuse across restarts and is what
 * save data keys on). The {@link #handle} is a dense runtime int used for fast lookups and compact
 * packet payloads within a single session; it is assigned by {@link AssemblyManager} and is NOT
 * stable across restarts.
 */
public record AssemblyId(UUID uuid, int handle) {

    /** Stream codec for the full id (used in create/remove packets). */
    public static final StreamCodec<FriendlyByteBuf, AssemblyId> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblyId decode(FriendlyByteBuf buf) {
            UUID uuid = buf.readUUID();
            int handle = buf.readVarInt();
            return new AssemblyId(uuid, handle);
        }

        @Override
        public void encode(FriendlyByteBuf buf, AssemblyId id) {
            buf.writeUUID(id.uuid);
            buf.writeVarInt(id.handle);
        }
    };

    @Override
    public boolean equals(Object o) {
        return o instanceof AssemblyId other && other.uuid.equals(this.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }
}
