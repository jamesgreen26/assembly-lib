package com.assemblylib.impl.entity.render;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import com.assemblylib.AssemblyLib;

import java.io.IOException;

import static com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK;

@OnlyIn(Dist.CLIENT)
public class AssemblyShaders {

    private static ShaderInstance assemblyShader;

    public static ShaderInstance getAssemblyShader() {
        return assemblyShader;
    }

    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                new ShaderInstance(
                        event.getResourceProvider(),
                        ResourceLocation.fromNamespaceAndPath(AssemblyLib.MOD_ID, "assembly"),
                        BLOCK
                ),
                shader -> assemblyShader = shader
        );
    }
}