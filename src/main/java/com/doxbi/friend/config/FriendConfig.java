package com.doxbi.friend.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class FriendConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLE_FRIEND;
    public static final ForgeConfigSpec.BooleanValue ENABLE_DREAMS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_VOID;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CAT_CURSE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BLOCK_DESTRUCTION;
    public static final ForgeConfigSpec.DoubleValue EVENT_FREQUENCY_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue ATTACK_FREQUENCY_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue FRIEND_SPEED_MULTIPLIER;
    public static final ForgeConfigSpec.BooleanValue DEBUG_MODE;
    public static final ForgeConfigSpec.BooleanValue SUBTLE_MODE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("friend");
        ENABLE_FRIEND = builder.comment("Master switch for Friend director logic.").define("enableFriend", true);
        ENABLE_DREAMS = builder.comment("Allow nightmare corridor events.").define("enableDreams", true);
        ENABLE_VOID = builder.comment("Allow the cat curse ending dimension.").define("enableVoid", true);
        ENABLE_CAT_CURSE = builder.comment("Enable secret phase after killing a cat.").define("enableCatCurse", true);
        ENABLE_BLOCK_DESTRUCTION = builder.comment("Allow Friend to break only player-placed anti-abuse blocks during chase logic.").define("enableBlockDestruction", true);
        EVENT_FREQUENCY_MULTIPLIER = builder.comment("Lower values make ambient events more frequent.").defineInRange("eventFrequencyMultiplier", 1.0D, 0.1D, 10.0D);
        ATTACK_FREQUENCY_MULTIPLIER = builder.comment("Lower values make controlled attacks more frequent.").defineInRange("attackFrequencyMultiplier", 1.0D, 0.1D, 10.0D);
        FRIEND_SPEED_MULTIPLIER = builder.comment("Multiplier for Friend chase speed. 1.0 = normal fast chase, 0.75 = slower, 1.25 = faster.").defineInRange("friendSpeedMultiplier", 1.38D, 0.25D, 2.0D);
        DEBUG_MODE = builder.comment("Extra actionbar/status hints for testing.").define("debugMode", false);
        SUBTLE_MODE = builder.comment("Keeps normal hauntings quieter and rarer. Secret phase ignores this.").define("subtleMode", false);
        builder.pop();
        SPEC = builder.build();
    }

    private FriendConfig() {
    }
}
