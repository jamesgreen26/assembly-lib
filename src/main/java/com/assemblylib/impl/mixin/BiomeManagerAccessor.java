package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.biome.BiomeManager;

/**
 * Exposes the biome zoom seed so {@code WrappedServerLevel} can pass it to the
 * {@link net.minecraft.server.level.ServerLevel} constructor (keeping biome lookups consistent with
 * the wrapped level). Replaces catnip's {@code BiomeManagerAccessor}.
 */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {

	@Accessor("biomeZoomSeed")
	long zps$getBiomeZoomSeed();
}
