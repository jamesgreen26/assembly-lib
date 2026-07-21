package com.assemblylib.impl.entity.collision;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.assemblylib.impl.entity.AssemblyBlock;
import com.assemblylib.impl.entity.AssemblyEntity;

@OnlyIn(Dist.CLIENT)
public final class AssemblyCollisionDebug {

    private AssemblyCollisionDebug() {}

    public static void renderInLocalPose(AssemblyEntity entity, PoseStack poseStack,
                                         VertexConsumer consumer) {
        var blocks = entity.getAssembly().getBlocks();
        if (blocks.isEmpty()) return;

        var pos = entity.position();
        double fracX = pos.x - Math.floor(pos.x);
        double fracY = pos.y - Math.floor(pos.y);
        double fracZ = pos.z - Math.floor(pos.z);

        AssemblyCollisionLevel fakeLevel = new AssemblyCollisionLevel(blocks);
        CollisionContext ctx = CollisionContext.empty();

        for (AssemblyBlock block : blocks) {
            BlockPos rel = block.relativePos();
            VoxelShape shape = block.state().getCollisionShape(fakeLevel, rel, ctx);
            if (shape.isEmpty()) continue;

            var bb = shape.bounds()
                    .move(rel.getX() - fracX, rel.getY() - fracY, rel.getZ() - fracZ);

            LevelRenderer.renderLineBox(
                    poseStack, consumer,
                    bb.minX, bb.minY, bb.minZ,
                    bb.maxX, bb.maxY, bb.maxZ,
                    0.0F, 1.0F, 0.0F, 1.0F); // green = collision shape in grid frame
        }
    }
}