package com.assemblylib.assembly;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registries of live {@link AssemblyHost}s, kept out of any concrete host type so the
 * client interaction layer can iterate hosts without naming a BlockEntity or Entity implementation.
 */
public final class AssemblyHosts {

	/** Client-side set of currently-assembled hosts, for interaction raytracing and player collision. */
	public static final Set<AssemblyHost> ACTIVE_CLIENT = ConcurrentHashMap.newKeySet();

	private AssemblyHosts() {
	}
}
