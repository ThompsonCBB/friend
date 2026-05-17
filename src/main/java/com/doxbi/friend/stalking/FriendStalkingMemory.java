package com.doxbi.friend.stalking;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class FriendStalkingMemory {
    public static final String ROOT = "friend_stalking_memory";
    public static final String USED_COVERS = "used_covers";
    public static final String STATE = "state";
    public static final String CONTEXT = "context";
    public static final String COVER_TYPE = "cover_type";
    public static final String LAST_ANIMATION = "last_animation";
    public static final String LAST_REJECT = "last_reject";
    public static final String LAST_SCORE = "last_score";
    public static final String LAST_HIDDEN = "last_hidden_fraction";
    public static final String LAST_PEEK = "last_peek_fraction";
    public static final String LAST_COUNT = "last_candidate_count";
    public static final String LAST_USED_TICK = "last_used_tick";
    public static final String SPOTTED_COUNT = "spotted_count";
    public static final String IGNORED_COUNT = "ignored_count";
    public static final String ANXIETY = "anxiety";
    public static final String INTENSITY = "intensity";
    public static final String DEBUG = "debug";

    private FriendStalkingMemory() {
    }

    public static CompoundTag root(CompoundTag playerData) {
        CompoundTag root = playerData.getCompound(ROOT);
        playerData.put(ROOT, root);
        return root;
    }

    public static void rememberUsedCover(CompoundTag playerData, BlockPos pos, long time) {
        CompoundTag root = root(playerData);
        ListTag list = root.getList(USED_COVERS, Tag.TAG_COMPOUND);
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putLong("last", time);
        list.add(tag);
        while (list.size() > 50) {
            list.remove(0);
        }
        root.put(USED_COVERS, list);
        root.putLong(LAST_USED_TICK, time);
    }

    public static double recencyPenalty(CompoundTag playerData, BlockPos pos, long time) {
        ListTag list = root(playerData).getList(USED_COVERS, Tag.TAG_COMPOUND);
        double penalty = 0.0D;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            BlockPos old = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            if (old.distSqr(pos) > 10.0D * 10.0D) {
                continue;
            }
            long age = Math.max(0L, time - tag.getLong("last"));
            if (age < 20L * 180L) {
                penalty += 35.0D * (1.0D - age / (20.0D * 180.0D));
            }
        }
        return penalty;
    }

    public static void setDebugSummary(CompoundTag playerData, FriendStalkingState state, PlayerEnvironmentContext context,
                                       CoverCandidate candidate, int candidateCount, String animation, String reject) {
        CompoundTag root = root(playerData);
        root.putString(STATE, state.name());
        root.putString(CONTEXT, context.name());
        root.putInt(LAST_COUNT, candidateCount);
        root.putString(LAST_ANIMATION, animation);
        root.putString(LAST_REJECT, reject == null ? "" : reject);
        if (candidate != null) {
            root.putString(COVER_TYPE, candidate.coverType().name());
            root.putDouble(LAST_SCORE, candidate.finalScore());
            root.putDouble(LAST_HIDDEN, candidate.visibleFractionHidden());
            root.putDouble(LAST_PEEK, candidate.visibleFractionPeek());
        }
    }

    public static void spotted(CompoundTag playerData) {
        CompoundTag root = root(playerData);
        root.putInt(SPOTTED_COUNT, root.getInt(SPOTTED_COUNT) + 1);
        root.putDouble(ANXIETY, Math.min(100.0D, root.getDouble(ANXIETY) + 3.0D));
    }

    public static void ignored(CompoundTag playerData) {
        CompoundTag root = root(playerData);
        root.putInt(IGNORED_COUNT, root.getInt(IGNORED_COUNT) + 1);
        root.putDouble(ANXIETY, Math.min(100.0D, root.getDouble(ANXIETY) + 1.0D));
    }

    public static int intensity(CompoundTag playerData, int phase, boolean rage) {
        if (rage) {
            return 4;
        }
        int forced = root(playerData).getInt(INTENSITY);
        if (forced > 0) {
            return Math.min(4, forced);
        }
        return Math.max(0, Math.min(3, phase / 2));
    }

    public static void clear(CompoundTag playerData) {
        playerData.remove(ROOT);
    }
}
