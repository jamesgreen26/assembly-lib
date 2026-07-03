package com.assemblylib.debug.vchunk;

import org.joml.Quaternionf;

import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.vchunk.Assembly;
import com.assemblylib.impl.vchunk.AssemblyManager;
import com.assemblylib.impl.vchunk.AssemblySpace;
import com.assemblylib.impl.vchunk.AssemblyTransform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Milestone 1 acceptance tests: prove a hand-built assembly-space chunk behaves like a normal chunk
 * for vanilla block, block-entity, AND scheduled-tick (redstone) logic — driven entirely by vanilla's
 * own tick loops now that assembly chunks are loaded + registered.
 */
@GameTestHolder(AssemblyLib.MOD_ID)
@PrefixGameTestTemplate(false)
public class AssemblyVChunkGameTests {

    private static final String TEMPLATE = "gametest/flat_7x4x7";

    /** A block set into assembly-space round-trips through real chunk storage. */
    @GameTest(template = TEMPLATE)
    public static void blockRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        Assembly assembly = manager.createAssembly();
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());

        boolean placed = level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(placed, "setBlock into assembly-space returned true");
        helper.assertTrue(level.getBlockState(origin).is(Blocks.STONE), "stone reads back from assembly-space");

        level.setBlock(origin, Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(level.getBlockState(origin).isAir(), "block broke back to air in assembly-space");

        manager.deleteAssembly(assembly);
        helper.succeed();
    }

    /** A vanilla furnace, placed unmodified into assembly-space, smelts as if in the normal world. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void furnaceSmeltsInAssembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        Assembly assembly = manager.createAssembly();
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());

        level.setBlock(origin, Blocks.FURNACE.defaultBlockState(), 3);
        BlockEntity be = level.getBlockEntity(origin);
        helper.assertTrue(be instanceof AbstractFurnaceBlockEntity, "furnace block entity resolves in assembly-space");

        Container furnace = (Container) be;
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 1));
        furnace.setItem(1, new ItemStack(Items.COAL, 1));

        // No manual ticking: vanilla's own block-entity tick loop drives the furnace over real ticks.
        helper.succeedWhen(() -> helper.assertTrue(
            furnace.getItem(2).is(Items.IRON_INGOT), "furnace smelted raw iron into an iron ingot"));
    }

    /**
     * Vanilla's SCHEDULED-tick loop runs on assembly chunks — the case the old manual-bypass
     * implementation could not do. A tick is scheduled and we confirm vanilla consumes (runs) it. This
     * is the same {@code LevelTicks} mechanism redstone, observers, pistons and fluids depend on.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void scheduledTicksRunInAssembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        Assembly assembly = manager.createAssembly();
        BlockPos pos = AssemblySpace.tileOrigin(assembly.slot()).offset(1, 1, 0);

        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        level.scheduleTick(pos, Blocks.STONE, 2);
        helper.assertTrue(level.getBlockTicks().hasScheduledTick(pos, Blocks.STONE), "tick was scheduled");

        helper.succeedWhen(() -> helper.assertTrue(
            !level.getBlockTicks().hasScheduledTick(pos, Blocks.STONE),
            "scheduled tick was consumed by vanilla's tick loop in assembly-space"));
    }

    /**
     * The sound/level-event redirect chokepoint mixins (vanilla's own {@code playSound}/
     * {@code levelEvent} pipelines) must not throw when fired at a REAL assembly-space position under
     * a non-identity (translated + rotated) transform -- the exact case a naive implementation gets
     * wrong (forgetting to include rotation, or crashing when no player is nearby to receive the
     * resulting packet).
     */
    @GameTest(template = TEMPLATE)
    public static void soundAndLevelEventRedirectDontCrash(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        Assembly assembly = manager.createAssembly();
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());
        level.setBlock(origin, Blocks.NOTE_BLOCK.defaultBlockState(), 3);

        // Rotate + translate away from identity so a redirect bug that ignores rotation would surface.
        assembly.resetTransform(new AssemblyTransform(new Vec3(100, 64, 100), new Quaternionf().rotateY(1.2f)));

        level.playSound(null, origin, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 1f, 1f);
        level.levelEvent(2001, origin, Block.getId(Blocks.STONE.defaultBlockState()));

        helper.succeed();
    }

    /**
     * A vanilla piston, placed unmodified into assembly-space and powered by real redstone, pushes a
     * block — proving pistons "just work" via real chunks/scheduled ticks (server-side mechanics),
     * which is the load-bearing half of piston support (the client-side half is
     * {@code ClientAssembly#reconstruct}'s {@code MOVING_PISTON} special case, covering the
     * otherwise-invisible slide animation via the assembly's own {@code sendBlockUpdated}-driven sync).
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void pistonPushesBlockInAssembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        Assembly assembly = manager.createAssembly();
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());

        BlockPos pistonPos = origin;
        BlockPos targetPos = origin.relative(Direction.EAST, 2);
        level.setBlock(pistonPos, Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST), 3);
        level.setBlock(pistonPos.relative(Direction.EAST), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pistonPos.relative(Direction.WEST), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);

        helper.succeedWhen(() -> helper.assertTrue(
            level.getBlockState(targetPos).is(Blocks.STONE),
            "piston pushed the stone block one cell further in assembly-space"));
    }

    /**
     * A water source in assembly-space spreads to a neighbour cell via vanilla's own fluid tick loop.
     * (An earlier build had spreading broken; the FullChunkStatus/tick-container fixes since should
     * have resolved it — this test is the evidence either way.)
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void fluidFlowsInAssembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        Assembly assembly = manager.createAssembly();
        BlockPos source = AssemblySpace.tileOrigin(assembly.slot()).offset(1, 1, 1);

        // 3x3 solid floor under the source so sideways spread is possible in every direction (with a
        // floor under only ONE neighbour, vanilla's slope-distance search deliberately routes ALL the
        // water toward the floorless "hole" directions and that neighbour never fills — a test-design
        // trap, not an assembly bug).
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(source.below().offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(source, Blocks.WATER.defaultBlockState(), 3);

        helper.succeedWhen(() -> helper.assertTrue(
            !level.getFluidState(source.east()).isEmpty()
                || !level.getFluidState(source.west()).isEmpty()
                || !level.getFluidState(source.north()).isEmpty()
                || !level.getFluidState(source.south()).isEmpty(),
            "water spread to a neighbouring cell in assembly-space"));
    }

    /**
     * Vanilla's RANDOM-tick loop (crop growth, leaf decay, fire spread, farmland drying — anything
     * driven by {@code BlockState#randomTick}) is only ever invoked by {@code ServerChunkCache} for
     * chunks discovered via {@code chunkMap.getChunks()} (real ChunkHolders). Assembly chunks have
     * none, so without {@code AssemblyEvents} explicitly calling vanilla's own
     * {@code ServerLevel#tickChunk} for each resident chunk, random ticking silently never fires on
     * assemblies at all — scheduled ticks (redstone, furnaces) still worked via the separate
     * {@code LevelTicks} mechanism, which masked the gap. Isolated farmland (no water, no crop) with
     * moisture 0 deterministically reverts to dirt the first time ANY random tick lands on it; cranking
     * the random-tick-speed gamerule up makes that near-certain within a handful of ticks instead of
     * relying on luck.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void randomTickingRunsInAssembly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        Assembly assembly = manager.createAssembly();
        BlockPos pos = AssemblySpace.tileOrigin(assembly.slot()).offset(1, 1, 1);

        level.setBlock(pos, Blocks.FARMLAND.defaultBlockState(), 3);
        net.minecraft.world.level.GameRules.IntegerValue rule =
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING);
        int original = rule.get();
        rule.set(4096, level.getServer());

        helper.succeedWhen(() -> {
            boolean reverted = level.getBlockState(pos).is(Blocks.DIRT);
            if (reverted) {
                rule.set(original, level.getServer());
            }
            helper.assertTrue(reverted, "farmland random-ticked back to dirt in assembly-space");
        });
    }

    /**
     * A real (non-player) entity dropped above an assembly's transformed collision floor lands on it
     * and stays grounded — exercises the new mixin-driven collision pipeline
     * ({@code AssemblyEntityMoveMixin} wrapping {@code Entity.move()}'s own {@code collide}/
     * {@code setOnGroundWithMovement} calls) end-to-end: broad-phase candidate gathering, the OBB/SAT
     * substep resolve against the assembly's blocks at their APPARENT (transformed, nearby) position,
     * and the merged {@code onGround}/{@code verticalCollision} flags vanilla's own gravity/friction
     * code reads every tick.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void nonPlayerEntityRestsOnAssemblyFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        Assembly assembly = manager.createAssembly();
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());

        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                level.setBlock(origin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }

        // Anchor the assembly at a REAL, nearby position (inside the test structure) so the entity
        // collides against the far-away stored blocks purely through the transform -- the actual
        // scenario this whole system exists to support.
        BlockPos worldAnchor = helper.absolutePos(new BlockPos(1, 1, 1));
        assembly.resetTransform(new AssemblyTransform(Vec3.atLowerCornerOf(worldAnchor), new Quaternionf()));

        net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(level);
        pig.setPos(worldAnchor.getX() + 1.5, worldAnchor.getY() + 4.0, worldAnchor.getZ() + 1.5);
        level.addFreshEntity(pig);

        helper.succeedWhen(() -> {
            helper.assertTrue(pig.onGround(), "pig lands on the assembly's transformed collision floor");
            double expectedY = worldAnchor.getY() + 1.0;
            helper.assertTrue(Math.abs(pig.getY() - expectedY) < 0.1,
                "pig rests at the assembly floor's apparent height, not falling through it");
        });
    }

    /**
     * Directly probes {@code AssemblyEntityCollision.collide} (bypassing movement/gravity entirely —
     * zero motion, zero velocity) at a 5x5x5 grid of world positions around a SINGLE placed block, to
     * confirm collision resolves ONLY at the block's own cell and nowhere else — no "phantom"
     * collision extending into neighbouring empty cells. Regression test for exactly that class of bug.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void probeCollisionShapeAroundSingleBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        Assembly assembly = manager.createAssembly();
        BlockPos localBlockPos = AssemblySpace.tileOrigin(assembly.slot()).offset(5, 5, 5);
        level.setBlock(localBlockPos, Blocks.STONE.defaultBlockState(), 3);

        BlockPos worldAnchor = helper.absolutePos(new BlockPos(1, 1, 1));
        assembly.resetTransform(new AssemblyTransform(Vec3.atLowerCornerOf(worldAnchor), new Quaternionf()));
        assembly.setLoaded(true);

        // The block's LOCAL position relative to the tile origin is (5,5,5); with an identity,
        // translation-only transform, its apparent WORLD position is worldAnchor + (5,5,5).
        BlockPos blockWorldPos = worldAnchor.offset(5, 5, 5);

        // A tiny marker entity (0.25x0.25x0.25 hitbox) so probes 1+ cells away can't spuriously
        // overlap the target block due to the probe's OWN body extending into a neighbouring cell
        // (an armor stand's ~1.975 height reaches a full cell up/down from its feet position).
        net.minecraft.world.entity.item.ItemEntity probe = new net.minecraft.world.entity.item.ItemEntity(
            level, 0, 0, 0, new ItemStack(Items.STONE));
        probe.setNoGravity(true);
        level.addFreshEntity(probe);

        StringBuilder failures = new StringBuilder();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos probePos = blockWorldPos.offset(dx, dy, dz);
                    // Center the probe's tiny hitbox in the middle of the candidate cell.
                    probe.setPos(probePos.getX() + 0.5, probePos.getY() + 0.5, probePos.getZ() + 0.5);
                    probe.setDeltaMovement(Vec3.ZERO);

                    var candidates = com.assemblylib.impl.vchunk.collision.AssemblyCollisionCandidates.forLevel(level);
                    var info = com.assemblylib.impl.vchunk.collision.AssemblyEntityCollision.collide(
                        probe, Vec3.ZERO, Vec3.ZERO, candidates);

                    boolean shouldTouch = dx == 0 && dy == 0 && dz == 0;
                    boolean actuallyResolved = info.verticalCollision || info.horizontalCollision
                        || info.motion.lengthSqr() > 1.0e-9;
                    if (actuallyResolved != shouldTouch) {
                        failures.append(String.format(
                            "offset(%d,%d,%d) expected resolved=%s but got %s (verticalCollision=%s horizontalCollision=%s motion=%s)%n",
                            dx, dy, dz, shouldTouch, actuallyResolved, info.verticalCollision,
                            info.horizontalCollision, info.motion));
                    }
                }
            }
        }

        helper.assertTrue(failures.length() == 0, "collision probe found mismatches:\n" + failures);
        helper.succeed();
    }

    /**
     * Replicates the EXACT real-world scenario of {@code /assemblylib assembly create}: a player
     * standing on REAL vanilla terrain runs the command, which anchors the new assembly with
     * {@code AssemblyTransform.identity(player.position())} — i.e. the assembly's local Y=0 floor
     * starts exactly at the player's FEET, directly adjacent to (or overlapping) the real ground they
     * were already standing on. None of the other tests in this file have ever tested a REAL terrain
     * block touching/adjacent to an assembly block; every other test anchors the assembly floating in
     * open air above the test structure's own floor. If two independent collision systems (vanilla's
     * real-world block collision + our assembly SAT collision) disagree even slightly at that
     * boundary, this is where it would show up.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 120)
    public static void entityWalksAcrossRealTerrainToAssemblyFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);

        // Real vanilla ground: a small platform of real stone at local (0-2, 1, 0-2), separate from
        // the test template's own floor, standing proud so a probe can walk from real ground directly
        // onto the assembly without any other terrain in the way.
        BlockPos realGroundOrigin = helper.absolutePos(new BlockPos(0, 1, 0));
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                level.setBlock(realGroundOrigin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        // "Player" stands on top of the real ground, at realGroundOrigin + (1, 1, 1) feet position.
        BlockPos playerFeet = realGroundOrigin.offset(1, 1, 1);

        Assembly assembly = manager.createAssembly();
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());
        // Exact copy of AssemblyDebugCommand.buildDemoStructure's floor (5x5 stone at local y=0).
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                level.setBlock(origin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        // Exact copy of the debug command: identity transform at the "player's" feet position.
        assembly.resetTransform(AssemblyTransform.identity(Vec3.atLowerCornerOf(playerFeet)));
        assembly.setLoaded(true);

        // Drop a pig from above onto the REAL ground first (outside the assembly's footprint, which
        // now starts at the player's feet and extends +X/+Z), then force it to walk in a straight
        // line off the real ground and onto/across the assembly floor.
        net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(level);
        pig.setPos(realGroundOrigin.getX() + 0.5, realGroundOrigin.getY() + 3.0, realGroundOrigin.getZ() + 1.5);
        level.addFreshEntity(pig);

        double expectedTopY = playerFeet.getY();
        double targetX = playerFeet.getX() + 4.4; // well onto the assembly floor (5 blocks wide from playerFeet)

        helper.succeedWhen(() -> {
            if (pig.getX() < targetX) {
                Vec3 v = pig.getDeltaMovement();
                pig.setDeltaMovement(0.1, v.y, 0);
            }
            helper.assertTrue(pig.getY() >= expectedTopY - 0.15,
                "pig dipped below ground/floor level crossing from real terrain onto the assembly (y="
                    + pig.getY() + ", expected >= " + (expectedTopY - 0.15) + ")");
            helper.assertTrue(pig.getX() >= targetX, "still crossing from real terrain onto the assembly floor");
            helper.assertTrue(pig.onGround(), "pig finished the crossing but is not grounded");
        });
    }

    /**
     * A mob dropped ABOVE the demo structure's floor (real gameplay approach — falling onto a surface
     * from a safe starting point, never spawned embedded inside solid ground) then forced to walk in a
     * straight line across the ENTIRE 5-block floor must stay grounded and never dip below the floor's
     * surface. Exercises real per-tick collision resolution under continuous horizontal + vertical
     * motion together, unlike the static single-position probes above.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 120)
    public static void entityWalksAcrossFullFloorWithoutFalling(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        Assembly assembly = manager.createAssembly();
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());

        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                level.setBlock(origin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }

        BlockPos worldAnchor = helper.absolutePos(new BlockPos(1, 1, 1));
        assembly.resetTransform(new AssemblyTransform(Vec3.atLowerCornerOf(worldAnchor), new Quaternionf()));
        assembly.setLoaded(true);

        net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(level);
        // Drop from just above the floor's top surface (worldAnchor.y + 1), not spawned inside it.
        pig.setPos(worldAnchor.getX() + 0.5, worldAnchor.getY() + 1.5, worldAnchor.getZ() + 2.5);
        level.addFreshEntity(pig);

        double expectedTopY = worldAnchor.getY() + 1.0;

        helper.succeedWhen(() -> {
            // Force straight-line horizontal motion across the floor (dx 0 -> 4), bypassing AI so the
            // sweep is deterministic; vertical motion (gravity/collision) is left entirely to vanilla +
            // our collider.
            if (pig.getX() < worldAnchor.getX() + 4.4) {
                Vec3 v = pig.getDeltaMovement();
                pig.setDeltaMovement(0.1, v.y, 0);
            }
            helper.assertTrue(pig.getY() >= expectedTopY - 0.1,
                "pig dipped below the floor surface while crossing it (y=" + pig.getY() + ")");
            helper.assertTrue(pig.getX() >= worldAnchor.getX() + 4.4, "still crossing the floor");
            helper.assertTrue(pig.onGround(), "pig finished crossing the floor but is not grounded");
        });
    }

    /**
     * A non-player entity riding a MOVING assembly is carried along with it (not left behind) —
     * exercises the substep pose-interpolation + rigid-carry half of the collision pipeline, not just
     * static resting support. Uses a short window (40 ticks) so the assertion is robust to whatever
     * else is sharing the gametest world (many assemblies from other tests coexist in the same level).
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void movingAssemblyCarriesEntity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        Assembly assembly = manager.createAssembly();
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());

        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                level.setBlock(origin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }

        BlockPos worldAnchor = helper.absolutePos(new BlockPos(1, 1, 1));
        assembly.resetTransform(new AssemblyTransform(Vec3.atLowerCornerOf(worldAnchor), new Quaternionf()));
        assembly.setLinearVelocity(new Vec3(0.05, 0, 0));

        net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(level);
        pig.setPos(worldAnchor.getX() + 1.5, worldAnchor.getY() + 4.0, worldAnchor.getZ() + 1.5);
        level.addFreshEntity(pig);

        double[] checkpoint = new double[]{Double.NaN};
        helper.runAtTickTime(20, () -> checkpoint[0] = pig.getX());
        helper.runAtTickTime(60, () -> {
            double advanced = pig.getX() - checkpoint[0];
            // Expected advance over 40 ticks at 0.05/tick is 2.0; allow generous slack since other
            // assemblies sharing the test world can add noise, but a genuinely "left behind" entity
            // would show close to zero advance, not a fraction of the expected amount.
            helper.assertTrue(advanced > 1.0,
                "pig kept pace with the moving assembly instead of being left behind (advanced " + advanced + ")");
            helper.succeed();
        });
    }

    /**
     * The gameEvent chokepoint mixin (extra dispatch at the apparent position, original dispatch
     * preserved) must not crash or recurse when a vibration-producing event fires at a REAL
     * assembly-space position under a non-identity transform.
     */
    @GameTest(template = TEMPLATE)
    public static void gameEventRedirectDoesNotRecurse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AssemblyManager manager = AssemblyManager.get(level);
        Assembly assembly = manager.createAssembly();
        BlockPos origin = AssemblySpace.tileOrigin(assembly.slot());
        assembly.resetTransform(new AssemblyTransform(new Vec3(100, 64, 100), new Quaternionf().rotateY(0.7f)));

        // setBlock with the default flags fires GameEvent.BLOCK_PLACE through Level#gameEvent.
        level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3);
        level.gameEvent(net.minecraft.world.level.gameevent.GameEvent.BLOCK_PLACE, origin,
            net.minecraft.world.level.gameevent.GameEvent.Context.of(Blocks.STONE.defaultBlockState()));

        helper.succeed();
    }
}
