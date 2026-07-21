package com.assemblylib.impl.vchunk.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.vchunk.AssemblyId;
import com.assemblylib.impl.vchunk.AssemblyTransform;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side mirror of one {@link com.assemblylib.impl.vchunk.Assembly}: its id, an interpolated
 * previous/current transform pair, the block snapshot (LOCAL positions), a lazily-(re)built
 * {@link AssemblyBakedMesh}, and a LIVE cache of reconstructed block entities (chests, furnaces,
 * signs, ...) so they render with their real vanilla renderers and tick (lid animation, spawner
 * spin, etc.) exactly like the real world. Adapted from the old system's {@code AssemblyRenderState}.
 */
public final class ClientAssembly {

    /** A reconstructed block entity with its resolved client ticker. */
    private record TickingBE(BlockPos pos, BlockEntity be, BlockEntityTicker<BlockEntity> ticker) {}

    private final AssemblyId id;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    private final AssemblyBakedMesh mesh = new AssemblyBakedMesh();

    /** Host level for the reconstructed block entities; built once, never rebuilt (maps mutate in place). */
    private AssemblyBlockEntityLevel beLevel;

    @Nullable
    private List<TickingBE> tickers;
    private boolean tickersDirty = true;

    private AssemblyTransform previousTransform;
    private AssemblyTransform currentTransform;
    private boolean meshDirty = true;
    /** Whether a transform packet advanced this mirror during the current client tick. */
    private boolean transformUpdatedThisTick = false;

    public ClientAssembly(AssemblyId id, AssemblyTransform transform) {
        this.id = id;
        this.previousTransform = transform;
        this.currentTransform = transform;
        Level realLevel = Minecraft.getInstance().level;
        this.beLevel = new AssemblyBlockEntityLevel(realLevel, blocks, blockEntities, this::currentTransform);
    }

    public AssemblyId id() {
        return id;
    }

    public Map<BlockPos, BlockState> blocks() {
        return blocks;
    }

    public AssemblyBakedMesh mesh() {
        return mesh;
    }

    public boolean isMeshDirty() {
        return meshDirty;
    }

    public void clearMeshDirty() {
        meshDirty = false;
    }

    /** The interpolated transform at {@code partialTick}. */
    public AssemblyTransform transform(float partialTick) {
        return AssemblyTransform.interpolate(previousTransform, currentTransform, partialTick);
    }

    public AssemblyTransform currentTransform() {
        return currentTransform;
    }

    public AssemblyTransform previousTransform() {
        return previousTransform;
    }

    /** LOCAL-space AABB enclosing the snapshot blocks, or null if empty. */
    public AABB localBounds() {
        if (blocks.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks.keySet()) {
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    /** A client-side {@link com.assemblylib.impl.vchunk.collision.AssemblyLocalBlocks} over the snapshot. */
    public com.assemblylib.impl.vchunk.collision.AssemblyLocalBlocks clientBlocks() {
        return new com.assemblylib.impl.vchunk.collision.AssemblyLocalBlocks() {
            @Override
            public BlockState getLocal(BlockPos local) {
                BlockState s = blocks.get(local);
                return s == null ? Blocks.AIR.defaultBlockState() : s;
            }

            @Override
            public boolean isEmpty() {
                return blocks.isEmpty();
            }
        };
    }

    /** Apparent world origin (for biome tint sampling), the current transform's translation. */
    public BlockPos apparentOrigin() {
        return BlockPos.containing(currentTransform.translation());
    }

    /** Live reconstructed block entities, for the renderer to draw via their vanilla renderers. */
    public Iterable<BlockEntity> blockEntities() {
        return blockEntities.values();
    }

    /** Replace the full block + block-entity snapshot and transform (static / content edit). */
    public void setSnapshot(AssemblyTransform transform, List<BlockPos> positions, List<BlockState> states,
            List<BlockPos> bePositions, List<CompoundTag> beTags) {
        blocks.clear();
        for (int i = 0; i < positions.size(); i++) {
            blocks.put(positions.get(i), states.get(i));
        }
        this.previousTransform = transform;
        this.currentTransform = transform;
        this.meshDirty = true;

        Map<BlockPos, CompoundTag> tagsByPos = new HashMap<>();
        for (int i = 0; i < bePositions.size(); i++) {
            tagsByPos.put(bePositions.get(i), beTags.get(i));
        }
        reconcileBlockEntities(tagsByPos);
    }

    /**
     * Diff the cached block entities against the new snapshot: drop ones whose cell vanished or whose
     * block is no longer valid for the existing instance, add ones for new cells, and refresh an
     * unchanged cell's EXISTING instance in place via {@link BlockEntity#loadWithComponents} — this is
     * the load-bearing case, since it keeps transient animation fields (chest lid controller, etc.)
     * alive across syncs instead of resetting them.
     */
    private void reconcileBlockEntities(Map<BlockPos, CompoundTag> newTags) {
        Level realLevel = Minecraft.getInstance().level;
        if (realLevel == null) {
            return;
        }
        boolean structuralChange = false;

        for (Iterator<Map.Entry<BlockPos, BlockEntity>> it = blockEntities.entrySet().iterator(); it.hasNext();) {
            Map.Entry<BlockPos, BlockEntity> entry = it.next();
            BlockPos pos = entry.getKey();
            BlockState state = blocks.get(pos);
            if (state == null || !state.hasBlockEntity() || !entry.getValue().getType().isValid(state)) {
                entry.getValue().setRemoved();
                it.remove();
                structuralChange = true;
            }
        }

        if (upsertBlockEntities(newTags, realLevel) || structuralChange) {
            tickersDirty = true;
        }
    }

    /**
     * Add/refresh block entities for the given (LOCAL pos → NBT) map. Refreshing an unchanged cell's
     * EXISTING instance in place via {@link BlockEntity#loadWithComponents} is the load-bearing case:
     * it keeps transient animation fields (chest lid controller, etc.) alive across syncs.
     * Returns whether the set of instances structurally changed (tickers need a rebuild).
     */
    private boolean upsertBlockEntities(Map<BlockPos, CompoundTag> newTags, Level realLevel) {
        boolean structuralChange = false;
        for (Map.Entry<BlockPos, CompoundTag> entry : newTags.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = blocks.get(pos);
            if (state == null || !state.hasBlockEntity()) {
                continue;
            }
            CompoundTag tag = entry.getValue();
            BlockEntity existing = blockEntities.get(pos);
            if (existing == null) {
                BlockEntity be = reconstruct(pos, state);
                if (be != null) {
                    be.setLevel(beLevel);
                    be.setBlockState(state);
                    be.loadWithComponents(tag, realLevel.registryAccess());
                    blockEntities.put(pos, be);
                    structuralChange = true;
                }
            } else if (existing.getBlockState() != state) {
                existing.setBlockState(state);
                existing.loadWithComponents(tag, realLevel.registryAccess());
                structuralChange = true;
            } else {
                existing.loadWithComponents(tag, realLevel.registryAccess());
            }
        }
        return structuralChange;
    }

    /**
     * Apply an incremental per-block diff (the common sync path — a furnace lighting up, a door
     * opening, a single edit) without rebuilding the whole snapshot: update just the changed cells
     * (air = removal), drop/refresh only the block entities at those cells, and mark the mesh dirty.
     */
    public void applyDiff(List<BlockPos> positions, List<BlockState> states,
            List<BlockPos> bePositions, List<CompoundTag> beTags) {
        Level realLevel = Minecraft.getInstance().level;
        boolean structuralChange = false;
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            BlockState state = states.get(i);
            if (state.isAir()) {
                blocks.remove(pos);
            } else {
                blocks.put(pos, state);
            }
            BlockEntity existing = blockEntities.get(pos);
            if (existing != null
                && (state.isAir() || !state.hasBlockEntity() || !existing.getType().isValid(state))) {
                existing.setRemoved();
                blockEntities.remove(pos);
                structuralChange = true;
            }
        }
        this.meshDirty = true;

        if (realLevel != null && !bePositions.isEmpty()) {
            Map<BlockPos, CompoundTag> tagsByPos = new HashMap<>();
            for (int i = 0; i < bePositions.size(); i++) {
                tagsByPos.put(bePositions.get(i), beTags.get(i));
            }
            structuralChange |= upsertBlockEntities(tagsByPos, realLevel);
        }
        if (structuralChange) {
            tickersDirty = true;
        }
    }

    /**
     * Replay a forwarded vanilla block event on a mirror cell: chest/shulker/ender-chest lid, note
     * block pling, bell swing, etc. These drive transient, client-only animation state that never
     * rides the block-state diff, so the server forwards them explicitly (see
     * {@code AssemblyBlockEventS2CPacket}). Route through the mirror block entity when present (the
     * common case — the lid controller lives there and is already advanced each client tick); the few
     * block-only {@code triggerEvent} blocks fall back to the block state on the wrapped level.
     */
    public void applyBlockEvent(BlockPos local, int eventId, int param) {
        BlockEntity be = blockEntities.get(local);
        if (be != null) {
            be.triggerEvent(eventId, param);
            return;
        }
        BlockState state = blocks.get(local);
        if (state != null) {
            state.triggerEvent(beLevel, local, eventId, param);
        }
    }

    /**
     * {@code MovingPistonBlock#newBlockEntity} returns {@code null} by design (vanilla itself
     * constructs the moving BE by hand during a piston stroke) — the piston-slide animation is drawn
     * entirely from this BE's {@code progress} field by {@code PistonHeadRenderer}, so without this
     * special case a piston push/pull would be silently invisible on the client despite the underlying
     * block state being captured correctly by the server (proven by the assembly's own {@code
     * sendBlockUpdated}-driven resync). This is the client-side half of getting piston support "for
     * free": the state already arrives correctly; it just needs the same hand-construction vanilla
     * itself uses to turn it into a renderable, ticking instance.
     */
    @Nullable
    private BlockEntity reconstruct(BlockPos pos, BlockState state) {
        if (state.is(Blocks.MOVING_PISTON)) {
            return new PistonMovingBlockEntity(pos, state);
        }
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            return null;
        }
        return entityBlock.newBlockEntity(pos, state);
    }

    /** Per-tick transform update: shift current to previous, adopt the new current (drives interpolation). */
    public void updateTransform(AssemblyTransform transform) {
        this.previousTransform = this.currentTransform;
        this.currentTransform = transform;
        this.transformUpdatedThisTick = true;
    }

    private final RandomSource animateRandom = RandomSource.create();
    private final BlockPos.MutableBlockPos animateScratch = new BlockPos.MutableBlockPos();

    /**
     * Ambient block particles (torch flames, campfire smoke, brewing-stand bubbles, water drips).
     * Vanilla drives these from {@code Minecraft.tick()} calling {@code ClientLevel#animateTick(playerBlockX,
     * Y, Z)} once per client tick, which samples 667 random cells within a radius-16 cube AND another
     * 667 within a radius-32 cube centred on the PLAYER, and animate-ticks whichever block happens to
     * land there (@code ClientLevel.animateTick}/{@code doAnimateTick}, read from decompiled source).
     * This is a straight port of that exact algorithm — same sample count, same two radii, same
     * per-position odds — just centred on the player's position mapped into the assembly's LOCAL
     * space instead of world space. Matching the real algorithm (rather than a "total budget over the
     * assembly's block count", which was tried first and was wrong) is what makes a single campfire
     * flicker at the correct, real-world-matching rate regardless of how big or small the assembly is:
     * vanilla's odds are per-position, not per-structure.
     */
    private void animateBlocks() {
        if (blocks.isEmpty()) {
            return;
        }
        Level realLevel = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        if (realLevel == null || player == null) {
            return;
        }
        Vec3 local = currentTransform.worldToLocal(player.position());
        int cx = net.minecraft.util.Mth.floor(local.x);
        int cy = net.minecraft.util.Mth.floor(local.y);
        int cz = net.minecraft.util.Mth.floor(local.z);
        for (int i = 0; i < 667; i++) {
            sampleAndAnimate(cx, cy, cz, 16);
            sampleAndAnimate(cx, cy, cz, 32);
        }
    }

    private void sampleAndAnimate(int cx, int cy, int cz, int radius) {
        int x = cx + animateRandom.nextInt(radius) - animateRandom.nextInt(radius);
        int y = cy + animateRandom.nextInt(radius) - animateRandom.nextInt(radius);
        int z = cz + animateRandom.nextInt(radius) - animateRandom.nextInt(radius);
        animateScratch.set(x, y, z);
        BlockState state = blocks.get(animateScratch);
        if (state == null) {
            return;
        }
        BlockPos pos = animateScratch.immutable();
        state.getBlock().animateTick(state, beLevel, pos, animateRandom);
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            fluid.animateTick(beLevel, pos, animateRandom);
        }
    }

    /** Client-tick every reconstructed block entity via its client ticker (mirrors the server tick). */
    public void tick() {
        // Mirror the server's settleTransform: if no transform packet advanced us this tick, the
        // assembly is idle, so collapse previous -> current (like a vanilla entity's xo = x). Without
        // this a mirror that stopped receiving transform packets (assembly came to rest) keeps a stale
        // previous pose, and the client-side entity-collision substep loop smears the block boxes
        // across the gap to that stale pose -- phantom "air" collision trailing the assembly.
        if (!transformUpdatedThisTick) {
            this.previousTransform = this.currentTransform;
        }
        transformUpdatedThisTick = false;

        animateBlocks();
        for (TickingBE ticking : getTickers()) {
            BlockEntity be = ticking.be();
            BlockState state = be.getBlockState();
            if (be.isRemoved() || !be.getType().isValid(state)) {
                continue;
            }
            try {
                ticking.ticker().tick(beLevel, ticking.pos(), state, be);
            } catch (Exception e) {
                AssemblyLib.LOGGER.error("Assembly client block entity at {} threw while ticking", ticking.pos(), e);
            }
        }
    }

    private List<TickingBE> getTickers() {
        if (tickers == null || tickersDirty) {
            rebuildTickers();
        }
        return tickers;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rebuildTickers() {
        List<TickingBE> rebuilt = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
            BlockEntity be = entry.getValue();
            BlockState state = be.getBlockState();
            if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
                continue;
            }
            BlockEntityTicker ticker = entityBlock.getTicker(beLevel, state, (BlockEntityType) be.getType());
            if (ticker == null) {
                continue;
            }
            rebuilt.add(new TickingBE(entry.getKey(), be, ticker));
        }
        tickers = rebuilt;
        tickersDirty = false;
    }

    public void dispose() {
        mesh.dispose();
        for (BlockEntity be : blockEntities.values()) {
            be.setRemoved();
        }
        blockEntities.clear();
    }
}
