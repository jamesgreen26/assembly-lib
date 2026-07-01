package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;

import com.assemblylib.impl.assembly.AssemblyRotatedEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.FallingBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Applies a falling block's inherited assembly rotation (see
 * {@link AssemblyRotatedEntity}) about the block's own centre, so a block detached
 * from a tilted/rotating assembly renders with that orientation as it falls. Wraps
 * vanilla's render with a balanced push/pop.
 *
 * <p>Also draws the block's block entity (chest, sign, …) from the synced update tag it carries, since
 * vanilla's {@link FallingBlockRenderer} only draws the static block model — a falling container would
 * otherwise be invisible or blank.
 */
@Mixin(FallingBlockRenderer.class)
public class FallingBlockRendererMixin {

	@Inject(method = "render", at = @At("HEAD"))
	private void zps$pushRotation(FallingBlockEntity entity, float yaw, float partialTick, PoseStack pose,
		MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
		pose.pushPose();
		if (entity instanceof AssemblyRotatedEntity rotated) {
			Quaternionf rotation = rotated.zps$getAssemblyRotation();
			// Rotate about the block's own centre; identity (no inherited rotation) is a no-op.
			pose.translate(0.0, 0.5, 0.0);
			pose.mulPose(rotation);
			pose.translate(0.0, -0.5, 0.0);
			zps$renderBlockEntity(entity, rotated, partialTick, pose, buffer, packedLight);
		}
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void zps$popRotation(FallingBlockEntity entity, float yaw, float partialTick, PoseStack pose,
		MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
		pose.popPose();
	}

	/**
	 * Reconstruct the carried block entity from its synced update tag and draw it through its normal
	 * renderer, at the same offset vanilla uses for the block model. Rebuilt per frame — falling blocks
	 * are short-lived and few, and it keeps no state to invalidate.
	 */
	@Unique
	private void zps$renderBlockEntity(FallingBlockEntity entity, AssemblyRotatedEntity carrier, float partialTick,
		PoseStack pose, MultiBufferSource buffer, int packedLight) {
		BlockState state = entity.getBlockState();
		if (!state.hasBlockEntity() || !(state.getBlock() instanceof EntityBlock entityBlock))
			return;
		BlockPos pos = entity.blockPosition();
		BlockEntity be = entityBlock.newBlockEntity(pos, state);
		if (be == null)
			return;
		be.setLevel(entity.level());
		be.setBlockState(state);
		CompoundTag tag = carrier.zps$getBlockEntityTag();
		if (tag != null && !tag.isEmpty())
			be.handleUpdateTag(tag, entity.level().registryAccess());
		BlockEntityRenderer<BlockEntity> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
		if (renderer == null)
			return;
		pose.pushPose();
		pose.translate(-0.5, 0.0, -0.5);
		try {
			renderer.render(be, partialTick, pose, buffer, packedLight, OverlayTexture.NO_OVERLAY);
		} catch (Exception ignored) {
			// A misbehaving virtual render shouldn't crash the frame.
		}
		pose.popPose();
	}
}
