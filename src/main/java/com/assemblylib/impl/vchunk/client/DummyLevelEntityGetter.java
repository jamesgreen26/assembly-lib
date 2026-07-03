package com.assemblylib.impl.vchunk.client;

import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;

/**
 * An empty {@link LevelEntityGetter} for {@link AssemblyWrappedLevel} — it hosts only reconstructed
 * client block entities, never real entities. Clean-room port of catnip's
 * {@code DummyLevelEntityGetter} (same as the one already used by the old assembly system), copied
 * here so the new engine carries no dependency on it.
 */
public class DummyLevelEntityGetter<T extends EntityAccess> implements LevelEntityGetter<T> {

    @Override
    public T get(int id) {
        return null;
    }

    @Override
    public T get(UUID uuid) {
        return null;
    }

    @Override
    public Iterable<T> getAll() {
        return Collections.emptyList();
    }

    @Override
    public <U extends T> void get(EntityTypeTest<T, U> test, AbortableIterationConsumer<U> consumer) {
    }

    @Override
    public void get(AABB bounds, Consumer<T> consumer) {
    }

    @Override
    public <U extends T> void get(EntityTypeTest<T, U> test, AABB bounds, AbortableIterationConsumer<U> consumer) {
    }
}
