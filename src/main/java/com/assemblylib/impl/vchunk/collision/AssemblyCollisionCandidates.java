package com.assemblylib.impl.vchunk.collision;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import com.assemblylib.impl.vchunk.Assembly;
import com.assemblylib.impl.vchunk.AssemblyManager;
import com.assemblylib.impl.vchunk.AssemblySpace;
import com.assemblylib.impl.vchunk.AssemblyTransform;
import com.assemblylib.impl.vchunk.client.ClientAssembly;
import com.assemblylib.impl.vchunk.client.ClientAssemblyManager;
import com.assemblylib.impl.vchunk.collision.drag.AssemblyDraggingProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Gathers every collidable {@link AssemblyCollisionSource} for a level, side-appropriately. Called
 * once per {@code Entity.move()} from {@code AssemblyEntityMoveMixin} — this is the broad-phase entry
 * point that lets {@link AssemblyEntityCollision} stay agnostic to which side (and which underlying
 * data source) it's running against.
 *
 * <p>VEL's own broad phase gathers candidates via a SPATIALLY-BOUNDED entity query
 * ({@code level().getEntitiesOfClass(AssemblyEntity.class, wide)}), so an entity nowhere near any
 * assembly structurally never reaches the collision algorithm at all — the "only do the collision
 * when an entity is actually supposed to be colliding with an assembly" guarantee sable/VEL rely on
 * for zero-touch coexistence. Since our assemblies aren't entities, {@link #forLevel} alone can't be
 * spatially bounded the same way (it must enumerate every loaded assembly). {@link #forEntity} closes
 * that gap: it performs the SAME distance filter VEL's spatial query gets for free, but as an
 * explicit, standalone pre-filter BEFORE {@link AssemblyEntityCollision#collide} is ever called — so
 * an uninvolved entity's {@code move()} touches none of the collision machinery, not just ends up
 * with an empty result from it.
 */
public final class AssemblyCollisionCandidates {

    /**
     * The margin VEL's own broad phase uses to FIND candidate assemblies. Only candidacy — the block
     * candidate set inside {@link AssemblyEntityCollision} is bounded by the entity's swept box
     * (~1 block), so being within this radius alone never engages the collision path.
     */
    private static final double SEARCH_RADIUS = 8.0;

    private AssemblyCollisionCandidates() {}

    /**
     * Every loaded assembly genuinely within reach of {@code entity} this move — the currently-tracked
     * assembly is always included regardless of distance (VEL's {@code findNearbyAssemblies} does the
     * same: re-adds the tracked assembly even if it fell outside the query bounds, so a ride never
     * drops mid-substep purely from a distance-filter edge case). Empty when nothing is relevant, in
     * which case the caller should skip the collision system entirely rather than call into it with an
     * empty list.
     */
    public static List<AssemblyCollisionSource> forEntity(Entity entity, Vec3 collisionMotion) {
        List<AssemblyCollisionSource> all = forLevel(entity.level());
        if (all.isEmpty()) {
            return all;
        }

        int trackingHandle = -1;
        if (entity instanceof AssemblyDraggingProvider provider) {
            trackingHandle = provider.assemblylib$getDraggingInfo().getTrackingAssemblyHandle();
        }

        AABB entityBB = entity.getBoundingBox();
        double reachMargin = entityBB.getSize() * 0.5 + SEARCH_RADIUS;

        List<AssemblyCollisionSource> nearby = new ArrayList<>();
        for (AssemblyCollisionSource source : all) {
            if (source.handle() == trackingHandle) {
                nearby.add(source);
                continue;
            }
            AABB localBounds = source.localBounds();
            if (localBounds == null) {
                continue;
            }
            Vec3 worldCenter = source.currentTransform().localToWorld(localBounds.getCenter());
            double worldRadius = 0.5 * Math.sqrt(
                localBounds.getXsize() * localBounds.getXsize()
                    + localBounds.getYsize() * localBounds.getYsize()
                    + localBounds.getZsize() * localBounds.getZsize());
            double reach = worldRadius + reachMargin;
            if (worldCenter.distanceToSqr(entityBB.getCenter()) <= reach * reach) {
                nearby.add(source);
            }
        }
        return nearby;
    }

    /** The single loaded assembly with this handle in the given level, side-appropriately, or null. */
    @Nullable
    public static AssemblyCollisionSource forHandle(Level level, int handle) {
        if (handle == -1) {
            return null;
        }
        for (AssemblyCollisionSource source : forLevel(level)) {
            if (source.handle() == handle) {
                return source;
            }
        }
        return null;
    }

    public static List<AssemblyCollisionSource> forLevel(Level level) {
        if (level.isClientSide()) {
            List<AssemblyCollisionSource> out = new ArrayList<>();
            for (ClientAssembly assembly : ClientAssemblyManager.all()) {
                out.add(new ClientSource(assembly));
            }
            return out;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        AssemblyManager manager = AssemblyManager.active(serverLevel);
        if (manager == null) {
            return List.of();
        }
        List<AssemblyCollisionSource> out = new ArrayList<>();
        for (Assembly assembly : manager.assemblies()) {
            if (assembly.isLoaded()) {
                out.add(new ServerSource(manager, assembly));
            }
        }
        return out;
    }

    private static final class ServerSource implements AssemblyCollisionSource {
        private final AssemblyManager manager;
        private final Assembly assembly;

        ServerSource(AssemblyManager manager, Assembly assembly) {
            this.manager = manager;
            this.assembly = assembly;
        }

        @Override
        public int handle() {
            return assembly.id().handle();
        }

        @Override
        public AssemblyTransform previousTransform() {
            return assembly.previousTransform();
        }

        @Override
        public AssemblyTransform currentTransform() {
            return assembly.currentTransform();
        }

        @Nullable
        @Override
        public AABB localBounds() {
            return manager.localBlockBounds(assembly);
        }

        @Override
        public BlockState getLocal(BlockPos local) {
            return manager.blockStateLocal(assembly, local);
        }

        @Override
        public void forEachLocalBlock(BiConsumer<BlockPos, BlockState> visitor) {
            int slot = assembly.slot();
            manager.forEachNonAirBlock(slot, (abs, state) -> visitor.accept(AssemblySpace.absoluteToLocal(slot, abs), state));
        }
    }

    private static final class ClientSource implements AssemblyCollisionSource {
        private final ClientAssembly assembly;

        ClientSource(ClientAssembly assembly) {
            this.assembly = assembly;
        }

        @Override
        public int handle() {
            return assembly.id().handle();
        }

        @Override
        public AssemblyTransform previousTransform() {
            return assembly.previousTransform();
        }

        @Override
        public AssemblyTransform currentTransform() {
            return assembly.currentTransform();
        }

        @Nullable
        @Override
        public AABB localBounds() {
            return assembly.localBounds();
        }

        @Override
        public BlockState getLocal(BlockPos local) {
            return assembly.clientBlocks().getLocal(local);
        }

        @Override
        public void forEachLocalBlock(BiConsumer<BlockPos, BlockState> visitor) {
            assembly.blocks().forEach(visitor);
        }
    }
}
