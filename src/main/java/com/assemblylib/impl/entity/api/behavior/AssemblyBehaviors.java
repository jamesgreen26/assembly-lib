package com.assemblylib.impl.entity.api.behavior;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.assemblylib.AssemblyLib;

/**
 * The registry other mods register their {@link AssemblyBehaviorType}s into.
 * It's a normal NeoForge registry, so the id <-> behavior mapping works the
 * same way Blocks/Items/EntityTypes already do — no custom networking
 * needed just to know "which behavior type is this".
 *
 * <p>Other mods register against this exactly like they'd register a Block
 * or Item, just pointed at {@link #REGISTRY_KEY} instead of a vanilla one:</p>
 *
 * <pre>{@code
 * public static final DeferredRegister<AssemblyBehaviorType<?>> BEHAVIOR_TYPES =
 *         DeferredRegister.create(AssemblyBehaviors.REGISTRY_KEY, "yourmodid");
 *
 * public static final DeferredHolder<AssemblyBehaviorType<?>, AssemblyBehaviorType<MyBehavior>> MY_BEHAVIOR =
 *         BEHAVIOR_TYPES.register("my_behavior", () -> MyBehavior::new);
 *
 * // then in your mod constructor:
 * BEHAVIOR_TYPES.register(modEventBus);
 * }</pre>
 */
public class AssemblyBehaviors {

    public static final ResourceKey<Registry<AssemblyBehaviorType<?>>> REGISTRY_KEY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(AssemblyLib.MOD_ID, "assembly_behavior_type"));

    public static final DeferredRegister<AssemblyBehaviorType<?>> BEHAVIOR_TYPES =
            DeferredRegister.create(REGISTRY_KEY, AssemblyLib.MOD_ID);

    /** The actual registry object. Other mods generally don't need this directly — use REGISTRY_KEY to register into it. */
    public static final Registry<AssemblyBehaviorType<?>> BEHAVIOR_TYPE_REGISTRY =
            BEHAVIOR_TYPES.makeRegistry(builder -> {});

    /** The default behavior every AssemblyEntity has until something sets a different one. Does nothing. */
    public static final DeferredHolder<AssemblyBehaviorType<?>, AssemblyBehaviorType<AssemblyBehavior>> NONE =
            BEHAVIOR_TYPES.register("none", () -> AssemblyBehavior::new);

    public static void register(IEventBus modEventBus) {
        BEHAVIOR_TYPES.register(modEventBus);
    }
}