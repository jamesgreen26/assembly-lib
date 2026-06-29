package com.assemblylib.debug.gametest;

import com.assemblylib.AssemblyLib;
import com.assemblylib.impl.assembly.AssemblyTransform;
import com.assemblylib.impl.assembly.collision.AssemblyCollider;
import com.assemblylib.impl.assembly.collision.Matrix3d;
import com.assemblylib.impl.mixin.FallingBlockEntityInvoker;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Tests for entity-vs-assembly collision special cases.
 */
@GameTestHolder(AssemblyLib.MOD_ID)
@PrefixGameTestTemplate(false)
public class AssemblyCollisionGameTests {

	private static final String TEMPLATE = "gametest/flat_7x4x7";

	private static boolean near(Vec3 a, Vec3 b) {
		return a.distanceTo(b) < 1e-4;
	}

	/**
	 * A falling block's collision box is treated as axis-aligned WITH the assembly (identity in the
	 * assembly's local frame, where its blocks are axis-aligned), so a block detached from a rotating
	 * platform sweeps and rests flush on the grid. Any other entity instead keeps a world-aligned box
	 * that follows the platform's tilt. Guards {@link AssemblyCollider#entityBoxRotation}.
	 */
	@GameTest(template = TEMPLATE)
	public static void fallingBlockBoxIsAxisAlignedWithAssembly(GameTestHelper helper) {
		ServerLevel level = (ServerLevel) helper.getLevel();
		// A platform tilted about X, so it has a real (non-yaw) orientation a world-aligned box follows.
		AssemblyTransform tilted = AssemblyTransform.leaf(new Vec3(10, 5, 10), 30f, Direction.Axis.X);

		FallingBlockEntity falling = FallingBlockEntityInvoker.zps$create(level, 0, 0, 0,
			Blocks.SAND.defaultBlockState());
		ItemEntity item = new ItemEntity(level, 0, 0, 0, new ItemStack(Items.STONE));

		// The falling block's box is identity (axis-aligned with the structure): basis vectors unchanged.
		Matrix3d fallingRot = AssemblyCollider.entityBoxRotation(falling, tilted);
		for (Vec3 basis : new Vec3[] { new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1) }) {
			if (!near(fallingRot.transform(basis), basis)) {
				helper.fail("Falling block box should be axis-aligned (identity) in the assembly frame; "
					+ basis + " -> " + fallingRot.transform(basis));
				return;
			}
		}

		// A non-falling entity keeps the world-aligned (tilt-following) box: NOT identity, and equal to
		// the assembly's world->local-no-yaw rotation.
		Matrix3d itemRot = AssemblyCollider.entityBoxRotation(item, tilted);
		Vec3 up = new Vec3(0, 1, 0);
		if (near(itemRot.transform(up), up)) {
			helper.fail("A non-falling entity's box should tilt with the platform, not stay axis-aligned");
			return;
		}
		if (!near(itemRot.transform(up), tilted.worldToLocalRotationNoWorldYaw().transform(up))) {
			helper.fail("A non-falling entity's box should use the assembly's world->local-no-yaw rotation");
			return;
		}
		helper.succeed();
	}
}
