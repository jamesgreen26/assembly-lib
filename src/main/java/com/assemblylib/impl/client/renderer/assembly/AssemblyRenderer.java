package com.assemblylib.impl.client.renderer.assembly;

import org.joml.Matrix3f;
import org.joml.Quaternionf;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import com.assemblylib.impl.AssemblyClientConfig;
import com.assemblylib.impl.assembly.Assembly;
import com.assemblylib.impl.assembly.AssemblyTransform;
import com.assemblylib.api.AssemblyHost;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Host-agnostic rendering of an {@link AssemblyHost}'s assembly, driven by both the host's
 * block-entity renderer and its entity renderer. The static block geometry is drawn one of two ways,
 * chosen by {@link AssemblyClientConfig#useFlywheelRenderer()} (re-checked every frame):
 *
 * <ul>
 *   <li><b>baked-mesh</b> (default): a self-contained {@link AssemblyBakedMesh} cached on the
 *       {@link AssemblyRenderState}; no create/flywheel dependency at draw time.</li>
 *   <li><b>legacy Flywheel</b>: the structure is drawn by {@code AssemblyVisualCore}'s GPU instance;
 *       this renderer only draws a per-block vanilla fallback when the Flywheel backend is off.</li>
 * </ul>
 *
 * Captured block entities always render through their vanilla renderers (skipped only when Flywheel
 * is active and the block entity opts out, in which case its Flywheel child visual draws it).
 */
public final class AssemblyRenderer {

	private AssemblyRenderer() {
	}

	public static void render(AssemblyHost host, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
		int packedLight, int packedOverlay) {
		Direction facing = host.assemblyFacing();
		boolean flywheelMode = AssemblyClientConfig.useFlywheelRenderer();
		boolean flywheelActive = flywheelMode && host.assemblyLevel() != null
			&& VisualizationManager.supportsVisualization(host.assemblyLevel());

		poseStack.pushPose();
		// Renderer origin is the host's own cell corner; shift to the anchor block and pivot there by
		// the host's own (leaf) rotation, taken straight from its transform.
		poseStack.translate(facing.getStepX(), facing.getStepY(), facing.getStepZ());
		poseStack.translate(0.5, 0.5, 0.5);
		Quaternionf rotation = AssemblyTransform.rotationOf(host.getAssemblyTransform(partialTick));
		poseStack.mulPose(rotation);
		poseStack.translate(-0.5, -0.5, -0.5);

		// The head's own block is invisible; draw the piston-head model directly off the host's
		// facing so it appears immediately on placement, in both render backends.
		renderHead(facing, poseStack, buffers, packedLight, packedOverlay);

		Assembly assembly = host.getAssembly();
		if (assembly != null && !assembly.isEmpty()) {
			if (flywheelMode) {
				// Legacy path: Flywheel draws the structure instance. Only when its backend is off do we
				// fall back to drawing each block here. (The invisible head produces no geometry either way.)
				if (!flywheelActive)
					renderBlocksVanilla(assembly, poseStack, buffers, packedLight, packedOverlay);
			} else {
				// Baked-mesh path: draw the cached static mesh under the host transform.
				AssemblyRenderState renderState = host.getRenderState();
				if (renderState != null) {
					AssemblyBakedMesh mesh = renderState.getOrBuildMesh(host.assemblyHostBlockPos());
					if (mesh.isBuilt())
						mesh.draw(poseStack, RenderSystem.getProjectionMatrix(), new Matrix3f().rotation(rotation));
				}
			}

			// Captured block entities (chests, signs, ...): rendered via their vanilla renderers so they
			// keep rendering while assembled. A block entity is skipped only when Flywheel is active AND
			// it opts out of vanilla rendering — those are drawn by their Flywheel child visual instead.
			renderCapturedBlockEntities(host, partialTick, poseStack, buffers, packedLight, packedOverlay,
				flywheelActive);
		}

		poseStack.popPose();
	}

	/** Draw the vanilla piston-head model at the host's own cell (the head's local position). */
	private static void renderHead(Direction facing, PoseStack poseStack, MultiBufferSource buffers, int packedLight,
		int packedOverlay) {
		BlockState head = Blocks.PISTON_HEAD.defaultBlockState()
			.setValue(PistonHeadBlock.FACING, facing)
			.setValue(PistonHeadBlock.TYPE, PistonType.DEFAULT)
			.setValue(PistonHeadBlock.SHORT, false);
		BlockPos local = BlockPos.ZERO.relative(facing.getOpposite());
		poseStack.pushPose();
		poseStack.translate(local.getX(), local.getY(), local.getZ());
		Minecraft.getInstance().getBlockRenderer().renderSingleBlock(head, poseStack, buffers, packedLight,
			packedOverlay, ModelData.EMPTY, null);
		poseStack.popPose();
	}

	/** Per-block vanilla fallback for the legacy renderer when the Flywheel backend is unavailable. */
	private static void renderBlocksVanilla(Assembly assembly, PoseStack poseStack, MultiBufferSource buffers,
		int packedLight, int packedOverlay) {
		BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
		for (StructureBlockInfo info : assembly.getBlocks().values()) {
			BlockPos local = info.pos();
			poseStack.pushPose();
			poseStack.translate(local.getX(), local.getY(), local.getZ());
			dispatcher.renderSingleBlock(info.state(), poseStack, buffers, packedLight, packedOverlay,
				ModelData.EMPTY, null);
			poseStack.popPose();
		}
	}

	private static void renderCapturedBlockEntities(AssemblyHost host, float partialTick, PoseStack poseStack,
		MultiBufferSource buffers, int packedLight, int packedOverlay, boolean flywheelActive) {
		AssemblyRenderState renderState = host.getRenderState();
		if (renderState == null)
			return;

		BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
		for (BlockEntity captured : renderState.getBlockEntities()) {
			if (flywheelActive && VisualizationHelper.skipVanillaRender(captured))
				continue;
			BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(captured);
			if (renderer == null)
				continue;
			BlockPos local = captured.getBlockPos();
			poseStack.pushPose();
			poseStack.translate(local.getX(), local.getY(), local.getZ());
			try {
				renderer.render(captured, partialTick, poseStack, buffers, packedLight, packedOverlay);
			} catch (Exception ignored) {
				// A misbehaving virtual render shouldn't crash the frame.
			}
			poseStack.popPose();
		}
	}

}
