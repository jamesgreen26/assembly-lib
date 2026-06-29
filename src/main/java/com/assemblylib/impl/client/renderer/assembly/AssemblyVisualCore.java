package com.assemblylib.impl.client.renderer.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.ShaderLightVisual;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.ModelUtil;
import dev.engine_room.flywheel.lib.model.baked.BlockModelBuilder;
import dev.engine_room.flywheel.lib.task.PlanMap;
import dev.engine_room.flywheel.lib.task.RunnablePlan;
import com.assemblylib.impl.assembly.Assembly;
import com.assemblylib.api.AssemblyHost;
import com.assemblylib.impl.assembly.AssemblyTransform;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Host-agnostic GPU-instanced rendering of an {@link AssemblyHost}'s assembly while Flywheel is
 * active, modelled on Create's {@code ContraptionVisual}. The structure model and the child
 * block-entity visuals live on a {@link VisualEmbedding} that carries the assembly transform, and
 * lighting is provided by registering the assembly's world light sections (the block materials sample
 * those sections by world position on the GPU).
 *
 * <p>Extracted from the servo motor's block-entity visual so any Flywheel visual (block entity or
 * entity) can drive an assembly: the concrete visual creates one of these, passing its
 * {@link VisualizationContext} and a partial-tick-aware supplier for its render-origin-relative visual position, and
 * forwards the visual lifecycle ({@link #planFrame}/{@link #planTick}/{@link #delete}/
 * {@link #setSectionCollector}/{@link #collectCrumbling}).
 */
public class AssemblyVisualCore {

	private static final int LIGHT_PADDING = 1;

	private final AssemblyHost host;
	private final Function<Float, Vec3> visualPosition;

	private final VisualEmbedding embedding;
	private final List<BlockEntityVisual<?>> children = new ArrayList<>();
	private final PlanMap<DynamicVisual, DynamicVisual.Context> dynamicVisuals = new PlanMap<>();
	private final PlanMap<TickableVisual, TickableVisual.Context> tickableVisuals = new PlanMap<>();

	@org.jetbrains.annotations.Nullable
	private TransformedInstance structure;
	@org.jetbrains.annotations.Nullable
	private Assembly builtAssembly;
	/** Render-state structure revision the children were built for, so they survive data-only syncs. */
	private long builtRevision = -1;

	@org.jetbrains.annotations.Nullable
	private ShaderLightVisual.SectionCollector sectionCollector;
	private long minSection = Long.MIN_VALUE;
	private long maxSection = Long.MIN_VALUE;

	public AssemblyVisualCore(VisualizationContext ctx, AssemblyHost host, Function<Float, Vec3> visualPosition,
		float partialTick) {
		this.host = host;
		this.visualPosition = visualPosition;
		this.embedding = ctx.createEmbedding(Vec3i.ZERO);
		setEmbeddingTransform(partialTick);
		setupStructure();
		setupChildren(partialTick);
	}

	// region setup

	private void setupStructure() {
		builtAssembly = host.getAssembly();
		if (structure != null) {
			structure.delete();
			structure = null;
		}
		if (builtAssembly == null || builtAssembly.isEmpty())
			return;

		Model model = new BlockModelBuilder(
			new AssemblyRenderWorld(host.assemblyLevel(), builtAssembly),
			builtAssembly.getBlocks().keySet())
			// Assembly blocks are lit per-section like normal chunks, not like an entity.
			.materialFunc((renderType, shaded, ao) -> {
				Material material = ModelUtil.getMaterial(renderType, shaded, ao);
				if (material != null && material.cardinalLightingMode() == CardinalLightingMode.ENTITY)
					return SimpleMaterial.builderOf(material).cardinalLightingMode(CardinalLightingMode.CHUNK).build();
				return material;
			})
			.build();

		structure = embedding.instancerProvider()
			.instancer(InstanceTypes.TRANSFORMED, model)
			.createInstance();
		structure.setChanged();
	}

	@SuppressWarnings("unchecked")
	private void setupChildren(float partialTick) {
		children.forEach(BlockEntityVisual::delete);
		children.clear();
		dynamicVisuals.clear();
		tickableVisuals.clear();

		AssemblyRenderState renderState = host.getRenderState();
		builtRevision = renderState == null ? -1 : renderState.getStructureRevision();
		if (renderState == null)
			return;

		// One Flywheel child visual per captured block entity that has a visualizer
		// (matches Create). The BER independently decides whether to also draw it.
		for (BlockEntity be : renderState.getBlockEntities()) {
			BlockEntityVisualizer<? super BlockEntity> visualizer =
				(BlockEntityVisualizer<? super BlockEntity>) VisualizerRegistry.getVisualizer(be.getType());
			if (visualizer == null)
				continue;
			BlockEntityVisual<? super BlockEntity> visual = visualizer.createVisual(embedding, be, partialTick);
			children.add(visual);
			if (visual instanceof DynamicVisual dynamic)
				dynamicVisuals.add(dynamic, dynamic.planFrame());
			if (visual instanceof TickableVisual tickable)
				tickableVisuals.add(tickable, tickable.planTick());
		}
	}

	// endregion

	// region per-frame / per-tick plans

	public Plan<DynamicVisual.Context> planFrame() {
		// beginFrame must run before the children so structure/children changes are picked up.
		return RunnablePlan.<DynamicVisual.Context>of(this::beginFrame).then(dynamicVisuals);
	}

	public Plan<TickableVisual.Context> planTick() {
		return tickableVisuals;
	}

	private void beginFrame(DynamicVisual.Context ctx) {
		float partialTick = ctx.partialTick();
		setEmbeddingTransform(partialTick);
		checkAndUpdateLightSections();
		// The structure model (block geometry) is rebuilt whenever the assembly is re-synced.
		if (host.getAssembly() != builtAssembly)
			setupStructure();
		// Child block-entity visuals, however, are rebuilt only on a real structural change, so their
		// preserved (live, ticking) block-entity instances keep animating across data-only syncs.
		AssemblyRenderState renderState = host.getRenderState();
		long revision = renderState == null ? -1 : renderState.getStructureRevision();
		if (revision != builtRevision)
			setupChildren(partialTick);
	}

	private void setEmbeddingTransform(float partialTick) {
		float angle = host.getInterpolatedAngle(partialTick);
		Direction facing = host.assemblyFacing();
		// A nested host's embedding is a child of its parent's embedding, so its frame is the parent
		// assembly's LOCAL space: translate by the raw parent-local cell (no render-origin offset).
		// A root host's embedding sits in the section frame, so use the render-origin-relative position.
		Vec3 vp = host.assemblyLevel() instanceof AssemblyRenderLevel
			? Vec3.atLowerCornerOf(host.assemblyHostBlockPos())
			: visualPosition.apply(partialTick);

		Matrix4f pose = new Matrix4f();
		pose.translate((float) vp.x + facing.getStepX(), (float) vp.y + facing.getStepY(),
			(float) vp.z + facing.getStepZ());
		AssemblyTransform.pivotRotate(pose, host.getRotationAxis(), angle);

		embedding.transforms(pose, pose.normal(new Matrix3f()));
	}

	// endregion

	// region shader lighting

	public void setSectionCollector(ShaderLightVisual.SectionCollector collector) {
		this.sectionCollector = collector;
		minSection = Long.MIN_VALUE;
		maxSection = Long.MIN_VALUE;
		checkAndUpdateLightSections();
	}

	private void checkAndUpdateLightSections() {
		if (sectionCollector == null)
			return;

		AABB bounds = host.getRenderBoundingBox();
		int minX = SectionPos.blockToSectionCoord(Mth.floor(bounds.minX) - LIGHT_PADDING);
		int minY = SectionPos.blockToSectionCoord(Mth.floor(bounds.minY) - LIGHT_PADDING);
		int minZ = SectionPos.blockToSectionCoord(Mth.floor(bounds.minZ) - LIGHT_PADDING);
		int maxX = SectionPos.blockToSectionCoord(Mth.ceil(bounds.maxX) + LIGHT_PADDING);
		int maxY = SectionPos.blockToSectionCoord(Mth.ceil(bounds.maxY) + LIGHT_PADDING);
		int maxZ = SectionPos.blockToSectionCoord(Mth.ceil(bounds.maxZ) + LIGHT_PADDING);

		long min = SectionPos.asLong(minX, minY, minZ);
		long max = SectionPos.asLong(maxX, maxY, maxZ);
		if (min == minSection && max == maxSection)
			return;
		minSection = min;
		maxSection = max;

		LongSet sections = new LongArraySet();
		for (int x = minX; x <= maxX; x++)
			for (int y = minY; y <= maxY; y++)
				for (int z = minZ; z <= maxZ; z++)
					sections.add(SectionPos.asLong(x, y, z));
		sectionCollector.sections(sections);
	}

	// endregion

	public void delete() {
		if (structure != null) {
			structure.delete();
			structure = null;
		}
		children.forEach(BlockEntityVisual::delete);
		children.clear();
		dynamicVisuals.clear();
		tickableVisuals.clear();
		embedding.delete();
	}

	public void collectCrumbling(Consumer<Instance> consumer) {
		if (structure != null)
			consumer.accept(structure);
	}
}
