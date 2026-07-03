package com.assemblylib.impl.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces the local player's VIEW rotation to be interpolated across render frames. Ported verbatim
 * from VEL's {@code MixinLocalPlayer} (itself a direct port of Valkyrien Skies'
 * {@code MixinLocalPlayer.preGetViewYRot}).
 *
 * <p>Vanilla {@link LocalPlayer#getViewYRot(float)} returns the RAW {@code getYRot()} (no lerp) when
 * the player isn't a passenger -- fine for mouse-look (instant aim), but it means any per-tick change
 * to the player's yaw renders in 20 discrete steps per second. When an assembly carries (rotates) the
 * player (see {@code com.assemblylib.impl.vchunk.collision.drag.AssemblyDrag}), the dragger updates
 * yaw once per tick, so without this the view snaps at tick rate. The position is already
 * interpolated by the camera; only the view rotation was missing it. This can only be done by
 * overriding the vanilla render method -- there is no movement/"push" API that affects how the view
 * rotation is interpolated.
 */
@Mixin(LocalPlayer.class)
public abstract class AssemblyLocalPlayerViewYawMixin {

    @Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
    private void assemblylib$smoothViewYRot(float partialTick, CallbackInfoReturnable<Float> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        // When riding a vehicle, vanilla already delegates to the interpolating super impl.
        if (self.isPassenger()) return;
        cir.setReturnValue(Mth.lerp(partialTick, self.yRotO, self.getYRot()));
    }
}
