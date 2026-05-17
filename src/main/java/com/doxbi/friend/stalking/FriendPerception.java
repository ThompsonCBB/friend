package com.doxbi.friend.stalking;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GlassBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class FriendPerception {
    public enum VisibilityMode {
        NORMAL,
        THROUGH_GLASS,
        PEEK_ONLY
    }

    private FriendPerception() {
    }

    public static boolean isPointInFov(Player player, Vec3 point, double degrees) {
        Vec3 toTarget = point.subtract(player.getEyePosition());
        if (toTarget.lengthSqr() < 0.0001D) {
            return true;
        }
        double dot = Mth.clamp(player.getLookAngle().normalize().dot(toTarget.normalize()), -1.0D, 1.0D);
        double angle = Math.toDegrees(Math.acos(dot));
        return angle <= degrees * 0.5D;
    }

    public static boolean isPointInStrictFov(Player player, Vec3 point) {
        return isPointInFov(player, point, 45.0D);
    }

    public static boolean isPointInSoftFov(Player player, Vec3 point) {
        return isPointInFov(player, point, 106.0D);
    }

    public static boolean hasLineOfSight(Player player, Vec3 target, VisibilityMode mode) {
        HitResult hit = player.level().clip(new ClipContext(player.getEyePosition(), target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(target) < 0.65D) {
            return true;
        }
        if (mode == VisibilityMode.THROUGH_GLASS && hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = player.level().getBlockState(pos);
            return isGlass(state) && pos.getCenter().distanceTo(target) < 3.25D;
        }
        return false;
    }

    public static double calculateVisibleFraction(Player player, AABB visualBox, VisibilityMode mode) {
        List<Vec3> samples = samplePoints(visualBox);
        int visible = 0;
        for (Vec3 point : samples) {
            if (hasLineOfSight(player, point, mode)) {
                visible++;
            }
        }
        return samples.isEmpty() ? 0.0D : visible / (double) samples.size();
    }

    public static boolean isActuallySeen(Player player, AABB visualBox, double minFraction, int spottedTicks, int requiredTicks, VisibilityMode mode) {
        Vec3 center = visualBox.getCenter();
        if (!isPointInSoftFov(player, center)) {
            return false;
        }
        if (calculateVisibleFraction(player, visualBox, mode) < minFraction) {
            return false;
        }
        return spottedTicks >= requiredTicks;
    }

    public static AABB visualBox(Vec3 anchor, double width, double height) {
        double r = width * 0.5D;
        return new AABB(anchor.x - r, anchor.y, anchor.z - r, anchor.x + r, anchor.y + height, anchor.z + r);
    }

    private static List<Vec3> samplePoints(AABB box) {
        List<Vec3> samples = new ArrayList<>(11);
        double cx = (box.minX + box.maxX) * 0.5D;
        double cy = box.minY;
        double cz = (box.minZ + box.maxZ) * 0.5D;
        double w = box.getXsize() * 0.45D;
        double h = box.getYsize();
        samples.add(new Vec3(cx, cy + h * 0.88D, cz));
        samples.add(new Vec3(cx - w, cy + h * 0.86D, cz));
        samples.add(new Vec3(cx + w, cy + h * 0.86D, cz));
        samples.add(new Vec3(cx, cy + h * 0.72D, cz));
        samples.add(new Vec3(cx, cy + h * 0.55D, cz));
        samples.add(new Vec3(cx - w, cy + h * 0.58D, cz));
        samples.add(new Vec3(cx + w, cy + h * 0.58D, cz));
        samples.add(new Vec3(cx - w, cy + h * 0.42D, cz));
        samples.add(new Vec3(cx + w, cy + h * 0.42D, cz));
        samples.add(new Vec3(cx, cy + h * 0.28D, cz));
        samples.add(new Vec3(cx, cy + h * 0.12D, cz));
        return samples;
    }

    private static boolean isGlass(BlockState state) {
        return state.getBlock() instanceof GlassBlock || state.getBlock() instanceof StainedGlassBlock || state.is(Blocks.GLASS_PANE);
    }
}
