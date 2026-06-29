package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Exposes the server's {@code storageSource} so {@code WrappedServerLevel} can construct a genuine
 * {@link net.minecraft.server.level.ServerLevel} from an existing one (it needs the same save
 * storage). Replaces catnip's {@code MinecraftServerAccessor}.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

	@Accessor("storageSource")
	LevelStorageSource.LevelStorageAccess zps$getStorageSource();
}
