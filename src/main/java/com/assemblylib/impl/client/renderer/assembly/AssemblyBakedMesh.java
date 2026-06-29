package com.assemblylib.impl.client.renderer.assembly;

import java.util.LinkedHashMap;
import java.util.List;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.assemblylib.impl.assembly.Assembly;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * A self-contained baked mesh for an assembly's static block geometry, ported from VEL
 * ({@code net.vibey.vel.internal.assemblies.render.AssemblyBakedMesh}). Every non-air captured
 * block is rendered once into a static {@link VertexBuffer} per {@link RenderType} and uploaded to
 * the GPU; {@link #draw} then re-draws those buffers each frame under the host transform. This
 * replaces the old Flywheel GPU-instancing path and carries no create/flywheel/catnip dependency.
 *
 * <p>Blocks are baked at their integer assembly-LOCAL positions; the host transform (translation +
 * rotation) is applied by the caller's {@link PoseStack} in {@code AssemblyRenderer}, so no
 * fractional entity-position offset is needed (unlike VEL, whose host is a fractional entity).
 *
 * <p>Lighting follows VEL: blocks are baked at full sky-brightness via {@link AssemblyRenderWorld},
 * and {@link #draw} runs with the lightmap layer on, so the assembly tracks day/night skylight at
 * draw time. Captured block entities are NOT baked here — they keep rendering through their vanilla
 * block-entity renderers in {@code AssemblyRenderer}.
 *
 * <p><b>Threading:</b> {@link #rebuild} and {@link #dispose} touch {@link VertexBuffer} and must run
 * on the render thread only.
 */
public class AssemblyBakedMesh {

	private static final List<RenderType> RENDER_TYPES = List.of(
		RenderType.solid(),
		RenderType.cutout(),
		RenderType.cutoutMipped(),
		RenderType.translucent());

	private final LinkedHashMap<RenderType, VertexBuffer> buffers = new LinkedHashMap<>();
	private boolean built = false;

	/**
	 * Rebuild the static mesh from {@code assembly}'s captured blocks. {@code hostWorldPos} is the
	 * assembly's world anchor, used only to sample biome tint at the right location.
	 */
	public void rebuild(Level level, Assembly assembly, BlockPos hostWorldPos) {
		dispose();
		if (assembly == null || assembly.isEmpty())
			return;

		AssemblyRenderWorld renderWorld = new AssemblyRenderWorld(level, assembly, hostWorldPos);
		BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
		RandomSource random = RandomSource.create();

		for (RenderType renderType : RENDER_TYPES) {
			ByteBufferBuilder byteBuffer = new ByteBufferBuilder(renderType.bufferSize());
			BufferBuilder builder = new BufferBuilder(byteBuffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

			boolean hasAny = false;
			for (StructureBlockInfo info : assembly.getBlocks().values()) {
				BlockState state = info.state();
				if (state.isAir())
					continue;

				BakedModel model = dispatcher.getBlockModel(state);
				if (!model.getRenderTypes(state, random, ModelData.EMPTY).contains(renderType))
					continue;

				BlockPos pos = info.pos();
				PoseStack ps = new PoseStack();
				ps.translate(pos.getX(), pos.getY(), pos.getZ());

				dispatcher.renderBatched(state, pos, renderWorld, ps, builder, true, random, ModelData.EMPTY,
					renderType);
				hasAny = true;
			}

			if (hasAny) {
				MeshData mesh = builder.build();
				if (mesh != null) {
					VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
					vb.bind();
					vb.upload(mesh);
					VertexBuffer.unbind();
					buffers.put(renderType, vb);
				} else {
					byteBuffer.close();
				}
			} else {
				byteBuffer.close();
			}
		}

		built = !buffers.isEmpty();
	}

	/**
	 * Draw every baked buffer under {@code poseStack}. {@code normalMat} is accepted to match VEL's
	 * signature but is unused with the vanilla block shader (see plan: vanilla shader only).
	 */
	public void draw(PoseStack poseStack, Matrix4f projectionMatrix, Matrix3f normalMat) {
		if (!built || buffers.isEmpty())
			return;

		Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();

		Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose());

		for (var entry : buffers.entrySet()) {
			VertexBuffer vb = entry.getValue();
			entry.getKey().setupRenderState();
			vb.bind();
			vb.drawWithShader(modelView, projectionMatrix, RenderSystem.getShader());
			VertexBuffer.unbind();
			entry.getKey().clearRenderState();
		}

		Minecraft.getInstance().gameRenderer.lightTexture().turnOffLightLayer();
	}

	public boolean isBuilt() {
		return built;
	}

	public void dispose() {
		buffers.values().forEach(VertexBuffer::close);
		buffers.clear();
		built = false;
	}
}
