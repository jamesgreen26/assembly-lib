package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import com.assemblylib.impl.assembly.AssemblyGroundEntity;
import com.assemblylib.impl.client.AssemblyInteractionClient;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

@Mixin(Player.class)
public class PlayerMixin implements AssemblyGroundEntity {

	@Shadow
	@Final
	private Abilities abilities;

	@Unique
	private boolean zps$onAssemblyGround;

	@Override
	public void zps$setOnAssemblyGround(boolean onGround) {
		this.zps$onAssemblyGround = onGround;
	}

	@Override
	public boolean zps$isOnAssemblyGround() {
		return this.zps$onAssemblyGround;
	}

	/**
	 * View bobbing (and other onGround-gated effects) in {@link Player#aiStep()} read {@code onGround()}
	 * right after vanilla {@code move()} has cleared it — the assembly is not a real block — so a player
	 * walking on an assembly bobs as if airborne. Treat them as grounded when their last assembly
	 * collision left them standing on one.
	 */
	@ModifyExpressionValue(method = "aiStep",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;onGround()Z"))
	private boolean zps$bobAsGroundedOnAssembly(boolean original) {
		return original || zps$onAssemblyGround;
	}

	@Shadow
	protected boolean isStayingOnGroundSurface() {
		throw new AssertionError();
	}

	@Shadow
	private boolean isAboveGround(float maxUpStep) {
		throw new AssertionError();
	}

	@Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
	private void zps$maybeBackOffFromAssemblyEdge(Vec3 movement, MoverType moverType,
		CallbackInfoReturnable<Vec3> cir) {
		Player self = (Player) (Object) this;
		float maxUpStep = self.maxUpStep();
		if (!abilities.flying && movement.y <= 0.0D && (moverType == MoverType.SELF || moverType == MoverType.PLAYER)
			&& isStayingOnGroundSurface()
			&& (isAboveGround(maxUpStep) || AssemblyInteractionClient.isAboveAssemblyGround(self, maxUpStep))) {
			Vec3 adjusted = AssemblyInteractionClient.maybeBackOffFromAssemblyEdge(self, movement, maxUpStep);
			if (adjusted != null)
				cir.setReturnValue(adjusted);
		}
	}
}
