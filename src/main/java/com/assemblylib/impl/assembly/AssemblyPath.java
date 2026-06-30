package com.assemblylib.impl.assembly;

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
 * Identifies an {@link AssemblyHost} by an {@link AssemblyRootId} naming the host.
 *
 * <p>Interaction packets carry this instead of a single position, so the host can be addressed
 * uniformly whether it is a block entity or an entity. {@link #of} builds the path from a host
 * (client send side); {@link #resolve} returns the live host instance (both sides).
 *
 * <p>The root is abstracted behind {@link AssemblyRootId}; block-position roots identify
 * block-entity hosts, and entity roots identify live entity hosts by network entity id.
 */
public record AssemblyPath(AssemblyRootId root) {

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
			return new AssemblyPath(decodeRoot(buffer));
		}

		@Override
		public void encode(RegistryFriendlyByteBuf buffer, AssemblyPath path) {
			encodeRoot(buffer, path.root);
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

	/** The path identifying {@code host}. */
	public static AssemblyPath of(AssemblyHost host) {
		AssemblyRootId root = host instanceof Entity entity
			? new EntityRoot(entity.getId(), entity.position())
			: new BlockPosRoot(host.assemblyHostBlockPos());
		return new AssemblyPath(root);
	}

	/** The live host this path names, or {@code null} if it cannot be resolved. Works on either side. */
	@Nullable
	public AssemblyHost resolve(LevelReader level) {
		return root.resolve(level);
	}
}
