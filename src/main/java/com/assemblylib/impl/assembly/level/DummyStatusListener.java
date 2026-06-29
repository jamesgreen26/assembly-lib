package com.assemblylib.impl.assembly.level;

import javax.annotation.Nullable;

import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * A no-op {@link ChunkProgressListener} for {@link WrappedServerLevel}'s {@link net.minecraft.server.level.ServerLevel}
 * construction — the sim level never loads chunks for real. Ported from catnip's
 * {@code DummyStatusListener}.
 */
public class DummyStatusListener implements ChunkProgressListener {

	@Override
	public void updateSpawnPos(ChunkPos center) {
	}

	@Override
	public void onStatusChange(ChunkPos chunkPos, @Nullable ChunkStatus newStatus) {
	}

	@Override
	public void start() {
	}

	@Override
	public void stop() {
	}
}
