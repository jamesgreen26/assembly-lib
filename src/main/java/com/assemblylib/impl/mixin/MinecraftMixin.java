package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.assemblylib.impl.client.AssemblyInteractionClient;
import net.minecraft.client.Minecraft;

/**
 * Routes left/right click input to the assembly interaction handler when the
 * player is aiming at a assembly block (which lives in local space and is air
 * in the world, so vanilla's pick pipeline ignores it). When handled, vanilla's
 * world interaction is cancelled.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void zps$startAttack(CallbackInfoReturnable<Boolean> cir) {
		if (AssemblyInteractionClient.handleStartAttack())
			cir.setReturnValue(true);
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void zps$continueAttack(boolean leftDown, CallbackInfo ci) {
		if (AssemblyInteractionClient.handleContinueAttack(leftDown))
			ci.cancel();
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void zps$startUseItem(CallbackInfo ci) {
		if (AssemblyInteractionClient.handleStartUseItem())
			ci.cancel();
	}

	@Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
	private void zps$pickBlock(CallbackInfo ci) {
		if (AssemblyInteractionClient.handlePickBlock())
			ci.cancel();
	}
}
