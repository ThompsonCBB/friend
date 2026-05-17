package com.doxbi.friend.stalking;

import com.doxbi.friend.entity.FriendEntity;
import com.doxbi.friend.registry.FriendEntityTypes;
import com.doxbi.friend.registry.FriendSoundEvents;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

public final class FriendStalkingDirector {
    public static final String EVENT_PREFIX = "stalk_";
    private static final Random RANDOM = new Random();
    private static final String PHASE = "friend_phase";
    private static final String RAGE = "friend_rage";
    private static final String ACTIVE = "friend_active";
    private static final String EVENT_COOLDOWN = "friend_event_cooldown";
    private static final String STALK_FORCE = "friend_stalk_force";
    private static final String STALK_DEBUG_PARTICLES = "friend_stalk_debug_particles";

    private FriendStalkingDirector() {
    }

    public static boolean tryStart(ServerLevel level, ServerPlayer player, CompoundTag data, String forcedType) {
        Optional<CoverType> parsedForced = parseCoverType(forcedType);
        if (forcedType != null && !forcedType.isBlank() && parsedForced.isEmpty()) {
            FriendStalkingMemory.setDebugSummary(data, FriendStalkingState.SEARCHING_COVER,
                    FriendCoverQuery.detectContext(level, player, data), null, 0, "", "invalid_forced_type:" + forcedType);
            return false;
        }
        CoverType forced = parsedForced.orElse(null);
        int phase = Math.max(1, data.getInt(PHASE));
        boolean rage = data.getBoolean(RAGE);
        int intensity = FriendStalkingMemory.intensity(data, phase, rage);
        PlayerEnvironmentContext context = FriendCoverQuery.detectContext(level, player, data);
        FriendStalkingMemory.setDebugSummary(data, FriendStalkingState.SCANNING_CONTEXT, context, null, 0, "", "");

        List<CoverCandidate> candidates = FriendCoverQuery.query(level, player, data, context, forced, intensity);
        if (candidates.isEmpty() && forced == null && context == PlayerEnvironmentContext.OPEN_FIELD) {
            candidates = FriendCoverQuery.query(level, player, data, PlayerEnvironmentContext.OPEN_FIELD, CoverType.DISTANT_OBSERVE, intensity);
        }
        if (forced == CoverType.DISTANT_OBSERVE) {
            return spawnFallbackDwellerStand(level, player, data, context, phase, rage, Math.max(2, intensity), "forced_distant_stand");
        }

        if (candidates.isEmpty()) {
            // Do not waste events. If a cinematic cover-peek cannot find a perfect anchor,
            // fall back to a readable Man-from-the-Fog style distant stand instead of failing.
            String reason = forcedType != null && !forcedType.isBlank()
                    ? "forced_cover_failed_fallback_stand:" + forcedType
                    : "no_valid_cover_fallback_stand";
            if (spawnFallbackDwellerStand(level, player, data, context, phase, rage, Math.max(2, intensity), reason)) {
                return true;
            }
            FriendStalkingMemory.setDebugSummary(data, FriendStalkingState.SEARCHING_COVER, context, null, 0, "animation.friend.idle_stare", reason + "|fallback_failed");
            return false;
        }

        CoverCandidate chosen = choose(candidates, intensity);
        String eventId = eventId(chosen, intensity);
        String animation = animationName(eventId);
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return false;
        }
        friend.moveTo(chosen.hiddenAnchor().x, chosen.hiddenAnchor().y, chosen.hiddenAnchor().z, chosen.bodyYaw(), 0.0F);
        friend.configure(player.getUUID().toString(), eventId, lifetime(chosen, intensity), 0);
        friend.noPhysics = false;
        friend.setNoGravity(false);
        friend.setNoAi(false);
        friend.setCornerEscalated(false);
        CompoundTag tag = friend.getPersistentData();
        tag.putInt(FriendEntity.TAG_COVER_X, chosen.coverBlock().getX());
        tag.putInt(FriendEntity.TAG_COVER_Y, chosen.coverBlock().getY());
        tag.putInt(FriendEntity.TAG_COVER_Z, chosen.coverBlock().getZ());
        tag.putInt(FriendEntity.TAG_PEEK_PANIC_TICKS, 0);
        tag.putString("friend_cover_type", chosen.coverType().name());
        tag.putString("friend_peek_side", chosen.peekSide().name());
        tag.putDouble("friend_visible_fraction_hidden", chosen.visibleFractionHidden());
        tag.putDouble("friend_visible_fraction_peek", chosen.visibleFractionPeek());
        tag.putDouble("friend_peek_x", chosen.peekAnchor().x);
        tag.putDouble("friend_peek_y", chosen.peekAnchor().y);
        tag.putDouble("friend_peek_z", chosen.peekAnchor().z);
        tag.putInt("friend_stalking_state", stateFor(chosen).ordinal());
        tag.putInt("friend_spotted_required", spottedTicksRequired(phase, rage, chosen.coverType()));
        friend.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
        level.addFreshEntity(friend);

        data.putBoolean(ACTIVE, true);
        data.putLong(EVENT_COOLDOWN, level.getGameTime() + cooldown(phase, intensity, rage));
        FriendStalkingMemory.rememberUsedCover(data, chosen.coverBlock(), level.getGameTime());
        FriendStalkingMemory.setDebugSummary(data, FriendStalkingState.STAGING_AT_COVER, context, chosen, candidates.size(), animation, chosen.rejectReason());
        playStalkIntro(level, chosen);
        if (FriendStalkingMemory.root(data).getBoolean(STALK_DEBUG_PARTICLES)) {
            debugParticles(level, chosen);
        }
        return true;
    }

    public static boolean tickScene(ServerLevel level, ServerPlayer player, FriendEntity friend, CompoundTag data) {
        String event = friend.eventId();
        if (!isStalkingEvent(event)) {
            return false;
        }
        CompoundTag tag = friend.getPersistentData();
        Vec3 peek = new Vec3(tag.getDouble("friend_peek_x"), tag.getDouble("friend_peek_y"), tag.getDouble("friend_peek_z"));
        CoverType type = parseCoverType(tag.getString("friend_cover_type")).orElse(CoverType.WALL_CORNER);
        AABB visibleBox = FriendCoverQuery.peekVisualBox(peek, friend.position(), type, 5);
        FriendPerception.VisibilityMode mode = type == CoverType.WINDOW ? FriendPerception.VisibilityMode.THROUGH_GLASS : FriendPerception.VisibilityMode.NORMAL;
        double fraction = FriendPerception.calculateVisibleFraction(player, visibleBox, mode);
        boolean inStrict = FriendPerception.isPointInStrictFov(player, peek);
        boolean line = FriendPerception.hasLineOfSight(player, peek, mode);

        friend.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
        if (friend.isDebugForcedPeekActive()) {
            tag.putInt(FriendEntity.TAG_PEEK_PANIC_TICKS, 0);
            tag.putInt(FriendEntity.TAG_LIFETIME, Math.max(tag.getInt(FriendEntity.TAG_LIFETIME), 45));
            return true;
        }
        if (inStrict && line && fraction >= 0.08D) {
            int ticks = tag.getInt(FriendEntity.TAG_PEEK_PANIC_TICKS) + 1;
            tag.putInt(FriendEntity.TAG_PEEK_PANIC_TICKS, ticks);
            if (ticks >= tag.getInt("friend_spotted_required")) {
                return spotted(level, player, friend, data, type, fraction);
            }
        } else {
            tag.putInt(FriendEntity.TAG_PEEK_PANIC_TICKS, 0);
        }

        if (friend.tickCount == 18 || (friend.tickCount > 40 && friend.tickCount % 47 == 0 && RANDOM.nextFloat() < 0.28F)) {
            level.playSound(null, friend.blockPosition(), FriendSoundEvents.QUIET_BREATH.get(), SoundSource.AMBIENT, 0.055F, 0.82F + RANDOM.nextFloat() * 0.2F);
        }
        if (friend.tickCount > 70 && RANDOM.nextFloat() < 0.018F) {
            FriendStalkingMemory.ignored(data);
            slipBehind(level, player, friend, data, "ignored_exit");
            return true;
        }
        return true;
    }

    public static boolean isStalkingEvent(String event) {
        return event != null && event.startsWith(EVENT_PREFIX);
    }

    public static String debugStatus(CompoundTag data) {
        CompoundTag root = FriendStalkingMemory.root(data);
        return String.format(Locale.ROOT, "stalk state=%s context=%s cover=%s score=%.1f hidden=%.2f peek=%.2f count=%d anim=%s reject=%s spotted=%d ignored=%d anxiety=%.1f",
                root.getString(FriendStalkingMemory.STATE), root.getString(FriendStalkingMemory.CONTEXT),
                root.getString(FriendStalkingMemory.COVER_TYPE), root.getDouble(FriendStalkingMemory.LAST_SCORE),
                root.getDouble(FriendStalkingMemory.LAST_HIDDEN), root.getDouble(FriendStalkingMemory.LAST_PEEK),
                root.getInt(FriendStalkingMemory.LAST_COUNT), root.getString(FriendStalkingMemory.LAST_ANIMATION),
                root.getString(FriendStalkingMemory.LAST_REJECT), root.getInt(FriendStalkingMemory.SPOTTED_COUNT),
                root.getInt(FriendStalkingMemory.IGNORED_COUNT), root.getDouble(FriendStalkingMemory.ANXIETY));
    }

    public static void setDebug(CompoundTag data, boolean enabled) {
        FriendStalkingMemory.root(data).putBoolean(FriendStalkingMemory.DEBUG, enabled);
        FriendStalkingMemory.root(data).putBoolean(STALK_DEBUG_PARTICLES, enabled);
    }

    public static void setIntensity(CompoundTag data, int intensity) {
        FriendStalkingMemory.root(data).putInt(FriendStalkingMemory.INTENSITY, Mth.clamp(intensity, 0, 4));
    }

    public static void clearMemory(CompoundTag data) {
        FriendStalkingMemory.clear(data);
    }

    private static boolean spotted(ServerLevel level, ServerPlayer player, FriendEntity friend, CompoundTag data, CoverType type, double fraction) {
        // A peek being noticed must stay a peek: it should retreat, not convert into a cheap attack.
        FriendStalkingMemory.spotted(data);
        slipBehind(level, player, friend, data, type.name().toLowerCase(Locale.ROOT) + "_spotted");
        return true;
    }

    private static void slipBehind(ServerLevel level, ServerPlayer player, FriendEntity friend, CompoundTag data, String reason) {
        String side = friend.getPersistentData().getString("friend_peek_side");
        friend.setEventId("RIGHT".equals(side) ? "stalk_slip_right" : "stalk_slip_left");
        friend.getPersistentData().putInt(FriendEntity.TAG_LIFETIME, 16);

        // Do not push the real entity backwards. The retreat is visual-only through the animation,
        // otherwise Friend looks like he is flying away from the cover.
        friend.getNavigation().stop();
        friend.setDeltaMovement(Vec3.ZERO);

        FriendStalkingMemory.setDebugSummary(data, FriendStalkingState.SLIPPING_BEHIND,
                FriendCoverQuery.detectContext(level, player, data), null, 0, side, reason);
        level.playSound(null, friend.blockPosition(), FriendSoundEvents.DISAPPEAR_SOFT.get(), SoundSource.AMBIENT, 0.10F, 0.72F + RANDOM.nextFloat() * 0.18F);
    }

    private static boolean spawnFallbackDwellerStand(ServerLevel level, ServerPlayer player, CompoundTag data,
                                                     PlayerEnvironmentContext context, int phase, boolean rage,
                                                     int intensity, String reason) {
        Optional<BlockPos> spot = findFallbackStandSpot(level, player, data, intensity);
        if (spot.isEmpty()) {
            FriendStalkingMemory.setDebugSummary(data, FriendStalkingState.SEARCHING_COVER, context, null, 0,
                    "animation.friend.distant_idle", reason + "|fallback_failed");
            return false;
        }

        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            FriendStalkingMemory.setDebugSummary(data, FriendStalkingState.SEARCHING_COVER, context, null, 0,
                    "animation.friend.distant_idle", reason + "|entity_create_failed");
            return false;
        }

        BlockPos feet = spot.get();
        Vec3 face = feet.getCenter().add(0.0D, 2.05D, 0.0D);
        float yaw = yawToward(player.position(), Vec3.atBottomCenterOf(feet));
        int ignored = FriendStalkingMemory.root(data).getInt(FriendStalkingMemory.IGNORED_COUNT);
        int life = 20 * (ignored >= 2 || intensity >= 3 ? 15 + RANDOM.nextInt(8) : 9 + RANDOM.nextInt(7));

        friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, yaw, 0.0F);
        friend.configure(player.getUUID().toString(), "stalk_distant", life, 0);
        friend.noPhysics = false;
        friend.setNoGravity(false);
        friend.setNoAi(false);
        friend.setCornerEscalated(false);
        friend.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());

        CompoundTag tag = friend.getPersistentData();
        tag.putInt(FriendEntity.TAG_COVER_X, feet.getX());
        tag.putInt(FriendEntity.TAG_COVER_Y, feet.getY());
        tag.putInt(FriendEntity.TAG_COVER_Z, feet.getZ());
        tag.putInt(FriendEntity.TAG_PEEK_PANIC_TICKS, 0);
        tag.putString("friend_cover_type", CoverType.DISTANT_OBSERVE.name());
        tag.putString("friend_peek_side", PeekSide.LEFT.name());
        tag.putDouble("friend_visible_fraction_hidden", 0.0D);
        tag.putDouble("friend_visible_fraction_peek", 0.36D);
        tag.putDouble("friend_peek_x", face.x);
        tag.putDouble("friend_peek_y", face.y);
        tag.putDouble("friend_peek_z", face.z);
        tag.putInt("friend_stalking_state", FriendStalkingState.OBSERVING_DISTANT.ordinal());
        tag.putInt("friend_spotted_required", Math.max(2, spottedTicksRequired(phase, rage, CoverType.DISTANT_OBSERVE) - (ignored >= 2 ? 4 : 2)));

        level.addFreshEntity(friend);

        data.putBoolean(ACTIVE, true);
        data.putLong(EVENT_COOLDOWN, level.getGameTime() + cooldown(phase, Math.max(2, intensity), rage));
        FriendStalkingMemory.setDebugSummary(data, FriendStalkingState.OBSERVING_DISTANT, context, null, 0,
                "animation.friend.distant_idle", reason + "|fallback_dweller_stand");
        level.playSound(null, feet, FriendSoundEvents.QUIET_BREATH.get(), SoundSource.HOSTILE, 0.11F, 0.72F + RANDOM.nextFloat() * 0.16F);
        return true;
    }

    private static Optional<BlockPos> findFallbackStandSpot(ServerLevel level, ServerPlayer player, CompoundTag data, int intensity) {
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        if (look.lengthSqr() < 0.001D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        look = look.normalize();
        Vec3 side = new Vec3(-look.z, 0.0D, look.x).normalize();
        int ignored = FriendStalkingMemory.root(data).getInt(FriendStalkingMemory.IGNORED_COUNT);
        boolean bold = ignored >= 2 || intensity >= 3;

        // First pass: readable positions, usually in front or at the side.
        Optional<BlockPos> readable = findFallbackStandSpotPass(level, player, look, side, bold, true, 220);
        if (readable.isPresent()) {
            return readable;
        }
        // Second pass: any safe standable place around the player. Commands and survival events should not fail
        // just because the perfect cinematic FOV/LOS point was unavailable.
        return findFallbackStandSpotPass(level, player, look, side, true, false, 360);
    }

    private static Optional<BlockPos> findFallbackStandSpotPass(ServerLevel level, ServerPlayer player, Vec3 look, Vec3 side,
                                                               boolean bold, boolean requireReadable, int attempts) {
        for (int i = 0; i < attempts; i++) {
            double distance = bold ? 8.0D + RANDOM.nextInt(20) : 14.0D + RANDOM.nextInt(26);
            double lateral = (RANDOM.nextBoolean() ? 1.0D : -1.0D) * RANDOM.nextInt(bold ? 12 : 16);
            double forward;
            if (requireReadable) {
                forward = RANDOM.nextFloat() < 0.82F ? distance : -(8.0D + RANDOM.nextInt(14));
            } else {
                forward = switch (RANDOM.nextInt(4)) {
                    case 0 -> distance;
                    case 1 -> -distance;
                    case 2 -> RANDOM.nextInt(9) - 4;
                    default -> RANDOM.nextInt(9) - 4;
                };
                if (Math.abs(forward) < 3.0D) {
                    lateral = (RANDOM.nextBoolean() ? 1.0D : -1.0D) * (10.0D + RANDOM.nextInt(18));
                }
            }

            BlockPos rough = BlockPos.containing(player.position().add(look.scale(forward)).add(side.scale(lateral)));
            BlockPos feet = findStandableNear(level, rough, player.blockPosition().getY());
            if (feet == null || player.blockPosition().distSqr(feet) < 7.0D * 7.0D) {
                continue;
            }

            Vec3 face = feet.getCenter().add(0.0D, 2.05D, 0.0D);
            FriendPerception.VisibilityMode mode = FriendPerception.VisibilityMode.NORMAL;
            if (requireReadable) {
                if (!FriendPerception.hasLineOfSight(player, face, mode)) {
                    continue;
                }
                if (!bold && !FriendPerception.isPointInSoftFov(player, face)) {
                    continue;
                }
            }
            return Optional.of(feet.immutable());
        }
        return Optional.empty();
    }

    private static BlockPos findStandableNear(ServerLevel level, BlockPos rough, int preferredY) {
        int baseY = Mth.clamp(rough.getY(), level.getMinBuildHeight() + 2, level.getMaxBuildHeight() - 5);
        for (int dy = 5; dy >= -9; dy--) {
            BlockPos feet = new BlockPos(rough.getX(), baseY + dy, rough.getZ());
            if (FriendCoverQuery.canStandAt(level, feet, 3.55D)) {
                return feet;
            }
        }
        for (int dy = 5; dy >= -9; dy--) {
            BlockPos feet = new BlockPos(rough.getX(), Mth.clamp(preferredY + dy, level.getMinBuildHeight() + 2, level.getMaxBuildHeight() - 5), rough.getZ());
            if (FriendCoverQuery.canStandAt(level, feet, 3.55D)) {
                return feet;
            }
        }
        return null;
    }

    private static float yawToward(Vec3 target, Vec3 from) {
        Vec3 delta = target.subtract(from);
        return (float) (Mth.atan2(delta.z, delta.x) * (180.0D / Math.PI)) - 90.0F;
    }

    private static CoverCandidate choose(List<CoverCandidate> candidates, int intensity) {
        int pool = Math.min(candidates.size(), intensity >= 3 ? 5 : 3);
        double total = 0.0D;
        for (int i = 0; i < pool; i++) {
            total += Math.max(1.0D, candidates.get(i).finalScore());
        }
        double roll = RANDOM.nextDouble() * total;
        for (int i = 0; i < pool; i++) {
            roll -= Math.max(1.0D, candidates.get(i).finalScore());
            if (roll <= 0.0D) {
                return candidates.get(i);
            }
        }
        return candidates.get(0);
    }

    private static String eventId(CoverCandidate c, int intensity) {
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
            default -> intensity >= 2 && RANDOM.nextFloat() < 0.35F ? "stalk_corner_finger_" + side : "stalk_corner_" + side;
        };
    }

    public static String animationName(String eventId) {
        return switch (eventId) {
            case "stalk_distant" -> "animation.friend.idle_stare";
            case "stalk_slip_right", "stalk_slip_left" -> "animation.friend.peek_return";
            default -> "animation.friend.peek";
        };
    }

    private static FriendStalkingState stateFor(CoverCandidate c) {
        return switch (c.coverType()) {
            case TREE -> FriendStalkingState.PEEKING_TREE;
            case WINDOW -> FriendStalkingState.PEEKING_WINDOW;
            case CEILING_HOLE -> FriendStalkingState.PEEKING_TOP;
            case FLOOR_HOLE -> FriendStalkingState.PEEKING_BOTTOM;
            case LOW_CEILING -> FriendStalkingState.LOW_CEILING_PEEK;
            case DISTANT_OBSERVE -> FriendStalkingState.OBSERVING_DISTANT;
            default -> c.peekSide() == PeekSide.RIGHT ? FriendStalkingState.PEEKING_RIGHT : FriendStalkingState.PEEKING_LEFT;
        };
    }

    private static int spottedTicksRequired(int phase, boolean rage, CoverType type) {
        if (rage) return 1;
        int base = type == CoverType.DISTANT_OBSERVE ? 10 : type == CoverType.WINDOW ? 5 : 6;
        if (phase >= 7) return Math.max(1, base - 4);
        if (phase >= 5) return Math.max(2, base - 2);
        return base;
    }

    private static int lifetime(CoverCandidate c, int intensity) {
        int base = c.coverType() == CoverType.DISTANT_OBSERVE ? 20 * 12 : 20 * (4 + RANDOM.nextInt(4));
        return intensity >= 3 ? base + 50 : base;
    }

    private static long cooldown(int phase, int intensity, boolean rage) {
        if (rage) return 20L * 18L;
        int min = phase >= 7 ? 55 : phase >= 5 ? 75 : 95;
        int max = phase >= 7 ? 140 : phase >= 5 ? 190 : 260;
        if (intensity >= 3) {
            min -= 15;
            max -= 30;
        }
        return 20L * (min + RANDOM.nextInt(Math.max(1, max - min)));
    }

    private static void playStalkIntro(ServerLevel level, CoverCandidate chosen) {
        float volume = chosen.coverType() == CoverType.WINDOW ? 0.14F : 0.07F;
        level.playSound(null, BlockPos.containing(chosen.peekAnchor()), FriendSoundEvents.QUIET_BREATH.get(), SoundSource.AMBIENT, volume, 0.82F + RANDOM.nextFloat() * 0.16F);
    }

    private static void debugParticles(ServerLevel level, CoverCandidate c) {
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, c.hiddenAnchor().x, c.hiddenAnchor().y + 1.0D, c.hiddenAnchor().z, 3, 0.08D, 0.08D, 0.08D, 0.0D);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.peekAnchor().x, c.peekAnchor().y, c.peekAnchor().z, 3, 0.06D, 0.06D, 0.06D, 0.0D);
        level.sendParticles(ParticleTypes.PORTAL, c.coverBlock().getX() + 0.5D, c.coverBlock().getY() + 0.5D, c.coverBlock().getZ() + 0.5D, 4, 0.12D, 0.12D, 0.12D, 0.0D);
    }

    private static Optional<CoverType> parseCoverType(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.toLowerCase(Locale.ROOT);
        return switch (text) {
            case "corner", "wall", "wall_corner" -> Optional.of(CoverType.WALL_CORNER);
            case "tree" -> Optional.of(CoverType.TREE);
            case "window" -> Optional.of(CoverType.WINDOW);
            case "door", "doorframe" -> Optional.of(CoverType.DOORFRAME);
            case "cave", "cave_edge" -> Optional.of(CoverType.CAVE_EDGE);
            case "ceiling", "top" -> Optional.of(CoverType.CEILING_HOLE);
            case "floor", "bottom" -> Optional.of(CoverType.FLOOR_HOLE);
            case "low", "low_ceiling" -> Optional.of(CoverType.LOW_CEILING);
            case "distant", "observe" -> Optional.of(CoverType.DISTANT_OBSERVE);
            default -> Optional.empty();
        };
    }
}
