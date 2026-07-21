package com.assemblylib.impl.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Palette-based (de)serialization for a list of {@link AssemblyBlock}s.
 *
 * <p>Instead of writing a full block-state compound per block (which makes large
 * regions blow past the 2&nbsp;MB network NBT limit and bloats disk saves), distinct
 * block states are deduplicated into a palette and each block stores only a small
 * int index into it. This is the same approach vanilla {@code StructureTemplate} uses.</p>
 *
 * <p>Layout:</p>
 * <pre>
 *   palette : ListTag&lt;CompoundTag&gt;   // distinct block states, via NbtUtils.writeBlockState
 *   pos     : long[]                  // BlockPos.asLong per block
 *   state   : int[]                   // palette index per block (parallel to pos)
 *   be      : ListTag&lt;CompoundTag&gt;   // sparse block-entity data: { i:int, data:CompoundTag }
 * </pre>
 */
public final class AssemblyCodec {

    private AssemblyCodec() {}

    /** Serializes blocks into a palette-backed compound. */
    public static CompoundTag save(List<AssemblyBlock> blocks) {
        CompoundTag tag = new CompoundTag();

        ListTag palette = new ListTag();
        Map<BlockState, Integer> paletteIds = new HashMap<>();

        long[] positions = new long[blocks.size()];
        int[] stateIds = new int[blocks.size()];
        ListTag blockEntities = new ListTag();

        for (int i = 0; i < blocks.size(); i++) {
            AssemblyBlock block = blocks.get(i);

            Integer id = paletteIds.get(block.state());
            if (id == null) {
                id = palette.size();
                paletteIds.put(block.state(), id);
                palette.add(NbtUtils.writeBlockState(block.state()));
            }

            positions[i] = block.relativePos().asLong();
            stateIds[i] = id;

            if (block.blockEntityData() != null) {
                CompoundTag be = new CompoundTag();
                be.putInt("i", i);
                be.put("data", block.blockEntityData());
                blockEntities.add(be);
            }
        }

        tag.put("palette", palette);
        tag.put("pos", new LongArrayTag(positions));
        tag.put("state", new IntArrayTag(stateIds));
        if (!blockEntities.isEmpty()) {
            tag.put("be", blockEntities);
        }
        return tag;
    }

    /** Deserializes blocks from a palette-backed compound produced by {@link #save}. */
    public static List<AssemblyBlock> load(CompoundTag tag, HolderLookup.Provider registries) {
        var blockLookup = registries.lookupOrThrow(Registries.BLOCK);

        ListTag palette = tag.getList("palette", Tag.TAG_COMPOUND);
        BlockState[] states = new BlockState[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            states[i] = NbtUtils.readBlockState(blockLookup, palette.getCompound(i));
        }

        long[] positions = tag.getLongArray("pos");
        int[] stateIds = tag.getIntArray("state");

        // Block-entity data keyed by block index (sparse).
        Map<Integer, CompoundTag> beByIndex = new HashMap<>();
        ListTag blockEntities = tag.getList("be", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag be = blockEntities.getCompound(i);
            beByIndex.put(be.getInt("i"), be.getCompound("data"));
        }

        int count = Math.min(positions.length, stateIds.length);
        List<AssemblyBlock> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            blocks.add(new AssemblyBlock(
                    BlockPos.of(positions[i]),
                    states[stateIds[i]],
                    beByIndex.get(i)
            ));
        }
        return blocks;
    }
}
