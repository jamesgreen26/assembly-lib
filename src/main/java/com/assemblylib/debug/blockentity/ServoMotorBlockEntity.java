package com.assemblylib.debug.blockentity;

import javax.annotation.Nullable;

import com.assemblylib.impl.assembly.AssemblyController;
import com.assemblylib.api.AssemblyHost;
import com.assemblylib.impl.assembly.AssemblyHostLevel;
import com.assemblylib.debug.block.ModBlocks;
import com.assemblylib.debug.block.ServoMotorHeadBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/**
 * A block-entity {@link AssemblyHost}: hosts a assembly directly (no separate entity). On toggle it
 * assembles the connected blocks in front of it, spins them around the block's facing axis, and
 * writes them back to the world on disassembly. Behaves like Create's Mechanical Bearing, but the
 * structure lives in this BlockEntity.
 *
 * <p>All the assembly state and behaviour lives in the owned {@link AssemblyController}; this class is
 * a thin adapter supplying the block-entity environment (level, facing, position, dirty/sync hooks)
 * and forwarding ticking and persistence.
 */
public class ServoMotorBlockEntity extends BlockEntity implements AssemblyHost {

	private final AssemblyController controller = new AssemblyController(this);

	public ServoMotorBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.SERVO_MOTOR.get(), pos, state);
	}

	@Override
	public AssemblyController assemblyController() {
		return controller;
	}

	// region AssemblyHost: environment / identity

	@Override
	public Level assemblyLevel() {
		return level;
	}

	@Override
	public Direction assemblyFacing() {
		return getFacing();
	}

	@Override
	public BlockPos assemblyHostBlockPos() {
		return worldPosition;
	}

	/**
	 * The host that hosts this one, when this motor is itself a block inside another assembly (its
	 * level is an {@link AssemblyHostLevel} — the server sim level or the client render level).
	 */
	@Override
	@Nullable
	public AssemblyHost assemblyParentHost() {
		return level instanceof AssemblyHostLevel host ? host.getAssemblyHost() : null;
	}

	@Override
	public void markAssemblyChanged() {
		setChanged();
	}

	@Override
	public void syncAssemblyToClients() {
		if (level != null && !level.isClientSide)
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
	}

	@Override
	public BlockState createHeadBlockState() {
		return ModBlocks.SERVO_MOTOR_HEAD.get().defaultBlockState()
			.setValue(ServoMotorHeadBlock.FACING, getFacing());
	}

	@Override
	public boolean isAssemblyPowered() {
		return level != null && level.hasNeighborSignal(worldPosition);
	}

	@Override
	public void breakWholeHost(ServerPlayer player) {
		if (level == null || level.isClientSide)
			return;
		level.levelEvent(2001, worldPosition, Block.getId(getBlockState()));
		level.removeBlock(worldPosition, false);
	}

	// endregion

	// region block-entity helpers

	public Direction getFacing() {
		BlockState state = getBlockState();
		return state.hasProperty(BlockStateProperties.FACING) ? state.getValue(BlockStateProperties.FACING)
			: state.hasProperty(HorizontalDirectionalBlock.FACING)
				? state.getValue(HorizontalDirectionalBlock.FACING)
				: Direction.NORTH;
	}

	/** Block-entity-typed view of {@link #assemblyParentHost()}, for callers that need the concrete motor. */
	@Nullable
	public ServoMotorBlockEntity hostMotor() {
		return assemblyParentHost() instanceof ServoMotorBlockEntity motor ? motor : null;
	}

	/** Block-entity-typed view of {@link #getNestedHost(BlockPos)}, for callers that need the concrete motor. */
	@Nullable
	public ServoMotorBlockEntity getNestedMotor(BlockPos local) {
		return getNestedHost(local) instanceof ServoMotorBlockEntity motor ? motor : null;
	}

	// endregion

	// region sync + persistence

	@Override
	public void setRemoved() {
		super.setRemoved();
		controller.onClientUnload();
	}

	@Override
	public AABB getRenderBoundingBox() {
		return controller.getRenderBoundingBox();
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		controller.writeState(tag, registries);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		controller.readState(tag, registries);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		CompoundTag tag = super.getUpdateTag(registries);
		controller.writeState(tag, registries);
		return tag;
	}

	@Nullable
	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
		if (pkt != null && pkt.getTag() != null)
			handleUpdateTag(pkt.getTag(), registries);
	}

	@Override
	public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
		controller.readState(tag, registries);
	}

	// endregion
}
