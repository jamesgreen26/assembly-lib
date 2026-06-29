package com.assemblylib.debug.client;

import java.util.function.Consumer;

import com.assemblylib.impl.client.renderer.assembly.AssemblyVisualCore;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.ShaderLightVisual;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import com.assemblylib.debug.blockentity.ServoMotorBlockEntity;

/**
 * Flywheel block-entity visual for a Servo Motor's assembly. The host-agnostic rendering lives in
 * {@link AssemblyVisualCore}; this class is the thin {@link AbstractBlockEntityVisual} shell that
 * Flywheel registers per block-entity type and that forwards the visual lifecycle to the core.
 */
public class ServoMotorVisual extends AbstractBlockEntityVisual<ServoMotorBlockEntity>
	implements DynamicVisual, TickableVisual, ShaderLightVisual {

	private final AssemblyVisualCore core;

	public ServoMotorVisual(VisualizationContext ctx, ServoMotorBlockEntity blockEntity, float partialTick) {
		super(ctx, blockEntity, partialTick);
		core = new AssemblyVisualCore(visualizationContext, blockEntity, this::getVisualPosition, partialTick);
	}

	@Override
	public Plan<DynamicVisual.Context> planFrame() {
		return core.planFrame();
	}

	@Override
	public Plan<TickableVisual.Context> planTick() {
		return core.planTick();
	}

	@Override
	public void setSectionCollector(SectionCollector collector) {
		core.setSectionCollector(collector);
	}

	@Override
	public void updateLight(float partialTick) {
		// Brightness comes from the shader light sections registered by the core; nothing to do.
	}

	@Override
	protected void _delete() {
		core.delete();
	}

	@Override
	public void collectCrumblingInstances(Consumer<Instance> consumer) {
		core.collectCrumbling(consumer);
	}
}
