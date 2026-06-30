package com.assemblylib.impl.assembly;

import com.assemblylib.api.AssemblyHost;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import com.assemblylib.impl.assembly.collision.Matrix3d;
import com.assemblylib.impl.assembly.util.AssemblyMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Maps points and directions between world space and a assembly's
 * anchor-local space for the current rotation, and builds the render pose for
 * drawing local-space geometry (selection box, breaking overlay) in the world.
 *
 * <p>This is the single source of truth for the assembly transform shared by
 * the collider, the raytracer, the interaction renderer and the placement
 * context. The transform is {@code world = R(+angle)*(local - CENTER) + CENTER + anchor}
 * (rotation about the motor's facing axis); it is stored as a general rigid map
 * {@code world = rotation*local + translation}.
 */
public final class AssemblyTransform {

	/** Local -> world rotation. */
	private final Matrix3d rotation;
	/** World = rotation*local + translation. */
	private final Vec3 translation;
	/** Cached world -> local rotation ({@code rotation} transposed). */
	private final Matrix3d invRotation;

	private AssemblyTransform(Matrix3d rotation, Vec3 translation) {
		this.rotation = rotation;
		this.translation = translation;
		this.invRotation = rotation.copy().transpose();
	}

	/** A single (un-composed) assembly transform about {@code anchor}. */
	public static AssemblyTransform leaf(Vec3 anchor, float angle, Direction.Axis axis) {
		Matrix3d r = rotationMatrix(angle, axis);
		Vec3 t = AssemblyMath.CENTER_OF_ORIGIN.subtract(r.transform(AssemblyMath.CENTER_OF_ORIGIN)).add(anchor);
		return new AssemblyTransform(r, t);
	}

	/**
	 * Build the leaf local -> reference-frame 4x4 matrix for a rotation about {@code anchor}: the
	 * standard pose a spinning host hands back from {@link AssemblyHost#getAssemblyTransform(float)}.
	 * Pure JOML (no {@code com.mojang.math.Axis}) so it is dist-safe and runs server-side.
	 */
	public static Matrix4f spinMatrix(Vec3 anchor, float angleDeg, Direction.Axis axis) {
		float rad = (float) Math.toRadians(angleDeg);
		Matrix4f m = new Matrix4f();
		m.translate((float) anchor.x, (float) anchor.y, (float) anchor.z);
		m.translate(0.5f, 0.5f, 0.5f);
		switch (axis) {
			case X -> m.rotateX(rad);
			case Y -> m.rotateY(rad);
			case Z -> m.rotateZ(rad);
		}
		m.translate(-0.5f, -0.5f, -0.5f);
		return m;
	}

	/** Adapt a host-supplied 4x4 leaf transform (local -> its reference frame) into an {@link AssemblyTransform}. */
	public static AssemblyTransform fromMatrix(Matrix4f leaf) {
		Matrix3d r = new Matrix3d().setRotationFrom(leaf);
		Vec3 t = new Vec3(leaf.m30(), leaf.m31(), leaf.m32());
		return new AssemblyTransform(r, t);
	}

	/** Transform for the interpolated client render pose at {@code partialTick}. */
	public static AssemblyTransform ofInterpolated(AssemblyHost be, float partialTick) {
		return fromMatrix(be.getAssemblyTransform(partialTick));
	}

	/** Transform for the raw (server / current) pose. */
	public static AssemblyTransform ofCurrent(AssemblyHost be) {
		return fromMatrix(be.getAssemblyTransform(1.0f));
	}

	/**
	 * Transform for the pose the host will hold after advancing one more tick by its own intended motion
	 * ({@link AssemblyHost#getAssemblyTransformNext()}). Differencing {@code ofCurrent} and
	 * {@code ofIntendedNext} at a platform point yields its carried velocity. The host decides what "one
	 * tick forward" means (a spin step, a translation, …), so a stopped host reports the same pose and
	 * thus no carried motion. Server-side.
	 */
	public static AssemblyTransform ofIntendedNext(AssemblyHost be) {
		return fromMatrix(be.getAssemblyTransformNext());
	}

	private static Matrix3d rotationMatrix(float angleDeg, Direction.Axis axis) {
		float rad = (float) (angleDeg / 180d * Math.PI);
		return switch (axis) {
			case X -> new Matrix3d().asXRotation(rad);
			case Y -> new Matrix3d().asYRotation(rad);
			case Z -> new Matrix3d().asZRotation(rad);
		};
	}

	/**
	 * The platform's yaw (rotation about world Y) in degrees, extracted from the composed rotation:
	 * zero for a pure tilt (X/Z) transform and equal to a Y-turntable's angle. Used to measure
	 * player-relative block placement and to turn a rider with a turntable.
	 */
	public float yawDegrees() {
		Vec3 localXInWorld = rotation.transform(new Vec3(1, 0, 0));
		return (float) Math.toDegrees(Math.atan2(-localXInWorld.z, localXInWorld.x));
	}

	public Vec3 worldToLocal(Vec3 world) {
		return invRotation.transform(world.subtract(translation));
	}

	public Vec3 localToWorld(Vec3 local) {
		return rotation.transform(local).add(translation);
	}

	/** Rotate a world-space direction vector (no translation) into local space. */
	public Vec3 worldDirToLocal(Vec3 worldDir) {
		return invRotation.transform(worldDir);
	}

	/** Rotate a local-space direction vector (no translation) into world space. */
	public Vec3 localDirToWorld(Vec3 localDir) {
		return rotation.transform(localDir);
	}

	/** Nearest local-space {@link Direction} for a world-space direction vector. */
	public Direction worldDirToLocal(Direction worldDir) {
		return Direction.getNearest(worldDirToLocal(Vec3.atLowerCornerOf(worldDir.getNormal())));
	}

	/** World-space center of a local block position at the current rotation. */
	public Vec3 localBlockCenterToWorld(BlockPos local) {
		return localToWorld(Vec3.atCenterOf(local));
	}

	/** World -> local rotation (a fresh copy), e.g. for orienting collision boxes. */
	public Matrix3d worldToLocalRotation() {
		return invRotation.copy();
	}

	/** Local -> world rotation (a fresh copy). */
	public Matrix3d localToWorldRotation() {
		return rotation.copy();
	}

	/** Local -> world rotation as a quaternion, e.g. to tag a detached falling block. */
	public org.joml.Quaternionf localToWorldRotationQuat() {
		return rotation.toQuaternion();
	}

	/**
	 * World -> local rotation with the rotation about <em>world</em> Y (yaw) removed, used to orient a
	 * colliding entity's box: a turntable's rider turns with the platform (its box stays aligned to the
	 * assembly's axes), while a tilt keeps the rider world-aligned.
	 */
	public Matrix3d worldToLocalRotationNoWorldYaw() {
		Vec3 localXInWorld = rotation.transform(new Vec3(1, 0, 0));
		float yaw = (float) Math.atan2(-localXInWorld.z, localXInWorld.x);
		Matrix3d yawLocalToWorld = new Matrix3d().asYRotation(yaw);
		return invRotation.copy().multiply(yawLocalToWorld);
	}

	/**
	 * Whether the platform tilts off the horizontal (world up is not preserved by the composed
	 * rotation). For a plain Y-axis turntable this is {@code false}.
	 */
	public boolean hasVerticalRotation() {
		Vec3 up = rotation.transform(new Vec3(0, 1, 0));
		return Math.abs(up.x) > 1e-6 || Math.abs(up.z) > 1e-6 || up.y < 1 - 1e-6;
	}

	/**
	 * Build the pose that places local-space block geometry into the world,
	 * relative to {@code cameraPos} (so it can be drawn under a level-stage
	 * PoseStack).
	 */
	public Matrix4f renderPose(Vec3 cameraPos) {
		Matrix4f pose = new Matrix4f();
		pose.translate((float) (translation.x - cameraPos.x), (float) (translation.y - cameraPos.y),
			(float) (translation.z - cameraPos.z));
		pose.mul(rotation.getAsMatrix4f());
		return pose;
	}

	/** The leaf rotation of a host-supplied 4x4 transform, as a quaternion (for render embedding). */
	public static Quaternionf rotationOf(Matrix4f leaf) {
		return leaf.getNormalizedRotation(new Quaternionf());
	}

	/** Shared pivot rotation about a block's center used by the renderer and interaction. */
	public static void pivotRotate(Matrix4f pose, Quaternionf rotation) {
		pose.translate(0.5f, 0.5f, 0.5f);
		pose.rotate(rotation);
		pose.translate(-0.5f, -0.5f, -0.5f);
	}
}
