package com.assemblylib.impl.client.renderer.assembly;

import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import dev.engine_room.flywheel.api.visualization.VisualizationLevel;
import com.assemblylib.impl.assembly.Assembly;
import com.assemblylib.api.AssemblyHost;
import com.assemblylib.impl.assembly.AssemblyHostLevel;
import com.assemblylib.impl.assembly.AssemblySimLevel;
import com.assemblylib.impl.assembly.AssemblyTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side host {@link Level} for a assembly's live render block entities. Extends
 * {@link AssemblySimLevel} (which already overlays the assembly's blocks at their LOCAL
 * positions) and additionally:
 *
 * <ul>
 *   <li>returns the reconstructed block entity at a LOCAL position from {@link #getBlockEntity}
 *       (the base returns {@code null}), so neighbour-aware renderers/tickers — double chests,
 *       conduit frames — resolve against the assembly's own block entities;</li>
 *   <li>routes particle spawns through the assembly transform so smoke/flame appear on the
 *       moving structure instead of at the local origin near world 0,0,0.</li>
 * </ul>
 *
 * <p>Owned by {@link AssemblyRenderState}; the {@code blockEntities} map and the transform
 * supplier are the render state's live ones. The wrapped real client level (via
 * {@link #getLevel()}) is where transformed particles are actually emitted. This level reports
 * {@code isClientSide == true} (it copies the wrapped client level's flag), so block tickers
 * resolved against it are the client variants.
 *
 * <p>Implements Flywheel's {@link VisualizationLevel} marker so its hosted block entities are
 * reported as visualizable — otherwise Flywheel only considers {@code Minecraft#level} supported and
 * would disable the Flywheel visual path / {@code skipVanillaRender} for assembly block entities.
 */
public class AssemblyRenderLevel extends AssemblySimLevel implements VisualizationLevel, AssemblyHostLevel {

	private final Map<BlockPos, BlockEntity> blockEntities;
	@Nullable
	private final Supplier<AssemblyTransform> transform;
	/** The host whose assembly this level hosts, so a nested host can compose its transform. */
	@Nullable
	private final AssemblyHost host;

	public AssemblyRenderLevel(Level level, Assembly assembly,
		Map<BlockPos, BlockEntity> blockEntities, @Nullable Supplier<AssemblyTransform> transform,
		@Nullable AssemblyHost host) {
		super(level, assembly);
		this.blockEntities = blockEntities;
		this.transform = transform;
		this.host = host;
	}

	@Nullable
	@Override
	public AssemblyHost getAssemblyHost() {
		return host;
	}

	@Nullable
	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return blockEntities.get(pos);
	}

	@Override
	public void addParticle(ParticleOptions options, double x, double y, double z, double xs, double ys, double zs) {
		forwardParticle(options, x, y, z, xs, ys, zs);
	}

	@Override
	public void addParticle(ParticleOptions options, boolean force, double x, double y, double z, double xs, double ys,
		double zs) {
		forwardParticle(options, x, y, z, xs, ys, zs);
	}

	@Override
	public void addAlwaysVisibleParticle(ParticleOptions options, double x, double y, double z, double xs, double ys,
		double zs) {
		forwardParticle(options, x, y, z, xs, ys, zs);
	}

	@Override
	public void addAlwaysVisibleParticle(ParticleOptions options, boolean ignoreRange, double x, double y, double z,
		double xs, double ys, double zs) {
		forwardParticle(options, x, y, z, xs, ys, zs);
	}

	/** Emit a particle (given in assembly-local space) onto the real level at its rotated world pose. */
	private void forwardParticle(ParticleOptions options, double x, double y, double z, double xs, double ys,
		double zs) {
		if (transform == null) {
			getLevel().addParticle(options, x, y, z, xs, ys, zs);
			return;
		}
		AssemblyTransform t = transform.get();
		Vec3 world = t.localToWorld(new Vec3(x, y, z));
		Vec3 vel = t.localDirToWorld(new Vec3(xs, ys, zs));
		getLevel().addParticle(options, world.x, world.y, world.z, vel.x, vel.y, vel.z);
	}
}
