package com.assemblylib.impl.client.renderer.assembly;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import dev.engine_room.flywheel.lib.task.PlanMap;
import dev.engine_room.flywheel.lib.task.RunnablePlan;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;

import com.assemblylib.impl.assembly.AssemblyRotatedEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Flywheel entity visual for a {@link FallingBlockEntity} detached from an assembly. Vanilla's
 * {@link net.minecraft.client.renderer.entity.FallingBlockRenderer} (plus our
 * {@link com.assemblylib.impl.mixin.FallingBlockRendererMixin}) already draws the block model and any
 * <em>vanilla</em> block-entity renderer, but it can't drive a Flywheel block-entity visualizer — so a
 * falling block whose block entity is Flywheel-instanced (and skips its vanilla renderer) would show
 * nothing. This visual fills that gap: it embeds the block entity's Flywheel visual at the falling
 * block's interpolated, assembly-rotated pose.
 *
 * <p>Registered {@code neverSkipVanillaRender}, so the vanilla path keeps drawing the block model and
 * the mixin still handles block entities without a Flywheel visualizer. A falling block that carries no
 * block entity (sand, gravel, …) builds no embedding and does nothing here.
 */
public class FallingBlockVisual extends AbstractEntityVisual<FallingBlockEntity>
	implements DynamicVisual, TickableVisual {

	@Nullable
	private VisualEmbedding embedding;
	@Nullable
	private BlockEntityVisual<?> child;
	private final PlanMap<DynamicVisual, DynamicVisual.Context> dynamicVisuals = new PlanMap<>();
	private final PlanMap<TickableVisual, TickableVisual.Context> tickableVisuals = new PlanMap<>();

	public FallingBlockVisual(VisualizationContext ctx, FallingBlockEntity entity, float partialTick) {
		super(ctx, entity, partialTick);
		setup(partialTick);
	}

	@SuppressWarnings("unchecked")
	private void setup(float partialTick) {
		BlockState state = entity.getBlockState();
		if (!state.hasBlockEntity() || !(state.getBlock() instanceof EntityBlock entityBlock))
			return;
		BlockPos pos = entity.blockPosition();
		BlockEntity be = entityBlock.newBlockEntity(pos, state);
		if (be == null)
			return;
		BlockEntityVisualizer<? super BlockEntity> visualizer =
			(BlockEntityVisualizer<? super BlockEntity>) VisualizerRegistry.getVisualizer(be.getType());
		if (visualizer == null)
			return; // No Flywheel visual for this block entity — the vanilla BER path (mixin) handles it.

		be.setLevel(entity.level());
		be.setBlockState(state);
		CompoundTag tag = ((AssemblyRotatedEntity) entity).zps$getBlockEntityTag();
		if (tag != null && !tag.isEmpty())
			be.handleUpdateTag(tag, entity.level().registryAccess());

		// Embedding render-origin = the block entity's own cell, so its visual sits at the embedding
		// origin (its light samples the real level there); the per-frame transform carries the fall.
		embedding = visualizationContext.createEmbedding(pos);
		setEmbeddingTransform(partialTick);
		BlockEntityVisual<? super BlockEntity> visual = visualizer.createVisual(embedding, be, partialTick);
		child = visual;
		if (visual instanceof DynamicVisual dynamic)
			dynamicVisuals.add(dynamic, dynamic.planFrame());
		if (visual instanceof TickableVisual tickable)
			tickableVisuals.add(tickable, tickable.planTick());
	}

	@Override
	public Plan<DynamicVisual.Context> planFrame() {
		// Update the embedding pose before the child draws so it tracks the falling block this frame.
		return RunnablePlan.<DynamicVisual.Context>of(ctx -> setEmbeddingTransform(ctx.partialTick()))
			.then(dynamicVisuals);
	}

	@Override
	public Plan<TickableVisual.Context> planTick() {
		return tickableVisuals;
	}

	private void setEmbeddingTransform(float partialTick) {
		if (embedding == null)
			return;
		var origin = renderOrigin();
		double x = Mth.lerp(partialTick, entity.xOld, entity.getX()) - origin.getX();
		double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) - origin.getY();
		double z = Mth.lerp(partialTick, entity.zOld, entity.getZ()) - origin.getZ();
		Quaternionf rotation = ((AssemblyRotatedEntity) entity).zps$getAssemblyRotation();

		// Mirror FallingBlockRendererMixin: pivot the block about its own centre by the inherited
		// assembly rotation, then shift the [0,1] block model so it's centred on the entity origin.
		Matrix4f pose = new Matrix4f();
		pose.translate((float) x, (float) y, (float) z);
		pose.translate(0.0f, 0.5f, 0.0f);
		pose.rotate(rotation);
		pose.translate(-0.5f, -0.5f, -0.5f);
		embedding.transforms(pose, pose.normal(new Matrix3f()));
	}

	@Override
	protected void _delete() {
		if (child != null) {
			child.delete();
			child = null;
		}
		dynamicVisuals.clear();
		tickableVisuals.clear();
		if (embedding != null) {
			embedding.delete();
			embedding = null;
		}
	}
}
