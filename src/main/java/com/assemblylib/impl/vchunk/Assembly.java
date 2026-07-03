package com.assemblylib.impl.vchunk;

import java.util.UUID;

import javax.annotation.Nullable;

import org.joml.Quaternionf;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * A first-class assembly: an identity, a private assembly-space tile (its {@link #slot}), and a
 * previous/current-tick {@link AssemblyTransform} pair for smooth client interpolation.
 *
 * <p>An assembly is NOT owned by or anchored to any block or entity (the old {@code AssemblyHost}
 * abstraction is gone). Real vanilla blocks/block-entities are placed into its tile and behave
 * exactly as if placed in the normal world; this object only tracks where that tile's contents
 * <em>appear</em> in the world (the transform) and which chunks it owns.
 */
public final class Assembly {

    private final AssemblyId id;
    private final int slot;

    /** Transform as of the previous server tick (for interpolation). */
    private AssemblyTransform previousTransform;
    /** Transform as of the current server tick. */
    private AssemblyTransform currentTransform;

    /**
     * Transient test-driver motion (Milestone 2): a per-tick linear velocity and an angular step
     * applied each tick. Real gameplay movement (an engine/control mechanism) is out of scope; this
     * exists so a moving/rotating assembly can be exercised via the debug command. Not persisted.
     */
    private Vec3 linearVelocity = Vec3.ZERO;
    private Quaternionf angularStep = new Quaternionf();

    /**
     * Block content loaded from disk but not yet applied to resident chunks (Milestone 5). Hydrated
     * lazily on the first server tick, when the level is fully ready to accept {@code setBlock}.
     */
    private CompoundTag pendingContent;

    /**
     * Whether this assembly is currently "loaded": its content has resident, vanilla-ticking chunks.
     * An unloaded assembly costs nothing beyond its id/transform/footprint and a serialized content
     * blob sitting in memory (see {@link AssemblyManager#updateLoadState}).
     */
    private boolean loaded = false;

    public Assembly(AssemblyId id, int slot, AssemblyTransform transform) {
        this.id = id;
        this.slot = slot;
        this.previousTransform = transform;
        this.currentTransform = transform;
    }

    public AssemblyId id() {
        return id;
    }

    public int slot() {
        return slot;
    }

    public AssemblyTransform currentTransform() {
        return currentTransform;
    }

    public AssemblyTransform previousTransform() {
        return previousTransform;
    }

    /** The interpolated transform at {@code partialTick} (client render / interaction). */
    public AssemblyTransform transform(float partialTick) {
        return AssemblyTransform.interpolate(previousTransform, currentTransform, partialTick);
    }

    /**
     * Advance one tick: the current transform becomes the previous, and {@code next} becomes current.
     * Returns true if the transform actually changed (so callers can skip a no-op sync).
     */
    public boolean advance(AssemblyTransform next) {
        boolean changed = !next.translation().equals(currentTransform.translation())
            || !next.rotation().equals(currentTransform.rotation(), 1.0e-6f);
        this.previousTransform = this.currentTransform;
        this.currentTransform = next;
        return changed;
    }

    /** Set both previous and current to the same transform (e.g. on load / teleport — no interpolation). */
    public void resetTransform(AssemblyTransform transform) {
        this.previousTransform = transform;
        this.currentTransform = transform;
    }

    /**
     * Collapse the interpolation pair ({@code previous = current}) for a tick in which the assembly
     * did not move — the exact thing vanilla does for every entity each tick via {@code xo = x}.
     *
     * <p>Without this, {@code previousTransform} is only ever refreshed inside {@link #advance} (i.e.
     * only while moving), so the instant an assembly stops it keeps a {@code previous != current} pair
     * frozen one motion-step back <em>forever</em>. The entity-collision substep loop interpolates the
     * assembly pose from previous → current and resolves against every intermediate pose, so a frozen
     * stale {@code previous} smears the block collision boxes across the gap between the two poses —
     * phantom "air" collision trailing the (now stationary) assembly, coexisting with the correct
     * collision at the current pose. Calling this every idle tick keeps a still assembly's pair
     * collapsed, exactly like a still entity's {@code xo == x}.
     */
    public void settleTransform() {
        this.previousTransform = this.currentTransform;
    }

    // ---- transient test-driver motion (Milestone 2) ----

    public void setLinearVelocity(Vec3 velocity) {
        this.linearVelocity = velocity;
    }

    /** Set a per-tick rotation about a world axis through the assembly's origin. */
    public void setAngularStep(Quaternionf step) {
        this.angularStep = new Quaternionf(step);
    }

    public void stopMotion() {
        this.linearVelocity = Vec3.ZERO;
        this.angularStep = new Quaternionf();
    }

    public boolean hasMotion() {
        return linearVelocity.lengthSqr() > 1.0e-12 || !angularStep.equals(new Quaternionf(), 1.0e-7f);
    }

    /**
     * Apply one tick of test-driver motion (translate by the linear velocity, pre-multiply the
     * rotation by the angular step — a rotation in place about the assembly's origin) and advance the
     * previous/current transform pair. Returns true if the transform changed.
     */
    public boolean tickMotion() {
        if (!hasMotion()) {
            return false;
        }
        Vec3 newTranslation = currentTransform.translation().add(linearVelocity);
        Quaternionf newRotation = new Quaternionf(angularStep).mul(currentTransform.rotation());
        return advance(new AssemblyTransform(newTranslation, newRotation));
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    // ---- pending block content (Milestone 5) ----

    public void setPendingContent(CompoundTag content) {
        this.pendingContent = content;
    }

    @Nullable
    public CompoundTag takePendingContent() {
        CompoundTag c = pendingContent;
        pendingContent = null;
        return c;
    }

    public boolean hasPendingContent() {
        return pendingContent != null;
    }

    /** The serialized content of an UNLOADED assembly without consuming it (world-save path). */
    @Nullable
    public CompoundTag peekPendingContent() {
        return pendingContent;
    }

    // ---- persistence (registry-level; block content handled by AssemblyManager) ----

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", id.uuid());
        tag.putInt("handle", id.handle());
        tag.putInt("slot", slot);
        Vec3 t = currentTransform.translation();
        tag.putDouble("tx", t.x);
        tag.putDouble("ty", t.y);
        tag.putDouble("tz", t.z);
        Quaternionf r = currentTransform.rotation();
        tag.putFloat("qx", r.x);
        tag.putFloat("qy", r.y);
        tag.putFloat("qz", r.z);
        tag.putFloat("qw", r.w);
        return tag;
    }

    public static Assembly load(CompoundTag tag) {
        UUID uuid = tag.getUUID("uuid");
        int handle = tag.getInt("handle");
        int slot = tag.getInt("slot");
        Vec3 t = new Vec3(tag.getDouble("tx"), tag.getDouble("ty"), tag.getDouble("tz"));
        Quaternionf r = new Quaternionf(tag.getFloat("qx"), tag.getFloat("qy"), tag.getFloat("qz"), tag.getFloat("qw"));
        return new Assembly(new AssemblyId(uuid, handle), slot, new AssemblyTransform(t, r));
    }
}
