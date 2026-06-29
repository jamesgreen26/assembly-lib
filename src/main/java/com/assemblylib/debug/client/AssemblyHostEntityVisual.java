package com.assemblylib.debug.client;

import com.assemblylib.debug.entity.AssemblyHostEntity;
import com.assemblylib.impl.client.renderer.assembly.AssemblyVisualCore;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.ShaderLightVisual;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class AssemblyHostEntityVisual extends AbstractEntityVisual<AssemblyHostEntity>
	implements DynamicVisual, TickableVisual, ShaderLightVisual {

	private static final Vec3 ASSEMBLY_VISUAL_OFFSET = new Vec3(-0.5, 0.82, -0.5);

	private final AssemblyVisualCore core;

	public AssemblyHostEntityVisual(VisualizationContext ctx, AssemblyHostEntity entity, float partialTick) {
		super(ctx, entity, partialTick);
		core = new AssemblyVisualCore(visualizationContext, entity, this::getAssemblyVisualPosition, partialTick);
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
	protected void _delete() {
		core.delete();
	}

	private Vec3 getAssemblyVisualPosition(float partialTick) {
		Vec3i origin = renderOrigin();
		return new Vec3(
			Mth.lerp(partialTick, entity.xOld, entity.getX()) - origin.getX(),
			Mth.lerp(partialTick, entity.yOld, entity.getY()) - origin.getY(),
			Mth.lerp(partialTick, entity.zOld, entity.getZ()) - origin.getZ())
			.add(ASSEMBLY_VISUAL_OFFSET);
	}
}
