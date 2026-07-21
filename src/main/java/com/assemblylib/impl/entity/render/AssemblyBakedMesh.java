package com.assemblylib.impl.entity.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import com.assemblylib.impl.entity.AssemblyBlock;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.LinkedHashMap;
import java.util.List;

public class AssemblyBakedMesh {

    // We only bake into solid and cutout — translucent needs separate handling
    // (sorting) which we can add later. Cutout mipped is the same shader as cutout.
    private static final List<RenderType> RENDER_TYPES = List.of(
            RenderType.solid(),
            RenderType.cutout(),
            RenderType.cutoutMipped(),
            RenderType.translucent()
    );

    private final LinkedHashMap<RenderType, VertexBuffer> buffers = new LinkedHashMap<>();
    private boolean built = false;

    public void rebuild(List<AssemblyBlock> blocks, Vec3 entityPos) {
        rebuild(blocks, entityPos, java.util.Set.of());
    }

    /**
     * @param dynamicBlocks relative positions to leave OUT of the static bake because they
     *                      are rendered per-frame instead (animated parts). See
     *                      {@link com.assemblylib.impl.entity.api.AssemblyAnimator}.
     */
    public void rebuild(List<AssemblyBlock> blocks, Vec3 entityPos, java.util.Set<BlockPos> dynamicBlocks) {
        dispose();
        if (blocks.isEmpty()) return;

        BlockPos entityBlockPos = BlockPos.containing(entityPos);
        double fracX = entityPos.x - entityBlockPos.getX();
        double fracY = entityPos.y - entityBlockPos.getY();
        double fracZ = entityPos.z - entityBlockPos.getZ();

        AssemblyFakeLevel fakeLevel = new AssemblyFakeLevel(blocks, entityBlockPos);
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        RandomSource random = RandomSource.create();

        for (RenderType renderType : RENDER_TYPES) {
            ByteBufferBuilder byteBuffer = new ByteBufferBuilder(renderType.bufferSize());
            BufferBuilder builder = new BufferBuilder(
                    byteBuffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK
            );

            boolean hasAny = false;

            for (AssemblyBlock block : blocks) {
                var state = block.state();
                if (state.isAir()) continue;
                if (dynamicBlocks.contains(block.relativePos())) continue; // rendered per-frame, not baked

                BakedModel model = dispatcher.getBlockModel(state);
                if (!model.getRenderTypes(state, random, ModelData.EMPTY).contains(renderType)) continue;

                BlockPos pos = block.relativePos();
                PoseStack ps = new PoseStack();
                ps.translate(pos.getX() - fracX, pos.getY() - fracY, pos.getZ() - fracZ);

                dispatcher.renderBatched(
                        state, pos, fakeLevel, ps,
                        builder, true, random,
                        ModelData.EMPTY, renderType
                );
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

//    public void draw(PoseStack poseStack, Matrix4f projectionMatrix, Matrix3f normalMat) {
//        if (!built || buffers.isEmpty()) return;
//
//        ShaderInstance shader = AssemblyShaders.getAssemblyShader();
//        if (shader == null) return; // not registered yet, skip
//
//        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
//
//        // Upload our normal matrix. This is the only custom uniform —
//        // setDefaultUniforms handles everything else (fog, lightmap, etc.)
//        var normalMatUniform = shader.getUniform("AssemblyNormalMat");
//        if (normalMatUniform != null) {
//            normalMatUniform.set(normalMat);
//        }
//
//        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix())
//                .mul(poseStack.last().pose());
//
//        for (var entry : buffers.entrySet()) {
//            VertexBuffer vb = entry.getValue();
//
//            // Use our shader for every render type — we have one shader that
//            // handles solid/cutout/translucent all the same way (alpha < 0.1 discard).
//            // Vanilla render type state (depth, blending) is still set by setupRenderState.
//            entry.getKey().setupRenderState();
//
//            vb.bind();
//            vb.drawWithShader(modelView, projectionMatrix, shader);
//            VertexBuffer.unbind();
//
//            entry.getKey().clearRenderState();
//        }
//
//        Minecraft.getInstance().gameRenderer.lightTexture().turnOffLightLayer();
//    }

    public void draw(PoseStack poseStack, Matrix4f projectionMatrix, Matrix3f normalMat) {
        if (!built || buffers.isEmpty()) return;

        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();

        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix())
                .mul(poseStack.last().pose());

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

    public boolean isBuilt() { return built; }

    public void dispose() {
        buffers.values().forEach(VertexBuffer::close);
        buffers.clear();
        built = false;
    }
}