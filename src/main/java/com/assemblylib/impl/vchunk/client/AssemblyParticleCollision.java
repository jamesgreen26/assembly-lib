package com.assemblylib.impl.vchunk.client;

import java.util.ArrayList;
import java.util.List;

import com.assemblylib.impl.vchunk.AssemblyTransform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Client-side particle ↔ assembly collision, the particle analogue of {@code AssemblyEntityCollision}.
 * A physics particle ({@code hasPhysics}) collides against real terrain through vanilla's
 * {@code Entity.collideBoundingBox}; that call knows nothing about an assembly's blocks (they live at
 * far-away real coordinates, not where the assembly appears), so a falling-dust or block-crack
 * particle sails straight through a moving structure. This runs AFTER vanilla's terrain collision and
 * further restricts the already-collided motion against each nearby assembly.
 *
 * <p>Rather than reuse the entity collider (which is bound to {@code Entity}: dragging info, yaw
 * carry, OBB sweeping, the server-player trust branch), each assembly is handled in its OWN local
 * space: the particle's box and motion are mapped world→local by the assembly's transform, collided
 * against the local block shapes with the same axis-by-axis algorithm vanilla uses
 * ({@code Entity.collideWithShapes}), and the result mapped local→world. The particle's tiny box is
 * treated as axis-aligned in local space — for a sub-0.3-block particle the rotation error is far
 * below a pixel, so this stays correct even for a rotating assembly without needing full OBB math.
 * Composing across assemblies is sequential: each assembly restricts the motion the previous one left.
 */
public final class AssemblyParticleCollision {

    private AssemblyParticleCollision() {}

    private static final double EPSILON = 1.0e-7;

    /**
     * Motion trims smaller than this on any axis are treated as no collision at all. The world↔local
     * mapping in {@link AssemblyTransform} runs in float precision (~1e-5 error), so a particle merely
     * RESTING on an assembly surface maps back a hair inside it and vanilla's shape collision reports a
     * microscopic block on the horizontal axes. That matters because {@code Particle.move} hard-zeroes
     * {@code xd}/{@code zd} on ANY detected x/z collision (unlike entities, which slide), so without
     * this skin a particle sitting on a moving structure instantly loses all horizontal momentum. The
     * skin is ~50× the float error yet far below a block, so genuine collisions still register.
     */
    private static final double SKIN = 5.0e-4;

    /**
     * @param worldBox    the particle's current bounding box, in world space
     * @param worldMotion the motion vanilla's terrain collision already resolved for this tick
     * @return the motion further restricted by every nearby assembly
     */
    public static Vec3 collide(AABB worldBox, Vec3 worldMotion) {
        if (worldMotion.lengthSqr() < EPSILON * EPSILON) {
            return worldMotion;
        }
        Vec3 motion = worldMotion;
        Vec3 worldCenter = worldBox.getCenter();
        for (ClientAssembly assembly : ClientAssemblyManager.all()) {
            if (assembly.blocks().isEmpty()) {
                continue;
            }
            AssemblyTransform tf = assembly.currentTransform();
            if (!nearby(assembly, tf, worldCenter, motion)) {
                continue;
            }
            motion = collideWithAssembly(assembly, tf, worldBox, motion);
            if (motion.lengthSqr() < EPSILON * EPSILON) {
                return Vec3.ZERO;
            }
        }
        // Snap each axis back to the pre-assembly motion when we only trimmed it by float noise, so a
        // particle resting on / sliding along an assembly keeps its horizontal momentum exactly as it
        // would on real terrain (where Particle.move sees d0 == x and leaves xd/zd untouched).
        return new Vec3(
            snap(motion.x, worldMotion.x),
            snap(motion.y, worldMotion.y),
            snap(motion.z, worldMotion.z));
    }

    private static double snap(double collided, double original) {
        return Math.abs(collided - original) < SKIN ? original : collided;
    }

    /** Coarse bounding-sphere reject so a particle nowhere near an assembly never touches block data. */
    private static boolean nearby(ClientAssembly assembly, AssemblyTransform tf, Vec3 worldCenter, Vec3 motion) {
        AABB local = assembly.localBounds();
        if (local == null) {
            return false;
        }
        Vec3 centerWorld = tf.localToWorld(local.getCenter());
        double radius = 0.5 * Math.sqrt(local.getXsize() * local.getXsize()
            + local.getYsize() * local.getYsize() + local.getZsize() * local.getZsize());
        double reach = radius + motion.length() + 2.0;
        return worldCenter.distanceToSqr(centerWorld) <= reach * reach;
    }

    private static Vec3 collideWithAssembly(ClientAssembly assembly, AssemblyTransform tf, AABB worldBox, Vec3 worldMotion) {
        Vec3 localCenter = tf.worldToLocal(worldBox.getCenter());
        double hx = worldBox.getXsize() * 0.5;
        double hy = worldBox.getYsize() * 0.5;
        double hz = worldBox.getZsize() * 0.5;
        AABB localBox = new AABB(localCenter.x - hx, localCenter.y - hy, localCenter.z - hz,
            localCenter.x + hx, localCenter.y + hy, localCenter.z + hz);
        Vec3 localMotion = tf.worldDirToLocal(worldMotion);

        List<VoxelShape> shapes = gatherShapes(assembly, localBox, localMotion);
        if (shapes.isEmpty()) {
            return worldMotion;
        }
        Vec3 collided = collideWithShapes(localMotion, localBox, shapes);
        return tf.localDirToWorld(collided);
    }

    /** Local-space block collision shapes overlapping the particle's swept box. */
    private static List<VoxelShape> gatherShapes(ClientAssembly assembly, AABB localBox, Vec3 localMotion) {
        AABB swept = localBox.expandTowards(localMotion).inflate(EPSILON);
        AssemblyRenderWorld view = new AssemblyRenderWorld(assembly.blocks(), assembly.apparentOrigin());
        List<VoxelShape> shapes = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = Mth.floor(swept.minX);
        int minY = Mth.floor(swept.minY);
        int minZ = Mth.floor(swept.minZ);
        int maxX = Mth.floor(swept.maxX);
        int maxY = Mth.floor(swept.maxY);
        int maxZ = Mth.floor(swept.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState state = assembly.blocks().get(pos.set(x, y, z));
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(view, pos);
                    if (!shape.isEmpty()) {
                        shapes.add(shape.move(x, y, z));
                    }
                }
            }
        }
        return shapes;
    }

    /**
     * Vanilla's {@code Entity.collideWithShapes} (private) reproduced verbatim: resolve Y first, then
     * the smaller of X/Z, then the other, moving the box after each axis so diagonal motion slides
     * along a corner exactly as it does for entities.
     */
    private static Vec3 collideWithShapes(Vec3 movement, AABB box, List<VoxelShape> shapes) {
        double x = movement.x;
        double y = movement.y;
        double z = movement.z;
        if (y != 0.0) {
            y = Shapes.collide(Direction.Axis.Y, box, shapes, y);
            if (y != 0.0) {
                box = box.move(0.0, y, 0.0);
            }
        }
        boolean zBeforeX = Math.abs(x) < Math.abs(z);
        if (zBeforeX && z != 0.0) {
            z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
            if (z != 0.0) {
                box = box.move(0.0, 0.0, z);
            }
        }
        if (x != 0.0) {
            x = Shapes.collide(Direction.Axis.X, box, shapes, x);
            if (x != 0.0) {
                box = box.move(x, 0.0, 0.0);
            }
        }
        if (!zBeforeX && z != 0.0) {
            z = Shapes.collide(Direction.Axis.Z, box, shapes, z);
        }
        return new Vec3(x, y, z);
    }
}
