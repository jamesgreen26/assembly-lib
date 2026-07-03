package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.assemblylib.impl.vchunk.collision.drag.AssemblyDraggingInformation;
import com.assemblylib.impl.vchunk.collision.drag.AssemblyDraggingProvider;

import net.minecraft.world.entity.Entity;

/**
 * Attaches an {@link AssemblyDraggingInformation} to every vanilla {@code Entity}, so the entity <->
 * assembly collision/dragging system (see {@link AssemblyEntityMoveMixin}) can remember which
 * assembly each entity is standing on. Ported from VEL's {@code MixinEntityDragging}.
 */
@Mixin(Entity.class)
public abstract class AssemblyEntityDraggingMixin implements AssemblyDraggingProvider {

    @Unique
    private final AssemblyDraggingInformation assemblylib$draggingInfo = new AssemblyDraggingInformation();

    @Override
    public AssemblyDraggingInformation assemblylib$getDraggingInfo() {
        return assemblylib$draggingInfo;
    }
}
