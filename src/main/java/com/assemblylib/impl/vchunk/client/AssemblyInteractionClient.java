package com.assemblylib.impl.vchunk.client;

import javax.annotation.Nullable;

import com.assemblylib.impl.vchunk.AssemblyTransform;
import com.assemblylib.impl.vchunk.net.AssemblyAttackC2SPacket;
import com.assemblylib.impl.vchunk.net.AssemblyUseC2SPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side interaction with assemblies: transforms the player's look ray into each assembly's
 * LOCAL space, raycasts against the synced block snapshot, and — if an assembly block is closer than
 * whatever vanilla is aiming at — intercepts the attack/use input and forwards it to the server as a
 * LOCAL-space hit. The server then drives vanilla break/place/use at the block's real coordinates.
 */
public final class AssemblyInteractionClient {

    private AssemblyInteractionClient() {}

    /** A resolved hit on an assembly: which assembly, the LOCAL block hit, and squared world distance to the eye. */
    public record Hit(int handle, BlockHitResult localHit, double distSqr) {}

    @Nullable
    public static Hit raycast(LocalPlayer player, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);
        double reach = player.blockInteractionRange();
        Vec3 end = eye.add(look.scale(reach));

        Hit best = null;
        for (ClientAssembly assembly : ClientAssemblyManager.all()) {
            if (assembly.blocks().isEmpty()) {
                continue;
            }
            AssemblyTransform transform = assembly.transform(partialTick);
            Vec3 localEye = transform.worldToLocal(eye);
            Vec3 localEnd = transform.worldToLocal(end);
            AssemblyRenderWorld view = new AssemblyRenderWorld(assembly.blocks(), assembly.apparentOrigin());
            BlockHitResult hit = view.clip(new ClipContext(localEye, localEnd,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS) {
                continue;
            }
            Vec3 worldHit = transform.localToWorld(hit.getLocation());
            double distSqr = worldHit.distanceToSqr(eye);
            if (best == null || distSqr < best.distSqr) {
                best = new Hit(assembly.id().handle(), hit, distSqr);
            }
        }
        return best;
    }

    /** True if an assembly hit is closer to the eye than whatever vanilla is currently targeting. */
    private static boolean beatsVanilla(LocalPlayer player, Hit hit, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        HitResult vanilla = mc.hitResult;
        if (vanilla == null || vanilla.getType() == HitResult.Type.MISS) {
            return true;
        }
        double vanillaDistSqr = vanilla.getLocation().distanceToSqr(player.getEyePosition(partialTick));
        return hit.distSqr < vanillaDistSqr;
    }

    @SubscribeEvent
    public static void onInteractionInput(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        if (ClientAssemblyManager.all().isEmpty()) {
            return;
        }
        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(true);
        Hit hit = raycast(player, partialTick);
        if (hit == null || !beatsVanilla(player, hit, partialTick)) {
            return;
        }

        if (event.isAttack()) {
            PacketDistributor.sendToServer(new AssemblyAttackC2SPacket(hit.handle, hit.localHit.getBlockPos()));
            player.swing(InteractionHand.MAIN_HAND);
            event.setCanceled(true);
        } else if (event.isUseItem()) {
            InteractionHand hand = event.getHand();
            PacketDistributor.sendToServer(new AssemblyUseC2SPacket(hit.handle, hit.localHit.getBlockPos(),
                hit.localHit.getDirection(), hit.localHit.getLocation(), hand));
            player.swing(hand);
            event.setCanceled(true);
        }
    }
}
