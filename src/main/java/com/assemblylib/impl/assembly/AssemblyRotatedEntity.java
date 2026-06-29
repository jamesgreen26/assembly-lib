package com.assemblylib.impl.assembly;

import org.joml.Quaternionf;

/**
 * Implemented (via mixin) by entities that can carry a assembly's orientation, so a
 * falling block detached from a rotating assembly keeps that rotation as it falls.
 * The rotation is a full {@link Quaternionf} (synced and persisted) so it can represent a
 * nested assembly's <em>composed</em> orientation (a product of rotations about
 * different axes), not just a single angle about one axis.
 */
public interface AssemblyRotatedEntity {

	/** Set the inherited orientation (local -&gt; world rotation of the source assembly). */
	void zps$setAssemblyRotation(Quaternionf rotation);

	/** The inherited orientation, identity if none was set. */
	Quaternionf zps$getAssemblyRotation();
}
