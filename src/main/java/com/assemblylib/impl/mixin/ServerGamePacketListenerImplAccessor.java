package com.assemblylib.impl.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private "floating too long" kick timers so a assembly can reset
 * them for riders standing on blocks that aren't present in the world. This is
 * the mixin equivalent of the access transformer Create uses for the same field.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {

	@Accessor("aboveGroundTickCount")
	void setAboveGroundTickCount(int value);

	@Accessor("aboveGroundVehicleTickCount")
	void setAboveGroundVehicleTickCount(int value);
}
