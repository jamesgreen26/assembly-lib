package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.assemblylib.impl.vchunk.AssemblyEffectRedirect;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

/**
 * Single chokepoint fix for the dozens of vanilla "play this canned sound+particle effect at a block
 * position" call sites (block break, door open/close, TNT prime, potion splash, dispenser fail sound,
 * end gateway spawn, ...) that all go through {@code LevelAccessor#levelEvent(int, BlockPos, int)}.
 * Rewrite the position to the block's apparent world position before the (per-side) real
 * implementation runs, so both the server's proximity-based packet broadcast and the client's local
 * particle/sound spawn operate on a normal, nearby coordinate — with zero changes needed as new
 * vanilla (or modded) behaviors are added that happen to route through this method.
 */
@Mixin(LevelAccessor.class)
public interface AssemblyLevelEventMixin {

    @Inject(method = "levelEvent(ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"), cancellable = true)
    private void assemblylib$levelEvent(int type, BlockPos pos, int data, CallbackInfo ci) {
        if (!(this instanceof ServerLevel serverLevel)) {
            return;
        }
        Vec3 apparent = AssemblyEffectRedirect.apparentCenter(serverLevel, pos);
        if (apparent == null) {
            return;
        }
        serverLevel.levelEvent(null, type, BlockPos.containing(apparent), data);
        ci.cancel();
    }
}
