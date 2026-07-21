package com.assemblylib.impl.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.assemblylib.impl.entity.api.AssemblyAnimator;
import com.assemblylib.impl.entity.api.BlockEntityDriver;
import com.assemblylib.impl.entity.api.behavior.AssemblyBehavior;
import com.assemblylib.impl.entity.api.behavior.AssemblyBehaviorType;
import com.assemblylib.impl.entity.api.behavior.AssemblyBehaviors;
import com.assemblylib.impl.entity.Assembly;
import com.assemblylib.impl.entity.AssemblyBlock;
import com.assemblylib.impl.entity.render.AssemblyBakedMesh;
import com.assemblylib.impl.entity.render.AssemblyDynamicRenderer;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class AssemblyEntity extends Entity {

    private static final EntityDataAccessor<Quaternionf> DATA_ROTATION =
            SynchedEntityData.defineId(AssemblyEntity.class, EntityDataSerializers.QUATERNION);
    private static final EntityDataAccessor<Vector3f> DATA_SCALE =
            SynchedEntityData.defineId(AssemblyEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<String> DATA_BEHAVIOR_TYPE =
            SynchedEntityData.defineId(AssemblyEntity.class, EntityDataSerializers.STRING);

    private Assembly assembly = new Assembly(new ArrayList<>());
    private final Quaternionf rotation = new Quaternionf(); // identity = no rotation
    private Vector3f pivot = new Vector3f(0, 0.5f, 0);
    private Vector3f angularVelocity = new Vector3f(0.005f, 0.013f, 0.007f); // matches old hardcoded spin

    /** The behavior currently attached to this entity. Lazily resolved — see ensureBehaviorResolved(). */
    private AssemblyBehavior behavior;

    public AssemblyEntity(EntityType<? extends AssemblyEntity> type, Level level) {
        super(type, level);
        this.noCulling = true;
        this.noPhysics = true;
    }

    public Assembly getAssembly() { return assembly; }

    public boolean cornersSet = false;

    public double minX, minY, minZ, maxX, maxY, maxZ;

    public void assemblyCorners() {
        minX = Double.MAX_VALUE;  minY = Double.MAX_VALUE;  minZ = Double.MAX_VALUE;
        maxX = -Double.MAX_VALUE; maxY = -Double.MAX_VALUE; maxZ = -Double.MAX_VALUE;

        for (AssemblyBlock block : assembly.getBlocks()) {
            BlockPos p = block.relativePos();
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX() + 1);
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY() + 1);
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ() + 1);
        }

        int sizeX = (int)(maxX - minX);
        int sizeZ = (int)(maxZ - minZ);
        int sizeY = (int)(maxY - minY);

        if (sizeX % 2 != 0) { minX -= 0.5; maxX -= 0.5; }
        if (sizeZ % 2 != 0) { minZ -= 0.5; maxZ -= 0.5; }
        if (sizeY % 2 != 0) { minY -= 0.0; maxY -= 0.0; } else { minY -= 0.5; maxY -= 0.5; }
    }

    public void setAssembly(Assembly assembly) {
        this.assembly = assembly;
        invalidateBoundingRadius();
        if (level().isClientSide()) {
            meshDirty = true;
        }
    }

    /** Pastes the entity's assembly into the world. Does not remove the entity itself. */
    public void placeInWorld(BlockPos origin) {
        assembly.place(level(), origin);
        ensureBehaviorResolved();
        behavior.onPlacedInWorld();
    }

    public Quaternionf getRotation() {
        return entityData.get(DATA_ROTATION);
    }

    public void setRotation(Quaternionf q) {
        entityData.set(DATA_ROTATION, new Quaternionf(q).normalize());
    }

    public void setPivot(float x, float y, float z) {
        this.pivot = new Vector3f(x, y, z);
    }

    public Vector3f getPivot() { return pivot; }

    public Vector3f getScale() {
        return entityData.get(DATA_SCALE);
    }

    public void setScale(Vector3f scale) {
        entityData.set(DATA_SCALE, scale);
        invalidateBoundingRadius();
    }

    public void setScale(float uniform) {
        setScale(new Vector3f(uniform, uniform, uniform));
    }

    /** Rotation applied per tick, in radians, around the local X/Y/Z axes. Currently unused by default — see tickAssembly(). */
    public Vector3f getAngularVelocity() { return angularVelocity; }

    public void setAngularVelocity(Vector3f angularVelocity) {
        this.angularVelocity = angularVelocity;
    }

    public Vec3 getVelocity() { return getDeltaMovement(); }

    public void setVelocity(Vec3 velocity) { setDeltaMovement(velocity); }

    public void addVelocity(Vec3 delta) { setDeltaMovement(getDeltaMovement().add(delta)); }

    // --- Behavior -------------------------------------------------------

    /** The behavior currently attached to this entity. Never null — defaults to a no-op AssemblyBehavior. */
    public AssemblyBehavior getBehavior() {
        ensureBehaviorResolved();
        return behavior;
    }

    /**
     * Attaches a behavior to this entity. Safe to call any time after spawning
     * (e.g. right after {@code AssemblyEntityTypes.ASSEMBLY.get().create(level)}),
     * and again later if you want to swap behaviors at runtime.
     */
    public void setBehaviorType(AssemblyBehaviorType<?> type) {
        ResourceLocation id = AssemblyBehaviors.BEHAVIOR_TYPE_REGISTRY.getKey(type);
        if (id == null) {
            throw new IllegalArgumentException("Cannot set an unregistered AssemblyBehaviorType: " + type);
        }
        entityData.set(DATA_BEHAVIOR_TYPE, id.toString());
        this.behavior = type.create(this);
    }

    private void ensureBehaviorResolved() {
        if (behavior == null) {
            resolveBehavior();
        }
    }

    private void resolveBehavior() {
        AssemblyBehaviorType<?> type = null;
        ResourceLocation id = ResourceLocation.tryParse(entityData.get(DATA_BEHAVIOR_TYPE));
        if (id != null) {
            type = AssemblyBehaviors.BEHAVIOR_TYPE_REGISTRY.get(id);
        }
        this.behavior = (type != null) ? type.create(this) : new AssemblyBehavior(this);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_BEHAVIOR_TYPE.equals(key)) {
            resolveBehavior();
        }
    }

    // ---------------------------------------------------------------------

    public final Quaternionf prevRotation = new Quaternionf();
    public final Vector3f prevScale = new Vector3f(1, 1, 1);

    /**
     * Stable per-tick transform snapshots used ONLY by the dragging system (the rigid carry
     * computed in {@link com.assemblylib.impl.entity.collision.AssemblyEntityCollision}).
     *
     * <p>{@link #prevRotation}/{@link #prevScale} above are captured at the START of the tick
     * and paired with the LIVE {@code getRotation()} for sub-tick render interpolation. They
     * are NOT usable for dragging: on the client the rotation only advances when a sync packet
     * lands, so reading the live value mid-tick yields a bursty delta and riders stutter at
     * tick rate ("20 fps"). These snapshots instead capture BOTH endpoints at the END of each
     * tick, so consecutive ticks give a consistent one-tick transform delta — the VEL analog
     * of VS reading {@code ship.transform} vs {@code ship.prevTickTransform}.</p>
     */
    public final Quaternionf dragRotPrev = new Quaternionf();
    public final Quaternionf dragRotCur = new Quaternionf();
    public Vec3 dragPosPrev = Vec3.ZERO;
    public Vec3 dragPosCur = Vec3.ZERO;
    public final Vector3f dragScalePrev = new Vector3f(1, 1, 1);
    public final Vector3f dragScaleCur = new Vector3f(1, 1, 1);
    private boolean dragSnapInit = false;

    /**
     * Cached world-space radius: the farthest any block corner sits from the entity
     * center, after scale. Rotation doesn't change a radius, so this is rotation-free.
     * Used by collision to find this assembly even when the player is near a block far
     * from the center (the entity's own AABB is tiny and wouldn't be returned otherwise).
     */
    private double cachedBoundingRadius = -1.0;

    /** World-space radius from the entity center out to the farthest block corner (after scale). */
    public double getBoundingRadius() {
        if (cachedBoundingRadius < 0.0) {
            double maxSq = 0.0;
            Vector3f s = getScale();
            for (AssemblyBlock block : assembly.getBlocks()) {
                BlockPos p = block.relativePos();
                // Farthest corner of this block cell from the local origin, then scaled.
                double cx = Math.max(Math.abs(p.getX()), Math.abs(p.getX() + 1)) * Math.abs(s.x);
                double cy = Math.max(Math.abs(p.getY()), Math.abs(p.getY() + 1)) * Math.abs(s.y);
                double cz = Math.max(Math.abs(p.getZ()), Math.abs(p.getZ() + 1)) * Math.abs(s.z);
                double sq = cx * cx + cy * cy + cz * cz;
                if (sq > maxSq) maxSq = sq;
            }
            cachedBoundingRadius = Math.sqrt(maxSq) + 1.0; // +1 margin
        }
        return cachedBoundingRadius;
    }

    /** Call whenever blocks or scale change so the radius (and culling box) refresh. */
    public void invalidateBoundingRadius() {
        cachedBoundingRadius = -1.0;
    }

    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        double r = getBoundingRadius();
        return new net.minecraft.world.phys.AABB(
                getX() - r, getY() - r, getZ() - r,
                getX() + r, getY() + r, getZ() + r);
    }

    @Override
    public void tick() {
        prevRotation.set(getRotation());
        prevScale.set(getScale());

        tickAssembly();

        ensureBehaviorResolved();
        behavior.tick();

        // Advance any tick-rate block-entity animations (bell swing, shulker progress, …) at the
        // fixed 20/s entity tick — the per-frame driveBlockEntities() hook is too fast for these.
        if (level().isClientSide()) {
            BlockEntityDriver driver = getBlockEntityDriver();
            if (driver != null && dynamicRenderer != null) {
                driver.tickBlockEntities(dynamicRenderer.getBlockEntities());
            }
        }

        // After the behavior has moved/rotated us this tick, advance the drag snapshots so
        // riders read a clean, consistent one-tick transform delta (see field docs above).
        updateDragSnapshots();
    }

    /** True if this assembly's transform changed this tick (used to push entities it sweeps into). */
    public boolean movedThisTick() {
        if (!dragSnapInit) return false;
        double dp = dragPosCur.distanceToSqr(dragPosPrev);
        double dq = Math.abs(dragRotCur.x() - dragRotPrev.x())
                + Math.abs(dragRotCur.y() - dragRotPrev.y())
                + Math.abs(dragRotCur.z() - dragRotPrev.z())
                + Math.abs(dragRotCur.w() - dragRotPrev.w());
        return dp > 1.0E-12 || dq > 1.0E-7;
    }

    private void updateDragSnapshots() {
        if (!dragSnapInit) {
            dragRotPrev.set(getRotation());
            dragPosPrev = position();
            dragScalePrev.set(getScale());
            dragSnapInit = true;
        } else {
            dragRotPrev.set(dragRotCur);
            dragPosPrev = dragPosCur;
            dragScalePrev.set(dragScaleCur);
        }
        dragRotCur.set(getRotation());
        dragPosCur = position();
        dragScaleCur.set(getScale());
    }

    /**
     * VEL's own built-in per-tick assembly logic. Intentionally left empty for
     * now — fill it back in here later if/when default movement/physics are
     * needed again. Runs every tick, on both sides, before the attached
     * behavior's tick().
     */
    protected void tickAssembly() {
        // intentionally empty for now — this used to run by default:
        //
//         if (angularVelocity.x != 0 || angularVelocity.y != 0 || angularVelocity.z != 0) {
//             setRotation(new Quaternionf(getRotation())
//                     .rotateXYZ(angularVelocity.x, angularVelocity.y, angularVelocity.z));
//         }
//
//         Vec3 velocity = getDeltaMovement();
//         if (velocity.lengthSqr() > 1.0E-7 /*smol number*/) {
//             setPos(getX() + velocity.x, getY() + velocity.y, getZ() + velocity.z);
//         }
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ensureBehaviorResolved();
        if (behavior.interact(player, hand)) {
            return InteractionResult.SUCCESS;
        }
        return super.interact(player, hand);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ROTATION, new Quaternionf());
        builder.define(DATA_SCALE, new Vector3f(1, 1, 1));
        builder.define(DATA_BEHAVIOR_TYPE, "vel:none");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("blocks", com.assemblylib.impl.entity.AssemblyCodec.save(assembly.getBlocks()));

        Quaternionf rotation = getRotation();
        CompoundTag rot = new CompoundTag();
        rot.putFloat("x", rotation.x());
        rot.putFloat("y", rotation.y());
        rot.putFloat("z", rotation.z());
        rot.putFloat("w", rotation.w());
        tag.put("rotation", rot);

        Vector3f scale = getScale();
        CompoundTag scaleTag = new CompoundTag();
        scaleTag.putFloat("x", scale.x);
        scaleTag.putFloat("y", scale.y);
        scaleTag.putFloat("z", scale.z);
        tag.put("scale", scaleTag);

        CompoundTag angVel = new CompoundTag();
        angVel.putFloat("x", angularVelocity.x);
        angVel.putFloat("y", angularVelocity.y);
        angVel.putFloat("z", angularVelocity.z);
        tag.put("angularVelocity", angVel);

        ensureBehaviorResolved();
        tag.putString("behaviorType", entityData.get(DATA_BEHAVIOR_TYPE));
        tag.put("behaviorData", behavior.save());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        List<AssemblyBlock> blocks = com.assemblylib.impl.entity.AssemblyCodec.load(
                tag.getCompound("blocks"), level().registryAccess());
        this.assembly = new Assembly(blocks);
        invalidateBoundingRadius();

        if (tag.contains("rotation")) {
            CompoundTag rot = tag.getCompound("rotation");
            setRotation(new Quaternionf(rot.getFloat("x"), rot.getFloat("y"), rot.getFloat("z"), rot.getFloat("w")));
        }
        if (tag.contains("scale")) {
            CompoundTag s = tag.getCompound("scale");
            setScale(new Vector3f(s.getFloat("x"), s.getFloat("y"), s.getFloat("z")));
        }
        if (tag.contains("angularVelocity")) {
            CompoundTag av = tag.getCompound("angularVelocity");
            setAngularVelocity(new Vector3f(av.getFloat("x"), av.getFloat("y"), av.getFloat("z")));
        }

        if (tag.contains("behaviorType")) {
            entityData.set(DATA_BEHAVIOR_TYPE, tag.getString("behaviorType"));
        }
        resolveBehavior();
        if (tag.contains("behaviorData")) {
            behavior.load(tag.getCompound("behaviorData"));
        }

        if (level().isClientSide()) meshDirty = true;
    }

    @Override
    public void remove(RemovalReason reason) {
        ensureBehaviorResolved();
        behavior.onRemoved(reason);

        super.remove(reason);
        if (level().isClientSide()) {
            if (bakedMesh != null) bakedMesh.dispose();
            if (dynamicRenderer != null) dynamicRenderer.dispose();
        }
    }

    @OnlyIn(Dist.CLIENT) private AssemblyBakedMesh bakedMesh;
    @OnlyIn(Dist.CLIENT) private AssemblyDynamicRenderer dynamicRenderer;
    @OnlyIn(Dist.CLIENT) private boolean meshDirty = true;

    /** Client-side render animator. May be set explicitly, or supplied by the behavior. */
    @OnlyIn(Dist.CLIENT) private AssemblyAnimator animator;

    @OnlyIn(Dist.CLIENT)
    public AssemblyBakedMesh getOrBuildMesh() {
        if (bakedMesh == null) {
            bakedMesh = new AssemblyBakedMesh();
        }
        if (meshDirty) {
            meshDirty = false;
            var dynamic = getAnimator() != null ? getAnimator().getDynamicBlocks() : java.util.Set.<BlockPos>of();
            bakedMesh.rebuild(assembly.getBlocks(), position(), dynamic);
            // Animated blocks left out of the bake changed -> BE cache may need a refresh too.
            if (dynamicRenderer != null) dynamicRenderer.rebuild(assembly.getBlocks());
        }
        return bakedMesh;
    }

    /** The per-frame dynamic pass (block entities + animated blocks). Lazily created. */
    @OnlyIn(Dist.CLIENT)
    public AssemblyDynamicRenderer getOrBuildDynamicRenderer() {
        if (dynamicRenderer == null) {
            dynamicRenderer = new AssemblyDynamicRenderer();
            dynamicRenderer.rebuild(assembly.getBlocks());
        }
        return dynamicRenderer;
    }

    /**
     * Resolved animator: an explicitly-set one wins, otherwise the attached behavior if it
     * happens to implement {@link AssemblyAnimator}.
     */
    @OnlyIn(Dist.CLIENT)
    public AssemblyAnimator getAnimator() {
        if (animator != null) return animator;
        AssemblyBehavior b = getBehavior();
        return (b instanceof AssemblyAnimator a) ? a : null;
    }

    /** Attach a render animator. Triggers a rebake since the dynamic-block set may change. */
    @OnlyIn(Dist.CLIENT)
    public void setAnimator(AssemblyAnimator animator) {
        this.animator = animator;
        markMeshDirty();
    }

    /**
     * Resolved block-entity driver: the explicit animator if it implements
     * {@link BlockEntityDriver}, otherwise the attached behavior if it does. Null if neither
     * drives block entities. Used for both the per-frame and per-tick BE animation hooks.
     */
    @OnlyIn(Dist.CLIENT)
    public BlockEntityDriver getBlockEntityDriver() {
        if (animator instanceof BlockEntityDriver a) return a;
        AssemblyBehavior b = getBehavior();
        return (b instanceof BlockEntityDriver d) ? d : null;
    }

    /** Force the static mesh (and dynamic block set) to rebuild on the next frame. */
    @OnlyIn(Dist.CLIENT)
    public void markMeshDirty() {
        meshDirty = true;
    }
}