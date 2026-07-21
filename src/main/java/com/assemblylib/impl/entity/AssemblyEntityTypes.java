package com.assemblylib.impl.entity;

import com.assemblylib.AssemblyLib;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Entity-type registry for the entity-based assembly engine. An assembly is a real
 * {@link AssemblyEntity} carrying a captured block set, as opposed to the vchunk engine's
 * far-away real chunks.
 */
public class AssemblyEntityTypes {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, AssemblyLib.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<AssemblyEntity>> ASSEMBLY =
            ENTITY_TYPES.register("assembly", () ->
                    EntityType.Builder.<AssemblyEntity>of(AssemblyEntity::new, MobCategory.MISC)
                            .sized(1f, 1f)
                            .clientTrackingRange(10)
                            .build("assembly")
            );

    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }
}
