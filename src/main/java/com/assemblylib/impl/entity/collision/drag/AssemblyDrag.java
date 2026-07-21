package com.assemblylib.impl.entity.collision.drag;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import com.assemblylib.impl.entity.collision.AssemblyCollideAccess;
import com.assemblylib.impl.entity.collision.AssemblyCollisionInfo;

/**
 * Applies the rigid-body carry computed by the collision pass — VEL's port of the dragging half of
 * sable's {@code LivingEntityMixin}/{@code EntityMixin}. Position carry ({@code inheritedMotion}) is
 * run through the entity's vanilla collide so a rider isn't shoved into world blocks; yaw carry
 * ({@code inheritedYaw}) turns the entity's facing with the assembly. The look-rotation drag is the
 * piece sable handles in its (separate, un-ported) {@code entities_stick_sublevels} mixins, kept
 * here so VEL riders still turn with a rotating assembly.
 */
public final class AssemblyDrag {

    private AssemblyDrag() {}

    /**
     * Apply the assembly carry (position + yaw) to {@code entity} from its last collision result.
     * For living entities the carry is also recorded as residual {@code inheritedVelocity} so it
     * decays smoothly after dismount (see {@link #decayInheritedVelocity}).
     */
    public static void applyCarry(Entity entity, AssemblyCollisionInfo info) {
        if (info == null) return;

        if (info.inheritedMotion != null && info.inheritedMotion.lengthSqr() > 1.0E-12) {
            Vec3 collided = ((AssemblyCollideAccess) entity).vel$vanillaCollide(info.inheritedMotion);
            entity.setPos(entity.position().add(collided));

            if (entity instanceof LivingEntity && entity instanceof AssemblyDraggingProvider p) {
                p.vel$getDraggingInfo().inheritedVelocity.set(
                        info.inheritedMotion.x, info.inheritedMotion.y, info.inheritedMotion.z);
            }
        }

        applyYaw(entity, (float) info.inheritedYaw);
    }

    /**
     * Decay the residual carry velocity of a living entity that is no longer being dragged, mirroring
     * sable's {@code sable$applyDrag}. The decayed value is fed back into the next collision pass as
     * coasting momentum so the entity glides to a stop instead of snapping still.
     */
    public static void decayInheritedVelocity(LivingEntity living, AssemblyDraggingInformation info) {
        var v = info.inheritedVelocity;

        if (living.verticalCollision || living.onGround()) {
            v.mul(0.7, 0.0, 0.7);
        }
        if (living.horizontalCollision) {
            v.mul(0.8, 0.6, 0.8);
        }
        if (living instanceof Player player && player.getAbilities().flying) {
            v.mul(0.9);
        }
        v.mul(0.99);
        if (Math.abs(v.y) < 0.01) {
            v.y = 0.0;
        }
    }

    private static void applyYaw(Entity entity, float addedYaw) {
        if (addedYaw == 0.0f || !Float.isFinite(addedYaw)) return;

        boolean serverAuthoritative = !entity.level().isClientSide();
        boolean dragRotation = serverAuthoritative
                || (!entity.isControlledByLocalInstance() && !(entity instanceof Player));

        if (dragRotation) {
            entity.setYRot(Mth.wrapDegrees(entity.getYRot() + addedYaw));
            if (entity instanceof LivingEntity living) {
                living.setYHeadRot(Mth.wrapDegrees(living.getYHeadRot() + addedYaw));
                living.setYBodyRot(Mth.wrapDegrees(living.yBodyRot + addedYaw));
            }
        } else {
            // Local player: turn their view with the assembly without wrapping (smooth interp).
            entity.setYRot(entity.getYRot() + addedYaw);
            if (entity instanceof LivingEntity living) {
                living.setYHeadRot(living.getYHeadRot() + addedYaw);
                living.setYBodyRot(living.yBodyRot + addedYaw);
            }
        }
    }
}
