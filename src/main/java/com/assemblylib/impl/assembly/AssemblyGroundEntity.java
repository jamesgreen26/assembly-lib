package com.assemblylib.impl.assembly;

/**
 * Implemented (via mixin) by the player, recording whether their most recent assembly collision
 * left them standing on an assembly surface. Vanilla's {@code move()} clears {@code onGround} every
 * tick because the assembly isn't a real block, so onGround-driven effects (notably view bobbing in
 * {@code Player#aiStep}) would behave as if the player were airborne. This flag lets those effects
 * treat a player riding an assembly as grounded.
 */
public interface AssemblyGroundEntity {

	/** Mark whether the player is currently standing on an assembly surface. */
	void zps$setOnAssemblyGround(boolean onGround);

	/** Whether the player's last assembly collision left them on an assembly surface. */
	boolean zps$isOnAssemblyGround();
}
