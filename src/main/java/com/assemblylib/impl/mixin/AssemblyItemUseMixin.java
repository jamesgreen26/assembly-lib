package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.assemblylib.impl.vchunk.AssemblyUseContext;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The single deep chokepoint that makes <em>all</em> raycasting "use" items (water/lava buckets,
 * ender pearls, bottles, splash/lingering potions, fishing rods, ...) target assembly blocks instead
 * of the real world under the player's feet.
 *
 * <p>These items call the static {@code Item.getPlayerPOVHitResult(level, player, fluid)} inside
 * {@code Item#use} to decide what they act on, re-raycasting the real level from the player's actual
 * eye. When the server is driving an assembly interaction (see {@code AssemblyInteractionServer}),
 * that raycast would find the world beneath the player rather than the far-away assembly block the
 * player actually clicked — so the item either does nothing or acts on the wrong spot. While an
 * assembly {@code use()} is in flight, {@link AssemblyUseContext} holds the correct absolute-space
 * hit and we return it here, so the item's own placement/pickup logic runs unmodified at the
 * assembly block's real coordinates and everything downstream (fluid placement, waterlogging, block
 * updates, sync) is plain vanilla.
 */
@Mixin(Item.class)
public abstract class AssemblyItemUseMixin {

    @Inject(method = "getPlayerPOVHitResult", at = @At("HEAD"), cancellable = true)
    private static void assemblylib$overridePovHit(net.minecraft.world.level.Level level,
            net.minecraft.world.entity.player.Player player, ClipContext.Fluid fluidMode,
            CallbackInfoReturnable<BlockHitResult> cir) {
        BlockHitResult override = AssemblyUseContext.current();
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
