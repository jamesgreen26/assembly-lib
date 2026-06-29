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
}
