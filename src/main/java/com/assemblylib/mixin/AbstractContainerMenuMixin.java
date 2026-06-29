package com.assemblylib.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.assemblylib.assembly.AssemblySimServerLevel;
import com.assemblylib.assembly.AssemblyTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Companion to {@link ContainerMixin} for the other standard reach helper. {@code ServerPlayer#tick}
 * closes a menu whose {@link AbstractContainerMenu#stillValid(Player)} returns false; the common
 * {@code stillValid(ContainerLevelAccess, Player, Block)} helper measures the 8-block distance to the
 * block's position, but a block entity riding a assembly sits at assembly-LOCAL coordinates in
 * {@link AssemblySimServerLevel}, so the check fails the instant the GUI opens and the menu
 * vanishes. When the lookup level is a assembly sim level, redo the distance test against the
 * block's actual rotated WORLD position. Fixing it in the shared helper means every menu-backed block
 * works on a assembly with no per-menu changes.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

	@Inject(method = "lambda$stillValid$0(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Ljava/lang/Boolean;",
		at = @At("HEAD"), cancellable = true)
	private static void zps$assemblyStillValid(Block block, Player player, Level level, BlockPos pos,
		CallbackInfoReturnable<Boolean> cir) {
		if (!(level instanceof AssemblySimServerLevel sim))
			return;
		if (!level.getBlockState(pos).is(block)) {
			cir.setReturnValue(false);
			return;
		}
		AssemblyTransform transform = sim.getTransform();
		if (transform == null) {
			// No pose available (e.g. tests): block identity already checked, accept.
			cir.setReturnValue(true);
			return;
		}
		// Mirror vanilla's check (distance from the player to the block centre <= 8 blocks), but at
		// the block's rotated world position rather than its assembly-local coordinates.
		Vec3 worldCenter = transform.localBlockCenterToWorld(pos);
		cir.setReturnValue(player.distanceToSqr(worldCenter.x, worldCenter.y, worldCenter.z) <= 64.0);
	}
}
