package com.doxbi.friend.stalking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MinePeekAdapter {
    private static final double FULL_HEIGHT = 3.55D;

    private MinePeekAdapter() {
    }

    public static List<CoverCandidate> query(ServerLevel level, ServerPlayer player, CompoundTag data,
                                             PlayerEnvironmentContext context, int intensity) {
        MineContextSnapshot snapshot = MineContextAdapter.classify(level, player, data);
        if (!snapshot.isMineLike() || !isMineContext(context)) {
            return List.of();
        }

        List<CoverCandidate> candidates = new ArrayList<>();
        BlockPos center = player.blockPosition();
        int radius = snapshot.environmentType() == MineEnvironmentType.GENERATED_MINESHAFT ? 34 : 28;
        int radiusSq = radius * radius;
        int cap = 56;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radiusSq) {
                    continue;
                }
                for (int y = -7; y <= 7; y++) {
                    BlockPos cover = center.offset(x, y, z);
                    if (!level.hasChunkAt(cover)) {
                        continue;
                    }
                    CoverCandidate candidate = buildCandidate(level, player, data, snapshot, cover, intensity);
                    if (candidate != null) {
                        candidates.add(candidate);
                        if (candidates.size() >= cap) {
                            return sort(candidates);
                        }
                    }
                }
            }
        }
        return sort(candidates);
    }

    public static String debugAnchors(ServerLevel level, ServerPlayer player, CompoundTag data) {
        PlayerEnvironmentContext context = FriendCoverQuery.detectContext(level, player, data);
        MineContextSnapshot snapshot = MineContextAdapter.classify(level, player, data);
        List<CoverCandidate> anchors = query(level, player, data, context, FriendStalkingMemory.intensity(data, data.getInt("friend_phase"), data.getBoolean("friend_rage")));
        StringBuilder result = new StringBuilder();
        result.append("mine ").append(snapshot.environmentType()).append(" conf=").append(round(snapshot.confidence()))
                .append(" ").append(snapshot.features()).append(" anchors=").append(anchors.size());
        for (int i = 0; i < Math.min(4, anchors.size()); i++) {
            CoverCandidate c = anchors.get(i);
            result.append(" | ").append(i + 1).append(":").append(c.rejectReason())
                    .append(" score=").append(round(c.finalScore()))
                    .append(" pos=").append(c.coverBlock().toShortString());
        }
        return result.toString();
    }

    private static CoverCandidate buildCandidate(ServerLevel level, ServerPlayer player, CompoundTag data,
                                                 MineContextSnapshot snapshot, BlockPos cover, int intensity) {
        BlockState coverState = level.getBlockState(cover);
        MinePeekAnchorType anchor = classifyAnchor(level, player, snapshot, cover, coverState);
        if (anchor == null) {
            return null;
        }

        Direction face = directionToPlayer(cover, player.position());
        if (face == null) {
            return null;
        }
        BlockPos feet = hiddenFeetFor(level, cover, face, anchor);
        if (feet == null || !FriendCoverQuery.canStandAt(level, feet, FULL_HEIGHT)) {
            return null;
        }
        if (badBlock(level.getBlockState(feet.below())) || hasDangerBelow(level, feet)) {
            return null;
        }

        Vec3 hidden = Vec3.atBottomCenterOf(feet);
        Vec3 peek = peekAnchor(anchor, cover, face, feet);
        double distance = player.getEyePosition().distanceTo(peek);
        if (!distanceOk(snapshot.environmentType(), anchor, distance, intensity)) {
            return null;
        }
        if (!FriendPerception.hasLineOfSight(player, peek, FriendPerception.VisibilityMode.NORMAL)) {
            return null;
        }

        double darkness = darknessScore(level, cover, feet, anchor);
        double occlusion = occlusionScore(level, hidden, cover, peek);
        double tunnel = tunnelShapeScore(level, cover, face, snapshot, anchor);
        double escape = escapeScore(level, feet, face);
        double attention = playerAttentionScore(player, peek, intensity);
        double envBonus = environmentBonus(snapshot.environmentType(), anchor);
        double recent = MineShortTermMemory.recentActivityBonus(data, cover, level.getGameTime(), anchor);
        double psycho = psychologicalImpact(anchor, snapshot);
        double exposurePenalty = FriendPerception.isPointInStrictFov(player, peek) && intensity < 3 ? 24.0D : 0.0D;
        double distancePenalty = distancePenalty(snapshot.environmentType(), distance);
        double finalScore = darkness * 1.4D + occlusion * 1.8D + tunnel * 1.5D + escape * 1.2D
                + attention * 1.6D + envBonus + recent + psycho - exposurePenalty - distancePenalty;
        if (finalScore < 24.0D) {
            return null;
        }

        CoverType type = coverTypeFor(anchor);
        PeekSide side = peekSide(face, player);
        int height = availableHeight(level, feet);
        boolean low = height <= 2;
        String meta = "mine=" + snapshot.environmentType().name() + " anchor=" + anchor.name()
                + " conf=" + round(snapshot.confidence());
        float yaw = yawToward(player.position(), hidden);
        return new CoverCandidate(cover.immutable(), hidden, peek, face, side, type, distance,
                0.0D, 0.0D, occlusion, darkness, envBonus, 0.0D, finalScore, yaw, yaw,
                height, true, true, true, low, meta);
    }

    private static MinePeekAnchorType classifyAnchor(ServerLevel level, ServerPlayer player, MineContextSnapshot snapshot,
                                                    BlockPos cover, BlockState state) {
        MineEnvironmentType env = snapshot.environmentType();
        if (env == MineEnvironmentType.HYBRID) {
            if (isArtificialEdge(level, cover)) return MinePeekAnchorType.ARTIFICIAL_TO_NATURAL_BOUNDARY;
            if (nearMineshaftFeature(level, cover)) return MinePeekAnchorType.MINESHAFT_TO_CAVE_CROSSING;
            return MinePeekAnchorType.TRANSITION_EDGE;
        }
        if (env == MineEnvironmentType.GENERATED_MINESHAFT) {
            if (state.is(Blocks.COBWEB)) return MinePeekAnchorType.COBWEB_SILHOUETTE;
            if (state.is(Blocks.OAK_FENCE)) return player.getRandom().nextBoolean() ? MinePeekAnchorType.MINESHAFT_SUPPORT_LEFT : MinePeekAnchorType.MINESHAFT_SUPPORT_RIGHT;
            if (state.is(Blocks.OAK_PLANKS) || state.is(Blocks.OAK_LOG)) return MinePeekAnchorType.PLANK_CORNER_PEEK;
            if (MineContextAdapter.isRail(state)) return MinePeekAnchorType.RAIL_TUNNEL_END;
            if (isSolidCover(level, state, cover) && nearMineshaftFeature(level, cover)) return MinePeekAnchorType.CROSS_CORRIDOR_EDGE;
            return null;
        }
        if (env == MineEnvironmentType.PLAYER_DUG_MINE) {
            if (isTorch(state)) return MinePeekAnchorType.LAST_TORCH_WATCHER;
            if (state.getBlock() instanceof LadderBlock) return MinePeekAnchorType.LADDER_SHAFT_EDGE;
            if (state.getBlock() instanceof StairBlock) return MinePeekAnchorType.STAIRCASE_CORNER;
            if (isSolidCover(level, state, cover) && sideBranchScore(level, cover) >= 2) return MinePeekAnchorType.PLAYER_BRANCH_ENTRANCE;
            if (isSolidCover(level, state, cover) && isDeadEndNear(level, cover)) return MinePeekAnchorType.DEAD_END_EXIT;
            if (isSolidCover(level, state, cover) && isStraightTunnelEnd(level, cover)) return MinePeekAnchorType.STRIP_MINE_END;
            if (isSolidCover(level, state, cover) && level.getBrightness(LightLayer.BLOCK, cover) <= 5) return MinePeekAnchorType.TORCH_GAP_SHADOW;
            return null;
        }
        if (env == MineEnvironmentType.NATURAL_CAVE) {
            if (state.getFluidState().is(FluidTags.WATER)) return MinePeekAnchorType.WATERFALL_SILHOUETTE;
            if (state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.LAVA)) return MinePeekAnchorType.LAVA_BACKLIGHT_SILHOUETTE;
            if (isCeilingShadow(level, cover)) return MinePeekAnchorType.CEILING_SHADOW;
            if (isLedge(level, cover)) return MinePeekAnchorType.CAVE_LEDGE_WATCHER;
            if (isSolidCover(level, state, cover) && level.getBrightness(LightLayer.BLOCK, cover) <= 4) return MinePeekAnchorType.DARK_POCKET_PEEK;
            if (isSolidCover(level, state, cover)) return MinePeekAnchorType.CAVE_WALL_EDGE;
        }
        return null;
    }

    private static BlockPos hiddenFeetFor(ServerLevel level, BlockPos cover, Direction face, MinePeekAnchorType anchor) {
        if (anchor == MinePeekAnchorType.CEILING_SHADOW) {
            BlockPos feet = cover.above();
            return FriendCoverQuery.canStandAt(level, feet, FULL_HEIGHT) ? feet : null;
        }
        Direction back = face.getOpposite();
        BlockPos[] tries = {
                cover.relative(back).below(),
                cover.relative(back),
                cover.relative(back, 2).below(),
                cover.relative(back).relative(back.getClockWise()).below(),
                cover.relative(back).relative(back.getCounterClockWise()).below()
        };
        for (BlockPos feet : tries) {
            if (FriendCoverQuery.canStandAt(level, feet, FULL_HEIGHT)) {
                return feet;
            }
        }
        return null;
    }

    private static Vec3 peekAnchor(MinePeekAnchorType anchor, BlockPos cover, Direction face, BlockPos feet) {
        Vec3 edge = cover.getCenter().add(face.getStepX() * 0.62D, 0.0D, face.getStepZ() * 0.62D);
        double y = switch (anchor) {
            case CEILING_SHADOW, CAVE_LEDGE_WATCHER -> 1.65D;
            case COBWEB_SILHOUETTE, RAIL_TUNNEL_END, STRIP_MINE_END -> 2.25D;
            default -> 2.55D;
        };
        return edge.add(0.0D, y - 0.5D, 0.0D);
    }

    private static CoverType coverTypeFor(MinePeekAnchorType anchor) {
        return switch (anchor) {
            case CEILING_SHADOW -> CoverType.CEILING_HOLE;
            case LADDER_SHAFT_EDGE, STAIRCASE_CORNER -> CoverType.LOW_CEILING;
            default -> CoverType.CAVE_EDGE;
        };
    }

    private static List<CoverCandidate> sort(List<CoverCandidate> candidates) {
        candidates.sort(Comparator.comparingDouble(CoverCandidate::finalScore).reversed());
        return candidates;
    }

    private static boolean isMineContext(PlayerEnvironmentContext context) {
        return context == PlayerEnvironmentContext.CAVE
                || context == PlayerEnvironmentContext.MINESHAFT
                || context == PlayerEnvironmentContext.UNDERGROUND_TUNNEL
                || context == PlayerEnvironmentContext.LOW_CEILING_AREA;
    }

    private static boolean distanceOk(MineEnvironmentType type, MinePeekAnchorType anchor, double distance, int intensity) {
        double min = intensity >= 3 ? 4.0D : 7.0D;
        double max = switch (type) {
            case GENERATED_MINESHAFT -> 38.0D;
            case PLAYER_DUG_MINE -> 30.0D;
            case NATURAL_CAVE, HYBRID -> 36.0D;
            default -> 28.0D;
        };
        if (anchor == MinePeekAnchorType.RAIL_TUNNEL_END || anchor == MinePeekAnchorType.CAVE_LEDGE_WATCHER) {
            max += 12.0D;
        }
        return distance >= min && distance <= max;
    }

    private static double darknessScore(ServerLevel level, BlockPos cover, BlockPos feet, MinePeekAnchorType anchor) {
        int coverLight = level.getBrightness(LightLayer.BLOCK, cover);
        int feetLight = level.getBrightness(LightLayer.BLOCK, feet);
        double score = Mth.clamp(13.0D - Math.min(coverLight, feetLight), 0.0D, 13.0D);
        if (anchor == MinePeekAnchorType.LAVA_BACKLIGHT_SILHOUETTE) {
            score += 8.0D;
        }
        return score;
    }

    private static double occlusionScore(ServerLevel level, Vec3 hidden, BlockPos cover, Vec3 peek) {
        boolean hiddenBlocked = isBlocked(level, hidden.add(0.0D, 1.7D, 0.0D), cover.getCenter());
        boolean peekClear = level.clip(new ClipContext(cover.getCenter(), peek, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).getType() == HitResult.Type.MISS;
        return (hiddenBlocked ? 24.0D : 7.0D) + (peekClear ? 14.0D : 0.0D);
    }

    private static double tunnelShapeScore(ServerLevel level, BlockPos cover, Direction face, MineContextSnapshot snapshot, MinePeekAnchorType anchor) {
        double score = snapshot.regularTunnelScore() * 0.8D + snapshot.branches() * 1.2D;
        if (sideBranchScore(level, cover) >= 2) score += 12.0D;
        if (anchor == MinePeekAnchorType.RETURN_PATH_WATCHER || anchor == MinePeekAnchorType.DEAD_END_EXIT) score += 10.0D;
        if (isSolidCover(level, level.getBlockState(cover.relative(face.getClockWise())), cover.relative(face.getClockWise()))) score += 4.0D;
        if (isSolidCover(level, level.getBlockState(cover.relative(face.getCounterClockWise())), cover.relative(face.getCounterClockWise()))) score += 4.0D;
        return score;
    }

    private static double escapeScore(ServerLevel level, BlockPos feet, Direction face) {
        int exits = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = feet.relative(direction);
            if (FriendCoverQuery.canStandAt(level, pos, FULL_HEIGHT)) {
                exits++;
            }
        }
        return exits >= 2 ? 12.0D : exits == 1 ? 6.0D : -8.0D;
    }

    private static double playerAttentionScore(ServerPlayer player, Vec3 peek, int intensity) {
        if (FriendPerception.isPointInSoftFov(player, peek)) return 18.0D;
        if (FriendPerception.isPointInFov(player, peek, 135.0D)) return 8.0D;
        return intensity >= 2 ? 2.0D : -12.0D;
    }

    private static double environmentBonus(MineEnvironmentType env, MinePeekAnchorType anchor) {
        return switch (env) {
            case GENERATED_MINESHAFT -> anchor.name().startsWith("MINESHAFT") || anchor == MinePeekAnchorType.RAIL_TUNNEL_END
                    || anchor == MinePeekAnchorType.COBWEB_SILHOUETTE || anchor == MinePeekAnchorType.PLANK_CORNER_PEEK ? 24.0D : 6.0D;
            case PLAYER_DUG_MINE -> anchor.name().startsWith("PLAYER") || anchor == MinePeekAnchorType.LAST_TORCH_WATCHER
                    || anchor == MinePeekAnchorType.DEAD_END_EXIT || anchor == MinePeekAnchorType.STRIP_MINE_END ? 28.0D : 8.0D;
            case NATURAL_CAVE -> anchor.name().startsWith("CAVE") || anchor == MinePeekAnchorType.DARK_POCKET_PEEK
                    || anchor == MinePeekAnchorType.CEILING_SHADOW ? 24.0D : 8.0D;
            case HYBRID -> anchor == MinePeekAnchorType.TRANSITION_EDGE || anchor == MinePeekAnchorType.ARTIFICIAL_TO_NATURAL_BOUNDARY
                    || anchor == MinePeekAnchorType.PLAYER_TUNNEL_TO_CAVE_EXIT ? 30.0D : 12.0D;
            default -> 0.0D;
        };
    }

    private static double psychologicalImpact(MinePeekAnchorType anchor, MineContextSnapshot snapshot) {
        return switch (anchor) {
            case LAST_TORCH_WATCHER, DEAD_END_EXIT, RETURN_PATH_WATCHER, PLAYER_BRANCH_ENTRANCE -> 22.0D;
            case ORE_POCKET_PEEK, TORCH_GAP_SHADOW, STRIP_MINE_END -> 16.0D;
            case COBWEB_SILHOUETTE, RAIL_TUNNEL_END, MINESHAFT_TO_CAVE_CROSSING -> 14.0D;
            default -> snapshot.confidence() * 10.0D;
        };
    }

    private static double distancePenalty(MineEnvironmentType type, double distance) {
        double ideal = switch (type) {
            case GENERATED_MINESHAFT -> 22.0D;
            case PLAYER_DUG_MINE -> 15.0D;
            case NATURAL_CAVE, HYBRID -> 20.0D;
            default -> 18.0D;
        };
        return Math.abs(distance - ideal) * 0.7D;
    }

    private static boolean nearMineshaftFeature(ServerLevel level, BlockPos pos) {
        for (BlockPos check : BlockPos.betweenClosed(pos.offset(-3, -2, -3), pos.offset(3, 2, 3))) {
            BlockState state = level.getBlockState(check);
            if (MineContextAdapter.isRail(state) || state.is(Blocks.COBWEB) || state.is(Blocks.OAK_FENCE) || state.is(Blocks.OAK_PLANKS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArtificialEdge(ServerLevel level, BlockPos pos) {
        int regular = 0;
        int cave = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            if (level.getBlockState(side).isAir() && level.getBlockState(side.above()).isAir()) regular++;
            if (level.getBlockState(side).is(Blocks.CAVE_AIR) || level.getBlockState(side.above()).is(Blocks.CAVE_AIR)) cave++;
        }
        return regular >= 2 && cave >= 1;
    }

    private static int sideBranchScore(ServerLevel level, BlockPos pos) {
        int score = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            if (level.getBlockState(side).isAir() && level.getBlockState(side.above()).isAir()) {
                score++;
            }
        }
        return score;
    }

    private static boolean isDeadEndNear(ServerLevel level, BlockPos pos) {
        int airSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            if (level.getBlockState(side).isAir() && level.getBlockState(side.above()).isAir()) {
                airSides++;
            }
        }
        return airSides <= 1;
    }

    private static boolean isStraightTunnelEnd(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int run = 0;
            for (int i = 1; i <= 7; i++) {
                BlockPos next = pos.relative(direction, i);
                if (level.getBlockState(next).isAir() && level.getBlockState(next.above()).isAir()) {
                    run++;
                }
            }
            if (run >= 5) return true;
        }
        return false;
    }

    private static boolean isCeilingShadow(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() && !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getBrightness(LightLayer.BLOCK, pos) <= 4;
    }

    private static boolean isLedge(ServerLevel level, BlockPos pos) {
        return isSolidCover(level, level.getBlockState(pos), pos) && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below(3)).isAir();
    }

    private static boolean isTorch(BlockState state) {
        return state.getBlock() instanceof TorchBlock || state.getBlock() instanceof WallTorchBlock || state.getBlock() instanceof LanternBlock;
    }

    private static boolean badBlock(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.CACTUS) || state.getFluidState().is(FluidTags.LAVA) || state.getFluidState().is(FluidTags.WATER);
    }

    private static boolean hasDangerBelow(ServerLevel level, BlockPos feet) {
        for (int i = 1; i <= 4; i++) {
            BlockState state = level.getBlockState(feet.below(i));
            if (state.getFluidState().is(FluidTags.LAVA)) return true;
            if (!state.getCollisionShape(level, feet.below(i)).isEmpty()) return false;
        }
        return true;
    }

    private static boolean isSolidCover(ServerLevel level, BlockState state, BlockPos pos) {
        Block block = state.getBlock();
        if (state.isAir() || block instanceof FenceBlock || block instanceof GlassBlock || block instanceof StainedGlassBlock
                || block instanceof TrapDoorBlock || block instanceof TorchBlock || block instanceof WallTorchBlock
                || block instanceof LadderBlock || block instanceof CarpetBlock || block instanceof ButtonBlock) {
            return false;
        }
        return state.isSolidRender(level, pos) && !state.getCollisionShape(level, pos).isEmpty();
    }

    private static int availableHeight(ServerLevel level, BlockPos feet) {
        int height = 0;
        for (int i = 0; i < 6; i++) {
            BlockPos pos = feet.above(i);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) break;
            height++;
        }
        return height;
    }

    private static Direction directionToPlayer(BlockPos cover, Vec3 player) {
        Vec3 delta = player.subtract(cover.getCenter()).multiply(1.0D, 0.0D, 1.0D);
        if (delta.lengthSqr() < 0.25D) return null;
        return Math.abs(delta.x) > Math.abs(delta.z)
                ? (delta.x > 0 ? Direction.EAST : Direction.WEST)
                : (delta.z > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    private static PeekSide peekSide(Direction face, ServerPlayer player) {
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        Vec3 normal = new Vec3(face.getStepX(), 0.0D, face.getStepZ());
        double cross = normal.x * look.z - normal.z * look.x;
        return cross >= 0.0D ? PeekSide.LEFT : PeekSide.RIGHT;
    }

    private static boolean isBlocked(ServerLevel level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).getType() != HitResult.Type.MISS;
    }

    private static float yawToward(Vec3 target, Vec3 from) {
        Vec3 delta = target.subtract(from);
        return (float) (Mth.atan2(delta.z, delta.x) * (180F / Math.PI)) - 90.0F;
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
