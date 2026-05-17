package com.doxbi.friend.entity.ai;

import com.doxbi.friend.entity.FriendEntity;
import com.doxbi.friend.config.FriendConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class FriendMovementController {
    private static final String TAG_STUCK_MICRO_REPATH_COOLDOWN = "friend_stuck_micro_repath_cooldown";

    private FriendMovementController() {
    }

    public static ChaseResult tickChase(ServerLevel level, FriendEntity friend, LivingEntity target, double requestedSpeed) {
        if (friend.isDebugForcedPeekActive()) {
            friend.getNavigation().stop();
            return new ChaseResult("forced_peek_lock", false, false);
        }
        ensurePhysical(friend, requestedSpeed);
        boolean stuck = updateStuck(level, friend, target);
        int stuckTicks = friend.getPersistentData().getInt(FriendEntity.TAG_STUCK_TICKS);
        long now = level.getGameTime();

        friend.getLookControl().setLookAt(target, 50.0F, 50.0F);
        double navSpeed = Mth.clamp(requestedSpeed * (friend.rage() ? 1.55D : 1.35D), 0.55D, friend.rage() ? 1.18D : 0.98D);
        CompoundTag pathTag = friend.getPersistentData();
        boolean normalRepath = friend.getNavigation().isDone()
                || now >= pathTag.getLong(FriendEntity.TAG_LAST_PATH_RECALC);
        boolean stuckMicroRepath = stuck && now >= pathTag.getLong(TAG_STUCK_MICRO_REPATH_COOLDOWN);
        if (normalRepath || stuckMicroRepath) {
            friend.getNavigation().moveTo(target, stuck ? navSpeed + 0.18D : navSpeed);
            if (normalRepath) {
                pathTag.putLong(FriendEntity.TAG_LAST_PATH_RECALC, now + (stuck ? 5L : 9L));
            }
            if (stuck) {
                pathTag.putLong(TAG_STUCK_MICRO_REPATH_COOLDOWN, now + 6L);
            }
        }

        Vec3 forward = target.position().subtract(friend.position()).multiply(1.0D, 0.0D, 1.0D);
        if (forward.lengthSqr() > 0.001D) {
            forward = forward.normalize();
            if (stuckTicks >= 5 && now >= cooldown(friend, FriendEntity.TAG_JUMP_COOLDOWN) && tryJumpOverOneBlock(level, friend, forward)) {
                setCooldown(friend, FriendEntity.TAG_JUMP_COOLDOWN, now + 7L);
                return new ChaseResult("jump_obstacle", true, false);
            }
            if (stuckTicks >= 10 && now >= cooldown(friend, FriendEntity.TAG_SIDESTEP_COOLDOWN) && trySideStep(level, friend, target, forward, requestedSpeed)) {
                setCooldown(friend, FriendEntity.TAG_SIDESTEP_COOLDOWN, now + 11L);
                return new ChaseResult("side_step", true, false);
            }
        }

        if (stuckTicks >= 28 && now >= cooldown(friend, FriendEntity.TAG_REPATH_COOLDOWN)) {
            friend.getNavigation().recomputePath();
            friend.getNavigation().moveTo(target, friend.rage() ? 1.18D : 0.98D);
            setCooldown(friend, FriendEntity.TAG_REPATH_COOLDOWN, now + 18L);
            return new ChaseResult("repath", true, stuckTicks >= 58);
        }
        return new ChaseResult(stuck ? "stuck_scanning" : "ground_navigation", true, stuckTicks >= 58);
    }

    public static void ensurePhysical(FriendEntity friend, double speed) {
        friend.noPhysics = false;
        friend.setNoGravity(false);
        friend.setNoAi(false);
        friend.setMaxUpStep(1.65F);
        var attribute = friend.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null) {
            attribute.setBaseValue(Mth.clamp(speed, 0.38D, friend.rage() ? 0.98D : 0.82D));
        }
    }

    private static boolean updateStuck(ServerLevel level, FriendEntity friend, LivingEntity target) {
        CompoundTag tag = friend.getPersistentData();
        if (friend.tickCount % 5 != 0) {
            return tag.getInt(FriendEntity.TAG_STUCK_TICKS) > 12;
        }
        Vec3 last = new Vec3(tag.getDouble(FriendEntity.TAG_LAST_PROGRESS_X),
                tag.getDouble(FriendEntity.TAG_LAST_PROGRESS_Y),
                tag.getDouble(FriendEntity.TAG_LAST_PROGRESS_Z));
        double moved = friend.position().distanceTo(last);
        double distance = friend.distanceTo(target);
        double lastDistance = tag.getDouble(FriendEntity.TAG_LAST_DISTANCE);
        boolean wantsMove = !friend.getNavigation().isDone() || distance > 2.8D;
        boolean noProgress = wantsMove && moved < 0.07D && distance >= lastDistance - 0.18D;
        int stuck = noProgress ? tag.getInt(FriendEntity.TAG_STUCK_TICKS) + 5 : Math.max(0, tag.getInt(FriendEntity.TAG_STUCK_TICKS) - 7);
        tag.putInt(FriendEntity.TAG_STUCK_TICKS, stuck);
        tag.putDouble(FriendEntity.TAG_LAST_PROGRESS_X, friend.getX());
        tag.putDouble(FriendEntity.TAG_LAST_PROGRESS_Y, friend.getY());
        tag.putDouble(FriendEntity.TAG_LAST_PROGRESS_Z, friend.getZ());
        tag.putDouble(FriendEntity.TAG_LAST_DISTANCE, distance);
        if (!friend.onGround() && Math.abs(friend.getDeltaMovement().y) < 0.006D && isOpenBelow(level, friend.blockPosition())) {
            friend.setDeltaMovement(friend.getDeltaMovement().x, -0.12D, friend.getDeltaMovement().z);
        }
        return stuck > 8;
    }

    private static boolean tryJumpOverOneBlock(ServerLevel level, FriendEntity friend, Vec3 forward) {
        if (!friend.onGround() || isDropAhead(level, friend, forward, 3)) {
            return false;
        }
        Vec3 front = friend.position().add(forward.scale(0.82D));
        BlockPos obstacle = BlockPos.containing(front.x, friend.getY() + 0.1D, front.z);
        BlockPos landing = obstacle.above();
        if (!isSolid(level, obstacle) || !isFree(level, landing) || !isFree(level, landing.above())) {
            return false;
        }
        if (isHazard(level.getBlockState(obstacle)) || isHazard(level.getBlockState(landing.below()))) {
            return false;
        }
        friend.getJumpControl().jump();
        friend.setDeltaMovement(friend.getDeltaMovement().x + forward.x * 0.18D, 0.54D, friend.getDeltaMovement().z + forward.z * 0.18D);
        return true;
    }

    private static boolean trySideStep(ServerLevel level, FriendEntity friend, LivingEntity target, Vec3 forward, double speed) {
        Vec3 left = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3[] choices = new Vec3[]{left, left.scale(-1.0D), left.add(forward).normalize(), left.scale(-1.0D).add(forward).normalize()};
        double bestScore = Double.MAX_VALUE;
        Vec3 best = null;
        for (Vec3 choice : choices) {
            for (double distance : new double[]{1.4D, 2.2D, 3.0D}) {
                Vec3 targetPos = friend.position().add(choice.normalize().scale(distance));
                BlockPos feet = findGround(level, BlockPos.containing(targetPos.x, friend.getY(), targetPos.z));
                if (feet == null || !canStand(level, feet) || hasBlockedRay(level, friend.position().add(0, 0.8D, 0), Vec3.atBottomCenterOf(feet).add(0, 0.8D, 0))) {
                    continue;
                }
                double score = Vec3.atBottomCenterOf(feet).distanceToSqr(target.position());
                if (score < bestScore) {
                    bestScore = score;
                    best = Vec3.atBottomCenterOf(feet);
                }
            }
        }
        if (best == null) {
            return false;
        }
        friend.getNavigation().moveTo(best.x, best.y, best.z, speed > 0.60D ? 1.05D : 0.88D);
        return true;
    }

    private static BlockPos findGround(ServerLevel level, BlockPos origin) {
        for (int y = origin.getY() + 2; y >= origin.getY() - 4; y--) {
            BlockPos feet = new BlockPos(origin.getX(), y, origin.getZ());
            if (canStand(level, feet)) {
                return feet;
            }
        }
        return null;
    }

    private static boolean canStand(ServerLevel level, BlockPos feet) {
        return level.hasChunkAt(feet)
                && isSolid(level, feet.below())
                && isFree(level, feet)
                && isFree(level, feet.above())
                && !isHazard(level.getBlockState(feet.below()));
    }

    private static boolean isSolid(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !isIgnoredPathPlant(state) && !state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isFree(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return (isIgnoredPathPlant(state) || state.getCollisionShape(level, pos).isEmpty()) && state.getFluidState().isEmpty();
    }

    private static boolean isIgnoredPathPlant(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.BushBlock
                || state.is(Blocks.GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH);
    }

    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.CACTUS) || !state.getFluidState().isEmpty();
    }

    private static boolean isDropAhead(ServerLevel level, FriendEntity friend, Vec3 forward, int maxDrop) {
        Vec3 front = friend.position().add(forward.scale(1.05D));
        BlockPos feet = BlockPos.containing(front.x, friend.getY(), front.z);
        for (int i = 1; i <= maxDrop; i++) {
            if (isSolid(level, feet.below(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOpenBelow(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()
                && level.getBlockState(pos.below(2)).getCollisionShape(level, pos.below(2)).isEmpty();
    }

    private static boolean hasBlockedRay(ServerLevel level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).getType() != HitResult.Type.MISS;
    }

    private static long cooldown(FriendEntity friend, String key) {
        return friend.getPersistentData().getLong(key);
    }

    private static void setCooldown(FriendEntity friend, String key, long value) {
        friend.getPersistentData().putLong(key, value);
    }

    public record ChaseResult(String pathType, boolean handled, boolean needsIntervention) {
    }
}
