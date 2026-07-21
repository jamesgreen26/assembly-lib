package com.assemblylib.impl.entity.api.behavior;

import com.assemblylib.impl.entity.AssemblyEntity;

/**
 * A registered "kind" of {@link AssemblyBehavior}. Acts as a factory —
 * register one of these (see {@link AssemblyBehaviors}) and AssemblyEntity
 * will call {@link #create} to build a fresh behavior instance whenever it
 * needs one (on spawn, on load from disk, on receiving a sync packet).
 *
 * <p>In practice this is almost always just a constructor reference, e.g.
 * {@code MyBehavior::new}.</p>
 */
@FunctionalInterface
public interface AssemblyBehaviorType<T extends AssemblyBehavior> {
    T create(AssemblyEntity entity);
}