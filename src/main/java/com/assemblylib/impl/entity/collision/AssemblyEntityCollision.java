package com.assemblylib.impl.entity.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.assemblylib.impl.entity.api.AssemblyTransform;
import com.assemblylib.impl.entity.AssemblyBlock;
import com.assemblylib.impl.entity.collision.drag.AssemblyDraggingProvider;
import com.assemblylib.impl.entity.AssemblyEntity;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity ↔ assembly collision — a faithful port of sable's {@code SubLevelEntityCollision.collide}
 * onto VEL's {@link AssemblyEntity}/{@link AssemblyTransform}. The structure mirrors sable closely
 * because that exact structure is what makes the collision feel right:
 *
 * <ol>
 *   <li><b>Substep sweep with pose interpolation.</b> The intended motion is split into substeps;
 *       each substep samples the assembly's pose interpolated between its previous and current tick
 *       (sable's {@code lastPose.lerp(logicalPose, t)}), so a moving/rotating platform is collided
 *       at intermediate poses instead of one static snapshot (no tunnelling, no lag).</li>
 *   <li><b>In-loop tracking reposition.</b> Before resolving collision, an entity that is tracking
 *       an assembly is rigidly carried by that assembly's per-substep pose delta. Collision then
 *       resolves relative to the moved platform — this is what makes riding smooth rather than
 *       jittery, and what {@code inheritedMotion} (the carry applied to the entity) is measured
 *       from.</li>
 *   <li><b>Hitbox yaw alignment.</b> The entity's box is yawed to the assembly's grid orientation
 *       ({@link AssemblyHitboxYaw}) so it slides along rotated faces instead of snagging.</li>
 *   <li><b>SAT resolution.</b> Each block collision sub-box becomes a world-space
 *       {@link OrientedBoundingBox3d}; the largest MTV is resolved per inner iteration, interior
 *       collisions are discarded, vertical/horizontal contact is detected, and the entity steps up
 *       ledges. Which assembly it ends up standing on is tracked for the next tick.</li>
 * </ol>
 *
 * <p><b>Scale caveat:</b> an OBB with non-uniform scale + rotation shears; assemblies are virtually
 * always scale 1 or uniform, where mapping block dims by the per-axis scale is exact.</p>
 * <p><b>Simplification vs sable:</b> assemblies keep entities upright, so sable's
 * {@code customEntityOrientation} (wall-walking) machinery is dropped — the entity box only gets a
 * yaw, never a full reorientation.</p>
 */
public final class AssemblyEntityCollision {

    private AssemblyEntityCollision() {}

    private static final double SEARCH_RADIUS = 8.0;
    private static final double MAX_ASSEMBLY_QUERY_MARGIN = 64.0;
    private static final double SUBSTEP_DISTANCE = 0.25 / 16.0;

    public static AssemblyCollisionInfo collide(Entity entity, Vec3 collisionMotionMoj, Vec3 velocityMotionMoj) {
        AssemblyCollisionInfo info = new AssemblyCollisionInfo();
        info.motion = collisionMotionMoj;

        if (entity instanceof AssemblyEntity) return info;
        if (!(entity instanceof AssemblyDraggingProvider provider)) return info;

        int existingTrackingId = provider.vel$getDraggingInfo().getTrackingAssemblyId();
        info.trackingAssemblyId = existingTrackingId;

        // Server players are collided client-side and the result is sent up; re-colliding them here
        // fights client prediction and rubber-bands. While tracking, just trust the client and keep
        // the ground state (sable does the same for ServerPlayer).
        if (entity instanceof ServerPlayer && existingTrackingId != -1) {
            info.touchedAssembly = true;
            info.verticalCollision = true;
            info.verticalCollisionBelow = true;
            if (entity.getDeltaMovement().y < 0) {
                entity.setDeltaMovement(entity.getDeltaMovement().multiply(1.0, 0.0, 1.0));
            }
            return info;
        }

        AABB entityBB = entity.getBoundingBox();
        AABB sweptBox = entityBB.minmax(entityBB.move(collisionMotionMoj)).inflate(1.0);
        AABB searchBox = sweptBox.inflate(SEARCH_RADIUS);

        List<AssemblyEntity> assemblies = findNearbyAssemblies(entity, searchBox);
        if (existingTrackingId != -1
                && entity.level().getEntity(existingTrackingId) instanceof AssemblyEntity tracked
                && !assemblies.contains(tracked)) {
            assemblies = new ArrayList<>(assemblies);
            assemblies.add(tracked);
        }

        // Build per-assembly collision data (pose endpoints + candidate blocks in local space).
        CollisionContext ctx = CollisionContext.of(entity);
        List<AssemblyData> datas = new ArrayList<>();
        for (AssemblyEntity a : assemblies) {
            if (a.getAssembly().getBlocks().isEmpty()) continue;
            AssemblyData d = AssemblyData.build(a, collisionMotionMoj, sweptBox, ctx);
            if (!d.localBoxes.isEmpty()) datas.add(d);
        }
        if (datas.isEmpty()) {
            // No VEL assembly near this entity — stay a no-op so we never touch sable/vanilla handling.
            info.trackingAssemblyId = -1;
            info.motion = collisionMotionMoj;
            return info;
        }

        // A VEL assembly is genuinely in range: from here on the move/travel mixins may act.
        info.touchedAssembly = true;

        // If the assembly we were tracking is no longer nearby, stop riding it (and coast).
        if (info.trackingAssemblyId != -1) {
            boolean present = false;
            for (AssemblyData d : datas) if (d.id == info.trackingAssemblyId) { present = true; break; }
            if (!present) info.trackingAssemblyId = -1;
        }

        CollisionScratch sink = new CollisionScratch();

        double bbHalfY = entityBB.getYsize() * 0.5;
        Vector3d entityUp = new Vector3d(0, 1, 0);

        Vec3 worldCenter = entityBB.getCenter();
        Vector3d entityBoundsCenter = new Vector3d(worldCenter.x, worldCenter.y, worldCenter.z);
        Vector3d initialCenter = new Vector3d(entityBoundsCenter); // for inheritedMotion (feet carry)

        OrientedBoundingBox3d entityOBB = new OrientedBoundingBox3d(
                entityBoundsCenter.x, entityBoundsCenter.y, entityBoundsCenter.z,
                entityBB.getXsize(), entityBB.getYsize(), entityBB.getZsize(),
                new Quaterniond(), sink);
        OrientedBoundingBox3d cubeOBB = new OrientedBoundingBox3d(sink);

        Vector3d collisionMotion = new Vector3d();
        Vector3d steppingMotion = new Vector3d(collisionMotionMoj.x, collisionMotionMoj.y, collisionMotionMoj.z);
        Vector3d steppingVelocity = new Vector3d(velocityMotionMoj.x, velocityMotionMoj.y, velocityMotionMoj.z);

        // Sable scales substeps with motion (1-10). We floor it higher for EVERY entity, not just
        // the local player: with too few substeps the entity penetrates a block by a large amount
        // before the SAT pushes it back out, so it rest-jitters (and for server-controlled mobs that
        // jitter is then re-broadcast and amplified by client interpolation). More substeps means
        // each step penetrates less, so depenetration settles right at the surface.
        int substeps = Math.min(10, Math.max(8, (int) (steppingMotion.length() / SUBSTEP_DISTANCE)));

        Vector3d mtv = new Vector3d();
        Vector3d normalizedMtv = new Vector3d();
        Vector3d maxMTV = new Vector3d();
        Quaterniond entityBoxOrientation = new Quaterniond();

        boolean stopTrackingAtEnd = false;
        boolean swappedTracking = false;

        for (int i = 1; i <= substeps; i++) {
            double delta = 1.0 / substeps;
            collisionMotion.fma(delta, steppingMotion);
            if (info.trackingAssemblyId == -1) {
                collisionMotion.fma(delta, steppingVelocity);
            }

            for (AssemblyData d : datas) {
                AssemblyTransform lastTf = d.poseAt((double) (i - 1) / substeps);
                AssemblyTransform curTf = d.poseAt((double) i / substeps);

                // Align the entity's box yaw to this assembly's grid orientation.
                double yaw = AssemblyHitboxYaw.hitboxYaw(new Quaterniond(curTf.getRotation()));
                entityBoxOrientation.identity().rotateY(yaw);
                entityOBB.setOrientation(entityBoxOrientation);

                Quaterniond cubeOrientation = new Quaterniond(curTf.getRotation());
                cubeOBB.setOrientation(cubeOrientation);

                // Carry a tracked entity with the platform BEFORE resolving collision.
                if (info.trackingAssemblyId == d.id) {
                    Vec3 feet = new Vec3(entityBoundsCenter.x, entityBoundsCenter.y - bbHalfY, entityBoundsCenter.z);
                    Vec3 newFeet = rigidCarry(feet, lastTf, curTf);
                    entityBoundsCenter.set(newFeet.x, newFeet.y + bbHalfY, newFeet.z);
                    if (d.localBoxes.isEmpty()) stopTrackingAtEnd = true;
                }

                entityBoundsCenter.add(collisionMotion, entityOBB.getPosition());

                // Transform this assembly's candidate boxes into world space at the interpolated pose.
                List<CubeBox> worldBoxes = d.worldBoxesAt(curTf);

                for (int maxIter = 0; maxIter < 4; maxIter++) {
                    maxMTV.zero();
                    double maxLenSq = 0.0;
                    CubeBox maxBox = null;

                    for (CubeBox box : worldBoxes) {
                        cubeOBB.setPosition(box.cx, box.cy, box.cz);
                        cubeOBB.setDimensions(box.dx, box.dy, box.dz);
                        OrientedBoundingBox3d.sat(entityOBB, cubeOBB, mtv);
                        if (isUsable(mtv)) {
                            double lsq = mtv.lengthSquared();
                            if (lsq > maxLenSq) {
                                maxLenSq = lsq;
                                maxMTV.set(mtv);
                                maxBox = box;
                            }
                        }
                    }

                    if (maxBox == null || maxMTV.lengthSquared() <= 0.0) break;

                    // Start tracking the first assembly we touch.
                    if (info.trackingAssemblyId == -1) {
                        info.trackingAssemblyId = d.id;
                        stopTrackingAtEnd = false;
                    }

                    if (isInteriorCollision(d, curTf, maxBox, maxMTV, ctx)) break;

                    maxMTV.normalize(normalizedMtv);
                    double dot = normalizedMtv.dot(entityUp);
                    boolean vertical = Math.abs(dot) > 0.6;

                    if (vertical) {
                        info.verticalCollision = true;
                        if (dot > 0.0) {
                            info.verticalCollisionBelow = true;
                            if (info.trackingAssemblyId != d.id && !swappedTracking) {
                                swappedTracking = true;
                                info.trackingAssemblyId = d.id;
                            }
                        }
                        if (dot > 0.8) {
                            double pre = maxMTV.length();
                            entityUp.mul(maxMTV.dot(entityUp), maxMTV).normalize(pre);
                        }
                    } else {
                        boolean stepped = tryStepUp(entity, worldBoxes, entityOBB, cubeOBB,
                                cubeOrientation, normalizedMtv, collisionMotion, entityUp, sink);
                        if (!stepped) {
                            info.assemblyHorizontalCollision = true;
                            info.horizontalCollision = true;
                        }
                    }

                    collisionMotion.add(maxMTV);
                    entityBoundsCenter.add(collisionMotion, entityOBB.getPosition());
                }
            }
        }

        info.motion = new Vec3(collisionMotion.x, collisionMotion.y, collisionMotion.z);

        // inheritedMotion = how far the tracking reposition carried the entity's feet (entityBounds-
        // Center accumulates ONLY the rigid carry; collisionMotion holds the collision push and is
        // returned separately as motion).
        Vec3 carry = new Vec3(
                entityBoundsCenter.x - initialCenter.x,
                entityBoundsCenter.y - initialCenter.y,
                entityBoundsCenter.z - initialCenter.z);
        if (isFinite(carry) && carry.lengthSqr() > 1.0E-8) {
            info.inheritedMotion = carry;
        }

        if (stopTrackingAtEnd) info.trackingAssemblyId = -1;

        if (info.trackingAssemblyId != -1
                && entity.level().getEntity(info.trackingAssemblyId) instanceof AssemblyEntity tracked) {
            info.inheritedYaw = computeInheritedYaw(entity, tracked);
        }

        return info;
    }

    // ------------------------------------------------------------------------
    // Per-assembly data: pose endpoints + candidate block sub-boxes in local space
    // ------------------------------------------------------------------------

    private static final class AssemblyData {
        final int id;
        final AssemblyEntity entity;
        final AssemblyCollisionLevel level;
        // pose endpoints (previous tick -> current tick), interpolated per substep
        final Vec3 prevPos, curPos;
        final Quaternionf prevRot, curRot;
        final Vector3f prevScale, curScale;
        final List<LocalBox> localBoxes;

        private AssemblyData(int id, AssemblyEntity entity, AssemblyCollisionLevel level,
                             Vec3 prevPos, Vec3 curPos, Quaternionf prevRot, Quaternionf curRot,
                             Vector3f prevScale, Vector3f curScale, List<LocalBox> localBoxes) {
            this.id = id;
            this.entity = entity;
            this.level = level;
            this.prevPos = prevPos; this.curPos = curPos;
            this.prevRot = prevRot; this.curRot = curRot;
            this.prevScale = prevScale; this.curScale = curScale;
            this.localBoxes = localBoxes;
        }

        static AssemblyData build(AssemblyEntity a, Vec3 motion, AABB sweptWorldBox, CollisionContext ctx) {
            AssemblyCollisionLevel level = new AssemblyCollisionLevel(a.getAssembly().getBlocks());

            Vec3 prevPos = new Vec3(a.xo, a.yo, a.zo);
            Vec3 curPos = a.position();
            Quaternionf prevRot = new Quaternionf(a.prevRotation);
            Quaternionf curRot = a.getRotation();
            Vector3f prevScale = new Vector3f(a.prevScale);
            Vector3f curScale = new Vector3f(a.getScale());

            // Candidate block range: project the swept world box into local space under BOTH pose
            // endpoints and union, so blocks the moving platform sweeps over are all included.
            AssemblyTransform prevTf = AssemblyTransform.of(a, prevPos, prevRot, prevScale);
            AssemblyTransform curTf = AssemblyTransform.of(a, curPos, curRot, curScale);
            double[] lb = localBoundsOf(prevTf, sweptWorldBox);
            double[] lb2 = localBoundsOf(curTf, sweptWorldBox);
            double minX = Math.min(lb[0], lb2[0]) - 1, minY = Math.min(lb[1], lb2[1]) - 1, minZ = Math.min(lb[2], lb2[2]) - 1;
            double maxX = Math.max(lb[3], lb2[3]) + 1, maxY = Math.max(lb[4], lb2[4]) + 1, maxZ = Math.max(lb[5], lb2[5]) + 1;

            List<LocalBox> boxes = new ArrayList<>();
            for (AssemblyBlock block : a.getAssembly().getBlocks()) {
                BlockPos rel = block.relativePos();
                if (rel.getX() + 1 < minX || rel.getX() > maxX
                        || rel.getY() + 1 < minY || rel.getY() > maxY
                        || rel.getZ() + 1 < minZ || rel.getZ() > maxZ) {
                    continue;
                }
                VoxelShape shape = block.state().getCollisionShape(level, rel, ctx);
                if (shape.isEmpty()) continue;
                for (AABB sub : shape.toAabbs()) {
                    boxes.add(new LocalBox(rel, sub.move(rel.getX(), rel.getY(), rel.getZ())));
                }
            }

            return new AssemblyData(a.getId(), a, level, prevPos, curPos, prevRot, curRot, prevScale, curScale, boxes);
        }

        AssemblyTransform poseAt(double t) {
            Vec3 pos = prevPos.lerp(curPos, t);
            Quaternionf rot = new Quaternionf(prevRot).slerp(curRot, (float) t);
            Vector3f scale = new Vector3f(prevScale).lerp(curScale, (float) t);
            return AssemblyTransform.of(entity, pos, rot, scale);
        }

        /** Transform every candidate sub-box into a world-space OBB at the given pose. */
        List<CubeBox> worldBoxesAt(AssemblyTransform tf) {
            Vector3d scale = tf.getScale();
            List<CubeBox> out = new ArrayList<>(localBoxes.size());
            for (LocalBox lb : localBoxes) {
                Vec3 lc = lb.localBox.getCenter();
                Vec3 wc = tf.localToWorld(lc);
                out.add(new CubeBox(
                        wc.x, wc.y, wc.z,
                        lb.localBox.getXsize() * scale.x, lb.localBox.getYsize() * scale.y, lb.localBox.getZsize() * scale.z,
                        lb.rel, lb.localBox));
            }
            return out;
        }
    }

    /** A block collision sub-box in the assembly's local (block-grid) space. */
    private record LocalBox(BlockPos rel, AABB localBox) {}

    /** A block collision sub-box as a world-space OBB (center + dims), plus its local AABB. */
    private static final class CubeBox {
        final double cx, cy, cz, dx, dy, dz;
        final BlockPos rel;
        final AABB localBox;
        CubeBox(double cx, double cy, double cz, double dx, double dy, double dz, BlockPos rel, AABB localBox) {
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.dx = dx; this.dy = dy; this.dz = dz;
            this.rel = rel; this.localBox = localBox;
        }
    }

    // ------------------------------------------------------------------------
    // Dragging: rigid (frac-free) carry of a point from the previous pose to the current pose
    // ------------------------------------------------------------------------

    private static Vec3 rigidCarry(Vec3 worldPoint, AssemblyTransform last, AssemblyTransform cur) {
        Vec3 lastCenter = last.getWorldRotationCenter();
        Vec3 curCenter = cur.getWorldRotationCenter();
        Vec3 rel = worldPoint.subtract(lastCenter);
        Vec3 local = last.worldToLocalDirection(rel);
        Vec3 relNew = cur.localToWorldDirection(local);
        return curCenter.add(relNew);
    }

    private static double computeInheritedYaw(Entity entity, AssemblyEntity assembly) {
        AssemblyTransform prev = AssemblyTransform.of(assembly,
                new Vec3(assembly.xo, assembly.yo, assembly.zo), new Quaternionf(assembly.prevRotation), new Vector3f(assembly.prevScale));
        AssemblyTransform cur = AssemblyTransform.of(assembly,
                assembly.position(), assembly.getRotation(), new Vector3f(assembly.getScale()));

        double yaw = entity.getYRot();
        Vec3 lookYawOnly = new Vec3(Math.sin(-Math.toRadians(yaw)), 0.0, Math.cos(-Math.toRadians(yaw)));
        Vec3 newLook = cur.localToWorldDirection(prev.worldToLocalDirection(lookYawOnly));

        double newXRot = Math.asin(net.minecraft.util.Mth.clamp(-newLook.y, -1.0, 1.0));
        double xRotCos = Math.cos(newXRot);
        if (Math.abs(xRotCos) < 1.0E-6) return 0.0;
        double newYRot = -Math.atan2(newLook.x / xRotCos, newLook.z / xRotCos);
        double entityYaw = net.minecraft.util.Mth.wrapDegrees(yaw);
        double added = net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(newYRot) - entityYaw);
        return Double.isFinite(added) ? added : 0.0;
    }

    // ------------------------------------------------------------------------
    // Interior-collision discard (port of sable lines 320-363)
    // ------------------------------------------------------------------------

    private static boolean isInteriorCollision(AssemblyData d, AssemblyTransform tf, CubeBox maxBox,
                                               Vector3d maxMTV, CollisionContext ctx) {
        Vec3 localMtvV = tf.worldToLocalDirection(new Vec3(maxMTV.x, maxMTV.y, maxMTV.z));
        Vector3d localMtv = new Vector3d(localMtvV.x, localMtvV.y, localMtvV.z);
        if (localMtv.lengthSquared() <= 1.0E-12) return false;
        localMtv.normalize();

        int offX = (int) Math.round(localMtv.x);
        int offY = (int) Math.round(localMtv.y);
        int offZ = (int) Math.round(localMtv.z);
        if (offX == 0 && offY == 0 && offZ == 0) return false;

        Direction dir = Direction.get(Direction.AxisDirection.POSITIVE,
                Direction.getNearest(offX, offY, offZ).getAxis());
        int sx = dir.getStepX(), sy = dir.getStepY(), sz = dir.getStepZ();

        BlockPos offsetRel = maxBox.rel.offset(offX, offY, offZ);
        BlockState offsetState = d.level.getBlockState(offsetRel);
        if (offsetState.isAir()) return false;
        VoxelShape offsetShape = offsetState.getCollisionShape(d.level, offsetRel, ctx);
        if (offsetShape.isEmpty()) return false;

        AABB maxLocal = maxBox.localBox;
        double[] cmin = compress(maxLocal, sx, sy, sz);
        double minVol = volume(cmin);

        for (AABB box : offsetShape.toAabbs()) {
            AABB offsetLocal = box.move(offsetRel.getX(), offsetRel.getY(), offsetRel.getZ()).inflate(0.001);
            if (!maxLocal.intersects(offsetLocal)) continue;
            double[] coff = compress(offsetLocal, sx, sy, sz);
            double[] inter = intersect(cmin, coff);
            if (inter == null) continue;
            if (Math.abs(volume(inter) - minVol) < 0.01) return true;
        }
        return false;
    }

    private static double[] compress(AABB b, int sx, int sy, int sz) {
        return new double[]{
                b.minX * (1.0 - sx), b.minY * (1.0 - sy), b.minZ * (1.0 - sz),
                b.maxX * (1.0 - sx) + sx, b.maxY * (1.0 - sy) + sy, b.maxZ * (1.0 - sz) + sz
        };
    }

    private static double[] intersect(double[] a, double[] b) {
        double minX = Math.max(a[0], b[0]), minY = Math.max(a[1], b[1]), minZ = Math.max(a[2], b[2]);
        double maxX = Math.min(a[3], b[3]), maxY = Math.min(a[4], b[4]), maxZ = Math.min(a[5], b[5]);
        if (minX > maxX || minY > maxY || minZ > maxZ) return null;
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static double volume(double[] b) {
        return (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
    }

    // ------------------------------------------------------------------------
    // Step-up (port of sable tryStepUp / hasCollision)
    // ------------------------------------------------------------------------

    private static boolean tryStepUp(Entity entity, List<CubeBox> worldBoxes, OrientedBoundingBox3d entityOBB,
                                     OrientedBoundingBox3d cubeOBB, Quaterniond cubeOrientation,
                                     Vector3d normalizedMTV, Vector3d collisionMotion, Vector3d entityUp,
                                     CollisionScratch sink) {
        if (!entity.onGround()) return false;
        if (collisionMotion.dot(normalizedMTV) > 0.0) return true;

        double checkIncrement = 1.0 / 16.0;
        double maxStepHeight = entity.maxUpStep();

        Vector3d baseDims = new Vector3d(entityOBB.getDimensions());
        Vector3d base = new Vector3d(entityOBB.getPosition());
        double inflation = 0.1;
        entityOBB.setDimensions(baseDims.x + inflation, baseDims.y + inflation, baseDims.z + inflation);

        Vector3d lastStepTestMTV = new Vector3d();
        Vector3d testCenter = new Vector3d();
        int collidingCount = 0, freeCount = 0;
        double currentStepUp = 0;

        for (currentStepUp = 0; currentStepUp <= maxStepHeight; currentStepUp += checkIncrement) {
            base.fma(currentStepUp, entityUp, testCenter).fma(-2.0 / 16.0, normalizedMTV);
            if (hasCollision(worldBoxes, entityOBB, cubeOBB, cubeOrientation, testCenter, lastStepTestMTV)) {
                collidingCount++;
            } else {
                freeCount++;
                break;
            }
        }

        entityOBB.setDimensions(baseDims);

        if (freeCount > 0 && collidingCount > 0 && lastStepTestMTV.normalize().dot(entityUp) > 0.8) {
            collisionMotion.fma(currentStepUp, entityUp).fma(-1.0 / 16.0, normalizedMTV);
            return true;
        }
        return false;
    }

    private static boolean hasCollision(List<CubeBox> worldBoxes, OrientedBoundingBox3d entityOBB,
                                        OrientedBoundingBox3d cubeOBB, Quaterniond cubeOrientation,
                                        Vector3d boundsCenter, Vector3d outMtv) {
        entityOBB.setPosition(boundsCenter);
        cubeOBB.setOrientation(cubeOrientation);
        Vector3d mtv = new Vector3d();
        for (CubeBox box : worldBoxes) {
            cubeOBB.setPosition(box.cx, box.cy, box.cz);
            cubeOBB.setDimensions(box.dx, box.dy, box.dz);
            OrientedBoundingBox3d.sat(entityOBB, cubeOBB, mtv);
            if (isUsable(mtv)) {
                outMtv.set(mtv);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------
    // Broad-phase + helpers
    // ------------------------------------------------------------------------

    private static List<AssemblyEntity> findNearbyAssemblies(Entity entity, AABB searchBox) {
        AABB wide = searchBox.inflate(MAX_ASSEMBLY_QUERY_MARGIN);
        List<AssemblyEntity> candidates = entity.level().getEntitiesOfClass(AssemblyEntity.class, wide);
        if (candidates.isEmpty()) return candidates;

        Vec3 ec = entity.getBoundingBox().getCenter();
        double entityReach = entity.getBoundingBox().getSize() * 0.5 + SEARCH_RADIUS;

        List<AssemblyEntity> result = new ArrayList<>(candidates.size());
        for (AssemblyEntity a : candidates) {
            double reach = a.getBoundingRadius() + entityReach;
            if (a.position().distanceToSqr(ec) <= reach * reach) {
                result.add(a);
            }
        }
        return result;
    }

    private static double[] localBoundsOf(AssemblyTransform tf, AABB world) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int xi = 0; xi < 2; xi++) for (int yi = 0; yi < 2; yi++) for (int zi = 0; zi < 2; zi++) {
            Vec3 corner = new Vec3(xi == 0 ? world.minX : world.maxX,
                    yi == 0 ? world.minY : world.maxY,
                    zi == 0 ? world.minZ : world.maxZ);
            Vec3 l = tf.worldToLocal(corner);
            minX = Math.min(minX, l.x); maxX = Math.max(maxX, l.x);
            minY = Math.min(minY, l.y); maxY = Math.max(maxY, l.y);
            minZ = Math.min(minZ, l.z); maxZ = Math.max(maxZ, l.z);
        }
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static boolean isUsable(Vector3d mtv) {
        return mtv.lengthSquared() > 0.0
                && mtv.x != Double.MAX_VALUE && mtv.y != Double.MAX_VALUE && mtv.z != Double.MAX_VALUE
                && Double.isFinite(mtv.x) && Double.isFinite(mtv.y) && Double.isFinite(mtv.z);
    }

    private static boolean isFinite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
