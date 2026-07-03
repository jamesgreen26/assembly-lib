package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.assemblylib.impl.vchunk.AssemblyManager;
import com.assemblylib.impl.vchunk.AssemblySpace;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * The central chunk-read interception point for the real-coordinate assembly engine.
 *
 * <p>Hand-built assembly chunks are never registered with {@code ChunkMap}/{@code ChunkHolder}/the
 * ticket system. Instead, every chunk-read path that vanilla block/block-entity/redstone code uses
 * funnels through {@link ServerChunkCache}; this mixin short-circuits those for assembly-space
 * {@link ChunkPos}es and returns the resident {@link LevelChunk} owned by {@link AssemblyManager}.
 *
 * <p>Once vanilla <em>has</em> the real {@code LevelChunk} object, everything downstream
 * (neighbour updates, redstone, {@code BlockEntity#getLevel()} returning the real {@code
 * ServerLevel}) works unmodified — the entire payoff of using real chunks instead of a wrapper level.
 */
@Mixin(ServerChunkCache.class)
public abstract class AssemblyServerChunkCacheMixin {

    @Shadow
    @Final
    public ServerLevel level;

    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"),
        cancellable = true)
    private void assemblylib$getChunk(int x, int z, ChunkStatus status, boolean requireChunk,
            CallbackInfoReturnable<ChunkAccess> cir) {
        if (!AssemblySpace.isAssemblyChunk(x, z)) {
            return;
        }
        AssemblyManager manager = AssemblyManager.active(this.level);
        if (manager == null) {
            return;
        }
        ChunkPos pos = new ChunkPos(x, z);
        cir.setReturnValue(requireChunk ? manager.getOrBuildChunk(pos) : manager.getResidentChunk(pos));
    }

    @Inject(method = "getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("HEAD"), cancellable = true)
    private void assemblylib$getChunkNow(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        if (!AssemblySpace.isAssemblyChunk(x, z)) {
            return;
        }
        AssemblyManager manager = AssemblyManager.active(this.level);
        if (manager == null) {
            return;
        }
        cir.setReturnValue(manager.getResidentChunk(new ChunkPos(x, z)));
    }

    @Inject(method = "getChunkForLighting(II)Lnet/minecraft/world/level/chunk/LightChunk;", at = @At("HEAD"), cancellable = true)
    private void assemblylib$getChunkForLighting(int x, int z, CallbackInfoReturnable<LightChunk> cir) {
        if (!AssemblySpace.isAssemblyChunk(x, z)) {
            return;
        }
        AssemblyManager manager = AssemblyManager.active(this.level);
        if (manager == null) {
            return;
        }
        cir.setReturnValue(manager.getResidentChunk(new ChunkPos(x, z)));
    }

    @Inject(method = "hasChunk(II)Z", at = @At("HEAD"), cancellable = true)
    private void assemblylib$hasChunk(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (!AssemblySpace.isAssemblyChunk(x, z)) {
            return;
        }
        AssemblyManager manager = AssemblyManager.active(this.level);
        if (manager == null) {
            return;
        }
        cir.setReturnValue(manager.hasResidentChunk(new ChunkPos(x, z)));
    }

    @Inject(method = "isPositionTicking(J)Z", at = @At("HEAD"), cancellable = true)
    private void assemblylib$isPositionTicking(long packedChunkPos, CallbackInfoReturnable<Boolean> cir) {
        // Hot path (called from vanilla's scheduled-tick loop): band-check the raw ints before
        // allocating anything.
        if (!AssemblySpace.isAssemblyChunk(ChunkPos.getX(packedChunkPos), ChunkPos.getZ(packedChunkPos))) {
            return;
        }
        AssemblyManager manager = AssemblyManager.active(this.level);
        if (manager != null && manager.hasResidentChunk(new ChunkPos(packedChunkPos))) {
            cir.setReturnValue(true);
        }
    }
}
