package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.joml.Quaternionf;

import com.assemblylib.impl.assembly.AssemblyRotatedEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;

/**
 * Adds a synced + persisted assembly rotation (a full {@link Quaternionf}) to
 * {@link FallingBlockEntity}, so a block detached from a rotating assembly keeps the
 * structure's (possibly nested/composed) orientation while it falls. See
 * {@link AssemblyRotatedEntity}.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityRotationMixin extends Entity implements AssemblyRotatedEntity {

	@Unique
	private static final EntityDataAccessor<Quaternionf> ZPS_ROTATION =
		SynchedEntityData.defineId(FallingBlockEntity.class, EntityDataSerializers.QUATERNION);

	/** Block-entity update tag of a detached block, synced so the client can render it while it falls. */
	@Unique
	private static final EntityDataAccessor<CompoundTag> ZPS_BLOCK_ENTITY_TAG =
		SynchedEntityData.defineId(FallingBlockEntity.class, EntityDataSerializers.COMPOUND_TAG);

	private FallingBlockEntityRotationMixin(EntityType<?> type, Level level) {
		super(type, level);
	}

	@Inject(method = "defineSynchedData", at = @At("TAIL"))
	private void zps$defineRotation(SynchedEntityData.Builder builder, CallbackInfo ci) {
		builder.define(ZPS_ROTATION, new Quaternionf());
		builder.define(ZPS_BLOCK_ENTITY_TAG, new CompoundTag());
	}

	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	private void zps$saveRotation(CompoundTag tag, CallbackInfo ci) {
		Quaternionf q = this.entityData.get(ZPS_ROTATION);
		tag.putFloat("ZpsRotationX", q.x);
		tag.putFloat("ZpsRotationY", q.y);
		tag.putFloat("ZpsRotationZ", q.z);
		tag.putFloat("ZpsRotationW", q.w);
		CompoundTag beTag = this.entityData.get(ZPS_BLOCK_ENTITY_TAG);
		if (!beTag.isEmpty())
			tag.put("ZpsBlockEntityTag", beTag);
	}

	@Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
	private void zps$loadRotation(CompoundTag tag, CallbackInfo ci) {
		if (tag.contains("ZpsRotationW"))
			this.entityData.set(ZPS_ROTATION, new Quaternionf(
				tag.getFloat("ZpsRotationX"), tag.getFloat("ZpsRotationY"),
				tag.getFloat("ZpsRotationZ"), tag.getFloat("ZpsRotationW")));
		if (tag.contains("ZpsBlockEntityTag"))
			this.entityData.set(ZPS_BLOCK_ENTITY_TAG, tag.getCompound("ZpsBlockEntityTag"));
	}

	@Override
	public void zps$setAssemblyRotation(Quaternionf rotation) {
		this.entityData.set(ZPS_ROTATION, new Quaternionf(rotation));
	}

	@Override
	public Quaternionf zps$getAssemblyRotation() {
		return this.entityData.get(ZPS_ROTATION);
	}

	@Override
	public void zps$setBlockEntityTag(CompoundTag tag) {
		this.entityData.set(ZPS_BLOCK_ENTITY_TAG, tag == null ? new CompoundTag() : tag);
	}

	@Override
	public CompoundTag zps$getBlockEntityTag() {
		return this.entityData.get(ZPS_BLOCK_ENTITY_TAG);
	}
}
