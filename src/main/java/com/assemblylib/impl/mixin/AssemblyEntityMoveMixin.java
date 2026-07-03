package com.assemblylib.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.assemblylib.impl.vchunk.collision.AssemblyCollideAccess;
import com.assemblylib.impl.vchunk.collision.AssemblyCollisionCandidates;
import com.assemblylib.impl.vchunk.collision.AssemblyCollisionInfo;
import com.assemblylib.impl.vchunk.collision.AssemblyEntityCollision;
import com.assemblylib.impl.vchunk.collision.drag.AssemblyDraggingInformation;
import com.assemblylib.impl.vchunk.collision.drag.AssemblyDraggingProvider;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * The core chokepoint for entity <-> assembly collision — a straight port of VEL's
 * {@code MixinEntityMove} (itself a port of sable's {@code entity_sublevel_collision.EntityMixin}).
 * The three operation wraps inside {@code Entity.move()} are what make vanilla's own velocity/ground
 * handling treat an assembly collision exactly like a world collision, replacing the old tick-driven
 * "push entities after the fact" loop (a separate pass over every assembly, run once per server tick
 * and once more per client tick for the local player) with a single mixin every entity's own move()
 * call already runs through — on both sides, uniformly:
 *
 * <ul>
 *   <li>{@code collide(Vec3)} -> run {@link AssemblyEntityCollision#collide}, feed the corrected
 *       motion into vanilla collide, and record which assembly the entity tracks.</li>
 *   <li>{@code setOnGroundWithMovement(boolean, Vec3)} -> merge (OR) our freshly-computed collision
 *       flags onto vanilla's own, so onGround / friction / step-up all see the assembly.</li>
 *   <li>{@code Block.updateEntityAfterFallOn(...)} -> gate it on our verticalCollision so downward
 *       velocity is zeroed by VANILLA when the entity lands on an assembly — letting vanilla do it is
 *       what stops rest-jitter instead of hand-zeroing the velocity ourselves.</li>
 * </ul>
 *
 * <p>The positional carry of riders is applied for non-living entities at TAIL here; living entities
 * get it in {@link AssemblyLivingEntityTravelMixin}.
 */
@Mixin(Entity.class)
public abstract class AssemblyEntityMoveMixin implements AssemblyCollideAccess {

    @Shadow public boolean horizontalCollision;
    @Shadow public boolean verticalCollision;
    @Shadow public boolean verticalCollisionBelow;
    @Shadow public boolean minorHorizontalCollision;

    @Shadow protected abstract Vec3 collide(Vec3 movement);

    @Override
    public Vec3 assemblylib$vanillaCollide(Vec3 movement) {
        return collide(movement);
    }

    private AssemblyCollisionInfo assemblylib$ci() {
        Entity self = (Entity) (Object) this;
        return self instanceof AssemblyDraggingProvider p ? p.assemblylib$getDraggingInfo().getCollisionInfo() : null;
    }

    @WrapOperation(
            method = "move",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setOnGroundWithMovement(ZLnet/minecraft/world/phys/Vec3;)V")
    )
    private void assemblylib$applyFlags(Entity instance, boolean onGround, Vec3 movement, Operation<Void> original) {
        AssemblyCollisionInfo ci = assemblylib$ci();
        if (ci != null && ci.touchedAssembly) {
            // MERGE, never overwrite: ORing means we never erase whatever vanilla's own world
            // collision (computed just before this call) already set.
            this.horizontalCollision |= ci.horizontalCollision;
            this.verticalCollision |= ci.verticalCollision;
            this.verticalCollisionBelow |= ci.verticalCollisionBelow;
            this.minorHorizontalCollision |= ci.minorHorizontalCollision;
            original.call(instance, this.verticalCollisionBelow, movement);
        } else {
            // No assembly involved this move -- transparent passthrough.
            original.call(instance, onGround, movement);
        }
    }

    @WrapOperation(
            method = "move",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;updateEntityAfterFallOn(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;)V")
    )
    private void assemblylib$gateFallOn(Block instance, BlockGetter level, Entity entity, Operation<Void> original) {
        AssemblyCollisionInfo ci = assemblylib$ci();
        if (ci != null && ci.touchedAssembly) {
            // On an assembly: only run the (velocity-zeroing) fall-on handler if there was a vertical
            // collision -- the field is the merged value, so this agrees with vanilla's own flag.
            if (this.verticalCollision) {
                original.call(instance, level, entity);
            }
        } else {
            original.call(instance, level, entity);
        }
    }

    @WrapOperation(
            method = "move",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;")
    )
    private Vec3 assemblylib$collide(Entity self, Vec3 collisionMotion, Operation<Vec3> operation) {
        if (!(self instanceof AssemblyDraggingProvider provider)) {
            return operation.call(self, collisionMotion);
        }
        AssemblyDraggingInformation di = provider.assemblylib$getDraggingInfo();

        // Only do the collision work when this entity is actually supposed to be colliding with an
        // assembly this move -- mirrors VEL/sable's spatially-bounded broad phase, where an entity
        // nowhere near any assembly never reaches the collision algorithm at all. forEntity does the
        // SAME distance filter AssemblyEntityCollision used to do internally, but as a standalone
        // pre-check: if it comes back empty, we skip straight to vanilla's untouched collide() without
        // constructing a CollisionInfo, without touching AssemblyEntityCollision, without any of it --
        // a genuine zero-touch passthrough, not just an eventual no-op result from calling in anyway.
        var candidates = AssemblyCollisionCandidates.forEntity(self, collisionMotion);
        if (candidates.isEmpty()) {
            // Clear stale per-tick state so a later tick's travel-mixin read never sees leftover info
            // from the last time this entity WAS near an assembly (e.g. its tracked assembly having
            // just been deleted/unloaded out from under it).
            if (di.getTrackingAssemblyHandle() != -1) {
                di.setTrackingAssemblyHandle(-1);
            }
            di.setCollisionInfo(null);
            return operation.call(self, collisionMotion);
        }

        Vec3 velocity = Vec3.ZERO;
        if (self instanceof LivingEntity) {
            velocity = new Vec3(di.inheritedVelocity.x, di.inheritedVelocity.y, di.inheritedVelocity.z);
        }

        AssemblyCollisionInfo ci = AssemblyEntityCollision.collide(self, collisionMotion, velocity, candidates);
        di.setCollisionInfo(ci);

        if (!ci.touchedAssembly) {
            // No assembly near this entity: pass the ORIGINAL motion straight down the chain so
            // vanilla handles this move exactly as if this system weren't installed.
            return operation.call(self, collisionMotion);
        }

        // An assembly is involved. Publish tracking, then run vanilla world collision on OUR
        // corrected motion -- vanilla still resolves collision against real terrain afterward.
        if (ci.trackingAssemblyHandle != -1) {
            if (ci.verticalCollisionBelow) {
                di.setTrackingAssemblyHandle(ci.trackingAssemblyHandle);
            }
        } else if (!(self instanceof net.minecraft.server.level.ServerPlayer)) {
            di.setTrackingAssemblyHandle(-1);
        }

        Vec3 before = ci.motion;
        Vec3 after = operation.call(self, before);
        if (before.y != after.y) {
            // Vanilla world collision trimmed our motion's Y further -- the entity actually landed on
            // real terrain, not our assembly, so stop riding it.
            di.setTrackingAssemblyHandle(-1);
        }
        return after;
    }

    @Inject(method = "move", at = @At("TAIL"))
    private void assemblylib$tail(MoverType moverType, Vec3 movement, CallbackInfo cinfo) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof AssemblyDraggingProvider provider)) return;
        AssemblyCollisionInfo ci = provider.assemblylib$getDraggingInfo().getCollisionInfo();
        if (ci == null || !ci.touchedAssembly) return;

        this.horizontalCollision |= ci.assemblyHorizontalCollision;

        // Carry non-living entities along the assembly here (living entities are carried in travel()).
        if (!(self instanceof LivingEntity)
                && ci.inheritedMotion != null
                && ci.inheritedMotion.lengthSqr() > Math.pow(0.000001, 2)) {
            self.setPos(self.position().add(assemblylib$vanillaCollide(ci.inheritedMotion)));
        }
    }
}
