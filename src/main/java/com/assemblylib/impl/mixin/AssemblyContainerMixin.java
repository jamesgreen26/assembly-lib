package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.assemblylib.impl.vchunk.Assembly;
import com.assemblylib.impl.vchunk.AssemblyManager;
import com.assemblylib.impl.vchunk.AssemblySpace;
import com.assemblylib.impl.vchunk.AssemblyTransform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Companion to {@link AssemblyContainerMenuMixin} for the other standard reach helper
 * ({@link Container#stillValidBlockEntity}), used directly by some block entities (e.g. lecterns,
 * beacons). Same fix: redo the reach check against the block's apparent world position instead of
 * its real (far-away) storage coordinates.
 */
@Mixin(Container.class)
public interface AssemblyContainerMixin {

    @Inject(
        method = "stillValidBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/player/Player;F)Z",
        at = @At("HEAD"), cancellable = true)
    private static void assemblylib$stillValid(BlockEntity blockEntity, Player player, float distance,
            CallbackInfoReturnable<Boolean> cir) {
        Level level = blockEntity.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos();
        if (!AssemblySpace.isAssemblySpace(pos)) {
            return;
        }
        AssemblyManager manager = AssemblyManager.active(serverLevel);
        if (manager == null) {
            return;
        }
        Assembly assembly = manager.assemblyForChunk(new ChunkPos(pos));
        if (assembly == null) {
            return;
        }
        if (level.getBlockEntity(pos) != blockEntity) {
            cir.setReturnValue(false);
            return;
        }
        AssemblyTransform transform = assembly.currentTransform();
        BlockPos local = AssemblySpace.absoluteToLocal(assembly.slot(), pos);
        Vec3 worldCenter = transform.localToWorld(Vec3.atCenterOf(local));
        double reach = player.blockInteractionRange() + distance;
        cir.setReturnValue(worldCenter.distanceToSqr(player.getEyePosition()) < reach * reach);
    }
}
