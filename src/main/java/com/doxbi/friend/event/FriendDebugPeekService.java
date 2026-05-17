package com.doxbi.friend.event;

import com.doxbi.friend.entity.FriendEntity;
import com.doxbi.friend.registry.FriendEntityTypes;
import com.doxbi.friend.stalking.*;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class FriendDebugPeekService {
    private static FriendPeekCommandResult lastResult = FriendPeekCommandResult.fail("not run yet", 0, List.of());

    private FriendDebugPeekService() {
    }

    public static FriendPeekCommandResult forcePeek(ServerLevel level, ServerPlayer player, CompoundTag data, PeekCommandMode mode) {
        List<String> diagnostics = new ArrayList<>();
        diagnostics.add("serverSide=" + !level.isClientSide);
        diagnostics.add("dimension=" + level.dimension().location());
        diagnostics.add("player=" + player.blockPosition().toShortString());
        try {
            cleanupOwned(level, player);
            if (mode != PeekCommandMode.FALLBACK_ONLY) {
                PlayerEnvironmentContext context = FriendCoverQuery.detectContext(level, player, data);
                MineContextSnapshot mine = MineContextAdapter.classify(level, player, data);
                diagnostics.add("context=" + context);
                diagnostics.add("mine=" + mine.environmentType() + "/" + Math.round(mine.confidence() * 100.0D) + "%");
                List<CoverCandidate> candidates = FriendCoverQuery.query(level, player, data, context, null,
                        Math.max(2, FriendStalkingMemory.intensity(data, Math.max(1, data.getInt("friend_phase")), data.getBoolean("friend_rage"))));
                if (!candidates.isEmpty()) {
                    CoverCandidate chosen = candidates.stream()
                            .filter(c -> FriendPerception.hasLineOfSight(player, c.peekAnchor(), c.coverType() == CoverType.WINDOW
                                    ? FriendPerception.VisibilityMode.THROUGH_GLASS : FriendPerception.VisibilityMode.NORMAL))
                            .max(Comparator.comparingDouble(CoverCandidate::finalScore))
                            .orElse(candidates.get(0));
                    FriendPeekCommandResult result = spawnAtCandidate(level, player, data, chosen, candidates.size(), diagnostics);
                    lastResult = result;
                    return result;
                }
                diagnostics.add("CoverQuery returned 0 visible anchors");
                if (mode == PeekCommandMode.STRICT_ANCHOR_ONLY) {
                    FriendPeekCommandResult result = FriendPeekCommandResult.fail("CoverQuery returned 0 anchors", 0, diagnostics);
                    lastResult = result;
                    return result;
                }
            }

            Optional<BlockPos> fallback = findFallbackPeekPos(level, player);
            if (fallback.isEmpty()) {
                diagnostics.add("fallback tried=true");
                FriendPeekCommandResult result = FriendPeekCommandResult.fail("CoverQuery returned 0 anchors and fallback failed", 0, diagnostics);
                lastResult = result;
                return result;
            }
            FriendPeekCommandResult result = spawnFallback(level, player, data, fallback.get(), diagnostics);
            lastResult = result;
            return result;
        } catch (RuntimeException exception) {
            diagnostics.add("exception=" + exception.getClass().getSimpleName() + ":" + exception.getMessage());
            FriendPeekCommandResult result = FriendPeekCommandResult.fail("exception during forced peek", 0, diagnostics);
            lastResult = result;
            return result;
        }
    }

    public static FriendPeekCommandResult debugSpawn(ServerLevel level, ServerPlayer player, CompoundTag data) {
        cleanupOwned(level, player);
        Optional<BlockPos> pos = findFallbackPeekPos(level, player);
        if (pos.isEmpty()) {
            lastResult = FriendPeekCommandResult.fail("debug spawn fallback position failed", 0, List.of("player=" + player.blockPosition().toShortString()));
            return lastResult;
        }
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            lastResult = FriendPeekCommandResult.fail("FriendEntityTypes.FRIEND.create(level) returned null", 0, List.of());
            return lastResult;
        }
        BlockPos feet = pos.get();
        friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, player.getYRot() + 180.0F, 0.0F);
        friend.configure(player.getUUID().toString(), "stare_event", 20 * 8, 999);
        friend.setPersistenceRequired();
        friend.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
        boolean added = level.addFreshEntity(friend);
        lastResult = added
                ? FriendPeekCommandResult.ok("debug spawn added entity " + friend.getUUID(), feet, "fallback_debug_spawn", 1, List.of())
                : FriendPeekCommandResult.fail("level.addFreshEntity(friend) returned false", 0, List.of("pos=" + feet.toShortString()));
        return lastResult;
    }

    public static String debugLine(ServerLevel level, ServerPlayer player, CompoundTag data) {
        FriendEntity friend = nearestOwned(level, player, 128.0D);
        String entity = friend == null ? "entity=none" : "entity=" + friend.getUUID()
                + " event=" + friend.eventId()
                + " forced=" + friend.isDebugForcedPeekActive()
                + " forcedTicks=" + friend.getPersistentData().getInt(FriendEntity.TAG_DEBUG_FORCED_PEEK_TICKS)
                + " pos=" + friend.blockPosition().toShortString();
        return entity + " | last=" + lastResult.chatLine(PeekCommandMode.NORMAL_FORCED);
    }

    private static FriendPeekCommandResult spawnAtCandidate(ServerLevel level, ServerPlayer player, CompoundTag data,
                                                           CoverCandidate chosen, int count, List<String> diagnostics) {
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return FriendPeekCommandResult.fail("FriendEntityTypes.FRIEND.create(level) returned null", count, diagnostics);
        }
        friend.moveTo(chosen.hiddenAnchor().x, chosen.hiddenAnchor().y, chosen.hiddenAnchor().z, chosen.bodyYaw(), 0.0F);
        String event = eventId(chosen);
        friend.configure(player.getUUID().toString(), event, 20 * 8, 999);
        CompoundTag tag = friend.getPersistentData();
        tag.putInt(FriendEntity.TAG_COVER_X, chosen.coverBlock().getX());
        tag.putInt(FriendEntity.TAG_COVER_Y, chosen.coverBlock().getY());
        tag.putInt(FriendEntity.TAG_COVER_Z, chosen.coverBlock().getZ());
        tag.putString("friend_cover_type", chosen.coverType().name());
        tag.putString("friend_peek_side", chosen.peekSide().name());
        tag.putDouble("friend_visible_fraction_hidden", chosen.visibleFractionHidden());
        tag.putDouble("friend_visible_fraction_peek", chosen.visibleFractionPeek());
        tag.putDouble("friend_peek_x", chosen.peekAnchor().x);
        tag.putDouble("friend_peek_y", chosen.peekAnchor().y);
        tag.putDouble("friend_peek_z", chosen.peekAnchor().z);
        tag.putInt("friend_spotted_required", 999);
        friend.setPersistenceRequired();
        friend.startDebugForcedPeek(80);
        friend.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
        boolean added = level.addFreshEntity(friend);
        if (!added) {
            return FriendPeekCommandResult.fail("level.addFreshEntity(friend) returned false", count, diagnostics);
        }
        data.putBoolean("friend_active", true);
        FriendStalkingMemory.setDebugSummary(data, FriendStalkingState.STAGING_AT_COVER,
                FriendCoverQuery.detectContext(level, player, data), chosen, count, FriendStalkingDirector.animationName(event), "forced_command " + chosen.rejectReason());
        diagnostics.add("visibleFraction=" + chosen.visibleFractionPeek());
        diagnostics.add("entity=" + friend.getUUID());
        diagnostics.add("forcedTicks=80");
        return FriendPeekCommandResult.ok("real anchor selected", chosen.coverBlock(), chosen.rejectReason(), count, diagnostics);
    }

    private static FriendPeekCommandResult spawnFallback(ServerLevel level, ServerPlayer player, CompoundTag data,
                                                        BlockPos feet, List<String> diagnostics) {
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return FriendPeekCommandResult.fail("FriendEntityTypes.FRIEND.create(level) returned null", 0, diagnostics);
        }
        friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, player.getYRot() + 180.0F, 0.0F);
        friend.configure(player.getUUID().toString(), "stalk_corner_left", 20 * 8, 999);
        Vec3 peek = feet.getCenter().add(0.0D, 1.35D, 0.0D);
        CompoundTag tag = friend.getPersistentData();
        tag.putInt(FriendEntity.TAG_COVER_X, feet.getX());
        tag.putInt(FriendEntity.TAG_COVER_Y, feet.getY());
        tag.putInt(FriendEntity.TAG_COVER_Z, feet.getZ());
        tag.putString("friend_cover_type", CoverType.WALL_CORNER.name());
        tag.putString("friend_peek_side", PeekSide.LEFT.name());
        tag.putDouble("friend_visible_fraction_hidden", 0.0D);
        tag.putDouble("friend_visible_fraction_peek", 0.45D);
        tag.putDouble("friend_peek_x", peek.x);
        tag.putDouble("friend_peek_y", peek.y);
        tag.putDouble("friend_peek_z", peek.z);
        tag.putInt("friend_spotted_required", 999);
        friend.setPersistenceRequired();
        friend.startDebugForcedPeek(80);
        friend.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
        boolean added = level.addFreshEntity(friend);
        if (!added) {
            return FriendPeekCommandResult.fail("level.addFreshEntity(friend) returned false", 0, diagnostics);
        }
        data.putBoolean("friend_active", true);
        diagnostics.add("fallback tried=true");
        diagnostics.add("entity=" + friend.getUUID());
        diagnostics.add("forcedTicks=80");
        return FriendPeekCommandResult.ok("fallback visible spawn selected", feet, "fallback_visible_peek", 0, diagnostics);
    }

    private static Optional<BlockPos> findFallbackPeekPos(ServerLevel level, ServerPlayer player) {
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        if (look.lengthSqr() < 0.001D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        look = look.normalize();
        Vec3 right = new Vec3(-look.z, 0.0D, look.x).normalize();
        Vec3[] directions = new Vec3[]{
                look,
                look.add(right.scale(0.65D)).normalize(),
                look.add(right.scale(-0.65D)).normalize(),
                right,
                right.scale(-1.0D)
        };
        for (Vec3 dir : directions) {
            for (int distance = 7; distance <= 15; distance += 2) {
                BlockPos rough = BlockPos.containing(player.position().add(dir.scale(distance)));
                BlockPos feet = findNearestValidGround(level, rough, 5);
                if (feet != null && isValidFriendSpawnPos(level, player, feet)) {
                    Vec3 face = feet.getCenter().add(0.0D, 1.25D, 0.0D);
                    if (FriendPerception.isPointInFov(player, face, 125.0D)
                            && FriendPerception.hasLineOfSight(player, face, FriendPerception.VisibilityMode.NORMAL)) {
                        return Optional.of(feet.immutable());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static BlockPos findNearestValidGround(ServerLevel level, BlockPos origin, int vertical) {
        for (int y = origin.getY() + vertical; y >= origin.getY() - vertical; y--) {
            BlockPos feet = new BlockPos(origin.getX(), y, origin.getZ());
            if (isSpawnSpace(level, feet)) {
                return feet;
            }
        }
        return null;
    }

    private static boolean isValidFriendSpawnPos(ServerLevel level, ServerPlayer player, BlockPos feet) {
        double dist = Math.sqrt(player.blockPosition().distSqr(feet));
        return dist >= 5.5D && dist <= 18.0D && isSpawnSpace(level, feet)
                && level.hasChunkAt(feet)
                && !level.getBlockState(feet.below()).is(Blocks.LAVA)
                && level.clip(new ClipContext(player.getEyePosition(), feet.getCenter().add(0, 1.2D, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }

    private static boolean isSpawnSpace(ServerLevel level, BlockPos feet) {
        if (!level.hasChunkAt(feet)) return false;
        BlockState floor = level.getBlockState(feet.below());
        return !floor.getCollisionShape(level, feet.below()).isEmpty()
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getBlockState(feet).getFluidState().isEmpty()
                && level.getBlockState(feet.above()).getFluidState().isEmpty();
    }

    private static String eventId(CoverCandidate c) {
        String side = c.peekSide() == PeekSide.RIGHT ? "right" : "left";
        return switch (c.coverType()) {
            case TREE -> "stalk_tree_" + side;
            case WINDOW -> "stalk_window";
            case DOORFRAME -> "stalk_doorframe_" + side;
            case CAVE_EDGE -> "stalk_cave_edge";
            case CEILING_HOLE -> "stalk_ceiling";
            case FLOOR_HOLE -> "stalk_floor";
            case LOW_CEILING -> c.availableHeight() <= 1 ? "stalk_low1" : "stalk_low2";
            case DISTANT_OBSERVE -> "stalk_distant";
            default -> "stalk_corner_" + side;
        };
    }

    private static void cleanupOwned(ServerLevel level, ServerPlayer player) {
        level.getEntitiesOfClass(FriendEntity.class, player.getBoundingBox().inflate(220.0D),
                        friend -> player.getUUID().toString().equals(friend.ownerUuid()))
                .forEach(FriendEntity::discard);
    }

    private static FriendEntity nearestOwned(ServerLevel level, ServerPlayer player, double radius) {
        return level.getEntitiesOfClass(FriendEntity.class, player.getBoundingBox().inflate(radius),
                        friend -> player.getUUID().toString().equals(friend.ownerUuid()))
                .stream().min(Comparator.comparingDouble(friend -> friend.distanceToSqr(player))).orElse(null);
    }
}
