package com.assemblylib.impl.assembly;

import com.assemblylib.api.AssemblyHost;

import javax.annotation.Nullable;

/**
 * Implemented by the wrapped levels that host an assembly's blocks — the server
 * {@link AssemblySimServerLevel} and the client {@code AssemblyRenderLevel}. Lets a host
 * reconstructed <em>inside</em> another assembly discover the host that hosts it, so its
 * {@link AssemblyTransform} can compose through the parent's pose (nested assemblies).
 *
 * <p>Dist-safe by design: {@link AssemblyHost} dispatches on this interface rather than on the
 * client-only render level type, keeping a block-entity host free of client references.
 */
public interface AssemblyHostLevel {

	/** The host whose assembly this level hosts, or {@code null} if it cannot be resolved. */
	@Nullable
	AssemblyHost getAssemblyHost();

	/**
	 * Ask the parent that owns this level to re-sync its whole structure to clients. A nested host
	 * cannot reach watching clients on its own (they track the root, not the sim wrapper), so its
	 * controller routes a sync request here; the server sim level forwards it to the parent, and the
	 * client render level ignores it.
	 */
	default void requestAssemblySync() {}
}
