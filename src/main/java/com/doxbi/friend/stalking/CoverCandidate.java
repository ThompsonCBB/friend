package com.doxbi.friend.stalking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public record CoverCandidate(
        BlockPos coverBlock,
        Vec3 hiddenAnchor,
        Vec3 peekAnchor,
        Direction coverFace,
        PeekSide peekSide,
        CoverType coverType,
        double distanceToPlayer,
        double visibleFractionHidden,
        double visibleFractionPeek,
        double coverQuality,
        double darknessScore,
        double contextScore,
        double recencyPenalty,
        double finalScore,
        float bodyYaw,
        float headYaw,
        int availableHeight,
        boolean hasSafeFloor,
        boolean isChunkLoaded,
        boolean canFitFullBody,
        boolean requiresLowCeilingAnimation,
        String rejectReason
) {
    public CoverCandidate withFractions(double hidden, double peek, double finalScore, String rejectReason) {
        return new CoverCandidate(coverBlock, hiddenAnchor, peekAnchor, coverFace, peekSide, coverType, distanceToPlayer,
                hidden, peek, coverQuality, darknessScore, contextScore, recencyPenalty, finalScore, bodyYaw, headYaw,
                availableHeight, hasSafeFloor, isChunkLoaded, canFitFullBody, requiresLowCeilingAnimation, rejectReason);
    }
}
