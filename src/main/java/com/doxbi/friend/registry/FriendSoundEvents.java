package com.doxbi.friend.registry;

import com.doxbi.friend.FriendMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class FriendSoundEvents {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, FriendMod.MOD_ID);

    public static final RegistryObject<SoundEvent> DISTANT_STONE_SCRAPE = register("distant_stone_scrape");
    public static final RegistryObject<SoundEvent> DISTANT_METAL_HIT = register("distant_metal_hit");
    public static final RegistryObject<SoundEvent> QUIET_BREATH = register("quiet_breath");
    public static final RegistryObject<SoundEvent> WINDOW_KNOCK_1 = register("window_knock_1");
    public static final RegistryObject<SoundEvent> WINDOW_KNOCK_2 = register("window_knock_2");
    public static final RegistryObject<SoundEvent> WOOD_CREAK = register("wood_creak");
    public static final RegistryObject<SoundEvent> TUNNEL_STEP_1 = register("tunnel_step_1");
    public static final RegistryObject<SoundEvent> TUNNEL_STEP_2 = register("tunnel_step_2");
    public static final RegistryObject<SoundEvent> LOW_DRONE = register("low_drone");
    public static final RegistryObject<SoundEvent> DISAPPEAR_SOFT = register("disappear_soft");
    public static final RegistryObject<SoundEvent> CLOSE_PRESENCE = register("close_presence");
    public static final RegistryObject<SoundEvent> FRIEND_SCREAM = register("friend_scream");
    public static final RegistryObject<SoundEvent> DISTANT_SCRAPE = register("distant_scrape");
    public static final RegistryObject<SoundEvent> WINDOW_KNOCK = register("window_knock");
    public static final RegistryObject<SoundEvent> WINDOW_PRESS = register("window_press");
    public static final RegistryObject<SoundEvent> DOOR_OPEN_CLOSE = register("door_open_close");
    public static final RegistryObject<SoundEvent> CHEST_OPEN = register("chest_open");
    public static final RegistryObject<SoundEvent> CREAK_STEP = register("creak_step");
    public static final RegistryObject<SoundEvent> JOINT_CRACK = register("joint_crack");
    public static final RegistryObject<SoundEvent> LOW_BREATH = register("low_breath");
    public static final RegistryObject<SoundEvent> ATTACK_SCREAM = register("attack_scream");
    public static final RegistryObject<SoundEvent> FINAL_SCREAM = register("final_scream");
    public static final RegistryObject<SoundEvent> CAT_CURSE_ROAR = register("cat_curse_roar");
    public static final RegistryObject<SoundEvent> VOID_AMBIENT = register("void_ambient");
    public static final RegistryObject<SoundEvent> DREAM_AMBIENT = register("dream_ambient");
    public static final RegistryObject<SoundEvent> RED_LIGHT_BUZZ = register("red_light_buzz");
    public static final RegistryObject<SoundEvent> VOID_TRANSITION = register("void_transition");
    public static final RegistryObject<SoundEvent> PHASE_SHIFT = register("phase_shift");
    public static final RegistryObject<SoundEvent> SUBTITLE_NEAR = register("subtitle_near");
    public static final RegistryObject<SoundEvent> SUBTITLE_HEARD = register("subtitle_heard");
    public static final RegistryObject<SoundEvent> SUBTITLE_HOME = register("subtitle_home");
    public static final RegistryObject<SoundEvent> WHITE_GRIEF_SPEECH = register("white_grief_speech");
    public static final RegistryObject<SoundEvent> WHITE_GRIEF_CRY = register("white_grief_cry");
    public static final RegistryObject<SoundEvent> CAT_SIN_BACKGROUND = register("cat_sin_background");
    public static final RegistryObject<SoundEvent> FINAL_SENTENCE = register("final_sentence");
    public static final RegistryObject<SoundEvent> MINE_THROAT_CLICKS = register("mine_throat_clicks");
    public static final RegistryObject<SoundEvent> MINE_WALL_SCRAPE = register("mine_wall_scrape");
    public static final RegistryObject<SoundEvent> MINE_DISTANT_SCREAM = register("mine_distant_scream");
    public static final RegistryObject<SoundEvent> MINE_CRAWL = register("mine_crawl");

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name,
                () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(FriendMod.MOD_ID, name)));
    }

    private FriendSoundEvents() {
    }
}
