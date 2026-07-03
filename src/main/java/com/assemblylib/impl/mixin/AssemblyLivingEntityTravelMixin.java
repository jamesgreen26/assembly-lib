package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.assemblylib.impl.vchunk.collision.AssemblyCollisionInfo;
import com.assemblylib.impl.vchunk.collision.drag.AssemblyDrag;
import com.assemblylib.impl.vchunk.collision.drag.AssemblyDraggingInformation;
import com.assemblylib.impl.vchunk.collision.drag.AssemblyDraggingProvider;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Drags a living entity along with the assembly it's standing on. Ported from VEL's
 * {@code MixinLivingEntityTravel} (VEL's port of the dragging half of sable's
 * {@code entity_sublevel_collision.LivingEntityMixin}). {@code LivingEntity.travel()} runs the
 * entity's own movement (calling {@code Entity.move()}, where {@link AssemblyEntityMoveMixin}
 * computes the collision result); at travel RETURN we apply the rigid carry the assembly imparted
 * this tick and decay any residual carry once the entity leaves.
 */
@Mixin(LivingEntity.class)
public abstract class AssemblyLivingEntityTravelMixin {

    @Inject(method = "travel", at = @At("RETURN"))
    private void assemblylib$dragWithAssemblies(Vec3 travelVector, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.isSpectator()) return;
        if (!(self instanceof AssemblyDraggingProvider provider)) return;

        AssemblyDraggingInformation dragInfo = provider.assemblylib$getDraggingInfo();
        AssemblyCollisionInfo info = dragInfo.getCollisionInfo();

        // If no assembly was involved this move, do nothing at all (don't touch the entity or its
        // velocity).
        if (info != null && !info.touchedAssembly) {
            if (dragInfo.inheritedVelocity.lengthSquared() > 1.0E-7) {
                AssemblyDrag.decayInheritedVelocity(self, dragInfo);
            } else {
                dragInfo.inheritedVelocity.zero();
            }
            return;
        }

        if (info != null) {
            // Applies the positional carry (run through vanilla collide), records it as residual
            // inheritedVelocity, and turns the entity's facing with the assembly.
            AssemblyDrag.applyCarry(self, info);
        }

        double threshold = 1.0E-7;
        if (dragInfo.inheritedVelocity.lengthSquared() <= threshold) {
            dragInfo.inheritedVelocity.zero();
        }
        // No active carry this tick but momentum remains -> decay it so the entity glides to a stop.
        if ((info == null || info.inheritedMotion == null)
                && dragInfo.inheritedVelocity.lengthSquared() > threshold) {
            AssemblyDrag.decayInheritedVelocity(self, dragInfo);
        }
    }
}
