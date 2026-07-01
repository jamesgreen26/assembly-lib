package com.assemblylib.impl.assembly;

import javax.annotation.Nullable;

import org.joml.Quaternionf;

import net.minecraft.nbt.CompoundTag;

/**
 * Implemented (via mixin) by entities that can carry a assembly's orientation, so a
 * falling block detached from a rotating assembly keeps that rotation as it falls.
 * The rotation is a full {@link Quaternionf} (synced and persisted) so it can represent a
 * nested assembly's <em>composed</em> orientation (a product of rotations about
 * different axes), not just a single angle about one axis.
 *
 * <p>It also carries the detached block's block-entity client render (update) tag, synced so the
 * block entity can still be drawn while the block falls (the full contents ride the vanilla,
 * save-only {@code blockData} field instead, which the client never sees).
 */
public interface AssemblyRotatedEntity {

	/** Set the inherited orientation (local -&gt; world rotation of the source assembly). */
	void zps$setAssemblyRotation(Quaternionf rotation);

	/** The inherited orientation, identity if none was set. */
	Quaternionf zps$getAssemblyRotation();

	/** Set the carried block-entity update tag (null/empty clears it); synced for client rendering. */
	void zps$setBlockEntityTag(@Nullable CompoundTag tag);

	/** The carried block-entity update tag, an empty tag if none was set. */
	CompoundTag zps$getBlockEntityTag();
}
