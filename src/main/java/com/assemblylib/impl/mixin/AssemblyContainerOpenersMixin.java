package com.assemblylib.impl.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.assemblylib.impl.vchunk.AssemblySpace;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;

/**
 * Deep, per-block-type-free fix for open/close animations and sounds on EVERY vanilla/modded
 * container (chest, barrel, shulker box, ender chest, ...). All of them share one bookkeeper —
 * {@link ContainerOpenersCounter} — which periodically reconciles its live opener count against
 * reality by counting nearby players with this container open, via the private
 * {@code getPlayersWithContainerOpen(Level, BlockPos)}. That scan is an AABB around the block's
 * position; for a vchunk assembly block that position is the block's REAL storage coordinate tens of
 * millions of blocks away, where no player ever stands — so the recheck always sees zero openers and
 * resets {@code openCount} to 0 while the menu is still open. That desync is exactly the reported
 * symptom: the open sound fires once (from the direct {@code incrementOpeners}), the recheck then
 * corrupts the counter, and no further open/close sound ever plays.
 *
 * <p>The player↔container link (a player's {@code containerMenu}) is authoritative and
 * position-independent, so for an assembly position we bypass the distance scan entirely and return
 * exactly the players in this level whose menu is this container. The recheck then agrees with the
 * live count, and open/close sounds + the lid block-event fire correctly and repeatably.
 */
@Mixin(ContainerOpenersCounter.class)
public abstract class AssemblyContainerOpenersMixin {

    @Shadow
    protected abstract boolean isOwnContainer(Player player);

    @Inject(method = "getPlayersWithContainerOpen(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Ljava/util/List;",
        at = @At("HEAD"), cancellable = true)
    private void assemblylib$getPlayersWithContainerOpen(Level level, BlockPos pos,
            CallbackInfoReturnable<List<Player>> cir) {
        if (!(level instanceof ServerLevel serverLevel) || !AssemblySpace.isAssemblySpace(pos)) {
            return;
        }
        List<Player> open = new ArrayList<>();
        for (Player player : serverLevel.players()) {
            if (isOwnContainer(player)) {
                open.add(player);
            }
        }
        cir.setReturnValue(open);
    }
}
