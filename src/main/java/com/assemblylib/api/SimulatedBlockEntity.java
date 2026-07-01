package com.assemblylib.api;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Marker interface a {@link BlockEntity} must implement to be <b>simulated</b> inside an assembly:
 * reconstructed as a live block entity and ticked on the server, and reconstructed for rendering on
 * the client. Block entities that do not implement this stay in the assembly as inert blocks — their
 * state persists and they still render via their block model, but their block entity is never
 * reconstructed or ticked.
 *
 * <p>A block entity that also implements {@link AssemblyHost} is <em>never</em> simulated regardless
 * of this marker, so an assembly host cannot itself live inside another assembly (no nesting).
 */
public interface SimulatedBlockEntity {

	/**
	 * Whether this block entity's live server data (contents, progress, …) rides along when it detaches
	 * from the assembly as a falling block — restored into the block when it lands or is re-captured.
	 * Only simulated block entities keep this data; override to {@code false} to opt out (e.g. state
	 * that would be stale or invalid once the block is a loose falling entity).
	 */
	default boolean keepsDataWhileFalling() {
		return true;
	}
}
