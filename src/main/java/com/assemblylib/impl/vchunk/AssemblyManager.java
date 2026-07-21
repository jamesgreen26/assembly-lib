package com.assemblylib.impl.vchunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

/**
 * Per-{@code ServerLevel} owner of every assembly in that level. Responsibilities:
 * <ul>
 *   <li><b>Registry</b> (persisted via {@link SavedData}): the {@link Assembly} objects, their slot
 *       allocation, and runtime handles.</li>
 *   <li><b>Resident chunks</b> (transient): the hand-built {@link LevelChunk}s that back assembly
 *       block storage, built lazily and handed to vanilla via the chunk-read interception mixins.</li>
 *   <li><b>Ticking</b>: drives assembly block-entity ticking each server tick (vanilla won't, since
 *       these chunks have no {@code ChunkHolder}).</li>
 * </ul>
 *
 * <p>A static {@link #ACTIVE} map lets the chunk-interception mixin reach the manager for a level
 * without triggering {@code computeIfAbsent} re-entrantly during chunk operations.
 */
public final class AssemblyManager extends SavedData {

    private static final String DATA_NAME = "assemblylib_assemblies";

    /** Read-only lookup for mixins / client-agnostic code. Populated by {@link #get}, cleared on unload. */
    private static final Map<ResourceKey<Level>, AssemblyManager> ACTIVE = new ConcurrentHashMap<>();

    private final ServerLevel level;
    private final Map<UUID, Assembly> assemblies = new HashMap<>();
    private final Int2ObjectMap<Assembly> byHandle = new Int2ObjectOpenHashMap<>();
    /**
     * Slot → assembly index. Load-bearing for performance: {@code assemblyForChunk} runs on every
     * {@code sendBlockUpdated}/effect-redirect for an assembly position, so it must be O(1), not a
     * scan over all assemblies.
     */
    private final Int2ObjectMap<Assembly> bySlotIndex = new Int2ObjectOpenHashMap<>();
    /** Resident hand-built chunks, keyed by {@link ChunkPos#toLong()}. Not persisted (Milestone 1). */
    private final Long2ObjectMap<LevelChunk> residentChunks = new Long2ObjectOpenHashMap<>();

    private int nextHandle = 1;
    private int nextSlot = 0;
    private final TreeSet<Integer> freeSlots = new TreeSet<>();

    /**
     * How many individually-tracked changed blocks an assembly can accumulate in one sync window
     * before the sync layer falls back to a full snapshot instead of a per-block diff.
     */
    private static final int DIFF_LIMIT = 64;

    /**
     * Per-assembly changed LOCAL block positions this tick, for incremental client sync. A null value
     * means "too many / unknown — full resync". Replaces the old whole-assembly dirty flag so that a
     * single block change (a furnace's lit flag flipping) syncs one block, not the whole assembly.
     */
    private final Int2ObjectMap<java.util.Set<net.minecraft.core.BlockPos>> dirtyBlocks = new Int2ObjectOpenHashMap<>();
    /** Assembly handles that unloaded this check cycle (for the sync layer to tell clients to drop them). */
    private final java.util.Set<Integer> justUnloaded = new java.util.HashSet<>();
    /** Cached LOCAL-space bounds per assembly handle; null value means empty assembly. */
    private final Map<Integer, net.minecraft.world.phys.AABB> boundsCache = new HashMap<>();

    private AssemblyManager(ServerLevel level) {
        this.level = level;
    }

    // ---- access ----

    /** Get (creating if needed) the manager for a level, and register it in {@link #ACTIVE}. */
    public static AssemblyManager get(ServerLevel level) {
        AssemblyManager manager = level.getDataStorage().computeIfAbsent(
            new Factory<>(() -> new AssemblyManager(level), (tag, provider) -> load(level, tag), null),
            DATA_NAME);
        ACTIVE.put(level.dimension(), manager);
        return manager;
    }

    /** Read-only lookup with no side effects; returns null if no manager is active for the level yet. */
    @Nullable
    public static AssemblyManager active(Level level) {
        return ACTIVE.get(level.dimension());
    }

    public static void deactivate(ServerLevel level) {
        ACTIVE.remove(level.dimension());
    }

    public ServerLevel level() {
        return level;
    }

    public Collection<Assembly> assemblies() {
        return assemblies.values();
    }

    @Nullable
    public Assembly byHandle(int handle) {
        return byHandle.get(handle);
    }

    @Nullable
    public Assembly byUuid(UUID uuid) {
        return assemblies.get(uuid);
    }

    @Nullable
    public Assembly bySlot(int slot) {
        return bySlotIndex.get(slot);
    }

    /** The assembly that owns the tile a given chunk falls in, or null. */
    @Nullable
    public Assembly assemblyForChunk(ChunkPos pos) {
        if (!AssemblySpace.isAssemblyChunk(pos)) {
            return null;
        }
        int slot = AssemblySpace.slotForChunk(pos);
        return slot < 0 ? null : bySlot(slot);
    }

    // ---- lifecycle ----

    public Assembly createAssembly() {
        UUID uuid = UUID.randomUUID();
        int handle = nextHandle++;
        int slot = allocSlot();
        Vec3 worldOrigin = Vec3.atLowerCornerOf(AssemblySpace.tileOrigin(slot));
        Assembly assembly = new Assembly(new AssemblyId(uuid, handle), slot, AssemblyTransform.identity(worldOrigin));
        assemblies.put(uuid, assembly);
        byHandle.put(handle, assembly);
        bySlotIndex.put(slot, assembly);
        setDirty();
        return assembly;
    }

    public void deleteAssembly(Assembly assembly) {
        assemblies.remove(assembly.id().uuid());
        byHandle.remove(assembly.id().handle());
        bySlotIndex.remove(assembly.slot());
        dirtyBlocks.remove(assembly.id().handle());
        justUnloaded.remove(assembly.id().handle());
        // discard the assembly's resident chunks, unregistering them from vanilla ticking first
        for (LevelChunk chunk : residentChunksOf(assembly.slot())) {
            chunk.unregisterTickContainerFromLevel(level);
            chunk.setLoaded(false);
            residentChunks.remove(chunk.getPos().toLong());
        }
        boundsCache.remove(assembly.id().handle());
        freeSlots.add(assembly.slot());
        setDirty();
    }

    private int allocSlot() {
        if (!freeSlots.isEmpty()) {
            return freeSlots.pollFirst();
        }
        return nextSlot++;
    }

    // ---- resident chunks ----

    /** Return the resident chunk for {@code pos}, building a fresh empty one if absent. */
    public LevelChunk getOrBuildChunk(ChunkPos pos) {
        long key = pos.toLong();
        LevelChunk chunk = residentChunks.get(key);
        if (chunk == null) {
            chunk = AssemblyChunkSource.buildEmptyChunk(level, pos);
            // Make the chunk a genuine, ticking part of the level so vanilla drives it exactly like a
            // real chunk: block entities, scheduled block/fluid ticks, redstone and neighbour updates
            // all run through vanilla's own global loops (gated by AssemblyServerLevelMixin /
            // AssemblyWorldBorderMixin). No manual ticking.
            chunk.setLoaded(true);
            // FULL is *before* BLOCK_TICKING in FullChunkStatus, so a default chunk fails
            // LevelChunk.isTicking(); promote it to ENTITY_TICKING so vanilla ticks its block entities.
            chunk.setFullStatus(() -> net.minecraft.server.level.FullChunkStatus.ENTITY_TICKING);
            chunk.registerTickContainerInLevel(level);
            residentChunks.put(key, chunk);
            // Any incidental access that builds a chunk for a not-yet-loaded assembly (e.g. a stray
            // requireChunk=true read) is folded back into the normal load/unload bookkeeping so the
            // next distance check manages it instead of leaking a permanently-resident empty chunk.
            Assembly owner = bySlot(AssemblySpace.slotForChunk(pos));
            if (owner != null && !owner.isLoaded()) {
                owner.setLoaded(true);
            }
        }
        return chunk;
    }

    /** Return the resident chunk for {@code pos} if one exists, else null (no build). */
    @Nullable
    public LevelChunk getResidentChunk(ChunkPos pos) {
        return residentChunks.get(pos.toLong());
    }

    public boolean hasResidentChunk(ChunkPos pos) {
        return residentChunks.containsKey(pos.toLong());
    }

    /**
     * Snapshot of every currently-resident chunk (across all loaded assemblies). Unloaded assemblies
     * have zero resident chunks (removed by {@link #unloadAssembly}), so this is already implicitly
     * "loaded assemblies only" with no extra filtering needed.
     */
    public List<LevelChunk> allResidentChunks() {
        return new ArrayList<>(residentChunks.values());
    }

    /** All resident chunks belonging to the tile of {@code slot}. */
    public List<LevelChunk> residentChunksOf(int slot) {
        List<LevelChunk> out = new ArrayList<>();
        for (Long2ObjectMap.Entry<LevelChunk> e : residentChunks.long2ObjectEntrySet()) {
            if (AssemblySpace.slotForChunk(new ChunkPos(e.getLongKey())) == slot) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** Mark an assembly's whole content as changed (full resync — bulk edits, load hydration). */
    public void markContentDirty(ChunkPos pos) {
        Assembly a = assemblyForChunk(pos);
        if (a != null) {
            dirtyBlocks.put(a.id().handle(), null);
            boundsCache.remove(a.id().handle());
            // A resident assembly's blocks live only in hand-built chunks with no ChunkHolder, so
            // vanilla's chunk saver never touches them — this SavedData is their ONLY path to disk.
            // Mark it dirty on every content change or the edit is dropped at the next world save.
            setDirty();
        }
    }

    /**
     * Mark ONE changed block for incremental sync (the {@code sendBlockUpdated} chokepoint's path):
     * up to {@link #DIFF_LIMIT} positions accumulate per tick and go out as a per-block diff packet;
     * beyond that the entry degrades to a full-snapshot resync.
     */
    public void markBlockDirty(net.minecraft.core.BlockPos absolutePos) {
        Assembly a = assemblyForChunk(new ChunkPos(absolutePos));
        if (a == null) {
            return;
        }
        // Persist the edit: assembly blocks reach disk only through this SavedData (their chunks have
        // no ChunkHolder for vanilla to save), so an unmarked change is lost at the next world save.
        setDirty();
        int handle = a.id().handle();
        boundsCache.remove(handle);
        if (dirtyBlocks.containsKey(handle)) {
            java.util.Set<net.minecraft.core.BlockPos> set = dirtyBlocks.get(handle);
            if (set == null) {
                return; // already flagged for full resync
            }
            set.add(AssemblySpace.absoluteToLocal(a.slot(), absolutePos));
            if (set.size() > DIFF_LIMIT) {
                dirtyBlocks.put(handle, null);
            }
        } else {
            java.util.Set<net.minecraft.core.BlockPos> set = new java.util.HashSet<>();
            set.add(AssemblySpace.absoluteToLocal(a.slot(), absolutePos));
            dirtyBlocks.put(handle, set);
        }
    }

    public void invalidateBounds(Assembly assembly) {
        boundsCache.remove(assembly.id().handle());
    }

    /** The block state at a LOCAL position within an assembly (air if outside any resident chunk). */
    public net.minecraft.world.level.block.state.BlockState blockStateLocal(Assembly assembly, net.minecraft.core.BlockPos local) {
        net.minecraft.core.BlockPos abs = AssemblySpace.localToAbsolute(assembly.slot(), local);
        LevelChunk chunk = getResidentChunk(new ChunkPos(abs));
        return chunk == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : chunk.getBlockState(abs);
    }

    /** Cached AABB (in LOCAL space) enclosing an assembly's non-air blocks, or null if empty. */
    @Nullable
    public net.minecraft.world.phys.AABB localBlockBounds(Assembly assembly) {
        if (boundsCache.containsKey(assembly.id().handle())) {
            return boundsCache.get(assembly.id().handle());
        }
        net.minecraft.world.phys.AABB bounds = computeLocalBounds(assembly);
        boundsCache.put(assembly.id().handle(), bounds);
        return bounds;
    }

    /**
     * Visit every non-air block in an assembly's resident chunks, skipping all-air chunk sections
     * entirely. This is the shared fast path for bounds computation, content save, and snapshot
     * building: a typical assembly occupies 1-2 of a chunk's ~24 sections, so skipping empty sections
     * turns a ~98k-cell column scan per chunk into a ~4-8k-cell one.
     */
    public void forEachNonAirBlock(int slot, java.util.function.BiConsumer<net.minecraft.core.BlockPos, BlockState> visitor) {
        net.minecraft.core.BlockPos.MutableBlockPos abs = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (LevelChunk chunk : residentChunksOf(slot)) {
            ChunkPos cp = chunk.getPos();
            int baseX = cp.getMinBlockX();
            int baseZ = cp.getMinBlockZ();
            net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
            for (int si = 0; si < sections.length; si++) {
                net.minecraft.world.level.chunk.LevelChunkSection section = sections[si];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                int baseY = net.minecraft.core.SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(si));
                for (int dy = 0; dy < 16; dy++) {
                    for (int dx = 0; dx < 16; dx++) {
                        for (int dz = 0; dz < 16; dz++) {
                            BlockState state = section.getBlockState(dx, dy, dz);
                            if (state.isAir()) {
                                continue;
                            }
                            visitor.accept(abs.set(baseX + dx, baseY + dy, baseZ + dz), state);
                        }
                    }
                }
            }
        }
    }

    @Nullable
    private net.minecraft.world.phys.AABB computeLocalBounds(Assembly assembly) {
        net.minecraft.core.BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());
        int[] min = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE };
        int[] max = { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        forEachNonAirBlock(assembly.slot(), (abs, state) -> {
            int lx = abs.getX() - origin.getX();
            int ly = abs.getY() - origin.getY();
            int lz = abs.getZ() - origin.getZ();
            min[0] = Math.min(min[0], lx); min[1] = Math.min(min[1], ly); min[2] = Math.min(min[2], lz);
            max[0] = Math.max(max[0], lx); max[1] = Math.max(max[1], ly); max[2] = Math.max(max[2], lz);
        });
        if (min[0] == Integer.MAX_VALUE) {
            return null;
        }
        return new net.minecraft.world.phys.AABB(min[0], min[1], min[2], max[0] + 1, max[1] + 1, max[2] + 1);
    }

    /** A server-side {@link com.assemblylib.impl.vchunk.collision.AssemblyLocalBlocks} backed by real chunks. */
    public com.assemblylib.impl.vchunk.collision.AssemblyLocalBlocks serverBlocks(Assembly assembly) {
        net.minecraft.world.phys.AABB bounds = localBlockBounds(assembly);
        boolean empty = bounds == null;
        return new com.assemblylib.impl.vchunk.collision.AssemblyLocalBlocks() {
            @Override
            public net.minecraft.world.level.block.state.BlockState getLocal(net.minecraft.core.BlockPos local) {
                return blockStateLocal(assembly, local);
            }

            @Override
            public boolean isEmpty() {
                return empty;
            }
        };
    }

    /**
     * Drain this tick's dirty state: handle → changed LOCAL positions, or null value meaning
     * "full resync required". Empty map when nothing changed.
     */
    public Map<Integer, java.util.Set<net.minecraft.core.BlockPos>> drainDirtyContent() {
        if (dirtyBlocks.isEmpty()) {
            return Map.of();
        }
        Map<Integer, java.util.Set<net.minecraft.core.BlockPos>> copy = new HashMap<>(dirtyBlocks);
        dirtyBlocks.clear();
        return copy;
    }

    /** Assembly handles that unloaded this check cycle (for the sync layer to tell clients to drop them). */
    public java.util.Set<Integer> drainJustUnloaded() {
        if (justUnloaded.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<Integer> copy = java.util.Set.copyOf(justUnloaded);
        justUnloaded.clear();
        return copy;
    }

    // ---- load/unload distance gating ----

    /**
     * Load assemblies within {@code loadDistance} of any player (rehydrating their persisted content
     * into resident, vanilla-ticking chunks) and unload ones beyond {@code unloadDistance} (persisting
     * their content and discarding resident chunks). Only <em>loaded</em> assemblies cost anything —
     * an unloaded assembly is a handful of bytes (id/transform/footprint) plus its serialized block
     * content sitting in memory, with zero chunks, zero tickers, zero per-tick work.
     */
    public void updateLoadState(java.util.List<net.minecraft.server.level.ServerPlayer> players, double loadDistance, double unloadDistance) {
        if (players.isEmpty()) {
            // No one online to measure distance against (dedicated server startup window, gametests,
            // a brief gap between players) -- leave everything as-is rather than mass-unloading.
            return;
        }
        double loadSqr = loadDistance * loadDistance;
        double unloadSqr = unloadDistance * unloadDistance;
        for (Assembly assembly : assemblies.values()) {
            double nearestSqr = nearestPlayerDistSqr(assembly, players);
            if (!assembly.isLoaded()) {
                if (nearestSqr <= loadSqr) {
                    loadAssembly(assembly);
                }
            } else if (nearestSqr > unloadSqr) {
                unloadAssembly(assembly);
            }
        }
    }

    private double nearestPlayerDistSqr(Assembly assembly, Iterable<net.minecraft.server.level.ServerPlayer> players) {
        Vec3 pos = assembly.currentTransform().translation();
        double best = Double.MAX_VALUE;
        for (net.minecraft.server.level.ServerPlayer player : players) {
            double d = player.position().distanceToSqr(pos);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    private void loadAssembly(Assembly assembly) {
        if (assembly.hasPendingContent()) {
            applyContent(assembly, assembly.takePendingContent());
        }
        invalidateBounds(assembly);
        assembly.setLoaded(true);
        dirtyBlocks.put(assembly.id().handle(), null); // full resync on (re)load
    }

    private void unloadAssembly(Assembly assembly) {
        CompoundTag content = saveContent(assembly, level.registryAccess());
        assembly.setPendingContent(content);
        for (LevelChunk chunk : residentChunksOf(assembly.slot())) {
            chunk.unregisterTickContainerFromLevel(level);
            chunk.setLoaded(false);
            residentChunks.remove(chunk.getPos().toLong());
        }
        boundsCache.remove(assembly.id().handle());
        assembly.setLoaded(false);
        justUnloaded.add(assembly.id().handle());
        setDirty();
    }

    private void applyContent(Assembly assembly, CompoundTag content) {
        HolderLookup.Provider registries = level.registryAccess();
        ListTag blocks = content.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompound(i);
            int[] lp = entry.getIntArray("p");
            net.minecraft.core.BlockPos local = new net.minecraft.core.BlockPos(lp[0], lp[1], lp[2]);
            net.minecraft.core.BlockPos abs = AssemblySpace.localToAbsolute(assembly.slot(), local);
            net.minecraft.world.level.block.state.BlockState state =
                net.minecraft.nbt.NbtUtils.readBlockState(registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), entry.getCompound("s"));
            level.setBlock(abs, state, 3);
            if (entry.contains("e")) {
                net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(abs);
                if (be != null) {
                    be.loadWithComponents(entry.getCompound("e"), registries);
                }
            }
        }
    }

    private CompoundTag saveContent(Assembly assembly, HolderLookup.Provider registries) {
        net.minecraft.core.BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());
        ListTag blocks = new ListTag();
        forEachNonAirBlock(assembly.slot(), (abs, state) -> {
            CompoundTag entry = new CompoundTag();
            entry.putIntArray("p", new int[] { abs.getX() - origin.getX(), abs.getY() - origin.getY(), abs.getZ() - origin.getZ() });
            entry.put("s", net.minecraft.nbt.NbtUtils.writeBlockState(state));
            if (state.hasBlockEntity()) {
                net.minecraft.world.level.block.entity.BlockEntity be = getResidentChunk(new ChunkPos(abs)).getBlockEntity(abs);
                if (be != null) {
                    entry.put("e", be.saveWithFullMetadata(registries));
                }
            }
            blocks.add(entry);
        });
        CompoundTag content = new CompoundTag();
        content.put("blocks", blocks);
        return content;
    }

    // ---- persistence ----

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("nextHandle", nextHandle);
        tag.putInt("nextSlot", nextSlot);
        int[] free = freeSlots.stream().mapToInt(Integer::intValue).toArray();
        tag.putIntArray("freeSlots", free);
        ListTag list = new ListTag();
        for (Assembly a : assemblies.values()) {
            CompoundTag entry = a.save();
            // CRITICAL: an unloaded assembly has NO resident chunks — serializing "from chunks" would
            // write an empty content list over its real content (which lives in pendingContent),
            // silently deleting the assembly's blocks on world save.
            if (!a.isLoaded() && a.hasPendingContent()) {
                entry.put("content", a.peekPendingContent());
            } else {
                entry.put("content", saveContent(a, registries));
            }
            list.add(entry);
        }
        tag.put("assemblies", list);
        return tag;
    }

    private static AssemblyManager load(ServerLevel level, CompoundTag tag) {
        AssemblyManager manager = new AssemblyManager(level);
        manager.nextHandle = tag.getInt("nextHandle");
        manager.nextSlot = tag.getInt("nextSlot");
        for (int slot : tag.getIntArray("freeSlots")) {
            manager.freeSlots.add(slot);
        }
        ListTag list = tag.getList("assemblies", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            Assembly a = Assembly.load(entry);
            if (entry.contains("content")) {
                a.setPendingContent(entry.getCompound("content"));
            }
            manager.assemblies.put(a.id().uuid(), a);
            manager.byHandle.put(a.id().handle(), a);
            manager.bySlotIndex.put(a.slot(), a);
        }
        if (manager.nextHandle <= 0) {
            manager.nextHandle = 1;
        }
        return manager;
    }
}
