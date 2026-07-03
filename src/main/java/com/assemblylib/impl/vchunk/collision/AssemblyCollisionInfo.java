package com.assemblylib.impl.vchunk.collision;

import net.minecraft.world.phys.Vec3;

/**
 * The result of an entity <-> assembly collision pass, ported from VEL's
 * {@code AssemblyCollisionInfo} (itself a port of sable's {@code SubLevelEntityCollision
 * .CollisionInfo}). Produced by {@link AssemblyEntityCollision#collide} once per {@code Entity.move()}
 * and stashed on the entity (via {@link com.assemblylib.impl.vchunk.collision.drag
 * .AssemblyDraggingInformation}) so the move/travel mixins can apply the corrected motion, merge the
 * vanilla collision flags, and drag the entity along with the assembly it stands on.
 */
public final class AssemblyCollisionInfo {

    /** The motion vector after assembly collision, fed into vanilla {@code collide()}. */
    public Vec3 motion = Vec3.ZERO;

    /**
     * How far the tracked assembly carried this entity this tick (rigid-body delta of the entity's
     * foot point). Applied as positional drag in {@code travel()} (living) / {@code move} TAIL
     * (non-living). {@code null} when there is no meaningful carry.
     */
    public Vec3 inheritedMotion = null;

    /** Extra yaw (degrees) to turn the entity by, so its facing rotates with the assembly. */
    public double inheritedYaw = 0.0;

    /** Runtime handle of the assembly the entity is standing on / locked to, or -1 if none. */
    public int trackingAssemblyHandle = -1;

    /**
     * True only if an assembly was actually near this entity this move (so collision ran against it).
     * When false, the move/travel mixins stay COMPLETELY out of the pipeline — they don't touch
     * collision flags, velocity, or motion — so an entity interacting only with the world behaves
     * exactly as if this system weren't installed.
     */
    public boolean touchedAssembly = false;

    public boolean verticalCollision = false;
    public boolean verticalCollisionBelow = false;
    public boolean horizontalCollision = false;
    public boolean minorHorizontalCollision = false;
    /** Horizontal collision specifically caused by an assembly (step-up failed). */
    public boolean assemblyHorizontalCollision = false;
}
