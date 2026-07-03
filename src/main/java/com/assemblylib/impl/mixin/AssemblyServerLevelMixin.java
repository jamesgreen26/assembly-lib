package com.assemblylib.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.assemblylib.impl.vchunk.AssemblyEffectRedirect;
import com.assemblylib.impl.vchunk.AssemblyManager;
import com.assemblylib.impl.vchunk.AssemblySpace;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

/**
 * Makes vanilla's own global tick loops treat assembly chunks as fully-loaded, ticking chunks.
 *
 * <p>Block-entity ticking ({@code Level.tickBlockEntities}) and scheduled block/fluid ticks
 * ({@code LevelTicks}, gated by {@code isPositionTickingWithEntitiesLoaded = areEntitiesLoaded &&
 * chunkSource.isPositionTicking}) are both skipped unless these return true. Assembly chunks have no
 * {@code ChunkHolder}/entity-section-manager entry, so we answer true for positions inside the
 * plotgrid — vanilla then ticks the blocks exactly as it would anywhere else.
 */
@Mixin(ServerLevel.class)
public abstract class AssemblyServerLevelMixin {

    @Inject(method = "shouldTickBlocksAt(J)Z", at = @At("HEAD"), cancellable = true)
    private void assemblylib$shouldTickBlocksAt(long packedChunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (AssemblySpace.isAssemblyChunk(ChunkPos.getX(packedChunkPos), ChunkPos.getZ(packedChunkPos))) {
            AssemblyManager manager = AssemblyManager.active((ServerLevel) (Object) this);
            if (manager != null && manager.hasResidentChunk(new ChunkPos(packedChunkPos))) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "areEntitiesLoaded(J)Z", at = @At("HEAD"), cancellable = true)
    private void assemblylib$areEntitiesLoaded(long packedChunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (AssemblySpace.isAssemblyChunk(ChunkPos.getX(packedChunkPos), ChunkPos.getZ(packedChunkPos))) {
            AssemblyManager manager = AssemblyManager.active((ServerLevel) (Object) this);
            if (manager != null && manager.hasResidentChunk(new ChunkPos(packedChunkPos))) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "isNaturalSpawningAllowed(Lnet/minecraft/world/level/ChunkPos;)Z", at = @At("HEAD"), cancellable = true)
    private void assemblylib$isNaturalSpawningAllowed(ChunkPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (AssemblySpace.isAssemblyChunk(pos)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * The general "any block change auto-resyncs the client" hook: every block state change, no
     * matter the cause (redstone, a piston, a hopper, crop growth, a player's own edit), funnels
     * through this single vanilla method. Marking the owning assembly dirty here means new call sites
     * (mod-added blocks, future vanilla features) get correct client sync automatically — no per-
     * feature mixin needed, unlike remembering to call {@code markContentDirty} at every write site.
     */
    @Inject(method = "sendBlockUpdated(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;I)V",
        at = @At("HEAD"))
    private void assemblylib$sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (!AssemblySpace.isAssemblySpace(pos)) {
            return;
        }
        AssemblyManager manager = AssemblyManager.active((ServerLevel) (Object) this);
        if (manager != null) {
            manager.markBlockDirty(pos.immutable());
        }
    }

    /**
     * Purely-cosmetic chokepoint (unlike {@code blockEvent}, this never mutates client-side block
     * state — it only draws a crack overlay), so it gets the same simple redirect as sound/levelEvent:
     * mirror vanilla's own broadcast loop, but measure distance to and embed the block's APPARENT
     * position instead of its real one. Currently unreachable by our own instant-break interaction
     * path, but correct the moment incremental (held-down) mining is added for assembly blocks.
     */
    @Inject(method = "destroyBlockProgress(ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"), cancellable = true)
    private void assemblylib$destroyBlockProgress(int breakerId, BlockPos pos, int progress, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        Vec3 apparent = AssemblyEffectRedirect.apparentCenter(self, pos);
        if (apparent == null) {
            return;
        }
        BlockPos apparentPos = BlockPos.containing(apparent);
        for (ServerPlayer player : self.getServer().getPlayerList().getPlayers()) {
            if (player.level() != self || player.getId() == breakerId) {
                continue;
            }
            if (apparent.distanceToSqr(player.position()) < 1024.0) {
                player.connection.send(new ClientboundBlockDestructionPacket(breakerId, apparentPos, progress));
            }
        }
        ci.cancel();
    }

    /**
     * Game-event (vibration) chokepoint: every sculk-sensor-audible event in the game — block place,
     * block destroy, container open/close, dispense, explode, splash — funnels through this ONE method.
     * Unlike sound/levelEvent this is NOT a cancel-and-redirect: the original dispatch must still run
     * so sculk sensors mounted ON the assembly (real blocks at real assembly coordinates, with real
     * per-chunk listener registries) hear their own assembly's events; we ADDITIONALLY dispatch at the
     * apparent world position so sensors in the surrounding world hear the assembly too. No feedback
     * loop: the second dispatch's position is ordinary world-space, so this injection ignores it.
     */
    @Inject(method = "gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V",
        at = @At("HEAD"))
    private void assemblylib$gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        Vec3 apparent = AssemblyEffectRedirect.apparentPoint(self, pos.x, pos.y, pos.z);
        if (apparent != null) {
            self.gameEvent(event, apparent, context);
        }
    }

    /**
     * Server-spawned particle chokepoint: every {@code ServerLevel#sendParticles} overload builds its
     * packet from the coordinates passed to these two public entry points (composter fill, bonemeal,
     * dispenser smoke, mod-added block effects, ...). Same treatment as sound: rewrite the origin to
     * the apparent point (exact, not block-snapped — the fractional offset is part of the effect) and
     * re-enter, so vanilla's own per-player distance filtering runs against a normal nearby coordinate.
     */
    @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I",
        at = @At("HEAD"), cancellable = true)
    private void assemblylib$sendParticles(ParticleOptions particle, double x, double y, double z,
            int count, double dx, double dy, double dz, double speed, CallbackInfoReturnable<Integer> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        Vec3 apparent = AssemblyEffectRedirect.apparentPoint(self, x, y, z);
        if (apparent != null) {
            cir.setReturnValue(self.sendParticles(particle, apparent.x, apparent.y, apparent.z, count, dx, dy, dz, speed));
        }
    }

    @Inject(method = "sendParticles(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/particles/ParticleOptions;ZDDDIDDDD)Z",
        at = @At("HEAD"), cancellable = true)
    private void assemblylib$sendParticlesToPlayer(ServerPlayer player, ParticleOptions particle, boolean overrideLimiter,
            double x, double y, double z, int count, double dx, double dy, double dz, double speed,
            CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        Vec3 apparent = AssemblyEffectRedirect.apparentPoint(self, x, y, z);
        if (apparent != null) {
            cir.setReturnValue(self.sendParticles(player, particle, overrideLimiter,
                apparent.x, apparent.y, apparent.z, count, dx, dy, dz, speed));
        }
    }

    /**
     * Explosion chokepoint. The server-side explosion itself must run at the REAL coordinates
     * unmodified (it's what actually damages the assembly's blocks — correct as-is), but vanilla's
     * trailing broadcast loop measures distance to, and embeds, that real 30M+ position — so no player
     * ever sees or hears an assembly explosion. Inject AFTER vanilla's body: rebroadcast the packet to
     * players near the APPARENT position with an EMPTY to-blow list — the destroyed blocks reach the
     * client through the assembly's own {@code sendBlockUpdated}-driven resync, and sending the real
     * positions would make the client mutate unloaded/wrong coordinates (same corruption class that
     * ruled out the {@code blockEvent} redirect).
     */
    @Inject(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/Holder;)Lnet/minecraft/world/level/Explosion;",
        at = @At("RETURN"))
    private void assemblylib$explode(net.minecraft.world.entity.Entity source,
            net.minecraft.world.damagesource.DamageSource damageSource,
            net.minecraft.world.level.ExplosionDamageCalculator calculator,
            double x, double y, double z, float radius, boolean fire,
            net.minecraft.world.level.Level.ExplosionInteraction interaction,
            ParticleOptions smallParticles, ParticleOptions largeParticles,
            Holder<net.minecraft.sounds.SoundEvent> sound,
            CallbackInfoReturnable<Explosion> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        Vec3 apparent = AssemblyEffectRedirect.apparentPoint(self, x, y, z);
        if (apparent == null) {
            return;
        }
        Explosion explosion = cir.getReturnValue();
        for (ServerPlayer player : self.players()) {
            if (player.distanceToSqr(apparent.x, apparent.y, apparent.z) < 4096.0) {
                player.connection.send(new ClientboundExplodePacket(
                    apparent.x, apparent.y, apparent.z, radius,
                    List.of(),
                    explosion.getHitPlayers().get(player),
                    explosion.getBlockInteraction(),
                    explosion.getSmallExplosionParticles(),
                    explosion.getLargeExplosionParticles(),
                    explosion.getExplosionSound()));
            }
        }
    }
}
