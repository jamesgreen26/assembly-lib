package com.assemblylib.impl.vchunk.collision.drag;

import com.assemblylib.impl.vchunk.collision.AssemblyCollisionInfo;
import org.joml.Vector3d;

/**
 * Per-entity collision/dragging state, attached to every vanilla {@code Entity} via
 * {@code AssemblyEntityDraggingMixin}. Ported from VEL's {@code AssemblyDraggingInformation} (VEL's
 * merge of sable's {@code EntityMovementExtension} + {@code LivingEntityMovementExtension}).
 */
public class AssemblyDraggingInformation {

    /** Result of the most recent {@code Entity.move()} assembly-collision pass. */
    private AssemblyCollisionInfo collisionInfo;

    /**
     * Runtime handle of the assembly the entity is currently locked to (standing on), or -1. Persists
     * across ticks so a rider keeps being carried while near the assembly, and is cleared by the
     * collision pass once the entity has clearly left it (no surrounding blocks, or a world floor/
     * ceiling hit).
     */
    private int trackingAssemblyHandle = -1;

    /**
     * Residual carry velocity [blocks/tick] from the assembly, kept after the entity stops being
     * tracked so it decays smoothly instead of stopping dead. Only meaningful for living entities.
     */
    public final Vector3d inheritedVelocity = new Vector3d();

    public AssemblyCollisionInfo getCollisionInfo() {
        return collisionInfo;
    }

    public void setCollisionInfo(AssemblyCollisionInfo info) {
        this.collisionInfo = info;
    }

    public int getTrackingAssemblyHandle() {
        return trackingAssemblyHandle;
    }

    public void setTrackingAssemblyHandle(int handle) {
        this.trackingAssemblyHandle = handle;
    }
}
