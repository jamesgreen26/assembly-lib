package com.assemblylib.impl.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class Assembly {

    protected final List<AssemblyBlock> blocks;

    public Assembly(List<AssemblyBlock> blocks) {
        this.blocks = blocks;
    }

    public List<AssemblyBlock> getBlocks() {
        return blocks;
    }

    public static Assembly capture(Level level, BlockPos min, BlockPos max) {
        return capture(level, min, max, state -> true);
    }

    /**
     * Captures all non-air blocks (and any block entity data) in the region for
     * which {@code filter} returns true. Use {@link #blacklist} / {@link #whitelist}
     * for common cases.
     */
    public static Assembly capture(Level level, BlockPos min, BlockPos max, Predicate<BlockState> filter) {
        List<AssemblyBlock> blocks = new ArrayList<>();

        int originX = (int) Math.floor(min.getX() + (max.getX() - min.getX() + 1) / 2.0);
        int originY = (int) Math.floor(min.getY() + (max.getY() - min.getY()) / 2.0);
        int originZ = (int) Math.floor(min.getZ() + (max.getZ() - min.getZ() + 1) / 2.0);
        BlockPos origin = new BlockPos(originX, originY, originZ);

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !filter.test(state)) continue;

            CompoundTag blockEntityData = null;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                blockEntityData = blockEntity.saveWithoutMetadata(level.registryAccess());
            }

            blocks.add(new AssemblyBlock(pos.subtract(origin), state, blockEntityData));
        }

        return new Assembly(blocks);
    }

    /** Capture everything except the given blocks. */
    public static Predicate<BlockState> blacklist(Block... excluded) {
        Set<Block> set = Set.of(excluded);
        return state -> !set.contains(state.getBlock());
    }

    /** Capture only the given blocks. */
    public static Predicate<BlockState> whitelist(Block... included) {
        Set<Block> set = Set.of(included);
        return state -> set.contains(state.getBlock());
    }

    public void removeBlocks(Level level, BlockPos min, BlockPos max) {
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /** Pastes this assembly into the world, with {@code origin} as the position of (0,0,0). */
    public void place(Level level, BlockPos origin) {
        place(level, origin, 3);
    }

    public void place(Level level, BlockPos origin, int updateFlags) {
        HolderLookup.Provider registries = level.registryAccess();

        for (AssemblyBlock block : blocks) {
            BlockPos pos = origin.offset(block.relativePos());
            level.setBlock(pos, block.state(), updateFlags);

            if (block.blockEntityData() != null) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    blockEntity.loadWithComponents(block.blockEntityData(), registries);
                    blockEntity.setChanged();
                }
            }
        }
    }

    /** Returns a new Assembly with every block rotated around the assembly's origin. */
    public Assembly rotate(Rotation rotation) {
        List<AssemblyBlock> rotated = new ArrayList<>(blocks.size());
        for (AssemblyBlock block : blocks) {
            rotated.add(new AssemblyBlock(
                    rotatePos(block.relativePos(), rotation),
                    block.state().rotate(rotation),
                    block.blockEntityData()
            ));
        }
        return new Assembly(rotated);
    }

    /** Returns a new Assembly with every block mirrored around the assembly's origin. */
    public Assembly mirror(Mirror mirror) {
        List<AssemblyBlock> mirrored = new ArrayList<>(blocks.size());
        for (AssemblyBlock block : blocks) {
            mirrored.add(new AssemblyBlock(
                    mirrorPos(block.relativePos(), mirror),
                    block.state().mirror(mirror),
                    block.blockEntityData()
            ));
        }
        return new Assembly(mirrored);
    }

    private static BlockPos rotatePos(BlockPos pos, Rotation rotation) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return switch (rotation) {
            case NONE -> pos;
            case CLOCKWISE_90 -> new BlockPos(z, y, -x);
            case CLOCKWISE_180 -> new BlockPos(-x, y, -z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(-z, y, x);
        };
    }

    private static BlockPos mirrorPos(BlockPos pos, Mirror mirror) {
        return switch (mirror) {
            case NONE -> pos;
            case FRONT_BACK -> new BlockPos(-pos.getX(), pos.getY(), pos.getZ());
            case LEFT_RIGHT -> new BlockPos(pos.getX(), pos.getY(), -pos.getZ());
        };
    }
}