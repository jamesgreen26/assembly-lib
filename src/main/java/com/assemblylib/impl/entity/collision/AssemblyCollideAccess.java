package com.assemblylib.impl.entity.collision;

import net.minecraft.world.phys.Vec3;

/**
 * Duck interface mixed into vanilla {@code Entity} (by {@code MixinEntityMove}) exposing the
 * entity's own vanilla {@code collide(Vec3)} so the dragging code can run a carry vector through
 * world collision before applying it — the VEL analog of sable's {@code EntityExtension#sable$vanillaCollide}.
 * Without this, dragging an entity along an assembly could shove it into adjacent world blocks.
 */
public interface AssemblyCollideAccess {
    Vec3 vel$vanillaCollide(Vec3 movement);
}
