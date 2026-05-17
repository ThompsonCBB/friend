package com.doxbi.friend.stalking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

public final class MineContextAdapter {
    private MineContextAdapter() {
    }

    public static MineContextSnapshot classify(ServerLevel level, ServerPlayer player, CompoundTag data) {
        BlockPos center = player.blockPosition();
        if (!isUnderground(level, center)) {
            return new MineContextSnapshot(MineEnvironmentType.UNKNOWN, 0.0D, 0, 0, 0, 0, 0, 0, 0, 0, 0, "surface");
        }

        int rails = 0;
        int cobwebs = 0;
        int woodenSupports = 0;
        int torches = 0;
        int utility = 0;
        int branches = 0;
        int darkPockets = 0;
        int natural = 0;
        int fluids = 0;
        int caveAir = 0;
        int regularAir = 0;
        int straight = 0;
        int scanRadius = 9;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-scanRadius, -4, -scanRadius), center.offset(scanRadius, 4, scanRadius))) {
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            if (isRail(state)) rails++;
            if (state.is(Blocks.COBWEB)) cobwebs++;
            if (state.is(Blocks.OAK_PLANKS) || state.is(Blocks.OAK_FENCE) || state.is(Blocks.OAK_LOG)
                    || state.is(Blocks.CHAIN) || state.is(BlockTags.WOODEN_FENCES)) {
                woodenSupports++;
            }
            if (block instanceof TorchBlock || block instanceof WallTorchBlock || block instanceof LanternBlock) torches++;
            if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof FurnaceBlock
                    || block instanceof CraftingTableBlock || block instanceof BedBlock) utility++;
            if (state.is(Blocks.CAVE_AIR)) caveAir++;
            if (state.isAir()) regularAir++;
            if (state.getFluidState().is(FluidTags.WATER) || state.getFluidState().is(FluidTags.LAVA)) fluids++;
            if (level.getBrightness(LightLayer.BLOCK, pos) <= 2 && state.isAir() && hasStoneNear(level, pos)) darkPockets++;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            straight += corridorRun(level, center, direction);
            BlockPos side = center.relative(direction, 2);
            if (isTwoHighAir(level, side) && hasSolidSides(level, side, direction)) {
                branches += 2;
            }
        }

        natural += caveAir / 18;
        natural += fluids * 2;
        natural += darkPockets / 5;
        natural += irregularityScore(level, center);

        int generatedScore = rails * 10 + cobwebs * 8 + woodenSupports * 5;
        int playerScore = straight * 6 + branches * 5 + torches * 2 + utility * 10 + MineShortTermMemory.activityCount(data) / 3;
        int caveScore = natural * 5;

        MineEnvironmentType type = MineEnvironmentType.UNKNOWN;
        int top = Math.max(generatedScore, Math.max(playerScore, caveScore));
        int second = generatedScore + playerScore + caveScore - top - Math.min(generatedScore, Math.min(playerScore, caveScore));
        if (top >= 22 && second >= 18 && Math.abs(top - second) <= 32) {
            type = MineEnvironmentType.HYBRID;
        } else if (generatedScore >= 24 && generatedScore >= playerScore && generatedScore >= caveScore) {
            type = MineEnvironmentType.GENERATED_MINESHAFT;
        } else if (playerScore >= 20 && playerScore >= caveScore) {
            type = MineEnvironmentType.PLAYER_DUG_MINE;
        } else if (caveScore >= 20) {
            type = MineEnvironmentType.NATURAL_CAVE;
        } else if (regularAir > 90 && !level.canSeeSky(center)) {
            type = MineEnvironmentType.NATURAL_CAVE;
            caveScore += 12;
            top = Math.max(top, caveScore);
        }

        double confidence = Math.min(1.0D, top / 90.0D);
        String features = String.format(Locale.ROOT,
                "rails=%d webs=%d wood=%d straight=%d cave=%d torches=%d util=%d branch=%d dark=%d",
                rails, cobwebs, woodenSupports, straight, natural, torches, utility, branches, darkPockets);
        return new MineContextSnapshot(type, confidence, rails, cobwebs, woodenSupports, straight, natural,
                torches, utility, branches, darkPockets, features);
    }

    public static boolean isUnderground(ServerLevel level, BlockPos pos) {
        return level.dimension().equals(Level.OVERWORLD)
                && (pos.getY() <= 56 || !level.canSeeSky(pos) || level.getBrightness(LightLayer.SKY, pos) <= 4);
    }

    public static boolean isRail(BlockState state) {
        return state.is(Blocks.RAIL) || state.is(Blocks.POWERED_RAIL) || state.is(Blocks.DETECTOR_RAIL) || state.is(Blocks.ACTIVATOR_RAIL);
    }

    private static int corridorRun(ServerLevel level, BlockPos origin, Direction direction) {
        int run = 0;
        for (int i = 1; i <= 12; i++) {
            BlockPos pos = origin.relative(direction, i);
            if (!isTwoHighAir(level, pos)) {
                break;
            }
            if (hasSolidSides(level, pos, direction)) {
                run++;
            }
        }
        return run;
    }

    private static boolean isTwoHighAir(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir();
    }

    private static boolean hasSolidSides(ServerLevel level, BlockPos pos, Direction travel) {
        Direction left = travel.getClockWise();
        Direction right = travel.getCounterClockWise();
        return isSolid(level, pos.relative(left)) && isSolid(level, pos.relative(right));
    }

    private static boolean hasStoneNear(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState state = level.getBlockState(pos.relative(direction));
            if (state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF) || state.is(Blocks.COBBLESTONE)) {
                return true;
            }
        }
        return false;
    }

    private static int irregularityScore(ServerLevel level, BlockPos center) {
        int score = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int y = -3; y <= 5; y++) {
                BlockPos pos = center.relative(direction, 4).offset(0, y, 0);
                if (level.hasChunkAt(pos) && level.getBlockState(pos).isAir()) {
                    score++;
                }
            }
        }
        return score;
    }

    private static boolean isSolid(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }
}
