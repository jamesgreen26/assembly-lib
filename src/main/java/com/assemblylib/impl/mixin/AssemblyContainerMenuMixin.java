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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Fixes the "menu closes the instant it opens" bug for assembly blocks: the common
 * {@code stillValid(ContainerLevelAccess, Player, Block)} helper (used by chests, furnaces, etc.)
 * checks the player's distance to the block's REAL coordinates — but assembly blocks are genuinely
 * stored ~30,000,000 blocks away while the player stands at the assembly's APPARENT (transformed)
 * position, so the raw check always fails. Redo the check against the block's apparent world
 * position instead, letting every menu-backed block work on an assembly with no per-menu changes.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AssemblyContainerMenuMixin {

    @Inject(
        method = "lambda$stillValid$0(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Ljava/lang/Boolean;",
        at = @At("HEAD"), cancellable = true)
    private static void assemblylib$stillValid(Block block, Player player, Level level, BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        if (!(level instanceof ServerLevel serverLevel) || !AssemblySpace.isAssemblySpace(pos)) {
            return;
        }
        if (!level.getBlockState(pos).is(block)) {
            cir.setReturnValue(false);
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
        AssemblyTransform transform = assembly.currentTransform();
        BlockPos local = AssemblySpace.absoluteToLocal(assembly.slot(), pos);
        Vec3 worldCenter = transform.localToWorld(Vec3.atCenterOf(local));
        cir.setReturnValue(player.distanceToSqr(worldCenter.x, worldCenter.y, worldCenter.z) <= 64.0);
    }
}
