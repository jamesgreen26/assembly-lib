package com.assemblylib.debug.client;

import com.assemblylib.debug.entity.AssemblyHostEntity;
import com.assemblylib.impl.client.renderer.assembly.AssemblyRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

public class AssemblyHostEntityRenderer extends EntityRenderer<AssemblyHostEntity> {
	private static final ResourceLocation TEXTURE = InventoryMenu.BLOCK_ATLAS;

	private static final BlockState BODY = Blocks.LIME_CONCRETE.defaultBlockState();
	private static final BlockState FOOT = Blocks.GREEN_CONCRETE.defaultBlockState();
	private static final BlockState SHELL = Blocks.BROWN_TERRACOTTA.defaultBlockState();
	private static final BlockState SHELL_RIDGE = Blocks.YELLOW_TERRACOTTA.defaultBlockState();
	private static final BlockState EYE = Blocks.WHITE_CONCRETE.defaultBlockState();
	private static final BlockState PUPIL = Blocks.BLACK_CONCRETE.defaultBlockState();

	public AssemblyHostEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = 0.45f;
	}

	@Override
	public void render(AssemblyHostEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
		MultiBufferSource buffers, int packedLight) {
		float yaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());

		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
		renderSnail(poseStack, buffers, packedLight);
		poseStack.popPose();

		poseStack.pushPose();
		poseStack.translate(-0.5, 0.82, -0.5);
		AssemblyRenderer.render(entity, partialTick, poseStack, buffers, packedLight, OverlayTexture.NO_OVERLAY);
		poseStack.popPose();

		super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(AssemblyHostEntity entity) {
		return TEXTURE;
	}

	private static void renderSnail(PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
		renderBlock(BODY, poseStack, buffers, packedLight, 0.0, 0.12, 0.08, 1.1, 0.24, 1.45);
		renderBlock(FOOT, poseStack, buffers, packedLight, 0.0, 0.02, 0.12, 1.18, 0.08, 1.55);
		renderBlock(SHELL, poseStack, buffers, packedLight, 0.0, 0.36, -0.12, 0.86, 0.78, 0.76);
		renderBlock(SHELL_RIDGE, poseStack, buffers, packedLight, 0.0, 0.76, -0.12, 0.5, 0.12, 0.42);
		renderBlock(BODY, poseStack, buffers, packedLight, 0.0, 0.31, 0.78, 0.72, 0.32, 0.44);
		renderBlock(BODY, poseStack, buffers, packedLight, -0.22, 0.58, 0.94, 0.09, 0.46, 0.09);
		renderBlock(BODY, poseStack, buffers, packedLight, 0.22, 0.58, 0.94, 0.09, 0.46, 0.09);
		renderBlock(EYE, poseStack, buffers, packedLight, -0.22, 0.83, 1.0, 0.16, 0.16, 0.16);
		renderBlock(EYE, poseStack, buffers, packedLight, 0.22, 0.83, 1.0, 0.16, 0.16, 0.16);
		renderBlock(PUPIL, poseStack, buffers, packedLight, -0.22, 0.83, 1.09, 0.07, 0.07, 0.03);
		renderBlock(PUPIL, poseStack, buffers, packedLight, 0.22, 0.83, 1.09, 0.07, 0.07, 0.03);
	}

	private static void renderBlock(BlockState state, PoseStack poseStack, MultiBufferSource buffers, int packedLight,
		double centerX, double centerY, double centerZ, double sizeX, double sizeY, double sizeZ) {
		BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
		poseStack.pushPose();
		poseStack.translate(centerX - sizeX * 0.5, centerY - sizeY * 0.5, centerZ - sizeZ * 0.5);
		poseStack.scale((float) sizeX, (float) sizeY, (float) sizeZ);
		dispatcher.renderSingleBlock(state, poseStack, buffers, packedLight, OverlayTexture.NO_OVERLAY,
			ModelData.EMPTY, null);
		poseStack.popPose();
	}
}
