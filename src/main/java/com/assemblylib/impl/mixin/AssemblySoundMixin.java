package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.assemblylib.impl.vchunk.AssemblyEffectRedirect;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The single deepest chokepoint for every positional sound in the game: {@code playSound(Player,
 * BlockPos, ...)} (buttons, doors, ...), {@code playSound(Player, double..., SoundEvent, ...)}, and
 * every block that calls {@code playSeededSound} directly (note blocks via {@code SoundSource.RECORDS},
 * jukeboxes, ...) all funnel through {@code ServerLevel#playSeededSound(Player, double, double,
 * double, Holder, SoundSource, float, float, long)} before the server builds its distance-filtered
 * broadcast. Hooking the shallower {@code Level#playSound(Player, BlockPos, ...)} overload (the
 * previous target) missed the direct-{@code playSeededSound} callers, which is why buttons played but
 * note blocks were silent.
 *
 * <p>Redirect the origin to the block's apparent world position, then re-enter with a {@code null}
 * source player: vanilla excludes the {@code player} argument from the broadcast on the assumption
 * they hear it via client-side prediction, but for an assembly block (which the actor's client never
 * simulates locally) that would silence the sound for the very player who triggered it. Passing
 * {@code null} makes everyone in range — including the actor — hear it exactly once.
 */
@Mixin(ServerLevel.class)
public abstract class AssemblySoundMixin {

    @Inject(
        method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
        at = @At("HEAD"), cancellable = true)
    private void assemblylib$playSeededSound(Player player, double x, double y, double z, Holder<SoundEvent> sound,
            SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        Vec3 apparent = AssemblyEffectRedirect.apparentPoint(self, x, y, z);
        if (apparent == null) {
            return;
        }
        self.playSeededSound(null, apparent.x, apparent.y, apparent.z, sound, source, volume, pitch, seed);
        ci.cancel();
    }
}
