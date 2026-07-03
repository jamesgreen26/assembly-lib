package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.assemblylib.impl.vchunk.AssemblySpace;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;

/**
 * The plotgrid sits past 30,000,000 — outside the default world border. Vanilla gates block-entity
 * ticking ({@code LevelChunk.isTicking} → {@code worldBorder.isWithinBounds}) and some placement on
 * the border, so we report assembly-space positions as in-bounds. Real terrain never reaches out
 * there, so this only affects assembly chunks.
 */
@Mixin(WorldBorder.class)
public abstract class AssemblyWorldBorderMixin {

    @Inject(method = "isWithinBounds(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void assemblylib$isWithinBoundsBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (AssemblySpace.isAssemblySpace(pos)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isWithinBounds(Lnet/minecraft/world/level/ChunkPos;)Z", at = @At("HEAD"), cancellable = true)
    private void assemblylib$isWithinBoundsChunk(ChunkPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (AssemblySpace.isAssemblyChunk(pos)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isWithinBounds(DD)Z", at = @At("HEAD"), cancellable = true)
    private void assemblylib$isWithinBoundsCoords(double x, double z, CallbackInfoReturnable<Boolean> cir) {
        if (AssemblySpace.isAssemblySpace((int) Math.floor(x), (int) Math.floor(z))) {
            cir.setReturnValue(true);
        }
    }
}
