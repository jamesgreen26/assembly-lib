package com.assemblylib.impl.entity.collision;

import org.jetbrains.annotations.NotNull;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Objects;

/**
 * An oriented bounding box (center + dimensions + unit-quaternion orientation) plus a
 * Separating-Axis-Theorem solver. Copied near-verbatim from sable's
 * {@code dev.ryanhcode.sable.api.math.OrientedBoundingBox3d}; the only change is the reusable
 * scratch type ({@link CollisionScratch} instead of sable's {@code LevelReusedVectors}).
 *
 * <p>{@link #sat} returns the Minimum Translation Vector (MTV) that pushes box A out of box B,
 * or a zero vector if they don't overlap. The box is centered on its position.</p>
 */
public final class OrientedBoundingBox3d {

    public static final Vector3dc RIGHT = new Vector3d(1, 0, 0);
    public static final Vector3dc UP = new Vector3d(0, 1, 0);
    public static final Vector3dc FORWARD = new Vector3d(0, 0, 1);

    private final Vector3d position = new Vector3d();
    private final Vector3d dimensions = new Vector3d();
    private final Quaterniond orientation = new Quaterniond();
    private final CollisionScratch sink;

    public OrientedBoundingBox3d(@NotNull final CollisionScratch sink) {
        this.sink = sink;
    }

    public OrientedBoundingBox3d(final double x, final double y, final double z,
                                 final double sizeX, final double sizeY, final double sizeZ,
                                 @NotNull final Quaterniondc orientation,
                                 @NotNull final CollisionScratch sink) {
        this.position.set(x, y, z);
        this.dimensions.set(sizeX, sizeY, sizeZ);
        this.orientation.set(orientation);
        this.sink = sink;
    }

    public OrientedBoundingBox3d setPosition(final Vector3dc position) {
        this.position.set(position);
        return this;
    }

    public OrientedBoundingBox3d setPosition(final double x, final double y, final double z) {
        this.position.set(x, y, z);
        return this;
    }

    public OrientedBoundingBox3d setDimensions(final Vector3dc dimensions) {
        this.dimensions.set(dimensions);
        return this;
    }

    public OrientedBoundingBox3d setDimensions(final double x, final double y, final double z) {
        this.dimensions.set(x, y, z);
        return this;
    }

    public OrientedBoundingBox3d setOrientation(final Quaterniondc orientation) {
        this.orientation.set(orientation);
        return this;
    }

    public Quaterniond getOrientation() { return this.orientation; }
    public Vector3d getPosition()       { return this.position; }
    public Vector3d getDimensions()     { return this.dimensions; }

    /** Computes all 8 global vertices of this box into {@code result}. */
    public Vector3d @NotNull [] vertices(final Vector3d[] result) {
        this.dimensions.mul(0.5, this.sink.tempmin);
        this.dimensions.mul(-0.5, this.sink.tempmax);

        this.orientation.transform(this.sink.tempmin, result[0]).add(this.position);
        this.orientation.transform(this.sink.tempVert1.set(this.sink.tempmax.x, this.sink.tempmin.y, this.sink.tempmin.z), result[1]).add(this.position);
        this.orientation.transform(this.sink.tempVert4.set(this.sink.tempmin.x, this.sink.tempmin.y, this.sink.tempmax.z), result[4]).add(this.position);
        this.orientation.transform(this.sink.tempVert5.set(this.sink.tempmax.x, this.sink.tempmin.y, this.sink.tempmax.z), result[5]).add(this.position);
        this.orientation.transform(this.sink.tempVert3.set(this.sink.tempmax.x, this.sink.tempmax.y, this.sink.tempmin.z), result[3]).add(this.position);
        this.orientation.transform(this.sink.tempVert2.set(this.sink.tempmin.x, this.sink.tempmax.y, this.sink.tempmin.z), result[2]).add(this.position);
        this.orientation.transform(this.sink.tempVert6.set(this.sink.tempmin.x, this.sink.tempmax.y, this.sink.tempmax.z), result[6]).add(this.position);
        this.orientation.transform(this.sink.tempmax, result[7]).add(this.position);

        return result;
    }

    /** Rotates a vector from this OBB's local space to global space. */
    public Vector3d rotate(@NotNull final Vector3d vec) {
        return this.orientation.transform(vec);
    }

    private static boolean doesOverlap(@NotNull final Vector2d a, @NotNull final Vector2d b) {
        return a.x <= b.y && a.y >= b.x;
    }

    /** @return the overlap of two 1D intervals, or 0 if they don't overlap. */
    public static double getOverlap(@NotNull final Vector2d a, @NotNull final Vector2d b) {
        if (!doesOverlap(a, b)) {
            return 0.0;
        }
        return Math.min(a.y, b.y) - Math.max(a.x, b.x);
    }

    /** Computes the MTV between two OBBs (new vector). */
    public static @NotNull Vector3d sat(@NotNull final OrientedBoundingBox3d obbA, @NotNull final OrientedBoundingBox3d obbB) {
        return sat(obbA, obbB, new Vector3d());
    }

    /**
     * Computes the MTV (Minimum Translation Vector) between two OBBs into {@code dest}.
     * Returns a zero vector if the boxes are not overlapping.
     */
    public static @NotNull Vector3d sat(@NotNull final OrientedBoundingBox3d obbA, @NotNull final OrientedBoundingBox3d obbB, @NotNull final Vector3d dest) {
        Objects.requireNonNull(obbA, "obbA");
        Objects.requireNonNull(obbB, "obbB");
        Objects.requireNonNull(dest, "dest");

        final CollisionScratch context = obbA.sink;

        final Vector3d[] verticesA = obbA.vertices(context.a);
        final Vector3d[] verticesB = obbB.vertices(context.b);

        final Vector3d checker = obbA.position.sub(obbB.position, context.checker).normalize();

        final Vector3d aRight = obbA.rotate(context.obbARight.set(RIGHT));
        final Vector3d aUp = obbA.rotate(context.obbAUp.set(UP));
        final Vector3d aForward = obbA.rotate(context.obbAForward.set(FORWARD));

        final Vector3d bRight = obbB.rotate(context.obbBRight.set(RIGHT));
        final Vector3d bUp = obbB.rotate(context.obbBUp.set(UP));
        final Vector3d bForward = obbB.rotate(context.obbBForward.set(FORWARD));

        final Vector3d mtv = dest.set(Double.MAX_VALUE);

        genChecks(aRight, aUp, aForward, bRight, bUp, bForward, context.checks);

        double minOverlap = Double.MAX_VALUE;

        for (final Vector3d check : context.checks) {
            if (check.lengthSquared() <= 0) {
                continue;
            }

            check.normalize();

            checkSeparation(verticesA, check, context.proj1);
            checkSeparation(verticesB, check, context.proj2);

            if (check.dot(checker) > 0) {
                check.mul(-1.0);
            }

            final double overlap = getOverlap(context.proj1, context.proj2);

            if (overlap == 0.0) { // shapes are not overlapping
                return dest.zero();
            } else if (overlap < minOverlap) {
                minOverlap = overlap;
                mtv.set(check.mul(minOverlap));
            }
        }

        final boolean facingOpposite = obbA.position.sub(obbB.position, context.oppo).dot(mtv) < 0;
        if (facingOpposite) {
            mtv.mul(-1);
        }

        return mtv;
    }

    public static Vector3d[] genChecks(final Vector3d aRight, final Vector3d aUp, final Vector3d aForward,
                                       final Vector3d bRight, final Vector3d bUp, final Vector3d bForward,
                                       final Vector3d[] checks) {
        checks[0].set(aRight);
        checks[1].set(aUp);
        checks[2].set(aForward);
        checks[3].set(bRight);
        checks[4].set(bUp);
        checks[5].set(bForward);
        aRight.cross(bRight, checks[6]);
        aRight.cross(bUp, checks[7]);
        aRight.cross(bForward, checks[8]);
        aUp.cross(bRight, checks[9]);
        aUp.cross(bUp, checks[10]);
        aUp.cross(bForward, checks[11]);
        aForward.cross(bRight, checks[12]);
        aForward.cross(bUp, checks[13]);
        aForward.cross(bForward, checks[14]);
        return checks;
    }

    /**
     * Projects a set of vertices onto an axis for the Separating Axis Theorem.
     *
     * @return a 2d vector {@code (min, max)} of the projection interval.
     */
    public static @NotNull Vector2d checkSeparation(final Vector3d @NotNull [] self, @NotNull final Vector3d axis, final Vector2d result) {
        if (axis.lengthSquared() <= 0.0) {
            return result.set(0, 0);
        }

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (final Vector3d vec : self) {
            final double dot = vec.dot(axis);
            min = Math.min(dot, min);
            max = Math.max(dot, max);
        }

        return result.set(min, max);
    }
}
