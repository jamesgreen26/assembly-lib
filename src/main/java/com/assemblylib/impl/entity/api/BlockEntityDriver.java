package com.assemblylib.impl.entity.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * Client-side hook for driving the <em>animations</em> of block entities riding an assembly.
 *
 * <p>Block entities (chests, bells, shulker boxes, …) render through a
 * {@code BlockEntityRenderer} that reads animation state off the {@code BlockEntity} instance
 * itself — a chest's lid angle comes from its openers counter, a bell's swing from its
 * {@code ticks} field, a shulker's slide from its {@code progress} field. In the world that
 * state is advanced every tick by the block entity's ticker. On an assembly the block entities
 * are render-only stand-ins that are <strong>never ticked</strong>, so without this hook their
 * animations sit frozen at whatever state was captured.</p>
 *
 * <p>Implement this (typically on your {@code AssemblyBehavior}) to mutate those stand-ins each
 * frame before they render — making the chest open, the bell ring, the shulker animate. It is
 * auto-detected: if your behavior (or the entity's {@link AssemblyAnimator}) implements this
 * interface, {@link #driveBlockEntities} is called every frame, client-side only.</p>
 *
 * <p>The map is the live render map — keyed by relative position (matching
 * {@code AssemblyBlock.relativePos()}) — and mutations to the entries stick. You hold the
 * concrete {@code BlockEntity}, so you can call whatever method or field drives it. Some vanilla
 * drivers are public and trivial (e.g. {@code BellBlockEntity#onHit}); others are private
 * (the chest openers counter) and need an access transformer or your own state tracking.</p>
 *
 * <p>Example — ring a bell on the assembly:</p>
 * <pre>{@code
 * public class MyBehavior extends AssemblyBehavior implements BlockEntityDriver {
 *     private static final BlockPos BELL_POS = new BlockPos(0, 1, 0);
 *     public boolean ringing;
 *
 *     @Override
 *     @OnlyIn(Dist.CLIENT)
 *     public void driveBlockEntities(Map<BlockPos, BlockEntity> bes, float partialTick) {
 *         if (bes.get(BELL_POS) instanceof BellBlockEntity bell) {
 *             if (ringing && !bell.shaking) bell.onHit(Direction.NORTH); // start the swing
 *             // the bell's own clientTick would normally advance this; do it ourselves:
 *             if (bell.shaking && bell.ticks++ >= 50) { bell.shaking = false; bell.ticks = 0; }
 *         }
 *     }
 * }
 * }</pre>
 */
public interface BlockEntityDriver {

    /**
     * Called client-side every <em>frame</em>, before any block entity on the assembly is
     * rendered. Use this for things that depend on render frequency: triggering one-shot
     * actions ({@code bell.onHit(...)}), or reading {@code partialTick} for smooth motion.
     *
     * <p><strong>Do not advance tick-rate counters here</strong> (e.g. {@code bell.ticks++}).
     * This fires at your frame rate — 60, 144, whatever — so a per-tick counter would run far
     * too fast. Put those in {@link #tickBlockEntities} instead, which runs at a fixed 20/s.</p>
     *
     * @param blockEntities live map of relativePos -> render-only {@link BlockEntity}; mutations stick
     * @param partialTick   sub-tick interpolation factor for smooth motion
     */
    @OnlyIn(Dist.CLIENT)
    void driveBlockEntities(Map<BlockPos, BlockEntity> blockEntities, float partialTick);

    /**
     * Called client-side once per game <em>tick</em> (20/s), from the assembly entity's tick.
     * Put state that must advance at tick rate here — the same place vanilla's block entity
     * tickers run it: {@code bell.ticks++}, {@code shulker.progress += step}, lid decay, etc.
     *
     * <p>No-op by default. Note this runs on the entity tick, independent of rendering, so it
     * keeps advancing even on frames where the assembly is culled.</p>
     *
     * @param blockEntities live map of relativePos -> render-only {@link BlockEntity}; mutations stick
     */
    @OnlyIn(Dist.CLIENT)
    default void tickBlockEntities(Map<BlockPos, BlockEntity> blockEntities) {}
}
