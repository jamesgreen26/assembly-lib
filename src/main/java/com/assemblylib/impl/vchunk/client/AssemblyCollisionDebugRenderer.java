package com.assemblylib.impl.vchunk.client;

import com.assemblylib.impl.vchunk.AssemblyTransform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.Command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Debug overlay that draws each client assembly's real entity-collision boxes so they can be compared
 * against the rendered mesh directly (the analog of VEL's F3+B collision-box overlay). Toggle with the
 * client command {@code /assemblycollisiondebug}.
 *
 * <ul>
 *   <li><b>Green</b> — the collision cubes at the assembly's CURRENT transform. These should sit
 *       exactly on top of the rendered blocks; if they float away from the mesh, collision and render
 *       disagree on where the assembly is.</li>
 *   <li><b>Red</b> — the collision cubes at the assembly's PREVIOUS transform. The entity-collision
 *       substep loop interpolates from previous → current and collides against every pose in between,
 *       so any visible gap between the red and green boxes is exactly the phantom "air" collision an
 *       entity feels around the assembly.</li>
 * </ul>
 */
public final class AssemblyCollisionDebugRenderer {

    private AssemblyCollisionDebugRenderer() {}

    public static volatile boolean ENABLED = false;
    private static final java.util.Set<String> lastAnnounced = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            net.minecraft.commands.Commands.literal("assemblycollisiondebug").executes(ctx -> {
                ENABLED = !ENABLED;
                com.assemblylib.impl.vchunk.collision.AssemblyEntityCollision.DEBUG = ENABLED;
                lastAnnounced.clear();
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "Assembly collision debug: " + (ENABLED
                        ? "ON (green=current boxes, red=previous, orange=swept, cyan/magenta=ACTUAL hits)"
                        : "OFF")), false);
                return Command.SINGLE_SUCCESS;
            }));
    }

    @SubscribeEvent
    public static void onClientTickPre(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
        if (ENABLED) {
            com.assemblylib.impl.vchunk.collision.AssemblyEntityCollision.DEBUG_HITS.clear();
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!ENABLED || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (ClientAssemblyManager.all().isEmpty()) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        for (ClientAssembly assembly : ClientAssemblyManager.all()) {
            AssemblyTransform prev = assembly.previousTransform();
            AssemblyTransform cur = assembly.currentTransform();
            // Orange: the intermediate poses the collision substep loop actually samples between
            // previous -> current. If previous == current (settled) these all land on the mesh and you
            // see nothing extra; if they trail off into empty space, THAT swept region is the phantom
            // "air" collision an entity feels, even though neither endpoint sits there.
            for (int i = 1; i < 8; i++) {
                AssemblyTransform mid = AssemblyTransform.interpolate(prev, cur, i / 8.0f);
                drawBoxes(poseStack, lines, mid, assembly, cam, 1.0f, 0.55f, 0.0f);
            }
            drawBoxes(poseStack, lines, cur, assembly, cam, 0.0f, 1.0f, 0.0f);
            drawBoxes(poseStack, lines, prev, assembly, cam, 1.0f, 0.0f, 0.0f);
        }

        // Yellow: the ACTUAL world-space cube SAT matched for every collision resolved this tick --
        // i.e. where the collider THINKS a block is. If this sits away from every green/red block box
        // above, the collider is matching a cube that isn't in the client's own block map at all.
        var mc = Minecraft.getInstance();
        for (float[] h : com.assemblylib.impl.vchunk.collision.AssemblyEntityCollision.DEBUG_HITS) {
            AABB box = new AABB(h[0] - h[3] / 2, h[1] - h[4] / 2, h[2] - h[5] / 2,
                    h[0] + h[3] / 2, h[1] + h[4] / 2, h[2] + h[5] / 2)
                    .move(-cam.x, -cam.y, -cam.z);
            LevelRenderer.renderLineBox(poseStack, lines, box, 1.0f, 1.0f, 0.0f, 1.0f);
            int rel0 = (int) h[7], rel1 = (int) h[8], rel2 = (int) h[9], handle = (int) h[10];
            String key = handle + ":" + rel0 + "," + rel1 + "," + rel2;
            if (mc.player != null && lastAnnounced.add(key)) {
                boolean noRealBlock = rel0 == -1 && rel1 == -1 && rel2 == -1;
                mc.player.displayClientMessage(Component.literal(String.format(
                    "[assemblycollisiondebug] hit handle=%d local=%s world=(%.2f,%.2f,%.2f) %s",
                    handle, noRealBlock ? "NONE (ServerPlayer trust branch, no block checked)" : rel0 + "," + rel1 + "," + rel2,
                    h[0], h[1], h[2],
                    noRealBlock ? "" : "-- check this local pos against the assembly's blocks")), false);
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    private static void drawBoxes(PoseStack poseStack, VertexConsumer lines, AssemblyTransform tf,
            ClientAssembly assembly, Vec3 cam, float r, float g, float b) {
        poseStack.pushPose();
        poseStack.last().pose().mul(tf.renderPose(cam));
        for (BlockPos p : assembly.blocks().keySet()) {
            AABB box = new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0);
            LevelRenderer.renderLineBox(poseStack, lines, box, r, g, b, 1.0f);
        }
        poseStack.popPose();
    }
}
