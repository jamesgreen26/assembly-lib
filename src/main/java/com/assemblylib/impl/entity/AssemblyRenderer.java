package com.assemblylib.impl.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.assemblylib.impl.entity.AssemblyEntity;
import com.assemblylib.impl.entity.render.AssemblyBakedMesh;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

@OnlyIn(Dist.CLIENT)
public class AssemblyRenderer<T extends AssemblyEntity> extends EntityRenderer<T> {

    public AssemblyRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {

        AssemblyBakedMesh mesh = entity.getOrBuildMesh();
        if (mesh.isBuilt()) {
            Quaternionf interpolated = new Quaternionf(entity.prevRotation)
                    .slerp(entity.getRotation(), partialTick);

            Vector3f scale = new Vector3f(entity.prevScale).lerp(entity.getScale(), partialTick);

            Vector3f pivot = entity.getPivot();

            poseStack.pushPose();
            poseStack.translate(pivot.x, pivot.y, pivot.z);   // move to pivot
            poseStack.mulPose(interpolated);                    // rotate around pivot
            poseStack.scale(scale.x, scale.y, scale.z);         // scale around pivot
            poseStack.translate(-pivot.x, -pivot.y, -pivot.z); // move back

            Matrix3f normalMat = new Matrix3f().rotate(interpolated);
            mesh.draw(poseStack, RenderSystem.getProjectionMatrix(), normalMat);

            // Per-frame pass: block entities (chests/beds/signs) + animator-driven moving parts.
            entity.getOrBuildDynamicRenderer().render(
                    entity, entity.getAssembly().getBlocks(),
                    poseStack, buffer, partialTick, packedLight, entity.position());

            if (Minecraft.getInstance().getEntityRenderDispatcher().shouldRenderHitBoxes()) {
                renderDebugStuff(entity, poseStack, buffer);
                com.assemblylib.impl.entity.collision.AssemblyCollisionDebug
                        .renderInLocalPose(entity, poseStack,
                                buffer.getBuffer(RenderType.lines()));
            }

            poseStack.popPose();
        }

        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    public void renderDebugStuff(T entity, PoseStack poseStack, MultiBufferSource buffer){
        VertexConsumer consumer = buffer.getBuffer(RenderType.lines());

        if (!entity.cornersSet){
            entity.assemblyCorners();
            entity.cornersSet = true;
        }

        double minX = entity.minX, minY = entity.minY, minZ = entity.minZ;
        double maxX = entity.maxX, maxY = entity.maxY, maxZ = entity.maxZ;

        LevelRenderer.renderLineBox(
                poseStack,
                consumer,
                minX, minY, minZ,
                maxX, maxY, maxZ,
                1.0F, 0.0F, 0.0F, 1.0F); // red

        renderLine(
                poseStack,
                consumer,
                0,(float)minY - 2,0,
                0,(float)maxY + 2,0,
                0,0.1f,0.9f,0.9f
        );
    }

    public static void renderLine(PoseStack poseStack, VertexConsumer consumer,
                                  float x1, float y1, float z1,
                                  float x2, float y2, float z2,
                                  float red, float green, float blue, float alpha) {

        PoseStack.Pose pose = poseStack.last();

        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);

        consumer.addVertex(pose, x1, y1, z1)
                .setColor(red, green, blue, alpha)
                .setNormal(pose, dx/len, dy/len, dz/len);

        consumer.addVertex(pose, x2, y2, z2)
                .setColor(red, green, blue, alpha)
                .setNormal(pose, dx/len, dy/len, dz/len);
    }
}