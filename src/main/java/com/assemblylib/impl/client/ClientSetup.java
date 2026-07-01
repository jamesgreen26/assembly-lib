package com.assemblylib.impl.client;

import com.assemblylib.debug.blockentity.ModBlockEntities;
import com.assemblylib.debug.client.AssemblyHostEntityVisual;
import com.assemblylib.debug.client.AssemblyHostEntityRenderer;
import com.assemblylib.debug.client.ServoMotorBlockEntityRenderer;
import com.assemblylib.debug.client.ServoMotorVisual;
import com.assemblylib.debug.entity.ModEntities;
import com.assemblylib.impl.client.renderer.assembly.FallingBlockVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import dev.engine_room.flywheel.lib.visualization.SimpleEntityVisualizer;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

public class ClientSetup {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // Servo Motor assembly: Flywheel draws the static structure + child block-entity
            // visuals; the BER always runs to draw captured block entities via their vanilla
            // renderers (and the whole structure when Flywheel is unavailable).
            BlockEntityRenderers.register(ModBlockEntities.SERVO_MOTOR.get(), ServoMotorBlockEntityRenderer::new);
            EntityRenderers.register(ModEntities.ASSEMBLY_HOST.get(), AssemblyHostEntityRenderer::new);
            SimpleBlockEntityVisualizer.builder(ModBlockEntities.SERVO_MOTOR.get())
                    .factory(ServoMotorVisual::new)
                    .neverSkipVanillaRender()
                    .apply();
            SimpleEntityVisualizer.builder(ModEntities.ASSEMBLY_HOST.get())
                    .factory(AssemblyHostEntityVisual::new)
                    .neverSkipVanillaRender()
                    .apply();
            // Falling blocks detached from an assembly: draw the carried block entity's Flywheel
            // visual. neverSkipVanillaRender so the vanilla renderer still draws the block model and
            // the mixin still handles block entities without a Flywheel visualizer.
            SimpleEntityVisualizer.builder(EntityType.FALLING_BLOCK)
                    .factory(FallingBlockVisual::new)
                    .neverSkipVanillaRender()
                    .apply();

            NeoForge.EVENT_BUS.addListener(AssemblyInteractionClient::onRenderLevelStage);
            NeoForge.EVENT_BUS.addListener(AssemblyInteractionClient::onRenderHighlight);
            NeoForge.EVENT_BUS.addListener(AssemblyInteractionClient::onClientTick);
            NeoForge.EVENT_BUS.addListener(AssemblyInteractionClient::onComputeCameraAngles);
        });
    }
}
