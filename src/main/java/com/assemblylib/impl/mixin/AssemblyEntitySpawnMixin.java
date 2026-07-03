package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.assemblylib.impl.vchunk.Assembly;
import com.assemblylib.impl.vchunk.AssemblyManager;
import com.assemblylib.impl.vchunk.AssemblySpace;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Transforms entities that assembly blocks spawn directly into the world (item drops from breaking,
 * dispenser projectiles, primed TNT, etc.). Such code runs against the block's REAL far-away
 * coordinates, so by default the entity would appear ~25M blocks away; this redirect maps its
 * position and velocity through the owning assembly's transform so it appears where the assembly is.
 */
@Mixin(ServerLevel.class)
public abstract class AssemblyEntitySpawnMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void assemblylib$transformAssemblySpawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel level = (ServerLevel) (Object) this;
        BlockPos pos = entity.blockPosition();
        if (!AssemblySpace.isAssemblySpace(pos)) {
            return;
        }
        AssemblyManager manager = AssemblyManager.active(level);
        if (manager == null) {
            return;
        }
        Assembly assembly = manager.assemblyForChunk(new ChunkPos(pos));
        if (assembly == null) {
            return;
        }
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());
        Vec3 local = entity.position().subtract(origin.getX(), origin.getY(), origin.getZ());
        Vec3 world = assembly.currentTransform().localToWorld(local);
        Vec3 velocity = assembly.currentTransform().localDirToWorld(entity.getDeltaMovement());
        entity.setPos(world);
        entity.setDeltaMovement(velocity);
    }
}
