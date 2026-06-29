package com.assemblylib.client.renderer.assembly;

import com.mojang.blaze3d.vertex.PoseStack;

import com.assemblylib.blockentity.ServoMotorBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;

/**
 * Vanilla fallback renderer for a Servo Motor's assembly, used when the Flywheel backend is
 * unavailable. The actual drawing is host-agnostic and lives in {@link AssemblyRenderer}; this class
 * is the thin {@code BlockEntityRenderer} shell registered per block-entity type.
 */
public class ServoMotorBlockEntityRenderer implements BlockEntityRenderer<ServoMotorBlockEntity> {

	public ServoMotorBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

	@Override
	public void render(ServoMotorBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
		int packedLight, int packedOverlay) {
		AssemblyRenderer.render(be, partialTick, poseStack, buffers, packedLight, packedOverlay);
	}

	@Override
	public boolean shouldRenderOffScreen(ServoMotorBlockEntity be) {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 128;
	}

	@Override
	public AABB getRenderBoundingBox(ServoMotorBlockEntity blockEntity) {
		// The full rotated world bounds of the assembly, so the renderer isn't culled when the motor
		// block leaves the frustum but the spinning structure is still on screen.
		return blockEntity.getRenderBoundingBox();
	}
}
