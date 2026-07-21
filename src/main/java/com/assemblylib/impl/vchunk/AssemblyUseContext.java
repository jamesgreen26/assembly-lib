package com.assemblylib.impl.vchunk;

import javax.annotation.Nullable;

import net.minecraft.world.phys.BlockHitResult;

/**
 * A per-thread override for the block the player is "looking at" while the server drives an assembly
 * interaction through vanilla's own item pipeline.
 *
 * <p>Most block interactions run through {@code ServerPlayerGameMode.useItemOn}, which is handed a
 * {@link BlockHitResult} directly — so {@link AssemblyInteractionServer} can simply build the hit at
 * the assembly block's real (far-away) coordinates. But a whole class of items (buckets, ender
 * pearls, bottles, splash potions, fishing rods, ...) do their work in {@code Item#use}, which
 * ignores any provided hit and instead re-raycasts the real level from the player's actual eye via
 * the static {@code Item.getPlayerPOVHitResult}. That raycast can never reach an assembly block sat
 * past 30,000,000, so those items silently no-op on assemblies (the "can't place water" bug).
 *
 * <p>Rather than special-case each such item, we hook the single shared chokepoint they all funnel
 * through — {@code Item.getPlayerPOVHitResult} — and, while an assembly {@code use()} is in flight,
 * return this override instead. {@link AssemblyInteractionServer} sets the override to the assembly
 * block's absolute hit for exactly the duration of one {@code useItem} call, so the deep hook only
 * ever fires for our own interaction and is otherwise completely inert.
 */
public final class AssemblyUseContext {

    private static final ThreadLocal<BlockHitResult> OVERRIDE = new ThreadLocal<>();

    private AssemblyUseContext() {}

    /** Set the POV-hit override for the current thread (the assembly block's absolute-coordinate hit). */
    public static void push(BlockHitResult hit) {
        OVERRIDE.set(hit);
    }

    /** Clear the override for the current thread. Always call this in a {@code finally}. */
    public static void pop() {
        OVERRIDE.remove();
    }

    /** The active override, or {@code null} when no assembly {@code use()} is in flight on this thread. */
    @Nullable
    public static BlockHitResult current() {
        return OVERRIDE.get();
    }
}
