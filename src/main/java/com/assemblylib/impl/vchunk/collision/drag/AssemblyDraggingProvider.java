package com.assemblylib.impl.vchunk.collision.drag;

/**
 * Duck interface mixed into vanilla {@code Entity} (see {@code AssemblyEntityDraggingMixin}) so any
 * entity can carry its {@link AssemblyDraggingInformation}. Ported from VEL's
 * {@code AssemblyDraggingProvider} (itself the analog of Valkyrien Skies'
 * {@code IEntityDraggingInformationProvider}).
 */
public interface AssemblyDraggingProvider {
    AssemblyDraggingInformation assemblylib$getDraggingInfo();
}
