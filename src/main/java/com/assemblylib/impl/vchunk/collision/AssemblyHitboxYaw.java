package com.assemblylib.impl.vchunk.collision;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;

/**
 * Computes the yaw to align an entity's (axis-aligned) collision box with an assembly's rotated
 * block grid. Ported verbatim from VEL's {@code AssemblyHitboxYaw} (a port of sable's
 * {@code SubLevelEntityCollision.getHitBoxYaw} plus {@code SableMathUtils.clampQuaternionToGrid}).
 *
 * <p>The block grid is 90-degree-symmetric, so the box only needs aligning to the assembly's
 * rotation <em>modulo</em> the nearest 90-degree grid snap. Snapping the assembly orientation to the
 * closest cube rotation and taking the residual yaw gives exactly that: at 0/90/180/270 degrees the
 * box is axis-aligned; in between it tilts to stay parallel to the (rotated) block faces, so the
 * entity slides along a spinning platform instead of catching on box corners.</p>
 */
public final class AssemblyHitboxYaw {

    private AssemblyHitboxYaw() {}

    /** The "REAL" grid-quaternion representatives sable snaps to (ALL_QUATS indices 0,4,5,6,10). */
    private static final Quaterniondc[] GRID_REAL = {
            new Quaterniond(0, 0, 0, 1),
            new Quaterniond(1, 0, 0, 1).normalize(),
            new Quaterniond(0, 1, 0, 1).normalize(),
            new Quaterniond(0, 0, 1, 1).normalize(),
            new Quaterniond(1, 1, 1, 1).normalize(),
    };

    /** Yaw (radians) to rotate the entity's hitbox by so it stays aligned to {@code orientation}. */
    public static double hitboxYaw(Quaterniondc orientation) {
        Quaterniond snapped = clampToGrid(orientation, new Quaterniond());
        // relative = orientation * snapped^-1  (residual rotation off the grid snap)
        Quaterniond relative = new Quaterniond(orientation).div(snapped);
        // y-component of the residual axis: dot of UP with (relative.xyz)
        double dot = relative.y();
        return -2.0 * Math.atan2(-dot, relative.w());
    }

    /** Nearest grid orientation to {@code q} (sign-aware), per sable's clampQuaternionToGrid. */
    private static Quaterniond clampToGrid(Quaterniondc q, Quaterniond dest) {
        int signX = q.x() < 0 ? -1 : 1;
        int signY = q.y() < 0 ? -1 : 1;
        int signZ = q.z() < 0 ? -1 : 1;
        int signW = q.w() < 0 ? -1 : 1;

        dest.set(q);
        // Force all-non-positive entries so adding a grid quat behaves like subtraction.
        dest.x *= -signX;
        dest.y *= -signY;
        dest.z *= -signZ;
        dest.w *= -signW;

        Quaterniond temp = new Quaterniond();
        Quaterniond best = new Quaterniond();
        double distance = 10;

        for (Quaterniondc gq : GRID_REAL) {
            double currentDist = dest.add(gq, temp).lengthSquared();
            if (currentDist < distance) {
                distance = currentDist;
                best.set(gq);
            }
        }

        dest.set(best);
        dest.x *= signX;
        dest.y *= signY;
        dest.z *= signZ;
        dest.w *= signW;
        return dest;
    }
}
