package com.assemblylib.impl.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.assemblylib.impl.vchunk.client.AssemblyParticleCollision;

import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Makes physics particles collide with assemblies exactly like they collide with real terrain. Every
 * collision-having particle ({@code hasPhysics} — falling dust, block-break crack, etc.) resolves its
 * per-tick motion through this one {@code Entity.collideBoundingBox} call inside {@code Particle.move};
 * wrapping it lets vanilla collide against the real world first, then hands the result to
 * {@link AssemblyParticleCollision} to further restrict it against any nearby assembly. Because this
 * only trims the motion vector, vanilla's own downstream {@code Particle.move} logic
 * ({@code stoppedByCollision}, {@code onGround}, zeroing {@code xd}/{@code zd}) reacts to an assembly
 * landing identically to a terrain landing — no per-particle-type mixins, and decorative
 * non-physics particles (which never reach this call) still pass through, just as they do terrain.
 */
@Mixin(Particle.class)
public abstract class AssemblyParticleCollisionMixin {

    @WrapOperation(
        method = "move",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 assemblylib$collideWithAssemblies(Entity entity, Vec3 movement, AABB box, Level level,
            List<VoxelShape> extra, Operation<Vec3> original) {
        Vec3 vanilla = original.call(entity, movement, box, level, extra);
        return AssemblyParticleCollision.collide(box, vanilla);
    }
}
