package com.assemblylib.client;

import com.assemblylib.blockentity.ModBlockEntities;
import com.assemblylib.client.renderer.assembly.ServoMotorBlockEntityRenderer;
import com.assemblylib.client.renderer.assembly.ServoMotorVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
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
            SimpleBlockEntityVisualizer.builder(ModBlockEntities.SERVO_MOTOR.get())
                    .factory(ServoMotorVisual::new)
                    .neverSkipVanillaRender()
                    .apply();

            NeoForge.EVENT_BUS.addListener(AssemblyInteractionClient::onRenderLevelStage);
            NeoForge.EVENT_BUS.addListener(AssemblyInteractionClient::onRenderHighlight);
            NeoForge.EVENT_BUS.addListener(AssemblyInteractionClient::onClientTick);
            NeoForge.EVENT_BUS.addListener(AssemblyInteractionClient::onComputeCameraAngles);
        });
    }
}
