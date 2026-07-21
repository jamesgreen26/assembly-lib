package com.assemblylib.impl.vchunk.collision;

import java.util.ArrayList;
import java.util.List;

import com.assemblylib.impl.vchunk.AssemblyTransform;
import com.assemblylib.impl.vchunk.collision.drag.AssemblyDraggingInformation;
import com.assemblylib.impl.vchunk.collision.drag.AssemblyDraggingProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Entity <-> assembly collision — ported from VEL's {@code AssemblyEntityCollision} (itself a
 * faithful port of sable's {@code SubLevelEntityCollision.collide}), re-targeted at
 * {@link AssemblyCollisionSource} so it reads real ship-space chunks server-side and the synced
 * snapshot client-side, instead of VEL's entity-hosted flat block list. The algorithm is otherwise
 * unchanged:
 *
 * <ol>
 *   <li><b>Substep sweep with pose interpolation.</b> The intended motion is split into substeps;
 *       each substep samples the assembly's pose interpolated between its previous and current tick,
 *       so a moving/rotating assembly is collided at intermediate poses instead of one static
 *       snapshot (no tunnelling, no lag).</li>
 *   <li><b>In-loop tracking reposition.</b> Before resolving collision, an entity that is tracking
 *       an assembly is rigidly carried by that assembly's per-substep pose delta. Collision then
 *       resolves relative to the moved platform.</li>
 *   <li><b>Hitbox yaw alignment.</b> The entity's box is yawed to the assembly's grid orientation
 *       so it slides along rotated faces instead of snagging.</li>
 *   <li><b>SAT resolution.</b> Each block collision sub-box becomes a world-space
 *       {@link OrientedBoundingBox3d}; the largest MTV is resolved per inner iteration, interior
 *       collisions are discarded, vertical/horizontal contact is detected, and the entity steps up
 *       ledges. Which assembly it ends up standing on is tracked for the next tick.</li>
 * </ol>
 *
 * <p>Assemblies never scale (unlike VEL's entity-hosted assemblies), so the scale handling VEL's
 * version carries is dropped entirely — one less axis of floating-point drift to worry about.</p>
 */
public final class AssemblyEntityCollision {

    private AssemblyEntityCollision() {}

    /** Debug: when true, every resolved collision records the entity box that hit (drawn by the overlay). */
    public static volatile boolean DEBUG = false;
    /** Each entry: {cx, cy, cz, dx, dy, dz, verticalFlag}. Cleared each client tick. */
    public static final java.util.List<float[]> DEBUG_HITS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final double SUBSTEP_DISTANCE = 0.25 / 16.0;

    /**
     * @param candidates already broad-phase-filtered by {@link AssemblyCollisionCandidates#forEntity}
     *                   to assemblies genuinely within reach of {@code entity} this move (plus the
     *                   currently-tracked assembly, unconditionally). Callers should skip calling this
     *                   method entirely when that list is empty, rather than call in with nothing.
     */
    public static AssemblyCollisionInfo collide(Entity entity, Vec3 collisionMotionMoj, Vec3 velocityMotionMoj,
            List<AssemblyCollisionSource> candidates) {
        AssemblyCollisionInfo info = new AssemblyCollisionInfo();
        info.motion = collisionMotionMoj;

        if (!(entity instanceof AssemblyDraggingProvider provider)) return info;

        int existingTrackingHandle = provider.assemblylib$getDraggingInfo().getTrackingAssemblyHandle();
        info.trackingAssemblyHandle = existingTrackingHandle;

        // Server players are collided client-side and the result is sent up; re-colliding them here
        // fights client prediction and rubber-bands -- BUT ONLY while the tracked assembly is
        // genuinely still under the player's feet. The tracking handle is sticky by design (the
        // broad-phase in AssemblyCollisionCandidates.forEntity unconditionally re-adds the tracked
        // assembly regardless of distance, and this branch used to zero downward velocity
        // unconditionally, so vanilla's "trim Y further -> untrack" signal never fired either) -- so
        // once a player has EVER stood on the assembly, this branch kept asserting
        // verticalCollisionBelow=true and zeroing fall velocity FOREVER, everywhere within the sticky
        // candidate range, with NO block ever consulted. That is the "collide with air around the
        // assembly" bug: it happens entirely server-side (this is the ServerPlayer branch), so it never
        // shows up in the client-side collision-box overlay no matter how far that's pushed -- there is
        // no SAT box to draw, because none was ever tested. Verifying real support before trusting the
        // client closes exactly that gap; when unsupported we fall through to the normal broad-phase +
        // SAT path below like any other tick.
        if (entity instanceof ServerPlayer && existingTrackingHandle != -1) {
            AssemblyCollisionSource tracked = null;
            for (AssemblyCollisionSource s : candidates) {
                if (s.handle() == existingTrackingHandle) { tracked = s; break; }
            }
            if (tracked != null && isStandingOn(entity, tracked, CollisionContext.of(entity))) {
                info.touchedAssembly = true;
                info.verticalCollision = true;
                info.verticalCollisionBelow = true;
                if (entity.getDeltaMovement().y < 0) {
                    entity.setDeltaMovement(entity.getDeltaMovement().multiply(1.0, 0.0, 1.0));
                }
                return info;
            }
            info.trackingAssemblyHandle = -1;
        }

        if (candidates.isEmpty()) {
            info.trackingAssemblyHandle = -1;
            return info;
        }

        AABB entityBB = entity.getBoundingBox();
        AABB sweptBox = entityBB.minmax(entityBB.move(collisionMotionMoj)).inflate(1.0);

        // Broad-phase distance filtering already happened in AssemblyCollisionCandidates.forEntity
        // (mirroring VEL's spatially-bounded entity query) -- every source here is already known to be
        // in reach (or is the tracked assembly, unconditionally kept). Just skip ones with no blocks.
        CollisionContext ctx = CollisionContext.of(entity);
        List<AssemblyData> datas = new ArrayList<>();
        for (AssemblyCollisionSource source : candidates) {
            if (source.localBounds() == null) continue;
            // sweptBox, NOT searchBox: like VEL/sable, the block candidate set (and with it
            // touchedAssembly -- the switch that moves Entity.move() off the pure-vanilla path) must
            // only engage when the entity's swept box actually reaches the assembly's blocks (~1
            // block margin, sable's considerationBounds.expand(1.0)). SEARCH_RADIUS is only for
            // FINDING candidate assemblies. Building candidates from the 8-block search box made
            // every entity within that whole halo run the modified move path, which manifests as
            // soft phantom collision ("solid air") all around the assembly.
            AssemblyData d = AssemblyData.build(source, collisionMotionMoj, sweptBox, ctx);
            if (!d.localBoxes.isEmpty()) datas.add(d);
        }
        if (datas.isEmpty()) {
            info.trackingAssemblyHandle = -1;
            info.motion = collisionMotionMoj;
            return info;
        }

        info.touchedAssembly = true;

        if (info.trackingAssemblyHandle != -1) {
            boolean present = false;
            for (AssemblyData d : datas) if (d.handle == info.trackingAssemblyHandle) { present = true; break; }
            if (!present) info.trackingAssemblyHandle = -1;
        }

        CollisionScratch sink = new CollisionScratch();

        double bbHalfY = entityBB.getYsize() * 0.5;
        Vector3d entityUp = new Vector3d(0, 1, 0);

        Vec3 worldCenter = entityBB.getCenter();
        Vector3d entityBoundsCenter = new Vector3d(worldCenter.x, worldCenter.y, worldCenter.z);
        Vector3d initialCenter = new Vector3d(entityBoundsCenter);

        OrientedBoundingBox3d entityOBB = new OrientedBoundingBox3d(
                entityBoundsCenter.x, entityBoundsCenter.y, entityBoundsCenter.z,
                entityBB.getXsize(), entityBB.getYsize(), entityBB.getZsize(),
                new Quaterniond(), sink);
        OrientedBoundingBox3d cubeOBB = new OrientedBoundingBox3d(sink);

        Vector3d collisionMotion = new Vector3d();
        Vector3d steppingMotion = new Vector3d(collisionMotionMoj.x, collisionMotionMoj.y, collisionMotionMoj.z);
        Vector3d steppingVelocity = new Vector3d(velocityMotionMoj.x, velocityMotionMoj.y, velocityMotionMoj.z);

        // Floor substeps higher than a naive "scale with motion" formula: with too few substeps the
        // entity penetrates a block by a large amount before the SAT pushes it back out, so it
        // rest-jitters (and for server-controlled mobs that jitter is re-broadcast and amplified by
        // client interpolation). More substeps means each step penetrates less, so depenetration
        // settles right at the surface.
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
            if (info.trackingAssemblyHandle == -1) {
                collisionMotion.fma(delta, steppingVelocity);
            }

            for (AssemblyData d : datas) {
                AssemblyTransform lastTf = AssemblyTransform.interpolate(d.prevTf, d.curTf, (float) ((i - 1) / (double) substeps));
                AssemblyTransform curTf = AssemblyTransform.interpolate(d.prevTf, d.curTf, (float) (i / (double) substeps));

                double yaw = AssemblyHitboxYaw.hitboxYaw(toQuaterniond(curTf));
                entityBoxOrientation.identity().rotateY(yaw);
                entityOBB.setOrientation(entityBoxOrientation);

                Quaterniond cubeOrientation = toQuaterniond(curTf);
                cubeOBB.setOrientation(cubeOrientation);

                if (info.trackingAssemblyHandle == d.handle) {
                    Vec3 feet = new Vec3(entityBoundsCenter.x, entityBoundsCenter.y - bbHalfY, entityBoundsCenter.z);
                    Vec3 newFeet = rigidCarry(feet, lastTf, curTf);
                    entityBoundsCenter.set(newFeet.x, newFeet.y + bbHalfY, newFeet.z);
                    if (d.localBoxes.isEmpty()) stopTrackingAtEnd = true;
                }

                entityBoundsCenter.add(collisionMotion, entityOBB.getPosition());

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

                    if (info.trackingAssemblyHandle == -1) {
                        info.trackingAssemblyHandle = d.handle;
                        stopTrackingAtEnd = false;
                    }

                    if (isInteriorCollision(d, curTf, maxBox, maxMTV, ctx)) break;

                    maxMTV.normalize(normalizedMtv);
                    double dot = normalizedMtv.dot(entityUp);
                    boolean vertical = Math.abs(dot) > 0.6;

                    if (DEBUG) {
                        // Record the actual matched CUBE (not the entity) so the overlay shows exactly
                        // which local block position SAT believes is there -- previously we drew the
                        // entity's own bounding box, which just confirms "something resolved" without
                        // showing WHERE the phantom geometry is.
                        DEBUG_HITS.add(new float[]{(float) maxBox.cx, (float) maxBox.cy, (float) maxBox.cz,
                                (float) maxBox.dx, (float) maxBox.dy, (float) maxBox.dz, vertical ? 1f : 0f,
                                maxBox.rel.getX(), maxBox.rel.getY(), maxBox.rel.getZ(), d.handle});
                    }

                    if (vertical) {
                        info.verticalCollision = true;
                        if (dot > 0.0) {
                            info.verticalCollisionBelow = true;
                            if (info.trackingAssemblyHandle != d.handle && !swappedTracking) {
                                swappedTracking = true;
                                info.trackingAssemblyHandle = d.handle;
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

        // Snap each component back to the exactly-requested motion when the substep loop only drifted
        // it by floating-point accumulation noise (summing delta*motion over N substeps does not
        // reconstruct the input exactly when N doesn't divide evenly, e.g. 9 substeps -> ~1 ULP off).
        // That ~1e-17 noise is otherwise catastrophic: vanilla's own Entity.move() tests
        // `requestedMovement.y != collidedMotion.y` to decide verticalCollision, so a 1-ULP difference
        // makes vanilla fabricate a vertical collision every tick for EVERY entity merely inside an
        // assembly's broad-phase halo -- grounding it on "solid air" and killing its fall/motion, with
        // no SAT box ever involved. A genuine collision correction is many orders of magnitude larger
        // than this epsilon, so snapping never masks a real one.
        double snappedX = Math.abs(collisionMotion.x - collisionMotionMoj.x) < 1.0E-9 ? collisionMotionMoj.x : collisionMotion.x;
        double snappedY = Math.abs(collisionMotion.y - collisionMotionMoj.y) < 1.0E-9 ? collisionMotionMoj.y : collisionMotion.y;
        double snappedZ = Math.abs(collisionMotion.z - collisionMotionMoj.z) < 1.0E-9 ? collisionMotionMoj.z : collisionMotion.z;
        info.motion = new Vec3(snappedX, snappedY, snappedZ);

        Vec3 carry = new Vec3(
                entityBoundsCenter.x - initialCenter.x,
                entityBoundsCenter.y - initialCenter.y,
                entityBoundsCenter.z - initialCenter.z);
        if (isFinite(carry) && carry.lengthSqr() > 1.0E-8) {
            info.inheritedMotion = carry;
        }

        if (stopTrackingAtEnd) info.trackingAssemblyHandle = -1;

        if (info.trackingAssemblyHandle != -1) {
            for (AssemblyData d : datas) {
                if (d.handle == info.trackingAssemblyHandle) {
                    info.inheritedYaw = computeInheritedYaw(entity, d.prevTf, d.curTf);
                    break;
                }
            }
        }

        return info;
    }

    // ------------------------------------------------------------------------
    // Per-assembly data: pose endpoints + candidate block sub-boxes in local space
    // ------------------------------------------------------------------------

    private static final class AssemblyData {
        final int handle;
        final AssemblyTransform prevTf, curTf;
        final AssemblyCollisionBlockGetter blockGetter;
        final List<LocalBox> localBoxes;

        private AssemblyData(int handle, AssemblyTransform prevTf, AssemblyTransform curTf,
                AssemblyCollisionBlockGetter blockGetter, List<LocalBox> localBoxes) {
            this.handle = handle;
            this.prevTf = prevTf;
            this.curTf = curTf;
            this.blockGetter = blockGetter;
            this.localBoxes = localBoxes;
        }

        static AssemblyData build(AssemblyCollisionSource source, Vec3 motion,
                AABB sweptWorldBox, CollisionContext ctx) {
            AssemblyTransform prevTf = source.previousTransform();
            AssemblyTransform curTf = source.currentTransform();

            // Candidate block range: project the swept world box into local space under BOTH pose
            // endpoints and union, so blocks the moving assembly sweeps over are all included.
            double[] lb = localBoundsOf(prevTf, sweptWorldBox);
            double[] lb2 = localBoundsOf(curTf, sweptWorldBox);
            double minX = Math.min(lb[0], lb2[0]) - 1, minY = Math.min(lb[1], lb2[1]) - 1, minZ = Math.min(lb[2], lb2[2]) - 1;
            double maxX = Math.max(lb[3], lb2[3]) + 1, maxY = Math.max(lb[4], lb2[4]) + 1, maxZ = Math.max(lb[5], lb2[5]) + 1;

            AssemblyCollisionBlockGetter blockGetter = new AssemblyCollisionBlockGetter(source);
            List<LocalBox> boxes = new ArrayList<>();
            double fMinX = minX, fMinY = minY, fMinZ = minZ, fMaxX = maxX, fMaxY = maxY, fMaxZ = maxZ;
            source.forEachLocalBlock((rel, state) -> {
                if (rel.getX() + 1 < fMinX || rel.getX() > fMaxX
                        || rel.getY() + 1 < fMinY || rel.getY() > fMaxY
                        || rel.getZ() + 1 < fMinZ || rel.getZ() > fMaxZ) {
                    return;
                }
                VoxelShape shape = state.getCollisionShape(blockGetter, rel, ctx);
                if (shape.isEmpty()) return;
                for (AABB sub : shape.toAabbs()) {
                    boxes.add(new LocalBox(rel, sub.move(rel.getX(), rel.getY(), rel.getZ())));
                }
            });

            return new AssemblyData(source.handle(), prevTf, curTf, blockGetter, boxes);
        }

        /** Transform every candidate sub-box into a world-space OBB at the given pose. */
        List<CubeBox> worldBoxesAt(AssemblyTransform tf) {
            List<CubeBox> out = new ArrayList<>(localBoxes.size());
            for (LocalBox lb : localBoxes) {
                Vec3 lc = lb.localBox.getCenter();
                Vec3 wc = tf.localToWorld(lc);
                out.add(new CubeBox(
                        wc.x, wc.y, wc.z,
                        lb.localBox.getXsize(), lb.localBox.getYsize(), lb.localBox.getZsize(),
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
    // Dragging: rigid (frame-free) carry of a point from the previous pose to the current pose
    // ------------------------------------------------------------------------

    private static Vec3 rigidCarry(Vec3 worldPoint, AssemblyTransform last, AssemblyTransform cur) {
        Vec3 lastCenter = last.translation();
        Vec3 curCenter = cur.translation();
        Vec3 rel = worldPoint.subtract(lastCenter);
        Vec3 local = last.worldDirToLocal(rel);
        Vec3 relNew = cur.localDirToWorld(local);
        return curCenter.add(relNew);
    }

    private static double computeInheritedYaw(Entity entity, AssemblyTransform prev, AssemblyTransform cur) {
        double yaw = entity.getYRot();
        Vec3 lookYawOnly = new Vec3(Math.sin(-Math.toRadians(yaw)), 0.0, Math.cos(-Math.toRadians(yaw)));
        Vec3 newLook = cur.localDirToWorld(prev.worldDirToLocal(lookYawOnly));

        double newXRot = Math.asin(net.minecraft.util.Mth.clamp(-newLook.y, -1.0, 1.0));
        double xRotCos = Math.cos(newXRot);
        if (Math.abs(xRotCos) < 1.0E-6) return 0.0;
        double newYRot = -Math.atan2(newLook.x / xRotCos, newLook.z / xRotCos);
        double entityYaw = net.minecraft.util.Mth.wrapDegrees(yaw);
        double added = net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(newYRot) - entityYaw);
        return Double.isFinite(added) ? added : 0.0;
    }

    // ------------------------------------------------------------------------
    // Interior-collision discard
    // ------------------------------------------------------------------------

    private static boolean isInteriorCollision(AssemblyData d, AssemblyTransform tf, CubeBox maxBox,
                                               Vector3d maxMTV, CollisionContext ctx) {
        Vec3 localMtvV = tf.worldDirToLocal(new Vec3(maxMTV.x, maxMTV.y, maxMTV.z));
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
        BlockState offsetState = d.blockGetter.getBlockState(offsetRel);
        if (offsetState.isAir()) return false;
        VoxelShape offsetShape = offsetState.getCollisionShape(d.blockGetter, offsetRel, ctx);
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
    // Step-up
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
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * True if {@code entity}'s feet are directly above a solid block of {@code source} (in the
     * assembly's own local frame, so it works under any rotation). Sampled at the entity box's base
     * corners + centre, a hair below the feet -- the cell a resting entity's weight actually bears on.
     * Used to decide whether a tracking {@link ServerPlayer} is still genuinely supported by the
     * assembly (see the ServerPlayer branch in {@link #collide}) instead of blindly trusting a sticky
     * tracking handle.
     */
    private static boolean isStandingOn(Entity entity, AssemblyCollisionSource source, CollisionContext ctx) {
        AssemblyTransform tf = source.currentTransform();
        AABB bb = entity.getBoundingBox();
        double y = bb.minY - 0.05;
        double cx = (bb.minX + bb.maxX) * 0.5, cz = (bb.minZ + bb.maxZ) * 0.5;
        double[][] pts = {
                {cx, y, cz},
                {bb.minX, y, bb.minZ}, {bb.maxX, y, bb.minZ},
                {bb.minX, y, bb.maxZ}, {bb.maxX, y, bb.maxZ},
        };
        AssemblyCollisionBlockGetter getter = new AssemblyCollisionBlockGetter(source);
        for (double[] p : pts) {
            Vec3 local = tf.worldToLocal(new Vec3(p[0], p[1], p[2]));
            BlockPos cell = BlockPos.containing(local.x, local.y, local.z);
            BlockState state = source.getLocal(cell);
            if (state.isAir()) continue;
            if (!state.getCollisionShape(getter, cell, ctx).isEmpty()) return true;
        }
        return false;
    }

    private static Quaterniond toQuaterniond(AssemblyTransform tf) {
        var r = tf.rotation();
        return new Quaterniond(r.x, r.y, r.z, r.w);
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
