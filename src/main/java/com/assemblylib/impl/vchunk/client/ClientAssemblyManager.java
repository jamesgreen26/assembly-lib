package com.assemblylib.impl.vchunk.client;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * Client-side registry of {@link ClientAssembly} mirrors, keyed by runtime handle. Populated and
 * mutated by {@link ClientAssemblyNetwork} from sync packets; read by the renderer.
 */
public final class ClientAssemblyManager {

    private static final Map<Integer, ClientAssembly> ASSEMBLIES = new ConcurrentHashMap<>();

    private ClientAssemblyManager() {}

    public static Collection<ClientAssembly> all() {
        return ASSEMBLIES.values();
    }

    @Nullable
    public static ClientAssembly get(int handle) {
        return ASSEMBLIES.get(handle);
    }

    public static void put(int handle, ClientAssembly assembly) {
        ClientAssembly previous = ASSEMBLIES.put(handle, assembly);
        if (previous != null && previous != assembly) {
            previous.dispose();
        }
    }

    public static void remove(int handle) {
        ClientAssembly removed = ASSEMBLIES.remove(handle);
        if (removed != null) {
            removed.dispose();
        }
    }

    /** Drop all mirrors (e.g. on disconnect / level change). */
    public static void clear() {
        ASSEMBLIES.values().forEach(ClientAssembly::dispose);
        ASSEMBLIES.clear();
    }
}
