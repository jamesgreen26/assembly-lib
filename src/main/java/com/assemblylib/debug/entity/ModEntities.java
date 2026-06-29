package com.assemblylib.debug.entity;

import com.assemblylib.AssemblyLib;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {
	public static final DeferredRegister<EntityType<?>> ENTITIES =
		DeferredRegister.create(Registries.ENTITY_TYPE, AssemblyLib.MOD_ID);

	public static final DeferredHolder<EntityType<?>, EntityType<AssemblyHostEntity>> ASSEMBLY_HOST =
		ENTITIES.register("assembly_host", () -> EntityType.Builder
			.of(AssemblyHostEntity::new, MobCategory.CREATURE)
			.sized(1.25f, 1.05f)
			.eyeHeight(0.55f)
			.clientTrackingRange(10)
			.updateInterval(2)
			.build(AssemblyLib.resource("assembly_host").toString()));

	public static void registerAttributes(EntityAttributeCreationEvent event) {
		event.put(ASSEMBLY_HOST.get(), AssemblyHostEntity.createAttributes().build());
	}
}
