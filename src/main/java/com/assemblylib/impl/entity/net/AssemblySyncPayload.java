package com.assemblylib.impl.entity.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.entity.AssemblyBlock;
import com.assemblylib.impl.entity.AssemblyCodec;

import java.util.List;

public record AssemblySyncPayload(int entityId, CompoundTag assemblyNbt) implements CustomPacketPayload {

    public static final Type<AssemblySyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AssemblyLib.MOD_ID, "assembly_sync"));

    public static final StreamCodec<FriendlyByteBuf, AssemblySyncPayload> CODEC =
            StreamCodec.of(AssemblySyncPayload::encode, AssemblySyncPayload::decode);

    public static AssemblySyncPayload of(int entityId, List<AssemblyBlock> blocks) {
        return new AssemblySyncPayload(entityId, AssemblyCodec.save(blocks));
    }

    private static void encode(FriendlyByteBuf buf, AssemblySyncPayload payload) {
        buf.writeInt(payload.entityId());
        buf.writeNbt(payload.assemblyNbt());
    }

    private static AssemblySyncPayload decode(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        CompoundTag tag = buf.readNbt();
        return new AssemblySyncPayload(entityId, tag);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}