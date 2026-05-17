package com.doxbi.friend.stalking;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class MineShortTermMemory {
    private static final String ROOT = "friend_mine_memory";
    private static final String ACTIVITIES = "activities";
    private static final String LAST_X = "last_x";
    private static final String LAST_Y = "last_y";
    private static final String LAST_Z = "last_z";
    private static final String LAST_SAMPLE = "last_sample";
    private static final String LAST_ENV = "last_environment";
    private static final String LAST_FEATURES = "last_features";

    private MineShortTermMemory() {
    }

    public static CompoundTag root(CompoundTag data) {
        CompoundTag root = data.getCompound(ROOT);
        data.put(ROOT, root);
        return root;
    }

    public static void tickMovement(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        if (!level.dimension().equals(Level.OVERWORLD) || time < root(data).getLong(LAST_SAMPLE) + 20L) {
            return;
        }
        if (player.getY() > 62.0D && level.canSeeSky(player.blockPosition())) {
            decay(data, time);
            return;
        }
        CompoundTag root = root(data);
        BlockPos pos = player.blockPosition();
        BlockPos last = new BlockPos(root.getInt(LAST_X), root.getInt(LAST_Y), root.getInt(LAST_Z));
        root.putLong(LAST_SAMPLE, time);
        root.putInt(LAST_X, pos.getX());
        root.putInt(LAST_Y, pos.getY());
        root.putInt(LAST_Z, pos.getZ());

        MineContextSnapshot snapshot = MineContextAdapter.classify(level, player, data);
        root.putString(LAST_ENV, snapshot.environmentType().name());
        root.putString(LAST_FEATURES, snapshot.features());
        record(data, pos, time, MineActivityType.PLAYER_MOVED_THROUGH, snapshot.environmentType(), 1.0D);
        if (last.distSqr(pos) > 5.0D * 5.0D) {
            record(data, pos, time, MineActivityType.PLAYER_MOVED_FAR, snapshot.environmentType(), 1.3D);
        }
        BlockState below = level.getBlockState(pos.below());
        if (below.getBlock() instanceof LadderBlock || level.getBlockState(pos).getBlock() instanceof LadderBlock) {
            record(data, pos, time, MineActivityType.PLAYER_USED_LADDER, snapshot.environmentType(), 1.6D);
        }
        if (below.getBlock() instanceof StairBlock || level.getBlockState(pos).getBlock() instanceof StairBlock) {
            record(data, pos, time, MineActivityType.PLAYER_USED_STAIRS, snapshot.environmentType(), 1.4D);
        }
        if (snapshot.darkPockets() >= 4 && snapshot.naturalCaveScore() >= 10) {
            record(data, pos, time, MineActivityType.PLAYER_ENTERED_CAVE, snapshot.environmentType(), 1.2D);
        }
        decay(data, time);
    }

    public static void recordPlaced(ServerLevel level, ServerPlayer player, CompoundTag data, BlockPos pos, BlockState state, long time) {
        if (!isUnderground(level, pos)) {
            return;
        }
        MineEnvironmentType type = MineContextAdapter.classify(level, player, data).environmentType();
        if (state.getBlock() instanceof TorchBlock || state.getBlock() instanceof WallTorchBlock || state.is(Blocks.TORCH)) {
            record(data, pos, time, MineActivityType.PLAYER_PLACED_TORCH, type, 3.0D);
        } else {
            record(data, pos, time, MineActivityType.PLAYER_MOVED_THROUGH, type, 0.8D);
        }
    }

    public static void recordMined(ServerLevel level, ServerPlayer player, CompoundTag data, BlockPos pos, BlockState state, long time) {
        if (!isUnderground(level, pos)) {
            return;
        }
        MineEnvironmentType type = MineContextAdapter.classify(level, player, data).environmentType();
        record(data, pos, time, MineActivityType.PLAYER_MINED_BLOCK, type, isOre(state) ? 2.7D : 1.5D);
        if (isOre(state)) {
            record(data, pos, time, MineActivityType.PLAYER_FOUND_ORE_POCKET, type, 3.2D);
        }
    }

    public static double recentActivityBonus(CompoundTag data, BlockPos pos, long time, MinePeekAnchorType anchorType) {
        ListTag list = root(data).getList(ACTIVITIES, Tag.TAG_COMPOUND);
        double bonus = 0.0D;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            long age = time - tag.getLong("time");
            if (age < 0L || age > 20L * 180L) {
                continue;
            }
            BlockPos old = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            double dist = Math.sqrt(old.distSqr(pos));
            if (dist > 34.0D) {
                continue;
            }
            MineActivityType activity = MineActivityType.valueOf(tag.getString("activity"));
            double weight = tag.getDouble("weight") * (1.0D - age / (20.0D * 180.0D)) * Math.max(0.0D, 1.0D - dist / 38.0D);
            bonus += weight * activityMultiplier(activity, anchorType);
        }
        return Math.min(28.0D, bonus * 8.0D);
    }

    public static String debugSummary(CompoundTag data) {
        CompoundTag root = root(data);
        return "mine env=" + root.getString(LAST_ENV) + " features=" + root.getString(LAST_FEATURES)
                + " activities=" + root.getList(ACTIVITIES, Tag.TAG_COMPOUND).size();
    }

    public static int activityCount(CompoundTag data) {
        return root(data).getList(ACTIVITIES, Tag.TAG_COMPOUND).size();
    }

    private static void record(CompoundTag data, BlockPos pos, long time, MineActivityType activity, MineEnvironmentType env, double weight) {
        CompoundTag root = root(data);
        ListTag list = root.getList(ACTIVITIES, Tag.TAG_COMPOUND);
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putLong("time", time);
        tag.putString("activity", activity.name());
        tag.putString("environment", env.name());
        tag.putDouble("weight", weight);
        list.add(tag);
        while (list.size() > 72) {
            list.remove(0);
        }
        root.put(ACTIVITIES, list);
    }

    private static void decay(CompoundTag data, long time) {
        ListTag list = root(data).getList(ACTIVITIES, Tag.TAG_COMPOUND);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (time - list.getCompound(i).getLong("time") > 20L * 210L) {
                list.remove(i);
            }
        }
    }

    private static double activityMultiplier(MineActivityType activity, MinePeekAnchorType anchorType) {
        return switch (activity) {
            case PLAYER_PLACED_TORCH -> anchorType == MinePeekAnchorType.LAST_TORCH_WATCHER || anchorType == MinePeekAnchorType.TORCH_GAP_SHADOW ? 2.2D : 1.0D;
            case PLAYER_FOUND_ORE_POCKET, PLAYER_MINED_BLOCK -> anchorType == MinePeekAnchorType.ORE_POCKET_PEEK || anchorType == MinePeekAnchorType.DEAD_END_EXIT ? 2.0D : 1.1D;
            case PLAYER_MOVED_FAR -> anchorType == MinePeekAnchorType.RETURN_PATH_WATCHER || anchorType == MinePeekAnchorType.DEAD_END_EXIT ? 2.1D : 1.0D;
            case PLAYER_USED_LADDER -> anchorType == MinePeekAnchorType.LADDER_SHAFT_EDGE ? 2.0D : 0.9D;
            case PLAYER_USED_STAIRS -> anchorType == MinePeekAnchorType.STAIRCASE_CORNER ? 2.0D : 0.9D;
            case PLAYER_ENTERED_CAVE -> anchorType == MinePeekAnchorType.PLAYER_TUNNEL_TO_CAVE_EXIT || anchorType == MinePeekAnchorType.TRANSITION_EDGE ? 2.0D : 1.0D;
            default -> 1.0D;
        };
    }

    private static boolean isUnderground(ServerLevel level, BlockPos pos) {
        return pos.getY() <= 62 || !level.canSeeSky(pos) || level.getBrightness(LightLayer.SKY, pos) <= 4;
    }

    private static boolean isOre(BlockState state) {
        return state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)
                || state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)
                || state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE)
                || state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE)
                || state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)
                || state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE)
                || state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
                || state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE);
    }
}
