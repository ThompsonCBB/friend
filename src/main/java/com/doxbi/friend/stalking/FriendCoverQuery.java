package com.doxbi.friend.stalking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class FriendCoverQuery {
    private static final double FULL_HEIGHT = 3.55D;
    private static final double BODY_RADIUS = 0.44D;

    private FriendCoverQuery() {
    }

    public static PlayerEnvironmentContext detectContext(ServerLevel level, ServerPlayer player, CompoundTag data) {
        BlockPos center = player.blockPosition();
        int logs = 0;
        int leaves = 0;
        int glass = 0;
        int doors = 0;
        int stone = 0;
        int airAbove = 0;
        int ceiling = 0;
        int water = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-6, -2, -6), center.offset(6, 4, 6))) {
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS)) logs++;
            if (state.is(BlockTags.LEAVES)) leaves++;
            if (isWindow(state)) glass++;
            if (state.getBlock() instanceof DoorBlock) doors++;
            if (state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF) || state.is(Blocks.COBBLESTONE)) stone++;
            if (state.getFluidState().is(FluidTags.WATER)) water++;
        }
        for (int y = 1; y <= 4; y++) {
            if (level.getBlockState(center.above(y)).isAir()) {
                airAbove++;
            } else {
                ceiling++;
            }
        }
        boolean hasHome = data.getBoolean("friend_has_home");
        BlockPos home = new BlockPos(data.getInt("friend_home_x"), data.getInt("friend_home_y"), data.getInt("friend_home_z"));

        /*
         * Old logic trusted friend_has_home blindly. If the home tracker ever saved a bed inside a
         * village, every nearby village house became HOME_INTERIOR / NEAR_WINDOW / NEAR_DOOR.
         * That made Friend treat vanilla villages as the player's personal base.
         *
         * This query is the last defensive layer before choosing stalking cover, so we reject
         * "home" context when either the saved home point or the player's current area looks like
         * a generated village. A real player base can still be a home, but a cluster with villagers,
         * bells, path blocks, many beds, composters, hay, and many doors is treated as VILLAGE.
         */
        boolean rawNearHome = hasHome && home.distSqr(center) < 80.0D * 80.0D;
        boolean currentAreaLooksVillage = cachedLikelyVillageArea(level, data, center, "friend_current_area_village", 200L);
        boolean savedHomeLooksVillage = hasHome && rawNearHome
                && cachedLikelyVillageArea(level, data, home, "friend_saved_home_village", 400L);
        boolean trustedHome = hasHome && !currentAreaLooksVillage && !savedHomeLooksVillage;
        boolean nearHome = trustedHome && home.distSqr(center) < 38.0D * 38.0D;

        if (currentAreaLooksVillage || savedHomeLooksVillage || ((doors > 2) && !nearHome)) return PlayerEnvironmentContext.VILLAGE;
        if (glass > 0 && nearHome) return PlayerEnvironmentContext.NEAR_WINDOW;
        if (doors > 0 && nearHome) return PlayerEnvironmentContext.NEAR_DOOR;
        if (nearHome && !level.canSeeSky(center)) return PlayerEnvironmentContext.HOME_INTERIOR;
        if (nearHome) return PlayerEnvironmentContext.HOME_EXTERIOR;
        if (water > 8) return PlayerEnvironmentContext.NEAR_WATER;
        if (ceiling >= 2 && player.getY() <= 56.0D) return PlayerEnvironmentContext.LOW_CEILING_AREA;
        if (player.getY() <= 35.0D || (!level.canSeeSky(center) && stone > 80)) return PlayerEnvironmentContext.CAVE;
        if (stone > 120 && !level.canSeeSky(center)) return PlayerEnvironmentContext.UNDERGROUND_TUNNEL;
        if (logs >= 4 && leaves >= 8 && level.canSeeSky(center)) return PlayerEnvironmentContext.FOREST;
        return PlayerEnvironmentContext.OPEN_FIELD;
    }

    private static boolean cachedLikelyVillageArea(ServerLevel level, CompoundTag data, BlockPos center, String prefix, long ttlTicks) {
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;
        int bandY = center.getY() >> 4;
        long now = level.getGameTime();
        boolean sameCachedCell = data.contains(prefix + "_cx")
                && data.getInt(prefix + "_cx") == chunkX
                && data.getInt(prefix + "_cz") == chunkZ
                && data.getInt(prefix + "_by") == bandY;

        if (sameCachedCell && now < data.getLong(prefix + "_expires")) {
            return data.getBoolean(prefix + "_value");
        }

        /*
         * If the saved point is not loaded, do not force-load chunks just to classify
         * a possible village. Use the previous cached value when we have one; otherwise
         * fail closed as non-village until the player is close enough for the chunk to load.
         */
        if (!level.hasChunkAt(center)) {
            return sameCachedCell && data.getBoolean(prefix + "_value");
        }

        boolean value = isLikelyVillageArea(level, center);
        data.putInt(prefix + "_cx", chunkX);
        data.putInt(prefix + "_cz", chunkZ);
        data.putInt(prefix + "_by", bandY);
        data.putLong(prefix + "_expires", now + ttlTicks);
        data.putBoolean(prefix + "_value", value);
        return value;
    }

    private static boolean isLikelyVillageArea(ServerLevel level, BlockPos center) {
        if (!level.hasChunkAt(center)) {
            return false;
        }

        int villagers = level.getEntitiesOfClass(Villager.class, new AABB(center).inflate(48.0D)).size();
        int bells = 0;
        int beds = 0;
        int doors = 0;
        int pathBlocks = 0;
        int composters = 0;
        int hayBlocks = 0;
        int workstations = 0;

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - 18; x <= center.getX() + 18; x++) {
            for (int z = center.getZ() - 18; z <= center.getZ() + 18; z++) {
                for (int y = center.getY() - 5; y <= center.getY() + 6; y++) {
                    mutable.set(x, y, z);
                    if (!level.hasChunkAt(mutable)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(mutable);
                    Block block = state.getBlock();

                    if (state.is(Blocks.BELL)) bells++;
                    if (block instanceof BedBlock) beds++;
                    if (block instanceof DoorBlock) doors++;
                    if (state.is(Blocks.DIRT_PATH)) pathBlocks++;
                    if (state.is(Blocks.COMPOSTER)) composters++;
                    if (state.is(Blocks.HAY_BLOCK)) hayBlocks++;
                    if (isVillageWorkstation(state)) workstations++;
                }
            }
        }

        int score = 0;
        score += villagers * 28;
        score += bells * 55;
        score += Math.min(beds, 8) * 10;
        score += Math.min(doors, 12) * 4;
        score += Math.min(pathBlocks, 24) * 3;
        score += composters * 10;
        score += hayBlocks * 5;
        score += workstations * 8;

        return score >= 55 || villagers >= 2 || (bells > 0 && (beds > 0 || pathBlocks > 3 || workstations > 0));
    }

    private static boolean isVillageWorkstation(BlockState state) {
        return state.is(Blocks.BLAST_FURNACE)
                || state.is(Blocks.SMOKER)
                || state.is(Blocks.CARTOGRAPHY_TABLE)
                || state.is(Blocks.FLETCHING_TABLE)
                || state.is(Blocks.GRINDSTONE)
                || state.is(Blocks.LECTERN)
                || state.is(Blocks.LOOM)
                || state.is(Blocks.SMITHING_TABLE)
                || state.is(Blocks.STONECUTTER)
                || state.is(Blocks.BREWING_STAND)
                || state.is(Blocks.BARREL)
                || state.is(Blocks.CAULDRON);
    }

    public static List<CoverCandidate> query(ServerLevel level, ServerPlayer player, CompoundTag data,
                                             PlayerEnvironmentContext context, CoverType forcedType, int intensity) {
        int radius = switch (context) {
            case HOME_INTERIOR, HOME_EXTERIOR, NEAR_WINDOW, NEAR_DOOR -> 18;
            case CAVE, MINESHAFT, UNDERGROUND_TUNNEL, LOW_CEILING_AREA -> 30;
            case FOREST -> 38;
            case OPEN_FIELD -> forcedType == CoverType.DISTANT_OBSERVE ? 76 : 44;
            default -> 28;
        };
        List<CoverCandidate> rough = new ArrayList<>();
        if (forcedType == null || forcedType == CoverType.CAVE_EDGE || forcedType == CoverType.WALL_CORNER || forcedType == CoverType.LOW_CEILING) {
            rough.addAll(MinePeekAdapter.query(level, player, data, context, intensity));
        }
        BlockPos center = player.blockPosition();
        int step = radius > 32 ? 2 : 1;
        int radiusSq = radius * radius;
        for (int x = -radius; x <= radius; x += step) {
            for (int z = -radius; z <= radius; z += step) {
                if (x * x + z * z > radiusSq) {
                    continue;
                }
                for (int y = -5; y <= 5; y++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    addCandidateIfMatches(level, player, data, context, forcedType, intensity, rough, pos, state);
                    if (rough.size() > 72) {
                        return scoreAndSort(level, player, data, context, rough, intensity);
                    }
                }
            }
        }
        return scoreAndSort(level, player, data, context, rough, intensity);
    }

    private static void addCandidateIfMatches(ServerLevel level, ServerPlayer player, CompoundTag data,
                                               PlayerEnvironmentContext context, CoverType forcedType, int intensity,
                                               List<CoverCandidate> result, BlockPos cover, BlockState state) {
        CoverType type = forcedSpecialType(level, player, cover, state, forcedType);
        if (type == null) {
            type = classify(level, player, state, cover, context);
        }
        if (type == null && forcedType == null && (context == PlayerEnvironmentContext.CAVE
                || context == PlayerEnvironmentContext.UNDERGROUND_TUNNEL
                || context == PlayerEnvironmentContext.LOW_CEILING_AREA
                || context == PlayerEnvironmentContext.HOME_INTERIOR)) {
            if (isCeilingHole(level, player, cover, state)) {
                type = CoverType.CEILING_HOLE;
            } else if (isFloorHole(level, player, cover, state)) {
                type = CoverType.FLOOR_HOLE;
            }
        }
        if (type == null || (forcedType != null && forcedType != type)) {
            return;
        }
        Direction face = directionFromCoverToPlayer(cover, player.position());
        if (face == null) {
            return;
        }
        BlockPos hiddenFeet = hiddenFeetFor(type, cover, face);
        if (hiddenFeet == null) {
            return;
        }
        int availableHeight = availableHeight(level, hiddenFeet);
        boolean full = canStandAt(level, hiddenFeet, FULL_HEIGHT);
        boolean supported = hasSupportAt(level, hiddenFeet);
        boolean low = supported && availableHeight >= 1
                && (type == CoverType.LOW_CEILING || type == CoverType.CEILING_HOLE || type == CoverType.FLOOR_HOLE);
        if (!full && !low && type != CoverType.DISTANT_OBSERVE) {
            return;
        }
        Vec3 hidden = Vec3.atBottomCenterOf(hiddenFeet);
        Vec3 peek = peekAnchor(type, cover, face, hiddenFeet);
        double distance = player.position().distanceTo(peek);
        if (!distanceOk(type, context, distance)) {
            return;
        }
        if (FriendPerception.isPointInStrictFov(player, peek) && intensity < 3 && type != CoverType.WINDOW) {
            return;
        }
        double coverQuality = coverQuality(level, type, cover, face, hiddenFeet);
        if (coverQuality <= 0.0D) {
            return;
        }
        double darkness = darknessScore(level, hiddenFeet, type);
        double contextScore = contextScore(type, context);
        double recency = FriendStalkingMemory.recencyPenalty(data, cover, level.getGameTime());
        float yaw = yawToward(player.position(), hidden);
        PeekSide side = peekSide(face, player);
        double base = coverQuality + darkness + contextScore - recency;
        result.add(new CoverCandidate(cover.immutable(), hidden, peek, face, side, type, distance,
                0.0D, 0.0D, coverQuality, darkness, contextScore, recency, base, yaw, yaw,
                availableHeight, true, true, full, !full, ""));
    }

    private static List<CoverCandidate> scoreAndSort(ServerLevel level, ServerPlayer player, CompoundTag data,
                                                     PlayerEnvironmentContext context, List<CoverCandidate> rough, int intensity) {
        List<CoverCandidate> scored = new ArrayList<>();
        rough.sort(Comparator.comparingDouble(CoverCandidate::finalScore).reversed());
        int limit = Math.min(rough.size(), 16);
        for (int i = 0; i < limit; i++) {
            CoverCandidate c = rough.get(i);
            FriendPerception.VisibilityMode mode = c.coverType() == CoverType.WINDOW
                    ? FriendPerception.VisibilityMode.THROUGH_GLASS : FriendPerception.VisibilityMode.NORMAL;
            AABB hiddenBox = FriendPerception.visualBox(c.hiddenAnchor(), 0.82D, Math.max(1.2D, Math.min(FULL_HEIGHT, c.availableHeight())));
            AABB peekBox = peekVisualBox(c);
            double hidden = FriendPerception.calculateVisibleFraction(player, hiddenBox, mode);
            double peek = FriendPerception.calculateVisibleFraction(player, peekBox, mode);
            if (!FriendPerception.hasLineOfSight(player, c.peekAnchor(), mode) || peek <= 0.0D) {
                continue;
            }
            double target = targetFraction(c, intensity);
            double fractionScore = 45.0D - Math.abs(peek - target) * 150.0D;
            double hiddenPenalty = hidden > 0.12D && c.coverType() != CoverType.WINDOW ? hidden * 95.0D : 0.0D;
            double openPenalty = peek > 0.52D && intensity < 3 ? (peek - 0.52D) * 110.0D : 0.0D;
            double invisiblePenalty = peek < 0.045D ? 35.0D : 0.0D;
            double fovScore = FriendPerception.isPointInSoftFov(player, c.peekAnchor()) ? 16.0D : -22.0D;
            double finalScore = c.finalScore() + fractionScore + fovScore - hiddenPenalty - openPenalty - invisiblePenalty;
            String reject = c.rejectReason();
            if (peek <= 0.0D) reject = appendReason(reject, "fully_occluded");
            if (hiddenPenalty > 0) reject = appendReason(reject, "hidden_anchor_too_visible");
            if (invisiblePenalty > 0) reject = appendReason(reject, "peek_too_hidden");
            if (openPenalty > 0) reject = appendReason(reject, "peek_too_open");
            CoverCandidate scoredCandidate = c.withFractions(hidden, peek, finalScore, reject);
            if (isSceneVisibilityValid(scoredCandidate, intensity)) {
                scored.add(scoredCandidate);
            }
        }
        scored.sort(Comparator.comparingDouble(CoverCandidate::finalScore).reversed());
        return scored;
    }

    private static String appendReason(String current, String next) {
        return current == null || current.isBlank() ? next : current + " " + next;
    }

    private static boolean isSceneVisibilityValid(CoverCandidate c, int intensity) {
        double min = c.coverType() == CoverType.DISTANT_OBSERVE ? 0.05D : c.coverType() == CoverType.CAVE_EDGE ? 0.06D : 0.08D;
        double max = intensity >= 3 ? 0.70D : c.coverType() == CoverType.WINDOW ? 0.52D : 0.42D;
        if (c.visibleFractionPeek() < min || c.visibleFractionPeek() > max) {
            return false;
        }
        return c.coverType() == CoverType.WINDOW || c.visibleFractionHidden() <= 0.35D;
    }

    public static boolean canStandAt(ServerLevel level, BlockPos feet, double height) {
        if (!level.hasChunkAt(feet) || level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) {
            return false;
        }
        if (bad(level.getBlockState(feet.below())) || bad(level.getBlockState(feet))) {
            return false;
        }
        int air = Math.max(1, Mth.ceil(height));
        for (int i = 0; i < air; i++) {
            BlockPos pos = feet.above(i);
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        double x = feet.getX() + 0.5D;
        double z = feet.getZ() + 0.5D;
        return level.noCollision(new AABB(x - BODY_RADIUS, feet.getY(), z - BODY_RADIUS, x + BODY_RADIUS, feet.getY() + height, z + BODY_RADIUS));
    }

    public static AABB peekVisualBox(Vec3 peekAnchor, Vec3 hiddenAnchor, CoverType coverType, int availableHeight) {
        return switch (coverType) {
            case CEILING_HOLE -> FriendPerception.visualBox(peekAnchor.add(0.0D, -0.85D, 0.0D), 0.72D, 1.2D);
            case FLOOR_HOLE -> FriendPerception.visualBox(peekAnchor, 0.72D, 1.35D);
            case LOW_CEILING -> FriendPerception.visualBox(peekAnchor, 0.72D, Math.max(0.9D, Math.min(2.0D, availableHeight)));
            case WINDOW -> FriendPerception.visualBox(peekAnchor, 0.9D, 2.2D);
            case DISTANT_OBSERVE -> FriendPerception.visualBox(hiddenAnchor, 0.72D, 4.2D);
            default -> FriendPerception.visualBox(peekAnchor, 0.64D, 2.4D);
        };
    }

    public static AABB peekVisualBox(CoverCandidate c) {
        return peekVisualBox(c.peekAnchor(), c.hiddenAnchor(), c.coverType(), c.availableHeight());
    }

    private static CoverType forcedSpecialType(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state, CoverType forcedType) {
        if (forcedType == CoverType.CEILING_HOLE && isCeilingHole(level, player, pos, state)) {
            return CoverType.CEILING_HOLE;
        }
        if (forcedType == CoverType.FLOOR_HOLE && isFloorHole(level, player, pos, state)) {
            return CoverType.FLOOR_HOLE;
        }
        if (forcedType == CoverType.DISTANT_OBSERVE && isDistantObserveSpot(level, player, pos, state)) {
            return CoverType.DISTANT_OBSERVE;
        }
        return null;
    }

    private static CoverType classify(ServerLevel level, ServerPlayer player, BlockState state, BlockPos pos, PlayerEnvironmentContext context) {
        if ((context == PlayerEnvironmentContext.OPEN_FIELD || context == PlayerEnvironmentContext.FOREST)
                && isDistantObserveSpot(level, player, pos, state)
                && player.position().distanceTo(pos.getCenter()) > 28.0D) {
            return CoverType.DISTANT_OBSERVE;
        }
        if (state.is(BlockTags.LOGS)) return CoverType.TREE;
        if (isWindow(state)) return CoverType.WINDOW;
        if (state.getBlock() instanceof DoorBlock) return CoverType.DOORFRAME;
        if (context == PlayerEnvironmentContext.LOW_CEILING_AREA && isSolidCover(level, state, pos)) return CoverType.LOW_CEILING;
        if ((context == PlayerEnvironmentContext.CAVE || context == PlayerEnvironmentContext.UNDERGROUND_TUNNEL) && isSolidCover(level, state, pos)) {
            return level.getBrightness(LightLayer.BLOCK, pos) <= 7 ? CoverType.CAVE_EDGE : CoverType.WALL_CORNER;
        }
        return isSolidCover(level, state, pos) ? CoverType.WALL_CORNER : null;
    }

    private static BlockPos hiddenFeetFor(CoverType type, BlockPos cover, Direction faceToPlayer) {
        Direction back = faceToPlayer.getOpposite();
        return switch (type) {
            case CEILING_HOLE -> cover.above(2);
            case FLOOR_HOLE -> cover.below(3);
            case DISTANT_OBSERVE -> cover.above();
            case WINDOW -> cover.relative(back, 2).below();
            case DOORFRAME -> cover.relative(back).below();
            case LOW_CEILING -> cover.relative(back).below();
            default -> cover.relative(back).below();
        };
    }

    private static Vec3 peekAnchor(CoverType type, BlockPos cover, Direction faceToPlayer, BlockPos hiddenFeet) {
        Vec3 edge = cover.getCenter().add(faceToPlayer.getStepX() * 0.58D, 0.0D, faceToPlayer.getStepZ() * 0.58D);
        return switch (type) {
            case CEILING_HOLE -> cover.getCenter().add(0.0D, -0.15D, 0.0D);
            case FLOOR_HOLE -> cover.getCenter().add(0.0D, 0.55D, 0.0D);
            case LOW_CEILING -> Vec3.atBottomCenterOf(hiddenFeet).add(0.0D, 0.7D, 0.0D);
            case WINDOW -> cover.getCenter().add(faceToPlayer.getStepX() * 0.72D, -0.4D, faceToPlayer.getStepZ() * 0.72D);
            case DISTANT_OBSERVE -> Vec3.atBottomCenterOf(hiddenFeet).add(0.0D, 2.7D, 0.0D);
            default -> edge.add(0.0D, 2.55D, 0.0D);
        };
    }

    private static Direction directionFromCoverToPlayer(BlockPos cover, Vec3 player) {
        Vec3 delta = player.subtract(cover.getCenter()).multiply(1.0D, 0.0D, 1.0D);
        if (delta.lengthSqr() < 0.25D) {
            return null;
        }
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

    private static int availableHeight(ServerLevel level, BlockPos feet) {
        int height = 0;
        for (int i = 0; i < 6; i++) {
            BlockPos pos = feet.above(i);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                break;
            }
            height++;
        }
        return height;
    }

    private static boolean hasSupportAt(ServerLevel level, BlockPos feet) {
        return level.hasChunkAt(feet)
                && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
                && !bad(level.getBlockState(feet.below()));
    }

    private static boolean distanceOk(CoverType type, PlayerEnvironmentContext context, double distance) {
        return switch (type) {
            case DISTANT_OBSERVE -> distance >= 24.0D && distance <= 82.0D;
            case TREE -> distance >= 8.0D && distance <= 42.0D;
            case WINDOW, DOORFRAME -> distance >= 3.0D && distance <= 24.0D;
            case CEILING_HOLE, FLOOR_HOLE, LOW_CEILING -> distance >= 4.0D && distance <= 28.0D;
            default -> context == PlayerEnvironmentContext.CAVE || context == PlayerEnvironmentContext.UNDERGROUND_TUNNEL
                    ? distance >= 7.0D && distance <= 34.0D : distance >= 6.0D && distance <= 38.0D;
        };
    }

    private static double coverQuality(ServerLevel level, CoverType type, BlockPos cover, Direction face, BlockPos hiddenFeet) {
        double quality = switch (type) {
            case TREE -> 44.0D;
            case WINDOW -> 42.0D;
            case DOORFRAME -> 38.0D;
            case CAVE_EDGE -> 40.0D;
            case LOW_CEILING -> 35.0D;
            default -> 32.0D;
        };
        if (isBlocked(level, Vec3.atCenterOf(hiddenFeet).add(0.0D, 1.0D, 0.0D), cover.getCenter().add(0.0D, 0.8D, 0.0D))) {
            quality += 22.0D;
        }
        if (isSolidCover(level, level.getBlockState(cover.relative(face.getClockWise())), cover.relative(face.getClockWise()))) {
            quality += 8.0D;
        }
        if (isSolidCover(level, level.getBlockState(cover.relative(face.getCounterClockWise())), cover.relative(face.getCounterClockWise()))) {
            quality += 8.0D;
        }
        return quality;
    }

    private static double darknessScore(ServerLevel level, BlockPos pos, CoverType type) {
        int light = level.getBrightness(LightLayer.BLOCK, pos);
        if (type == CoverType.WINDOW) return 5.0D;
        return Mth.clamp(12.0D - light, 0.0D, 12.0D);
    }

    private static double contextScore(CoverType type, PlayerEnvironmentContext context) {
        return switch (context) {
            case HOME_INTERIOR, HOME_EXTERIOR, NEAR_WINDOW -> type == CoverType.WINDOW ? 35.0D : type == CoverType.DOORFRAME ? 24.0D : 12.0D;
            case FOREST -> type == CoverType.TREE ? 36.0D : -4.0D;
            case CAVE, UNDERGROUND_TUNNEL, LOW_CEILING_AREA -> type == CoverType.CAVE_EDGE || type == CoverType.LOW_CEILING ? 28.0D : 10.0D;
            case NEAR_DOOR -> type == CoverType.DOORFRAME ? 34.0D : 8.0D;
            default -> type == CoverType.DISTANT_OBSERVE ? 16.0D : 6.0D;
        };
    }

    private static double targetFraction(CoverCandidate candidate, int intensity) {
        String meta = candidate.rejectReason();
        double base;
        if (meta.contains("mine=GENERATED_MINESHAFT")) {
            base = 0.18D;
        } else if (meta.contains("mine=PLAYER_DUG_MINE")) {
            base = 0.32D;
        } else if (meta.contains("mine=NATURAL_CAVE")) {
            base = 0.23D;
        } else if (meta.contains("mine=HYBRID")) {
            base = 0.28D;
        } else {
            base = switch (candidate.coverType()) {
            case TREE -> 0.16D;
            case WINDOW -> 0.32D;
            case CAVE_EDGE -> 0.12D;
            case DISTANT_OBSERVE -> 0.28D;
            default -> 0.18D;
            };
        }
        return intensity >= 3 ? base + 0.12D : base;
    }

    private static boolean isSolidCover(ServerLevel level, BlockState state, BlockPos pos) {
        Block block = state.getBlock();
        if (state.isAir() || block instanceof FenceBlock || block instanceof FenceGateBlock || block instanceof GlassBlock
                || block instanceof StainedGlassBlock || block instanceof TrapDoorBlock || block instanceof TorchBlock
                || block instanceof WallTorchBlock || block instanceof LadderBlock || block instanceof CarpetBlock
                || block instanceof ButtonBlock) {
            return false;
        }
        return state.isSolidRender(level, pos) && !state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isWindow(BlockState state) {
        return state.getBlock() instanceof GlassBlock || state.getBlock() instanceof StainedGlassBlock || state.is(Blocks.GLASS_PANE);
    }

    private static boolean isCeilingHole(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        if (!state.isAir() || pos.getY() < player.blockPosition().getY() + 2 || pos.getY() > player.blockPosition().getY() + 6) {
            return false;
        }
        if (!level.getBlockState(pos.below()).isAir()) {
            return false;
        }
        if (level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) {
            return false;
        }
        return solidRing(level, pos) >= 2;
    }

    private static boolean isFloorHole(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        if (!state.isAir() || pos.getY() > player.blockPosition().getY() + 1 || pos.getY() < player.blockPosition().getY() - 3) {
            return false;
        }
        if (!level.getBlockState(pos.below()).isAir() || !level.getBlockState(pos.below(2)).isAir()) {
            return false;
        }
        return solidRing(level, pos) >= 2 && hasSupportAt(level, pos.below(3));
    }

    private static boolean isDistantObserveSpot(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        if (!isSolidCover(level, state, pos)) {
            return false;
        }
        BlockPos feet = pos.above();
        if (!canStandAt(level, feet, FULL_HEIGHT)) {
            return false;
        }
        double distance = player.position().distanceTo(Vec3.atBottomCenterOf(feet));
        return distance >= 24.0D && distance <= 82.0D;
    }

    private static int solidRing(ServerLevel level, BlockPos pos) {
        int solid = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            if (isSolidCover(level, level.getBlockState(side), side)) {
                solid++;
            }
        }
        return solid;
    }

    private static boolean bad(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.CACTUS)
                || state.getFluidState().is(FluidTags.WATER) || state.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean isBlocked(ServerLevel level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).getType() != HitResult.Type.MISS;
    }

    private static float yawToward(Vec3 target, Vec3 from) {
        Vec3 delta = target.subtract(from);
        return (float) (Mth.atan2(delta.z, delta.x) * (180F / Math.PI)) - 90.0F;
    }

    public static String debugLine(CoverCandidate c) {
        if (c == null) {
            return "no cover candidate";
        }
        return String.format(Locale.ROOT, "%s side=%s score=%.1f hidden=%.2f peek=%.2f dist=%.1f cover=%s",
                c.coverType(), c.peekSide(), c.finalScore(), c.visibleFractionHidden(), c.visibleFractionPeek(),
                c.distanceToPlayer(), c.coverBlock().toShortString());
    }
}
