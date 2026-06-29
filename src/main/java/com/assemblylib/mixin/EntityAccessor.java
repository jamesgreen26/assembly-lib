package com.assemblylib.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Exposes {@link Entity}'s protected {@code setLevel} so a assembly can re-home an entity that a
 * block/block-entity tick tried to spawn into the simulation level onto the real outer level instead
 * (see {@code AssemblySimServerLevel#addFreshEntity}).
 */
@Mixin(Entity.class)
public interface EntityAccessor {

	@Invoker("setLevel")
	void zps$setLevel(Level level);
}
