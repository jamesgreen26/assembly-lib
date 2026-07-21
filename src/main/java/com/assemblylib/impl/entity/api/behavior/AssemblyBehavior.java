package com.assemblylib.impl.entity.api.behavior;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.assemblylib.impl.entity.api.BlockEntityDriver;
import com.assemblylib.impl.entity.AssemblyEntity;

import java.util.Map;

/**
 * Extend this to give an {@link AssemblyEntity} custom behavior without
 * registering a new EntityType. One instance of your subclass is created
 * per assembly entity it's attached to (via your {@link AssemblyBehaviorType}),
 * so it's safe to keep mutable fields on it the same way you would on a
 * BlockEntity.
 *
 * <p>Every hook here is a no-op by default — override only what you need.
 * {@link #tick()} runs every tick on both sides, alongside (not instead of)
 * AssemblyEntity's own tick logic.</p>
 */
public class AssemblyBehavior implements BlockEntityDriver {

    protected final AssemblyEntity entity;

    public AssemblyBehavior(AssemblyEntity entity) {
        this.entity = entity;
    }

    public AssemblyEntity getEntity() {
        return entity;
    }

    /** Runs every tick, on both client and server, alongside AssemblyEntity's own tick. */
    public void tick() {
    }

    /**
     * Called when a player interacts with the assembly entity (e.g. right-click).
     * Return true to mark the interaction handled and suppress default handling.
     */
    public boolean interact(Player player, InteractionHand hand) {
        return false;
    }

    /** Called server-side right after this assembly's blocks are placed into the world. */
    public void onPlacedInWorld() {
    }

    /** Called when the entity is removed from the level, for any reason. */
    public void onRemoved(Entity.RemovalReason reason) {
    }

    /** Persist whatever this behavior needs. Folded into the entity's own save data. */
    public CompoundTag save() {
        return new CompoundTag();
    }

    /** Restore whatever this behavior needs. Called right after this instance is (re)created. */
    public void load(CompoundTag tag) {
    }

    @Override
    public void driveBlockEntities(Map<BlockPos, BlockEntity> bes, float partialTick) {
        for (BlockEntity be : bes.values()) {
            if (be instanceof BellBlockEntity bell && !bell.shaking) {
                bell.onHit(Direction.EAST);
            }
        }
    }

    @Override
    public void tickBlockEntities(Map<BlockPos, BlockEntity> bes) {
        for (BlockEntity be : bes.values()) {
            if (be instanceof BellBlockEntity bell && bell.shaking) {
                if (bell.ticks++ >= 50) { bell.shaking = false; bell.ticks = 0; }
            }
        }
    }
}