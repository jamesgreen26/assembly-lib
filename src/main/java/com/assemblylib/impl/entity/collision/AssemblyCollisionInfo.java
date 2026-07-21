package com.assemblylib.impl.entity.collision;

import net.minecraft.world.phys.Vec3;

/**
 * The result of an entity↔assembly collision pass — VEL's port of sable's
 * {@code SubLevelEntityCollision.CollisionInfo}. Produced by
 * {@link AssemblyEntityCollision#collide} each {@code Entity.move()} and stashed on the entity
 * (via {@link com.assemblylib.impl.entity.collision.drag.AssemblyDraggingInformation}) so
 * the move/travel mixins can apply the corrected motion, restore the vanilla collision flags,
 * and drag the entity along with the assembly it stands on.
 */
public final class AssemblyCollisionInfo {

    /** The motion vector after assembly collision, fed into vanilla {@code collide()}. */
    public Vec3 motion = Vec3.ZERO;

    /**
     * How far the tracked assembly carried this entity this tick (rigid-body delta of the
     * entity's foot point). Applied as positional drag in {@code travel()} (living) / {@code move}
     * TAIL (non-living). {@code null} when there is no meaningful carry.
     */
    public Vec3 inheritedMotion = null;

    /** Extra yaw (degrees) to turn the entity by, so its facing rotates with the assembly. */
    public double inheritedYaw = 0.0;

    /** Network id of the assembly the entity is standing on / locked to, or -1 if none. */
    public int trackingAssemblyId = -1;

    /**
     * True only if a VEL assembly was actually near this entity this move (so collision ran against
     * it). When false, the move/travel mixins stay COMPLETELY out of the pipeline — they don't touch
     * collision flags, velocity, or motion — so an entity interacting only with the world (or with a
     * sable sub-level) behaves exactly as if VEL weren't installed. This is what keeps VEL from
     * interfering with sable.
     */
    public boolean touchedAssembly = false;

    public boolean verticalCollision = false;
    public boolean verticalCollisionBelow = false;
    public boolean horizontalCollision = false;
    public boolean minorHorizontalCollision = false;
    /** Horizontal collision specifically caused by an assembly (step-up failed) — sable's subLevelHorizontalCollision. */
    public boolean assemblyHorizontalCollision = false;
}
