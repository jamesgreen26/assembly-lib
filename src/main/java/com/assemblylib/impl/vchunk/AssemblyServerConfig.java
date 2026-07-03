package com.assemblylib.impl.vchunk;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side configuration for the real-coordinate assembly engine's load/unload distance. Only
 * assemblies within {@code loadDistance} of a player are resident (chunks built, block entities
 * ticking, synced to clients); an assembly beyond {@code unloadDistance} is unloaded (chunks
 * unregistered from vanilla ticking and discarded from memory — its block content is already
 * persisted via {@code AssemblyManager}'s save data, so it rehydrates losslessly when a player
 * returns). The gap between the two distances avoids load/unload flapping at the boundary.
 */
public final class AssemblyServerConfig {

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue LOAD_DISTANCE;
    private static final ModConfigSpec.IntValue UNLOAD_DISTANCE;
    private static final ModConfigSpec.IntValue CHECK_INTERVAL_TICKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("AssemblyLib real-coordinate assembly engine").push("assemblies");
        LOAD_DISTANCE = builder
            .comment(
                "Distance (in blocks) from the nearest player, measured to an assembly's apparent",
                "position, within which the assembly is loaded: its chunks are built and registered",
                "with vanilla's tick loop (block entities tick, redstone/fluids run) and it is synced",
                "to clients. Default: 128 (matches a modest vanilla view distance).")
            .defineInRange("loadDistance", 128, 16, 4096);
        UNLOAD_DISTANCE = builder
            .comment(
                "Distance (in blocks) beyond which a loaded assembly is unloaded (chunks discarded from",
                "memory; content is already persisted and rehydrates when a player returns). Must be >=",
                "loadDistance -- the gap between the two prevents load/unload flapping at the boundary.",
                "Default: 192.")
            .defineInRange("unloadDistance", 192, 16, 8192);
        CHECK_INTERVAL_TICKS = builder
            .comment("How often (in server ticks) to re-check load/unload distance for every assembly.",
                "Default: 20 (once per second) -- this is a cheap distance check, not per-block work.")
            .defineInRange("checkIntervalTicks", 20, 1, 200);
        builder.pop();
        SPEC = builder.build();
    }

    private AssemblyServerConfig() {}

    public static double loadDistance() {
        return SPEC.isLoaded() ? LOAD_DISTANCE.get() : 128;
    }

    public static double unloadDistance() {
        return SPEC.isLoaded() ? Math.max(UNLOAD_DISTANCE.get(), LOAD_DISTANCE.get()) : 192;
    }

    public static int checkIntervalTicks() {
        return SPEC.isLoaded() ? CHECK_INTERVAL_TICKS.get() : 20;
    }
}
