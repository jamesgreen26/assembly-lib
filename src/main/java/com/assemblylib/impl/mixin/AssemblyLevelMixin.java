package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.assemblylib.impl.vchunk.AssemblySpace;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The plotgrid sits past 30,000,000 — beyond vanilla's {@code Level.isInWorldBounds} limit
 * ({@code |X|,|Z| < 30,000,000}). Various vanilla systems (block placement, fluid spread neighbour
 * probing, feature checks) refuse to operate outside those bounds, so we report assembly-space
 * positions as in-bounds. Only assembly chunks are affected; real terrain never reaches out there.
 */
@Mixin(Level.class)
public abstract class AssemblyLevelMixin {

    @Inject(method = "isInWorldBounds(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void assemblylib$isInWorldBounds(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (AssemblySpace.isAssemblySpace(pos)) {
            // still respect build height
            Level self = (Level) (Object) this;
            if (!self.isOutsideBuildHeight(pos)) {
                cir.setReturnValue(true);
            }
        }
    }
}
