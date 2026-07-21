package com.assemblylib.impl.entity.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import com.assemblylib.impl.entity.Assembly;
import com.assemblylib.impl.entity.AssemblyBlock;
import com.assemblylib.impl.entity.AssemblyEntity;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * The transform API for an {@link AssemblyEntity} — the VEL analog of Valkyrien Skies'
 * ship transform (getShipToWorld / getWorldToShip).
 *
 * <h2>Two coordinate spaces</h2>
 * <ul>
 *   <li><b>Local space</b> — block-relative coordinates. A block stored at
 *       {@code AssemblyBlock.relativePos()} occupies the unit cube
 *       {@code [relPos, relPos+1]} in local space. Always axis-aligned.</li>
 *   <li><b>World space</b> — actual Minecraft world coordinates, where the assembly
 *       appears rotated/scaled/translated.</li>
 * </ul>
 *
 * <h2>The transform (identical to what AssemblyRenderer draws)</h2>
 * <pre>
 *   world = entityPos + pivot + R * ( S * ( (local - frac) - pivot ) )
 *   local = frac + pivot + ( R^-1 * (world - entityPos - pivot) ) / S
 * </pre>
 * where {@code R} = rotation, {@code S} = scale, {@code pivot} = entity pivot,
 * {@code frac = entityPos - floor(entityPos)} (the mesh is baked relative to
 * {@code floor(entityPos)}, so local block positions carry this fractional offset).
 *
 * <p>Capture an instance once per tick via {@link #of(AssemblyEntity)} — it snapshots
 * the entity's current transform so all your queries are consistent within a frame.</p>
 */
public final class AssemblyTransform {

    private final AssemblyEntity entity;
    private final Assembly assembly;

    private final Vec3 entityPos;
    private final Vector3f pivot;
    private final Vector3d scale;
    private final Vec3 frac;
    private final Quaternionf rotation;
    private final Quaternionf invRotation;

    // Lazy world-space block lookup map (built on first worldToBlock query)
    private Map<BlockPos, BlockState> localBlockMap;

    private AssemblyTransform(AssemblyEntity entity, Vec3 pos, Vector3f scaleSrc, Quaternionf rot) {
        this.entity = entity;
        this.assembly = entity.getAssembly();

        this.entityPos = pos;
        this.pivot = new Vector3f(entity.getPivot());

        // Guard against zero/degenerate scale (would make the inverse blow up)
        this.scale = new Vector3d(
                Math.abs(scaleSrc.x) < 1.0E-6 ? 1.0 : scaleSrc.x,
                Math.abs(scaleSrc.y) < 1.0E-6 ? 1.0 : scaleSrc.y,
                Math.abs(scaleSrc.z) < 1.0E-6 ? 1.0 : scaleSrc.z
        );

        this.frac = new Vec3(
                entityPos.x - Math.floor(entityPos.x),
                entityPos.y - Math.floor(entityPos.y),
                entityPos.z - Math.floor(entityPos.z)
        );

        this.rotation = new Quaternionf(rot);
        this.invRotation = new Quaternionf(this.rotation).conjugate();
    }

    /** Snapshot the entity's current transform. Cheap; capture once per query batch. */
    public static AssemblyTransform of(AssemblyEntity entity) {
        return new AssemblyTransform(entity, entity.position(), entity.getScale(), entity.getRotation());
    }

    /**
     * Build a transform from explicit position/rotation/scale (rather than the entity's live
     * values). Used by the collision substep loop to sample the assembly's pose at an interpolated
     * point between the previous and current tick — the VEL analog of sable's
     * {@code lastPose.lerp(logicalPose, t)}.
     */
    public static AssemblyTransform of(AssemblyEntity entity, Vec3 pos, Quaternionf rot, Vector3f scale) {
        return new AssemblyTransform(entity, pos, scale, rot);
    }

    /**
     * Snapshot the entity's transform as it was at the START of this tick (previous-tick
     * position/rotation/scale). Used by the dragging system to compute how far a moving or
     * rotating assembly carried an entity standing on it this tick — the VEL analog of VS's
     * {@code prevTickTransform}.
     */
    public static AssemblyTransform ofPrevious(AssemblyEntity entity) {
        return new AssemblyTransform(
                entity,
                new Vec3(entity.xo, entity.yo, entity.zo),
                entity.prevScale,
                entity.prevRotation);
    }

    /**
     * Drag-system transforms built from the assembly's stable per-tick snapshots
     * ({@code dragRotCur}/{@code dragRotPrev} etc). Unlike {@link #of}/{@link #ofPrevious},
     * these use values captured at a consistent point each tick, so the current↔previous
     * delta is a clean one-tick rigid motion even on the client (where live rotation only
     * advances on sync packets). The VEL analog of VS's {@code ship.transform} vs
     * {@code ship.prevTickTransform}.
     */
    public static AssemblyTransform ofDragCurrent(AssemblyEntity entity) {
        return new AssemblyTransform(entity, entity.dragPosCur, entity.dragScaleCur, entity.dragRotCur);
    }

    public static AssemblyTransform ofDragPrevious(AssemblyEntity entity) {
        return new AssemblyTransform(entity, entity.dragPosPrev, entity.dragScalePrev, entity.dragRotPrev);
    }

    public AssemblyEntity getEntity() { return entity; }
    public Quaternionf getRotation()  { return new Quaternionf(rotation); }
    public Vector3d getScale()        { return new Vector3d(scale); }

    /**
     * The world-space point the assembly rotates about ({@code entityPos + pivot}). Used by the
     * dragging math to build a frac-free rigid carry: a point's offset from this center is rotated
     * by the inverse of the previous transform and re-rotated by the current one. (Going through
     * {@link #localToWorld}/{@link #worldToLocal} instead would mix the two transforms' {@code frac}
     * terms and quantise the carry to whole blocks.)
     */
    public Vec3 getWorldRotationCenter() {
        return new Vec3(entityPos.x + pivot.x, entityPos.y + pivot.y, entityPos.z + pivot.z);
    }

    // -----------------------------------------------------------------------
    // Position transforms (the core API)
    // -----------------------------------------------------------------------

    /**
     * Local (block-relative) position → exact world-space position (non-integer).
     *
     * <p>Example: {@code localToWorld(new Vec3(relX + 0.5, relY + 0.5, relZ + 0.5))}
     * gives the exact world position of that block's center, accounting for the
     * assembly's rotation, scale, and translation.</p>
     */
    public Vec3 localToWorld(Vec3 local) {
        // p = (local - frac) - pivot
        Vector3f p = new Vector3f(
                (float) (local.x - frac.x - pivot.x),
                (float) (local.y - frac.y - pivot.y),
                (float) (local.z - frac.z - pivot.z)
        );
        // p = S * p
        p.mul((float) scale.x, (float) scale.y, (float) scale.z);
        // p = R * p
        p.rotate(rotation);
        // world = entityPos + pivot + p
        return new Vec3(
                entityPos.x + pivot.x + p.x,
                entityPos.y + pivot.y + p.y,
                entityPos.z + pivot.z + p.z
        );
    }

    /** Convenience overload for a block's corner (its {@code relativePos}). */
    public Vec3 localToWorld(BlockPos localBlock) {
        return localToWorld(new Vec3(localBlock.getX(), localBlock.getY(), localBlock.getZ()));
    }

    /**
     * World-space position → local (block-relative) position (non-integer).
     *
     * <p>{@code Math.floor()} each component of the result to get the
     * {@code relativePos} of the block cell that contains the world point.</p>
     */
    public Vec3 worldToLocal(Vec3 world) {
        // t = R^-1 * (world - entityPos - pivot)
        Vector3f t = new Vector3f(
                (float) (world.x - entityPos.x - pivot.x),
                (float) (world.y - entityPos.y - pivot.y),
                (float) (world.z - entityPos.z - pivot.z)
        );
        t.rotate(invRotation);
        // local = t / S + pivot + frac
        return new Vec3(
                t.x / scale.x + pivot.x + frac.x,
                t.y / scale.y + pivot.y + frac.y,
                t.z / scale.z + pivot.z + frac.z
        );
    }

    /**
     * Transform a world-space DIRECTION/velocity into local space (rotation + inverse
     * scale only — no translation). Useful for collision sweeps.
     */
    public Vec3 worldToLocalDirection(Vec3 worldDir) {
        Vector3f t = new Vector3f((float) worldDir.x, (float) worldDir.y, (float) worldDir.z);
        t.rotate(invRotation);
        return new Vec3(t.x / scale.x, t.y / scale.y, t.z / scale.z);
    }

    /** Transform a local-space DIRECTION back into world space (scale + rotation). */
    public Vec3 localToWorldDirection(Vec3 localDir) {
        Vector3f t = new Vector3f(
                (float) (localDir.x * scale.x),
                (float) (localDir.y * scale.y),
                (float) (localDir.z * scale.z)
        );
        t.rotate(rotation);
        return new Vec3(t.x, t.y, t.z);
    }

    // -----------------------------------------------------------------------
    // Block queries
    // -----------------------------------------------------------------------

    /**
     * The {@code relativePos} of the block cell containing the given world position,
     * or {@code null} if the assembly has no (non-air) block there.
     *
     * <p>This is the "is there a block from this assembly at this world position?" query.</p>
     */
    public BlockPos worldToBlockPos(Vec3 world) {
        Vec3 local = worldToLocal(world);
        BlockPos rel = BlockPos.containing(
                Math.floor(local.x), Math.floor(local.y), Math.floor(local.z));
        return blockMap().containsKey(rel) ? rel : null;
    }

    /**
     * The {@link BlockState} of this assembly at the given world position, or
     * {@code Blocks.AIR.defaultBlockState()} if there is none.
     */
    public BlockState getBlockStateAtWorld(Vec3 world) {
        Vec3 local = worldToLocal(world);
        BlockPos rel = BlockPos.containing(
                Math.floor(local.x), Math.floor(local.y), Math.floor(local.z));
        return blockMap().getOrDefault(rel, Blocks.AIR.defaultBlockState());
    }

    /** True if this assembly has a (non-air) block whose cell contains the world position. */
    public boolean hasBlockAtWorld(Vec3 world) {
        return worldToBlockPos(world) != null;
    }

    /** Exact world-space center of a given local block. */
    public Vec3 blockCenterToWorld(BlockPos localBlock) {
        return localToWorld(new Vec3(
                localBlock.getX() + 0.5, localBlock.getY() + 0.5, localBlock.getZ() + 0.5));
    }

    private Map<BlockPos, BlockState> blockMap() {
        if (localBlockMap == null) {
            localBlockMap = new HashMap<>(assembly.getBlocks().size());
            for (AssemblyBlock b : assembly.getBlocks()) {
                if (!b.state().isAir()) {
                    localBlockMap.put(b.relativePos(), b.state());
                }
            }
        }
        return localBlockMap;
    }
}