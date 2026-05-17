package com.doxbi.friend.stalking;

public record MineContextSnapshot(
        MineEnvironmentType environmentType,
        double confidence,
        int rails,
        int cobwebs,
        int woodenSupports,
        int regularTunnelScore,
        int naturalCaveScore,
        int torches,
        int utilityBlocks,
        int branches,
        int darkPockets,
        String features
) {
    public boolean isMineLike() {
        return environmentType != MineEnvironmentType.UNKNOWN && confidence >= 0.24D;
    }
}
