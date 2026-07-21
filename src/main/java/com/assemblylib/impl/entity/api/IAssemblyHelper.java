package com.assemblylib.impl.entity.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import com.assemblylib.impl.entity.api.behavior.AssemblyBehaviorType;
import com.assemblylib.impl.entity.AssemblyEntityTypes;
import com.assemblylib.impl.entity.Assembly;
import com.assemblylib.impl.entity.AssemblyEntity;
import com.assemblylib.impl.entity.net.AssemblySyncPayload;

public interface IAssemblyHelper {

    default void assembleBetween(Level level, BlockPos pos1, BlockPos pos2){
        assembleBetween(level, pos1, pos2, null);
    }

    /** Same as {@link #assembleBetween(Level, BlockPos, BlockPos)}, but attaches a behavior to the result immediately. */
    default void assembleBetween(Level level, BlockPos pos1, BlockPos pos2, AssemblyBehaviorType<?> behaviorType){
        BlockPos min = new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
        );
        BlockPos max = new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
        );

        Assembly assembly = Assembly.capture(level, min, max);

        if (assembly.getBlocks().isEmpty()) return;

        assembly.removeBlocks(level, min, max);

        createAssemblyEntity(level, assembly, min, max, behaviorType);
    }

    default void createAssemblyEntity(Level level, Assembly assembly, BlockPos min, BlockPos max){
        createAssemblyEntity(level, assembly, min, max, null);
    }

    default void createAssemblyEntity(Level level, Assembly assembly, BlockPos min, BlockPos max, AssemblyBehaviorType<?> behaviorType){
        AssemblyEntity entity = AssemblyEntityTypes.ASSEMBLY.get().create(level);
        if (entity == null) return;

        entity.setAssembly(assembly);
        if (behaviorType != null) {
            entity.setBehaviorType(behaviorType);
        }

        double centerX = min.getX() + (max.getX() - min.getX() + 1) / 2.0;
        double centerY = min.getY() + (max.getY() - min.getY()) / 2.0;
        double centerZ = min.getZ() + (max.getZ() - min.getZ() + 1) / 2.0;
        entity.setPos(centerX, centerY, centerZ);

        level.addFreshEntity(entity);

        PacketDistributor.sendToPlayersNear(
                (net.minecraft.server.level.ServerLevel) level,
                null,
                entity.getX(), entity.getY(), entity.getZ(),
                64.0,
                AssemblySyncPayload.of(entity.getId(), assembly.getBlocks())
        );
    }

}