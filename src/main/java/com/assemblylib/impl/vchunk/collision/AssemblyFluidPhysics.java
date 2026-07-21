package com.assemblylib.impl.vchunk.collision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.assemblylib.impl.vchunk.AssemblyTransform;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Gives entities the fluid mechanics of an assembly's liquids — buoyancy, current push, swimming, and
 * eye-in-fluid (fog, breath) — even though the assembly's fluids are real blocks stored far away and,
 * for a moving/rotating assembly, are not axis-aligned with the world.
 *
 * <p>Vanilla's {@code Entity.updateFluidHeightAndDoFluidPushing} and {@code updateFluidOnEyes} sample
 * {@code level.getFluidState} in <em>world</em> space around the entity, so they never see an
 * assembly's far-away fluid cells. This class is the assembly-space parallel of those loops: it
 * transforms the entity's box (and eye) into each nearby assembly's local frame, walks the fluid
 * cells it overlaps, and accumulates the same fluid-height and flow-push quantities vanilla does —
 * rotating each cell's flow vector back into world space so a tilted assembly's current pushes in the
 * right direction. The result is fed back into the entity's NeoForge {@code FluidType} height map and
 * eye state by {@code AssemblyEntityFluidMixin}, so every downstream system (water movement in
 * {@code LivingEntity.travel}, splash, fall-damage reset, swimming pose, underwater fog/breathing)
 * then behaves exactly as it would in world-space water.
 *
 * <p><b>Scope note:</b> "height above the entity's feet" is computed from the world-Y of the fluid
 * cell's surface. For an upright or gently-tilted assembly this matches vanilla closely; for a
 * steeply rolled assembly the notion of a single vertical fluid column is inherently approximate.
 */
public final class AssemblyFluidPhysics {

    private AssemblyFluidPhysics() {}

    /** One fluid type's accumulated effect on an entity this tick. */
    public static final class Contribution {
        public final FluidType type;
        /** Submersion height above the entity box's bottom (vanilla's {@code fluidHeight}). */
        public double height;
        Vec3 flow = Vec3.ZERO;
        int flowBlocks;

        Contribution(FluidType type) {
            this.type = type;
        }
    }

    /**
     * Scan every nearby assembly's fluids overlapping {@code entity}, apply the resulting current push
     * to the entity's velocity, and return the per-fluid submersion heights to merge into the entity's
     * {@code FluidType} height map. Empty when no assembly fluid touches the entity — the common case,
     * kept allocation-free-ish and cheap so uninvolved entities pay almost nothing.
     */
    public static List<Contribution> applyFluidPushing(Entity entity) {
        List<AssemblyCollisionSource> candidates = AssemblyCollisionCandidates.forEntity(entity, Vec3.ZERO);
        if (candidates.isEmpty()) {
            return List.of();
        }

        AABB aabb = entity.getBoundingBox().deflate(0.001);
        boolean pushed = entity.isPushedByFluid();
        Map<FluidType, Contribution> byType = new LinkedHashMap<>();

        for (AssemblyCollisionSource source : candidates) {
            if (source.localBounds() == null) {
                continue;
            }
            AssemblyTransform tf = source.currentTransform();
            AssemblyCollisionBlockGetter getter = new AssemblyCollisionBlockGetter(source);

            int[] range = localCellRange(tf, aabb);
            for (int lx = range[0]; lx <= range[3]; lx++) {
                for (int ly = range[1]; ly <= range[4]; ly++) {
                    for (int lz = range[2]; lz <= range[5]; lz++) {
                        BlockPos cell = new BlockPos(lx, ly, lz);
                        FluidState fluid = source.getLocal(cell).getFluidState();
                        if (fluid.isEmpty()) {
                            continue;
                        }
                        FluidType type = fluid.getFluidType();
                        if (type.isAir()) {
                            continue;
                        }

                        float fh = fluid.getHeight(getter, cell);
                        // World-Y of this fluid cell's surface (cell centre column), mirroring vanilla's
                        // `(float)i2 + fluidstate.getHeight(...)` but through the assembly's rigid pose.
                        double surfaceY = tf.localToWorld(new Vec3(lx + 0.5, ly + fh, lz + 0.5)).y;
                        if (surfaceY < aabb.minY) {
                            continue;
                        }

                        Contribution c = byType.computeIfAbsent(type, Contribution::new);
                        c.height = Math.max(c.height, surfaceY - aabb.minY);

                        if (pushed && entity.isPushedByFluid(type)) {
                            Vec3 localFlow = fluid.getFlow(getter, cell);
                            Vec3 worldFlow = tf.localDirToWorld(localFlow);
                            if (c.height < 0.4D) {
                                worldFlow = worldFlow.scale(c.height);
                            }
                            c.flow = c.flow.add(worldFlow);
                            c.flowBlocks++;
                        }
                    }
                }
            }
        }

        if (byType.isEmpty()) {
            return List.of();
        }

        List<Contribution> out = new ArrayList<>(byType.size());
        for (Contribution c : byType.values()) {
            if (c.flow.length() > 0.0D) {
                Vec3 flow = c.flow;
                if (c.flowBlocks > 0) {
                    flow = flow.scale(1.0D / c.flowBlocks);
                }
                if (!(entity instanceof Player)) {
                    flow = flow.normalize();
                }
                flow = flow.scale(entity.getFluidMotionScale(c.type));
                Vec3 dm = entity.getDeltaMovement();
                if (Math.abs(dm.x) < 0.003D && Math.abs(dm.z) < 0.003D
                        && flow.length() < 0.0045000000000000005D) {
                    flow = flow.normalize().scale(0.0045000000000000005D);
                }
                entity.setDeltaMovement(entity.getDeltaMovement().add(flow));
            }
            out.add(c);
        }
        return out;
    }

    /**
     * The fluid type covering the entity's eye through some nearby assembly, or {@code null} if none.
     * Mirrors the eye-column test in vanilla's {@code updateFluidOnEyes}, in each assembly's frame.
     */
    @Nullable
    public static FluidType eyeFluid(Entity entity) {
        List<AssemblyCollisionSource> candidates = AssemblyCollisionCandidates.forEntity(entity, Vec3.ZERO);
        if (candidates.isEmpty()) {
            return null;
        }
        double eyeY = entity.getEyeY();
        Vec3 eye = new Vec3(entity.getX(), eyeY, entity.getZ());

        FluidType found = null;
        for (AssemblyCollisionSource source : candidates) {
            if (source.localBounds() == null) {
                continue;
            }
            AssemblyTransform tf = source.currentTransform();
            AssemblyCollisionBlockGetter getter = new AssemblyCollisionBlockGetter(source);
            Vec3 local = tf.worldToLocal(eye);
            BlockPos cell = BlockPos.containing(local.x, local.y, local.z);
            FluidState fluid = source.getLocal(cell).getFluidState();
            if (fluid.isEmpty()) {
                continue;
            }
            float fh = fluid.getHeight(getter, cell);
            double surfaceY = tf.localToWorld(new Vec3(cell.getX() + 0.5, cell.getY() + fh, cell.getZ() + 0.5)).y;
            if (surfaceY > eyeY) {
                found = fluid.getFluidType();
            }
        }
        return found;
    }

    /** Integer local-cell bounds {minX,minY,minZ,maxX,maxY,maxZ} covering a world AABB under a rigid pose. */
    private static int[] localCellRange(AssemblyTransform tf, AABB world) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    Vec3 corner = new Vec3(
                        xi == 0 ? world.minX : world.maxX,
                        yi == 0 ? world.minY : world.maxY,
                        zi == 0 ? world.minZ : world.maxZ);
                    Vec3 l = tf.worldToLocal(corner);
                    minX = Math.min(minX, l.x); maxX = Math.max(maxX, l.x);
                    minY = Math.min(minY, l.y); maxY = Math.max(maxY, l.y);
                    minZ = Math.min(minZ, l.z); maxZ = Math.max(maxZ, l.z);
                }
            }
        }
        return new int[]{
            Mth.floor(minX), Mth.floor(minY), Mth.floor(minZ),
            Mth.floor(maxX), Mth.floor(maxY), Mth.floor(maxZ)
        };
    }
}
