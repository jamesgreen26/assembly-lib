package com.assemblylib.impl.assembly;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.assemblylib.api.AssemblyHost;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.Vec3;

/**
 * Identifies an {@link AssemblyHost} by its place in the assembly hierarchy: a {@link AssemblyRootId}
 * naming the root host, followed by the chain of assembly-LOCAL cells descending through each nesting
 * level to the target host. A root host's path has an empty {@code nestedCells} list.
 *
 * <p>Interaction packets carry this instead of a single position, because a nested host lives at an
 * assembly-local cell that {@code level.getBlockEntity} cannot resolve directly. {@link #of} builds
 * the path by walking up the host chain (client send side); {@link #resolve} walks back down to the
 * live host instance (both sides, via {@link AssemblyHost#getNestedHost}).
 *
 * <p>The root is abstracted behind {@link AssemblyRootId}; block-position roots identify
 * block-entity hosts, and entity roots identify live entity hosts by network entity id.
 */
public record AssemblyPath(AssemblyRootId root, List<BlockPos> nestedCells) {

	/** How the root host of a path is named and resolved. */
	public sealed interface AssemblyRootId permits BlockPosRoot, EntityRoot {

		/** The live root host this id names, or {@code null} if it cannot be resolved. */
		@Nullable
		AssemblyHost resolve(LevelReader level);

		/** World-space centre of the root, for broadcast-range checks. */
		Vec3 broadcastCenter();
	}

	/** A root host identified by its real-world block position (a block-entity host). */
	public record BlockPosRoot(BlockPos pos) implements AssemblyRootId {
		@Override
		@Nullable
		public AssemblyHost resolve(LevelReader level) {
			return level.getBlockEntity(pos) instanceof AssemblyHost host ? host : null;
		}

		@Override
		public Vec3 broadcastCenter() {
			return Vec3.atCenterOf(pos);
		}
	}

	/** A root host identified by its live network entity id. */
	public record EntityRoot(int entityId, Vec3 center) implements AssemblyRootId {
		@Override
		@Nullable
		public AssemblyHost resolve(LevelReader level) {
			if (level instanceof Level realLevel) {
				Entity entity = realLevel.getEntity(entityId);
				return entity instanceof AssemblyHost host ? host : null;
			}
			return null;
		}

		@Override
		public Vec3 broadcastCenter() {
			return center;
		}
	}

	public static final StreamCodec<RegistryFriendlyByteBuf, AssemblyPath> STREAM_CODEC = new StreamCodec<>() {
		@Override
		public AssemblyPath decode(RegistryFriendlyByteBuf buffer) {
			AssemblyRootId root = decodeRoot(buffer);
			int n = buffer.readVarInt();
			List<BlockPos> cells = new ArrayList<>(n);
			for (int i = 0; i < n; i++)
				cells.add(buffer.readBlockPos());
			return new AssemblyPath(root, cells);
		}

		@Override
		public void encode(RegistryFriendlyByteBuf buffer, AssemblyPath path) {
			encodeRoot(buffer, path.root);
			buffer.writeVarInt(path.nestedCells.size());
			for (BlockPos cell : path.nestedCells)
				buffer.writeBlockPos(cell);
		}
	};

	/** One-byte discriminator per {@link AssemblyRootId} kind (0 = block position, 1 = entity). */
	private static AssemblyRootId decodeRoot(RegistryFriendlyByteBuf buffer) {
		byte type = buffer.readByte();
		return switch (type) {
			case 0 -> new BlockPosRoot(buffer.readBlockPos());
			case 1 -> new EntityRoot(buffer.readVarInt(),
				new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
			default -> throw new IllegalArgumentException("Unknown assembly root type " + type);
		};
	}

	private static void encodeRoot(RegistryFriendlyByteBuf buffer, AssemblyRootId root) {
		if (root instanceof BlockPosRoot blockPos) {
			buffer.writeByte(0);
			buffer.writeBlockPos(blockPos.pos());
		} else if (root instanceof EntityRoot entity) {
			buffer.writeByte(1);
			buffer.writeVarInt(entity.entityId());
			buffer.writeDouble(entity.center().x);
			buffer.writeDouble(entity.center().y);
			buffer.writeDouble(entity.center().z);
		} else {
			throw new IllegalArgumentException("Unknown assembly root " + root);
		}
	}

	/** The path identifying {@code host}, found by walking up its host chain to the root. */
	public static AssemblyPath of(AssemblyHost host) {
		List<BlockPos> cells = new ArrayList<>();
		AssemblyHost m = host;
		AssemblyHost parent;
		while ((parent = m.assemblyParentHost()) != null) {
			cells.add(0, m.assemblyCellInParent()); // m's cell in the parent's local space
			m = parent;
		}
		AssemblyRootId root = m instanceof Entity entity
			? new EntityRoot(entity.getId(), entity.position())
			: new BlockPosRoot(m.assemblyHostBlockPos());
		return new AssemblyPath(root, cells);
	}

	/** The live host this path names, or {@code null} if any link is missing. Works on either side. */
	@Nullable
	public AssemblyHost resolve(LevelReader level) {
		AssemblyHost host = root.resolve(level);
		if (host == null)
			return null;
		for (BlockPos cell : nestedCells) {
			host = host.getNestedHost(cell);
			if (host == null)
				return null;
		}
		return host;
	}
}
