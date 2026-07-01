package com.assemblylib.api;

import javax.annotation.Nullable;

import com.assemblylib.impl.assembly.*;
import com.assemblylib.impl.client.renderer.assembly.AssemblyRenderState;
import org.joml.Matrix4f;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
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
 * forward to {@link #getAssemblyController()}. Every collaborator — {@link AssemblyTransform},
 * {@link AssemblyPath}, the sim/render levels, the networking packets, and the client renderer /
 * interaction layer — targets this interface rather than any concrete host type.
 *
 * <p>Dist note: {@link #getRenderState()} returns a client-only type, exactly as the block entity
 * exposed it before; it is only ever touched on the client (guarded by {@code isClientSide} in the
 * controller), so the server never loads that class.
 */
public interface AssemblyHost {

	// region host-native (environment / identity)

	/** The level this host lives in — the real world the assembly is anchored in. */
	Level assemblyLevel();

	/** The direction the host faces; drives the head cell, placement, the head render model and the anchor. */
	Direction assemblyFacing();

	/**
	 * The host's real-world block position. Used as the assembly anchor base, the sim level's host key
	 * and the path root.
	 */
	BlockPos assemblyHostBlockPos();

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
	Matrix4f getAssemblyTransform(float partialTick);

	/**
	 * The leaf transform the host will hold after advancing one more tick by its own intended motion,
	 * used to carry riders and release detaching blocks with the platform velocity. Defaults to the
	 * current pose (no carried motion); a moving host overrides it to project its spin/translation
	 * forward one tick.
	 */
	Matrix4f getAssemblyTransformNext();

	void destroyAssemblyHost();

	/** The controller holding this host's assembly state and logic. */
	AssemblyController getAssemblyController();

	// endregion

	// region derived defaults

	/** World-space anchor of the leaf transform: the lower corner of the cell in front of the host. */
	default Vec3 assemblyAnchor() {
		return Vec3.atLowerCornerOf(assemblyHostBlockPos().relative(assemblyFacing()));
	}

	/** Local position of the head: the host's own cell, on the rotation axis so it spins in place. */
	default BlockPos headLocalPos() {
		return BlockPos.ZERO.relative(assemblyFacing().getOpposite());
	}

	@Nullable
	default Assembly getAssembly() {
		return getAssemblyController().getAssembly();
	}

	@Nullable
	default AssemblyRenderState getRenderState() {
		return getAssemblyController().getRenderState();
	}

	default int getAssemblyBlockCount() {
		return getAssemblyController().getAssemblyBlockCount();
	}

	default AABB getRenderBoundingBox() {
		return getAssemblyController().getRenderBoundingBox();
	}

	// endregion

	// region lifecycle / interaction hooks

	default void serverTick() {
		getAssemblyController().serverTick();
	}

	default void clientTick() {
		getAssemblyController().clientTick();
	}

	default void initAssembly() {
		getAssemblyController().initAssembly();
	}

	default void onHostRemoved() {
		getAssemblyController().onHostRemoved();
	}

	/**
	 * Called when the head block — the host's own cell in the assembly — is mined out of the structure.
	 * The host is then torn down automatically ({@link #destroyAssemblyHost()}); this hook only lets the
	 * host react and choose, via the returned {@link HeadBreakResult}, whether the head cell also breaks
	 * like a normal block (spawn its particles/sound, drop its own loot). Defaults to
	 * {@link HeadBreakResult#NONE} — nothing beyond the automatic host teardown.
	 */
	default HeadBreakResult onHeadBlockDestroyed() {
		return HeadBreakResult.NONE;
	}

	/**
	 * What the assembly should do to the head cell after {@link #onHeadBlockDestroyed()} runs: whether to
	 * spawn its break particles/sound, and whether to drop the head block's own loot.
	 */
	record HeadBreakResult(boolean spawnBreakEffects, boolean dropHeadItem) {

		/** Neither — the host handled the destruction itself (the default). */
		public static final HeadBreakResult NONE = new HeadBreakResult(false, false);

		/** Break particles and sound only, no drop. */
		public static final HeadBreakResult EFFECTS_ONLY = new HeadBreakResult(true, false);

		/** Break like a normal block: particles, sound, and the head's loot. */
		public static final HeadBreakResult EFFECTS_AND_DROP = new HeadBreakResult(true, true);
	}

	// endregion
}
