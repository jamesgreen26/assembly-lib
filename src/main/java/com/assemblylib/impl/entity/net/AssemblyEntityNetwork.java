package com.assemblylib.impl.entity.net;

import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.entity.Assembly;
import com.assemblylib.impl.entity.AssemblyBlock;
import com.assemblylib.impl.entity.AssemblyCodec;
import com.assemblylib.impl.entity.AssemblyEntity;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

public class AssemblyEntityNetwork {

    public static void register(IEventBus modBus) {
        modBus.addListener(AssemblyEntityNetwork::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(AssemblyLib.MOD_ID);

        registrar.playToClient(
                AssemblySyncPayload.TYPE,
                AssemblySyncPayload.CODEC,
                (payload, context) -> {
                    // This runs on the CLIENT
                    context.enqueueWork(() -> {
                        var level = net.minecraft.client.Minecraft.getInstance().level;
                        if (level == null) return;

                        Entity entity = level.getEntity(payload.entityId());
                        if (!(entity instanceof AssemblyEntity assemblyEntity)) return;

                        // Decode palette-backed NBT into blocks using client registry
                        List<AssemblyBlock> blocks =
                                AssemblyCodec.load(payload.assemblyNbt(), level.registryAccess());

                        assemblyEntity.setAssembly(new Assembly(blocks));
                    });
                }
        );
    }
}
