package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.assemblylib.impl.vchunk.AssemblyEffectRedirect;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Single chokepoint fix for every block-triggered sound in the game (buttons, doors, furnaces,
 * note blocks, brewing stands, anvils, ...): {@code Level#playSound(Player, BlockPos, ...)} is the
 * one method all of them funnel through before reaching the double-coordinate overload. Redirect the
 * position to the block's apparent world position before vanilla computes distance-based volume/
 * panning and picks which players to send the sound packet to — so it plays in the right place, at
 * the right volume, to the right players, with zero per-block-type changes.
 */
@Mixin(Level.class)
public abstract class AssemblySoundMixin {

    @Inject(
        method = "playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
        at = @At("HEAD"), cancellable = true)
    private void assemblylib$playSound(Player player, BlockPos pos, SoundEvent sound, SoundSource source,
            float volume, float pitch, CallbackInfo ci) {
        Level self = (Level) (Object) this;
        if (!(self instanceof ServerLevel serverLevel)) {
            return;
        }
        Vec3 apparent = AssemblyEffectRedirect.apparentCenter(serverLevel, pos);
        if (apparent == null) {
            return;
        }
        self.playSound(player, apparent.x, apparent.y, apparent.z, sound, source, volume, pitch);
        ci.cancel();
    }
}
