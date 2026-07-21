package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.assemblylib.impl.vchunk.AssemblyEffectRedirect;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Single chokepoint fix for the dozens of vanilla "play this canned sound+particle effect at a block
 * position" call sites (block break 2001, door open/close, TNT prime, potion splash, dispenser fail
 * sound, end gateway spawn, ...) that all bottom out at {@code Level#levelEvent(Player, int, BlockPos,
 * int)} — the abstract 4-arg method every other overload delegates to. The previous target, the 3-arg
 * {@code LevelAccessor#levelEvent(int, BlockPos, int)} convenience overload, only caught call sites
 * that happened to use it; block breaking calls the 4-arg form directly (with the breaking player as
 * the excluded {@code except} argument), so its break particles and break sound were never redirected
 * and never reached any client.
 *
 * <p>Rewrite the position to the block's apparent world position and re-enter with a {@code null}
 * {@code except} player: vanilla omits {@code except} from the broadcast because that player predicts
 * the effect client-side, but an assembly block is never simulated on the actor's client, so passing
 * {@code null} lets the actor see/hear the effect too. Both the server's proximity-based packet
 * broadcast and the client's local particle/sound spawn then operate on a normal, nearby coordinate.
 */
@Mixin(ServerLevel.class)
public abstract class AssemblyLevelEventMixin {

    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V",
        at = @At("HEAD"), cancellable = true)
    private void assemblylib$levelEvent(Player except, int type, BlockPos pos, int data, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        Vec3 apparent = AssemblyEffectRedirect.apparentCenter(self, pos);
        if (apparent == null) {
            return;
        }
        self.levelEvent(null, type, BlockPos.containing(apparent), data);
        ci.cancel();
    }
}
