package com.assemblylib.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.assemblylib.assembly.AssemblyMenuContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Client-side: while a menu is being constructed for a block on a assembly, redirect the menu
 * factory's {@code level.getBlockEntity(localPos)} lookup to the assembly's live client block
 * entity, so any menu-backed block can open its GUI on a assembly without per-block patching.
 * Outside that window {@link AssemblyMenuContext#resolve} returns {@code null} and the lookup is
 * untouched. See {@link AssemblyMenuContext}.
 */
@Mixin(Level.class)
public abstract class LevelMixin {

	@Inject(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
		at = @At("HEAD"), cancellable = true)
	private void zps$redirectAssemblyMenuLookup(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
		Level self = (Level) (Object) this;
		if (!self.isClientSide)
			return;
		BlockEntity be = AssemblyMenuContext.resolve(self, pos);
		if (be != null)
			cir.setReturnValue(be);
	}
}
