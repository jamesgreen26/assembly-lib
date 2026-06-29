package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.assemblylib.impl.client.AssemblyInteractionClient;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

@Mixin(Player.class)
public class PlayerMixin {

	@Shadow
	@Final
	private Abilities abilities;

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
