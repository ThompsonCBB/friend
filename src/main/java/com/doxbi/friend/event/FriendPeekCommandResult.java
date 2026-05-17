package com.doxbi.friend.event;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

public record FriendPeekCommandResult(
        boolean success,
        String message,
        @Nullable BlockPos selectedPos,
        @Nullable String selectedAnchorType,
        int candidateCount,
        List<String> diagnostics
) {
    public static FriendPeekCommandResult ok(String message, BlockPos pos, String anchor, int candidates, List<String> diagnostics) {
        return new FriendPeekCommandResult(true, message, pos, anchor, candidates, diagnostics);
    }

    public static FriendPeekCommandResult fail(String message, int candidates, List<String> diagnostics) {
        return new FriendPeekCommandResult(false, message, null, null, candidates, diagnostics);
    }

    public String chatLine(PeekCommandMode mode) {
        StringBuilder builder = new StringBuilder(success ? "[Friend Debug] Forced peek success." : "[Friend Debug] Forced peek failed.");
        builder.append(" Mode: ").append(mode);
        builder.append(" Reason: ").append(message);
        builder.append(" Candidates: ").append(candidateCount);
        if (selectedPos != null) {
            builder.append(" Pos: ").append(selectedPos.toShortString());
        }
        if (selectedAnchorType != null) {
            builder.append(" Anchor: ").append(selectedAnchorType);
        }
        if (!diagnostics.isEmpty()) {
            builder.append(" | ").append(String.join(" | ", diagnostics));
        }
        return builder.toString();
    }
}
