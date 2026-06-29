package com.assemblylib.impl.client.screens;

import javax.annotation.Nullable;

import com.assemblylib.api.AssemblyHost;
import com.assemblylib.impl.client.renderer.assembly.AssemblyRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Client-side counterpart to {@link com.assemblylib.impl.assembly.AssemblyScreenAccess}: resolves the
 * block entity backing a bespoke screen (Robotic Arm, Script Terminal, …). When {@code motorPos} is
 * given, the live block entity comes from the host motor's {@link AssemblyRenderState} cache (so
 * the screen always reads the current instance, which is rebuilt on each assembly re-sync);
 * otherwise it's the plain client-level lookup. Screens call this every frame.
 */
public final class AssemblyScreenAccessClient {

	private AssemblyScreenAccessClient() {
	}

	@Nullable
	public static BlockEntity getBlockEntity(@Nullable BlockPos motorPos, BlockPos localPos) {
		Level level = Minecraft.getInstance().level;
		if (level == null)
			return null;
		if (motorPos != null) {
			if (level.getBlockEntity(motorPos) instanceof AssemblyHost host) {
				AssemblyRenderState renderState = host.getRenderState();
				if (renderState != null)
					return renderState.getBlockEntity(localPos);
			}
			return null;
		}
		return level.getBlockEntity(localPos);
	}
}
