package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.assemblylib.impl.vchunk.AssemblyEffectRedirect;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The deeper of the two server-side particle paths. Unlike {@code ServerLevel#sendParticles} (used by
 * the handful of block behaviors that explicitly want a server-broadcast burst — composter fill,
 * bonemeal, ...), plain {@code addParticle}/{@code addAlwaysVisibleParticle} are what a block's own
 * logic calls when it "just spawns a particle" (the note block's note, redstone dust, ...). These
 * live on {@link Level} as concrete no-ops that only {@code ClientLevel} overrides to actually spawn:
 * normally the block's logic re-runs on each client (inside {@code BlockState#triggerEvent}, animate
 * ticks, ...) and the client spawns the particle locally. A vchunk assembly block is never simulated
 * on any client at its real far-away coordinates, so that client-side spawn never happens — the note
 * block's note particle is the visible casualty.
 *
 * <p>Because these methods are declared on {@code Level} (not overridden by {@code ServerLevel}), the
 * mixin must target {@code Level} and gate on {@code instanceof ServerLevel} — targeting {@code
 * ServerLevel} directly fails to locate the inherited method. For a server assembly position, turn
 * the no-op into a real broadcast at the apparent world position (a single directed particle:
 * {@code count == 0} makes the client read {@code dx/dy/dz} as velocity). {@code ClientLevel}'s own
 * override is untouched, and the {@code apparentPoint} guard makes non-assembly positions a no-op as
 * before.
 */
@Mixin(Level.class)
public abstract class AssemblyParticleMixin {

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"))
    private void assemblylib$addParticle(ParticleOptions particle, double x, double y, double z,
            double dx, double dy, double dz, CallbackInfo ci) {
        assemblylib$redirectAddParticle(particle, x, y, z, dx, dy, dz);
    }

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V", at = @At("HEAD"))
    private void assemblylib$addParticleForced(ParticleOptions particle, boolean force, double x, double y, double z,
            double dx, double dy, double dz, CallbackInfo ci) {
        assemblylib$redirectAddParticle(particle, x, y, z, dx, dy, dz);
    }

    @Inject(method = "addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"))
    private void assemblylib$addAlwaysVisibleParticle(ParticleOptions particle, double x, double y, double z,
            double dx, double dy, double dz, CallbackInfo ci) {
        assemblylib$redirectAddParticle(particle, x, y, z, dx, dy, dz);
    }

    @Inject(method = "addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V", at = @At("HEAD"))
    private void assemblylib$addAlwaysVisibleParticleForced(ParticleOptions particle, boolean force,
            double x, double y, double z, double dx, double dy, double dz, CallbackInfo ci) {
        assemblylib$redirectAddParticle(particle, x, y, z, dx, dy, dz);
    }

    @Unique
    private void assemblylib$redirectAddParticle(ParticleOptions particle, double x, double y, double z,
            double dx, double dy, double dz) {
        if (!((Object) this instanceof ServerLevel self)) {
            return;
        }
        Vec3 apparent = AssemblyEffectRedirect.apparentPoint(self, x, y, z);
        if (apparent != null) {
            self.sendParticles(particle, apparent.x, apparent.y, apparent.z, 0, dx, dy, dz, 1.0);
        }
    }
}
