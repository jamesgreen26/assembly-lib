package com.assemblylib.impl.vchunk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side break/place/use for assembly blocks. Because assembly blocks are REAL blocks in the
 * REAL {@code ServerLevel}, these handlers drive vanilla's own {@code ServerPlayerGameMode} pipeline
 * at the block's true (far-away) coordinates — no reimplementation of loot/placement is needed. The
 * client resolves which assembly block the player is aiming at and sends the LOCAL position; the
 * server maps it to absolute and lets vanilla do the rest.
 */
public final class AssemblyInteractionServer {

    private AssemblyInteractionServer() {}

    public static void handleAttack(ServerPlayer player, int handle, BlockPos local) {
        ServerLevel level = player.serverLevel();
        AssemblyManager manager = AssemblyManager.active(level);
        if (manager == null) {
            return;
        }
        Assembly assembly = manager.byHandle(handle);
        if (assembly == null) {
            return;
        }
        BlockPos abs = AssemblySpace.localToAbsolute(assembly.slot(), local);
        player.gameMode.destroyBlock(abs);
        manager.markContentDirty(new net.minecraft.world.level.ChunkPos(abs));
    }

    public static void handleUse(ServerPlayer player, int handle, BlockPos local, Direction face, Vec3 localHit,
            InteractionHand hand) {
        ServerLevel level = player.serverLevel();
        AssemblyManager manager = AssemblyManager.active(level);
        if (manager == null) {
            return;
        }
        Assembly assembly = manager.byHandle(handle);
        if (assembly == null) {
            return;
        }
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());
        BlockPos abs = AssemblySpace.localToAbsolute(assembly.slot(), local);
        Vec3 absHit = localHit.add(origin.getX(), origin.getY(), origin.getZ());
        BlockHitResult hit = new BlockHitResult(absHit, face, abs, false);
        ItemStack stack = player.getItemInHand(hand);
        player.gameMode.useItemOn(player, level, stack, hand, hit);
        manager.markContentDirty(new net.minecraft.world.level.ChunkPos(abs));
    }
}
