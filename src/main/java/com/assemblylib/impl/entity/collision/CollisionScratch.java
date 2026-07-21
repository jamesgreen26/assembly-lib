package com.assemblylib.impl.entity.collision;

import org.joml.Vector2d;
import org.joml.Vector3d;

/**
 * Reusable scratch vectors for the OBB/SAT collision math — a slimmed port of sable's
 * {@code LevelReusedVectors}. Sable keeps one of these per {@code Level} to avoid allocating
 * inside the collision hot loop; VEL has no level-sink plumbing, so {@link AssemblyEntityCollision}
 * just allocates one per {@code collide()} call (cheap relative to the per-block SAT work).
 *
 * <p>Only the fields {@link OrientedBoundingBox3d#sat} actually touches are kept here — all the
 * {@code Pose3d}/{@code BoundingBox3d} companion-library scratch sable carries is dropped, since
 * VEL transforms through {@link com.assemblylib.impl.entity.api.AssemblyTransform} instead.</p>
 */
public final class CollisionScratch {

    // --- OBB vertex scratch (OrientedBoundingBox3d.vertices) ---
    final Vector3d tempmin = new Vector3d();
    final Vector3d tempmax = new Vector3d();
    final Vector3d tempVert1 = new Vector3d();
    final Vector3d tempVert2 = new Vector3d();
    final Vector3d tempVert3 = new Vector3d();
    final Vector3d tempVert4 = new Vector3d();
    final Vector3d tempVert5 = new Vector3d();
    final Vector3d tempVert6 = new Vector3d();

    // --- SAT scratch (OrientedBoundingBox3d.sat) ---
    final Vector3d zero = new Vector3d();
    final Vector2d proj1 = new Vector2d();
    final Vector2d proj2 = new Vector2d();
    final Vector3d oppo = new Vector3d();
    final Vector3d checker = new Vector3d();
    final Vector3d obbARight = new Vector3d();
    final Vector3d obbAUp = new Vector3d();
    final Vector3d obbAForward = new Vector3d();
    final Vector3d obbBRight = new Vector3d();
    final Vector3d obbBUp = new Vector3d();
    final Vector3d obbBForward = new Vector3d();

    /** The 8 vertices of OBB A. */
    final Vector3d[] a = newVecArray(8);
    /** The 8 vertices of OBB B. */
    final Vector3d[] b = newVecArray(8);
    /** The 15 SAT candidate axes (6 face normals + 9 cross products). */
    final Vector3d[] checks = newVecArray(15);

    private static Vector3d[] newVecArray(int n) {
        Vector3d[] arr = new Vector3d[n];
        for (int i = 0; i < n; i++) arr[i] = new Vector3d();
        return arr;
    }
}
