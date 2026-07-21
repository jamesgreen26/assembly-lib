package com.assemblylib.impl.vchunk.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Self-contained baked mesh for one assembly's static block geometry, adapted from the old
 * {@code impl.client.renderer.assembly.AssemblyBakedMesh}. Every non-air block is rendered once into
 * a static {@link VertexBuffer} per {@link RenderType} and uploaded to the GPU; {@link #draw}
 * re-draws those buffers each frame under the assembly's interpolated transform.
 *
 * <p>The only difference from the old mesh is the data source: blocks come from the client mirror's
 * {@code Map<BlockPos, BlockState>} (reconstructed from sync packets) rather than the old
 * {@code Assembly} map. Blocks are baked at integer assembly-LOCAL positions; the transform is
 * applied by the caller's {@link PoseStack}.
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

    /** Rebuild the static mesh from an assembly's blocks. {@code apparentOrigin} sets biome tint sampling. */
    public void rebuild(Map<BlockPos, BlockState> blocks, BlockPos apparentOrigin) {
        dispose();
        if (blocks == null || blocks.isEmpty()) {
            return;
        }

        AssemblyRenderWorld renderWorld = new AssemblyRenderWorld(blocks, apparentOrigin);
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        RandomSource random = RandomSource.create();

        for (RenderType renderType : RENDER_TYPES) {
            ByteBufferBuilder byteBuffer = new ByteBufferBuilder(renderType.bufferSize());
            BufferBuilder builder = new BufferBuilder(byteBuffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

            boolean hasAny = false;
            OffsetVertexConsumer fluidBuilder = new OffsetVertexConsumer(builder);
            for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
                BlockState state = entry.getValue();
                if (state.isAir()) {
                    continue;
                }
                BlockPos pos = entry.getKey();

                // Block-model geometry (skipped for pure fluids, whose model is invisible).
                BakedModel model = dispatcher.getBlockModel(state);
                if (model.getRenderTypes(state, random, ModelData.EMPTY).contains(renderType)) {
                    PoseStack ps = new PoseStack();
                    ps.translate(pos.getX(), pos.getY(), pos.getZ());
                    dispatcher.renderBatched(state, pos, renderWorld, ps, builder, true, random, ModelData.EMPTY, renderType);
                    hasAny = true;
                }

                // Fluid geometry: water/lava (source blocks AND waterlogged blocks) have their visual
                // produced by the liquid renderer, not the block model, so vanilla chunk rendering runs
                // it as a separate pass. Without this the fluid is completely invisible on an assembly.
                // renderLiquid bakes vertices masked to a 16-block section (pos & 15); the offset
                // consumer adds the masked-away section origin back so it lands at the true local pos.
                FluidState fluidState = state.getFluidState();
                if (!fluidState.isEmpty()
                        && ItemBlockRenderTypes.getRenderLayer(fluidState) == renderType) {
                    fluidBuilder.setOffset(pos.getX() & ~15, pos.getY() & ~15, pos.getZ() & ~15);
                    dispatcher.renderLiquid(pos, renderWorld, fluidBuilder, state, fluidState);
                    hasAny = true;
                }
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

    /** Draw every baked buffer under {@code poseStack}. */
    public void draw(PoseStack poseStack, Matrix4f projectionMatrix, Matrix3f normalMat) {
        if (!built || buffers.isEmpty()) {
            return;
        }

        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose());

        for (Map.Entry<RenderType, VertexBuffer> entry : buffers.entrySet()) {
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
