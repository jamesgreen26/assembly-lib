package com.assemblylib.debug.vchunk;

import com.assemblylib.impl.vchunk.Assembly;
import com.assemblylib.impl.vchunk.AssemblyManager;
import com.assemblylib.impl.vchunk.AssemblySpace;
import com.assemblylib.impl.vchunk.AssemblyTransform;
import com.assemblylib.impl.vchunk.net.AssemblyNetwork;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import org.joml.Quaternionf;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Debug command for manually exercising the real-coordinate assembly engine:
 * {@code /assemblylib assembly create|delete|tp|list|resync}.
 *
 * <ul>
 *   <li><b>create</b> — make an assembly, build a small demo structure (stone platform + furnace +
 *       chest + hopper + glowstone) into its real ship-space chunks, and make it appear at the
 *       player's position.</li>
 *   <li><b>tp</b> — teleport to the assembly's REAL far-away coordinates to inspect/stand on the
 *       actual blocks (verifies the chunk-bypass directly, no transform involved).</li>
 * </ul>
 */
public final class AssemblyDebugCommand {

    private AssemblyDebugCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("assemblylib")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("assembly")
                .then(Commands.literal("create").executes(AssemblyDebugCommand::create))
                .then(Commands.literal("list").executes(AssemblyDebugCommand::list))
                .then(Commands.literal("delete")
                    .then(Commands.argument("handle", IntegerArgumentType.integer(1))
                        .executes(AssemblyDebugCommand::delete)))
                .then(Commands.literal("tp")
                    .then(Commands.argument("handle", IntegerArgumentType.integer(1))
                        .executes(AssemblyDebugCommand::tp)))
                .then(Commands.literal("move")
                    .then(Commands.argument("handle", IntegerArgumentType.integer(1))
                        .then(Commands.argument("vx", DoubleArgumentType.doubleArg())
                            .then(Commands.argument("vy", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("vz", DoubleArgumentType.doubleArg())
                                    .executes(AssemblyDebugCommand::move))))))
                .then(Commands.literal("spin")
                    .then(Commands.argument("handle", IntegerArgumentType.integer(1))
                        .then(Commands.argument("axis", StringArgumentType.word())
                            .then(Commands.argument("degPerTick", DoubleArgumentType.doubleArg())
                                .executes(AssemblyDebugCommand::spin)))))
                .then(Commands.literal("stop")
                    .then(Commands.argument("handle", IntegerArgumentType.integer(1))
                        .executes(AssemblyDebugCommand::stop)))
                .then(Commands.literal("resync").executes(AssemblyDebugCommand::resync))
                .then(Commands.literal("probe")
                    .then(Commands.argument("handle", IntegerArgumentType.integer(1))
                        .executes(AssemblyDebugCommand::probe)))));
    }

    private static int create(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        AssemblyManager manager = AssemblyManager.get(level);

        Assembly assembly = manager.createAssembly();
        // Make it appear where the player is standing (it is really stored ~25M blocks away).
        assembly.resetTransform(AssemblyTransform.identity(player.position()));

        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());
        buildDemoStructure(level, origin);
        manager.invalidateBounds(assembly);
        AssemblyNetwork.broadcastSnapshot(manager, assembly);

        ctx.getSource().sendSuccess(() -> Component.literal(
            "Created assembly handle " + assembly.id().handle() + " (slot " + assembly.slot()
                + ") at real " + origin.getX() + "," + origin.getY() + "," + origin.getZ()
                + " — appearing at your position."), true);
        return 1;
    }

    private static void buildDemoStructure(ServerLevel level, BlockPos origin) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                level.setBlock(origin.offset(dx, 0, dz), stone, 3);
            }
        }
        level.setBlock(origin.offset(2, 1, 2), Blocks.FURNACE.defaultBlockState(), 3);
        level.setBlock(origin.offset(1, 1, 2), Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(origin.offset(3, 1, 2), Blocks.HOPPER.defaultBlockState(), 3);
        level.setBlock(origin.offset(0, 1, 0), Blocks.GLOWSTONE.defaultBlockState(), 3);
        level.setBlock(origin.offset(4, 1, 4), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
    }

    private static int list(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = ctx.getSource().getPlayerOrException().serverLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        if (manager.assemblies().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No assemblies in this level."), false);
            return 0;
        }
        for (Assembly a : manager.assemblies()) {
            Vec3 t = a.currentTransform().translation();
            ctx.getSource().sendSuccess(() -> Component.literal(
                "#" + a.id().handle() + " slot " + a.slot() + " appears at "
                    + String.format("%.1f, %.1f, %.1f", t.x, t.y, t.z)), false);
        }
        return 1;
    }

    private static int delete(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = ctx.getSource().getPlayerOrException().serverLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        int handle = IntegerArgumentType.getInteger(ctx, "handle");
        Assembly a = manager.byHandle(handle);
        if (a == null) {
            ctx.getSource().sendFailure(Component.literal("No assembly #" + handle));
            return 0;
        }
        manager.deleteAssembly(a);
        AssemblyNetwork.broadcastRemove(a);
        ctx.getSource().sendSuccess(() -> Component.literal("Deleted assembly #" + handle), true);
        return 1;
    }

    private static int tp(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        AssemblyManager manager = AssemblyManager.get(player.serverLevel());
        int handle = IntegerArgumentType.getInteger(ctx, "handle");
        Assembly a = manager.byHandle(handle);
        if (a == null) {
            ctx.getSource().sendFailure(Component.literal("No assembly #" + handle));
            return 0;
        }
        BlockPos origin = AssemblySpace.tileOrigin(a.slot());
        player.connection.teleport(origin.getX() + 2.5, origin.getY() + 1.0, origin.getZ() + 2.5,
            player.getYRot(), player.getXRot());
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Teleported to the REAL storage of assembly #" + handle + " — you are standing on the actual blocks."), true);
        return 1;
    }

    private static int move(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Assembly a = require(ctx);
        if (a == null) {
            return 0;
        }
        double vx = DoubleArgumentType.getDouble(ctx, "vx");
        double vy = DoubleArgumentType.getDouble(ctx, "vy");
        double vz = DoubleArgumentType.getDouble(ctx, "vz");
        a.setLinearVelocity(new Vec3(vx, vy, vz));
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Assembly #" + a.id().handle() + " linear velocity set to " + vx + ", " + vy + ", " + vz + " /tick"), true);
        return 1;
    }

    private static int spin(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Assembly a = require(ctx);
        if (a == null) {
            return 0;
        }
        String axis = StringArgumentType.getString(ctx, "axis").toLowerCase();
        double deg = DoubleArgumentType.getDouble(ctx, "degPerTick");
        float rad = (float) Math.toRadians(deg);
        Quaternionf step = switch (axis) {
            case "x" -> new Quaternionf().rotateX(rad);
            case "y" -> new Quaternionf().rotateY(rad);
            case "z" -> new Quaternionf().rotateZ(rad);
            default -> null;
        };
        if (step == null) {
            ctx.getSource().sendFailure(Component.literal("Axis must be x, y or z."));
            return 0;
        }
        a.setAngularStep(step);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Assembly #" + a.id().handle() + " spinning " + deg + "°/tick about " + axis), true);
        return 1;
    }

    private static int stop(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Assembly a = require(ctx);
        if (a == null) {
            return 0;
        }
        a.stopMotion();
        ctx.getSource().sendSuccess(() -> Component.literal("Assembly #" + a.id().handle() + " stopped."), true);
        return 1;
    }

    /** Resolve the {@code handle} argument to an assembly in the source's level, or null (with a failure message). */
    private static Assembly require(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = ctx.getSource().getPlayerOrException().serverLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        int handle = IntegerArgumentType.getInteger(ctx, "handle");
        Assembly a = manager.byHandle(handle);
        if (a == null) {
            ctx.getSource().sendFailure(Component.literal("No assembly #" + handle));
        }
        return a;
    }

    /**
     * Diagnostic for a real bug hunt: prints the assembly's REAL server-side non-air block count and
     * local bounds, straight from the resident chunks -- the same data {@code AssemblyEntityCollision}
     * builds SAT candidates from. Compare this to what you can actually SEE. If the count or bounds are
     * bigger than the visible structure, there's real (server-authoritative, collidable) block data
     * beyond what got rendered/synced to the client -- exactly what would explain a never-touched mob
     * hitting invisible collision before it ever reaches the visible mesh.
     */
    private static int probe(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = ctx.getSource().getPlayerOrException().serverLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        int handle = IntegerArgumentType.getInteger(ctx, "handle");
        Assembly a = manager.byHandle(handle);
        if (a == null) {
            ctx.getSource().sendFailure(Component.literal("No assembly #" + handle));
            return 0;
        }

        manager.invalidateBounds(a);
        net.minecraft.world.phys.AABB bounds = manager.localBlockBounds(a);
        int[] count = {0};
        java.util.Map<String, Integer> byBlock = new java.util.TreeMap<>();
        BlockPos origin = AssemblySpace.tileOrigin(a.slot());
        manager.forEachNonAirBlock(a.slot(), (abs, state) -> {
            count[0]++;
            byBlock.merge(state.getBlock().builtInRegistryHolder().key().location().toString(), 1, Integer::sum);
        });

        ctx.getSource().sendSuccess(() -> Component.literal(
            "Assembly #" + handle + " REAL server data: " + count[0] + " non-air blocks, bounds(local)="
                + (bounds == null ? "null" : String.format("[%.0f,%.0f,%.0f -> %.0f,%.0f,%.0f] size %dx%dx%d",
                    bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ,
                    (int) bounds.getXsize(), (int) bounds.getYsize(), (int) bounds.getZsize()))), false);
        for (var e : byBlock.entrySet()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  " + e.getKey() + " x" + e.getValue()), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Real tile origin: " + origin.getX() + "," + origin.getY() + "," + origin.getZ()
                + " (resident chunks: " + manager.residentChunksOf(a.slot()).size() + ")"), false);
        return 1;
    }

    private static int resync(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = ctx.getSource().getPlayerOrException().serverLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        for (Assembly a : manager.assemblies()) {
            AssemblyNetwork.broadcastSnapshot(manager, a);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Resynced " + manager.assemblies().size() + " assemblies."), false);
        return 1;
    }
}
