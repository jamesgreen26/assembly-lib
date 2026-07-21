package com.assemblylib.impl.entity.collision.drag;

/**
 * Duck interface mixed into vanilla {@code Entity} (see {@code MixinEntityDragging}) so any
 * entity can carry its {@link AssemblyDraggingInformation}. The VEL analog of VS's
 * {@code IEntityDraggingInformationProvider}.
 */
public interface AssemblyDraggingProvider {
    AssemblyDraggingInformation vel$getDraggingInfo();
}
