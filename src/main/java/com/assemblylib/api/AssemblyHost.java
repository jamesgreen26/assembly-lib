package com.assemblylib.api;

import javax.annotation.Nullable;

import com.assemblylib.impl.assembly.*;
import com.assemblylib.impl.client.renderer.assembly.AssemblyRenderState;
import org.joml.Matrix4f;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The thing that owns and drives an {@link Assembly}: a moving block structure that spins about an
 * axis. Implemented today by {@code ServoMotorBlockEntity}, but deliberately not tied to a
 * BlockEntity — an Entity (or anything else) can host an assembly by implementing this interface and
 * owning an {@link AssemblyController}, which holds all the shared state and behaviour.
 *
 * <p>The interface splits into <b>host-native</b> methods (the environment the controller needs:
 * level, facing, position, dirty/sync hooks, the head seed) and <b>delegating defaults</b> that
 * forward to {@link #assemblyController()}. Every collaborator — {@link AssemblyTransform},
 * {@link AssemblyPath}, the sim/render levels, the networking packets, and the client renderer /
 * interaction layer — targets this interface rather than any concrete host type.
 *
 * <p>Dist note: {@link #getRenderState()} returns a client-only type, exactly as the block entity
 * exposed it before; it is only ever touched on the client (guarded by {@code isClientSide} in the
 * controller), so the server never loads that class.
 */
public interface AssemblyHost {

	// region host-native (environment / identity)

	/** The level this host lives in — the real world for a root host, or a parent sim/render wrapper when nested. */
	Level assemblyLevel();

	/** The direction the host faces; drives the head cell, placement, the head render model and the anchor. */
	Direction assemblyFacing();

	/**
	 * The host's block position: its real world position for a root host, or its cell in the parent's
	 * local space when nested. Used as the assembly anchor base, the sim level's host key and the path root.
	 */
	BlockPos assemblyHostBlockPos();

	/**
	 * The host that hosts this one, when this host is itself a block inside another assembly (its level
	 * is an {@link AssemblyHostLevel}). {@code null} for a host anchored directly in the real world.
	 */
	@Nullable
	AssemblyHost assemblyParentHost();

	/** Mark the host's backing storage dirty for persistence (block entity {@code setChanged} / entity equivalent). */
	void markAssemblyChanged();

	/** The block state seeding the assembly's head cell (for the servo motor: the Servo Motor Head facing its direction). */
	BlockState createHeadBlockState();

	/**
	 * The host-owned leaf transform mapping assembly-local space into the host's reference frame (the
	 * real world for a root host, the parent's local space when nested), at the given partial tick
	 * ({@code 1.0} = the current server pose). The host fully decides its own motion here — spin about
	 * an axis, translate, tilt, or stay put — so the library no longer bakes in any rotation policy.
	 * {@link AssemblyTransform#spinMatrix} builds the common "spin about an axis at an anchor" pose.
	 */
	Matrix4f assemblyTransform(float partialTick);

	/**
	 * The leaf transform the host will hold after advancing one more tick by its own intended motion,
	 * used to carry riders and release detaching blocks with the platform velocity. Defaults to the
	 * current pose (no carried motion); a moving host overrides it to project its spin/translation
	 * forward one tick.
	 */
	default Matrix4f assemblyTransformNext() {
		return assemblyTransform(1.0f);
	}

	/** Tear the whole host down (head broken): for the servo motor, play the break effect and remove the block. */
	void breakWholeHost(ServerPlayer player);

	/** The controller holding this host's assembly state and logic. */
	AssemblyController assemblyController();

	// endregion

	// region derived defaults

	/** World-space anchor of the leaf transform: the lower corner of the cell in front of the host. */
	default Vec3 assemblyAnchor() {
		return Vec3.atLowerCornerOf(assemblyHostBlockPos().relative(assemblyFacing()));
	}

	/** This host's cell in its parent's local space (for nesting / {@link AssemblyPath}); equals the host block pos. */
	default BlockPos assemblyCellInParent() {
		return assemblyHostBlockPos();
	}

	/** Local position of the head: the host's own cell, on the rotation axis so it spins in place. */
	default BlockPos headLocalPos() {
		return BlockPos.ZERO.relative(assemblyFacing().getOpposite());
	}

	@Nullable
	default Assembly getAssembly() {
		return assemblyController().getAssembly();
	}

	@Nullable
	default AssemblyRenderState getRenderState() {
		return assemblyController().getRenderState();
	}

	default int getAssemblyBlockCount() {
		return assemblyController().getAssemblyBlockCount();
	}

	@Nullable
	default AssemblyHost getNestedHost(BlockPos local) {
		return assemblyController().getNestedHost(local);
	}

	default AABB getRenderBoundingBox() {
		return assemblyController().getRenderBoundingBox();
	}

	// endregion

	// region lifecycle / interaction hooks

	default void serverTick() {
		assemblyController().serverTick();
	}

	default void clientTick() {
		assemblyController().clientTick();
	}

	default void initAssembly() {
		assemblyController().initAssembly();
	}

	default void onHostRemoved() {
		assemblyController().onHostRemoved();
	}

	// endregion
}
