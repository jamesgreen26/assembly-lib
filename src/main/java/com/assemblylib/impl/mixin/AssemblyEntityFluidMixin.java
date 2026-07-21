package com.assemblylib.impl.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.assemblylib.impl.vchunk.collision.AssemblyFluidPhysics;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * The deep-root hook that makes an assembly's liquids affect entities exactly like world liquids do —
 * buoyancy, current push, swimming, splash, fall-damage reset, and underwater fog/breathing — with no
 * per-mechanic special-casing. Both of vanilla's fluid-state entry points funnel every entity through
 * here every tick, so anything that reads {@code isInWater}/{@code isInLava}/{@code isEyeInFluid}
 * (vanilla or modded) transparently sees assembly fluids too.
 *
 * <ul>
 *   <li>{@code updateFluidHeightAndDoFluidPushing()} (the NeoForge unified per-tick fluid scan) — at
 *       its tail we merge the submersion heights from {@link AssemblyFluidPhysics#applyFluidPushing}
 *       into the entity's {@code FluidType} height map (max with whatever world fluid already
 *       contributed) and let that helper apply the current push. Populating the height map is what
 *       flips {@code isInFluidType}/{@code wasTouchingWater} true, so all downstream water movement
 *       follows for free.</li>
 *   <li>{@code updateFluidOnEyes()} — if the entity's eye isn't already in a world fluid but sits
 *       inside an assembly fluid, we set the eye fluid type so fog, breath, and vision behave.</li>
 * </ul>
 */
@Mixin(Entity.class)
public abstract class AssemblyEntityFluidMixin {

    @Shadow
    protected boolean wasEyeInWater;

    @Shadow
    private FluidType forgeFluidTypeOnEyes;

    @Shadow
    protected abstract void setFluidTypeHeight(FluidType type, double height);

    @Shadow
    public abstract double getFluidTypeHeight(FluidType type);

    @Shadow
    public abstract boolean isEyeInFluid(net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> tag);

    @Inject(method = "updateFluidHeightAndDoFluidPushing()V", at = @At("RETURN"))
    private void assemblylib$assemblyFluidPushing(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        List<AssemblyFluidPhysics.Contribution> contributions = AssemblyFluidPhysics.applyFluidPushing(self);
        for (AssemblyFluidPhysics.Contribution c : contributions) {
            double merged = Math.max(this.getFluidTypeHeight(c.type), c.height);
            this.setFluidTypeHeight(c.type, merged);
        }
    }

    @Inject(method = "updateFluidOnEyes()V", at = @At("RETURN"))
    private void assemblylib$assemblyEyeFluid(CallbackInfo ci) {
        // Only fill in when world-space eye detection found nothing, so real water always wins.
        if (!this.forgeFluidTypeOnEyes.isAir()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        FluidType eye = AssemblyFluidPhysics.eyeFluid(self);
        if (eye != null && !eye.isAir()) {
            this.forgeFluidTypeOnEyes = eye;
            this.wasEyeInWater = this.isEyeInFluid(FluidTags.WATER);
        }
    }
}
