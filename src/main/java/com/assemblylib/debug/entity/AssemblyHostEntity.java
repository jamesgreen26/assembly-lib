package com.assemblylib.debug.entity;

import javax.annotation.Nullable;

import com.assemblylib.api.AssemblyHost;
import com.assemblylib.debug.block.ModBlocks;
import com.assemblylib.debug.block.ServoMotorHeadBlock;
import com.assemblylib.debug.item.ModItems;
import com.assemblylib.impl.assembly.AssemblyController;
import com.assemblylib.impl.assembly.AssemblyTransform;
import org.joml.Matrix4f;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

public class AssemblyHostEntity extends PathfinderMob implements AssemblyHost {
	private static final String ASSEMBLY_TAG = "HostedAssembly";

	private static final double HOST_CELL_Y_OFFSET = 0.82;

	private final AssemblyController controller = new AssemblyController(this);
	private boolean assemblyRemoved;

	public AssemblyHostEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 12.0)
			.add(Attributes.MOVEMENT_SPEED, 0.08)
			.add(Attributes.FOLLOW_RANGE, 16.0)
			.add(Attributes.STEP_HEIGHT, 0.6);
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(4, new RandomStrollGoal(this, 0.65, 80));
		goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 6.0f));
		goalSelector.addGoal(6, new RandomLookAroundGoal(this));
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide)
			clientTick();
		else
			serverTick();
	}

	@Override
	public void onAddedToLevel() {
		super.onAddedToLevel();
		if (!level().isClientSide && getAssembly() == null)
			initAssembly();
	}

	@Override
	@Nullable
	@SuppressWarnings("deprecation")
	public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
		MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData) {
		SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
		if (!this.level().isClientSide && getAssembly() == null)
			initAssembly();
		return result;
	}

	@Override
	public AssemblyController getAssemblyController() {
		return controller;
	}

	@Override
	public Level assemblyLevel() {
		return level();
	}

	@Override
	public Direction assemblyFacing() {
		return Direction.UP;
	}

	@Override
	public BlockPos assemblyHostBlockPos() {
		return BlockPos.containing(hostCellCorner());
	}

	@Override
	@Nullable
	public AssemblyHost assemblyParentHost() {
		return null;
	}

	@Override
	public Vec3 assemblyAnchor() {
		return hostCellCorner().add(0.0, 1.0, 0.0);
	}

	@Override
	public BlockPos headLocalPos() {
		return BlockPos.ZERO.relative(Direction.DOWN);
	}

	@Override
	public void markAssemblyChanged() {
	}

	@Override
	public BlockState createHeadBlockState() {
		return ModBlocks.SERVO_MOTOR_HEAD.get().defaultBlockState()
			.setValue(ServoMotorHeadBlock.FACING, Direction.UP);
	}

	@Override
	public void destroyAssemblyHost() {
		if (level().isClientSide)
			return;
		level().levelEvent(2001, blockPosition(), Block.getId(Blocks.SLIME_BLOCK.defaultBlockState()));
		discard();
	}

	/** The mob drives the assembly straight off its own body yaw — no separate spin state. */
	@Override
	public Matrix4f getAssemblyTransform(float partialTick) {
		return AssemblyTransform.spinMatrix(assemblyAnchor(), Mth.rotLerp(partialTick, yRotO, getYRot()),
			Direction.Axis.Y);
	}

	@Override
	public Matrix4f getAssemblyTransformNext() {
		// Project the next pose forward by both this tick's body rotation AND its actual translation,
		// so a rider is carried along as the mob walks (not just turned as it spins).
		float next = getYRot() + Mth.wrapDegrees(getYRot() - yRotO);
		Vec3 motion = position().subtract(xOld, yOld, zOld);
		return AssemblyTransform.spinMatrix(assemblyAnchor().add(motion), next, Direction.Axis.Y);
	}

	@Override
	public AABB getBoundingBoxForCulling() {
		return getBoundingBox().minmax(controller.getRenderBoundingBox());
	}

	@Override
	public void remove(Entity.@NotNull RemovalReason reason) {
		if (reason.shouldDestroy() && isAddedToLevel())
			dropAssemblyOnce();
		super.remove(reason);
	}

	@Override
	public void onClientRemoval() {
		controller.onClientUnload();
		super.onClientRemoval();
	}

	@Override
	public void onRemovedFromLevel() {
		controller.onClientUnload();
		super.onRemovedFromLevel();
	}

	@Override
	public void addAdditionalSaveData(@NotNull CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		CompoundTag assembly = new CompoundTag();
		controller.writeState(assembly, registryAccess());
		tag.put(ASSEMBLY_TAG, assembly);
	}

	@Override
	public void readAdditionalSaveData(@NotNull CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		// Server-side disk load only; clients receive the assembly via AssemblySyncS2CPacket when they
		// start tracking this entity (and on every subsequent edit).
		if (tag.contains(ASSEMBLY_TAG))
			controller.readState(tag.getCompound(ASSEMBLY_TAG), registryAccess());
	}

	@Override
	public @NotNull ItemStack getPickResult() {
		return new ItemStack(ModItems.ASSEMBLY_HOST_SPAWN_EGG.get());
	}

	private Vec3 hostCellCorner() {
		return position().add(-0.5, HOST_CELL_Y_OFFSET, -0.5);
	}

	private void dropAssemblyOnce() {
		if (assemblyRemoved || level().isClientSide)
			return;
		assemblyRemoved = true;
		onHostRemoved();
	}
}
