package com.assemblylib.impl.vchunk;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/**
 * A rigid map between an assembly's local block space and world space, for one instant in time:
 * {@code world = rotation * local + translation}.
 *
 * <p>Rotation is stored as a {@link Quaternionf} (per the design doc's quaternion-based requirement)
 * so it interpolates cleanly with {@link Quaternionf#slerp} — no gimbal lock, free pitch/roll for
 * airships. The blocks' real stored coordinates never change; only this transform governs where the
 * assembly <em>appears</em> and how incoming queries (raycasts, collision) are redirected.
 *
 * <p>This is the real-coordinate counterpart of the old {@code impl.assembly.AssemblyTransform},
 * with the host-coupled factories and nesting composition dropped (assemblies are first-class and
 * do not nest under a host block here).
 */
public final class AssemblyTransform {

    /** World position of the assembly's local origin (its tile's chosen pivot in world space). */
    private final Vec3 translation;
    /** Local -> world rotation. */
    private final Quaternionf rotation;
    /** Cached world -> local rotation (conjugate). */
    private final Quaternionf invRotation;

    public AssemblyTransform(Vec3 translation, Quaternionf rotation) {
        this.translation = translation;
        this.rotation = new Quaternionf(rotation).normalize();
        this.invRotation = new Quaternionf(this.rotation).conjugate();
    }

    /** A fixed, un-rotated transform that simply places the assembly's local origin at {@code worldOrigin}. */
    public static AssemblyTransform identity(Vec3 worldOrigin) {
        return new AssemblyTransform(worldOrigin, new Quaternionf());
    }

    public Vec3 translation() {
        return translation;
    }

    public Quaternionf rotation() {
        return new Quaternionf(rotation);
    }

    public Vec3 localToWorld(Vec3 local) {
        Vector3f v = new Vector3f((float) local.x, (float) local.y, (float) local.z);
        rotation.transform(v);
        return new Vec3(v.x + translation.x, v.y + translation.y, v.z + translation.z);
    }

    public Vec3 worldToLocal(Vec3 world) {
        Vector3f v = new Vector3f(
            (float) (world.x - translation.x),
            (float) (world.y - translation.y),
            (float) (world.z - translation.z));
        invRotation.transform(v);
        return new Vec3(v.x, v.y, v.z);
    }

    /** Rotate a local-space direction vector (no translation) into world space. */
    public Vec3 localDirToWorld(Vec3 localDir) {
        Vector3f v = new Vector3f((float) localDir.x, (float) localDir.y, (float) localDir.z);
        rotation.transform(v);
        return new Vec3(v.x, v.y, v.z);
    }

    /** Rotate a world-space direction vector (no translation) into local space. */
    public Vec3 worldDirToLocal(Vec3 worldDir) {
        Vector3f v = new Vector3f((float) worldDir.x, (float) worldDir.y, (float) worldDir.z);
        invRotation.transform(v);
        return new Vec3(v.x, v.y, v.z);
    }

    /**
     * Build the pose that places local-space block geometry into the world, relative to
     * {@code cameraPos} (so it can be drawn under a level-stage {@link com.mojang.blaze3d.vertex.PoseStack}).
     */
    public Matrix4f renderPose(Vec3 cameraPos) {
        Matrix4f pose = new Matrix4f();
        pose.translate(
            (float) (translation.x - cameraPos.x),
            (float) (translation.y - cameraPos.y),
            (float) (translation.z - cameraPos.z));
        pose.rotate(rotation);
        return pose;
    }

    /**
     * Interpolate between two transforms by {@code partialTick} ∈ [0,1]: linear for translation,
     * spherical-linear for rotation. Used client-side to smooth assembly motion between the previous
     * and current server tick, exactly like vanilla entity position interpolation.
     */
    public static AssemblyTransform interpolate(AssemblyTransform from, AssemblyTransform to, float partialTick) {
        Vec3 t = from.translation.lerp(to.translation, partialTick);
        Quaternionf r = new Quaternionf(from.rotation).slerp(to.rotation, partialTick);
        return new AssemblyTransform(t, r);
    }

    public static final StreamCodec<FriendlyByteBuf, AssemblyTransform> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AssemblyTransform decode(FriendlyByteBuf buf) {
            Vec3 t = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            Quaternionf r = new Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
            return new AssemblyTransform(t, r);
        }

        @Override
        public void encode(FriendlyByteBuf buf, AssemblyTransform tf) {
            buf.writeDouble(tf.translation.x);
            buf.writeDouble(tf.translation.y);
            buf.writeDouble(tf.translation.z);
            buf.writeFloat(tf.rotation.x);
            buf.writeFloat(tf.rotation.y);
            buf.writeFloat(tf.rotation.z);
            buf.writeFloat(tf.rotation.w);
        }
    };
}
