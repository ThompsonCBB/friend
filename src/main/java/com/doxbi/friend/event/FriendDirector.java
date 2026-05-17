package com.doxbi.friend.event;

import com.doxbi.friend.FriendMod;
import com.doxbi.friend.config.FriendConfig;
import com.doxbi.friend.entity.FriendEntity;
import com.doxbi.friend.entity.ai.FriendMovementController;
import com.doxbi.friend.registry.FriendEntityTypes;
import com.doxbi.friend.registry.FriendSoundEvents;
import com.doxbi.friend.stalking.FriendStalkingDirector;
import com.doxbi.friend.stalking.MineContextAdapter;
import com.doxbi.friend.stalking.MinePeekAdapter;
import com.doxbi.friend.stalking.MineShortTermMemory;
import com.doxbi.friend.world.FriendDimensions;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Player.BedSleepingProblem;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.GlassBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = FriendMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FriendDirector {
    private static final Random RANDOM = new Random();
    private static final double FRIEND_COLLISION_RADIUS = 0.36D;
    private static final double FRIEND_COLLISION_HEIGHT = 1.95D;
    private static final int FRIEND_AIR_BLOCKS = 2;
    private static final String ROOT = "friend_state";
    private static final String PHASE = "friend_phase";
    private static final String INTEREST = "friend_interest";
    private static final String CONFIDENCE = "friend_confidence";
    private static final String SEEN_COUNT = "friend_seen_count";
    private static final String EVENT_COOLDOWN = "friend_event_cooldown";
    private static final String SOUND_COOLDOWN = "friend_sound_cooldown";
    private static final String TORCH_BACK_COOLDOWN = "friend_torch_back_cooldown";
    private static final String LAST_ATTACK_TIME = "friend_last_attack_time";
    private static final String ATTACK_LOCK_UNTIL = "friend_attack_lock_until";
    private static final String THREAT_DEBT = "friend_threat_debt";
    private static final String FEAR_SCORE = "friend_fear_score";
    private static final String LAST_VISIBLE_PRESSURE = "friend_last_visible_pressure";
    private static final String LAST_FALSE_ATTACK = "friend_last_false_attack";
    private static final String DOG_FEAR_COOLDOWN = "friend_dog_fear_cooldown";
    private static final String DOG_FEAR_ACTIVE = "friend_dog_fear_active";
    private static final String DOG_FEAR_WOLF_ID = "friend_dog_fear_wolf_id";
    private static final String DOG_FEAR_START = "friend_dog_fear_start";
    private static final String DOG_FEAR_OWNER = "friend_dog_fear_owner";
    private static final String DOG_FEAR_STAGE = "friend_dog_fear_stage";
    private static final String LAST_LIGHT_DRAIN = "friend_last_light_drain";
    private static final int MAX_RELEASE_PHASE = 4;
    private static final int ATTACK_COOLDOWN_MIN_TICKS = 20 * 360;
    private static final int ATTACK_COOLDOWN_MAX_TICKS = 20 * 480;
    private static final String HOME_X = "friend_home_x";
    private static final String HOME_Y = "friend_home_y";
    private static final String HOME_Z = "friend_home_z";
    private static final String HAS_HOME = "friend_has_home";
    private static final String HOME_CANDIDATES = "friend_home_candidates";
    private static final String HOME_INTERACTION_BOOST = "friend_home_interaction_boost";
    private static final String HOME_LAST_SAMPLE_X = "friend_home_last_sample_x";
    private static final String HOME_LAST_SAMPLE_Y = "friend_home_last_sample_y";
    private static final String HOME_LAST_SAMPLE_Z = "friend_home_last_sample_z";
    private static final String ACTIVE = "friend_active";
    private static final String LAST_EVENT = "friend_last_event";
    private static final String LAST_SEEN_TICK = "friend_last_seen_tick";
    private static final String SPAWN_FAIL_COUNT = "friend_spawn_fail_count";
    private static final String DARKNESS_TIME = "friend_player_darkness_time";
    private static final String UNDERGROUND_TIME = "friend_player_underground_time";
    private static final String HOME_TIME = "friend_player_home_time";
    private static final String LAST_X = "friend_last_player_x";
    private static final String LAST_Y = "friend_last_player_y";
    private static final String LAST_Z = "friend_last_player_z";
    private static final String DEBUG = "friend_debug_enabled";
    private static final String LAST_PUNISH = "friend_last_punish_tick";
    private static final String BOOK_GIVEN = "friend_book_given";
    private static final String CAT_CURSE = "friend_cat_curse";
    private static final String CAT_GRIEF_COOLDOWN = "friend_cat_grief_cooldown";
    private static final String CAT_BRIDGE_BREAK_COOLDOWN = "friend_cat_bridge_break_cooldown";
    private static final String CAT_DISTANCE = "friend_cat_distance";
    private static final String CAT_ROAR_COOLDOWN = "friend_cat_roar_cooldown";
    private static final String CAT_DISTANCE_GLITCH_COOLDOWN = "friend_cat_distance_glitch_cooldown";
    private static final String CAT_SIN = "friend_cat_sin";
    private static final String RAGE = "friend_rage";
    private static final String PUNISHMENT_ACTIVE = "friend_punishment_active";
    private static final String PUNISHMENT_START = "friend_punishment_start";
    private static final String PUNISHMENT_STAGE = "friend_punishment_stage";
    private static final String PUNISHMENT_CAT_X = "friend_punishment_cat_x";
    private static final String PUNISHMENT_CAT_Y = "friend_punishment_cat_y";
    private static final String PUNISHMENT_CAT_Z = "friend_punishment_cat_z";
    private static final String PUNISHMENT_VOICE_PLAYED = "friend_punishment_voice_played";
    private static final String PUNISHMENT_NEXT_CRY = "friend_punishment_next_cry";
    private static final String CAT_BACKGROUND_NEXT = "friend_cat_background_next";
    private static final String PUNISHMENT_NEXT_WITNESS = "friend_punishment_next_witness";
    private static final String PUNISHMENT_WITNESS_COUNT = "friend_punishment_witness_count";
    private static final String FIFTH_PENDING = "friend_fifth_pending";
    private static final String FIFTH_STAGE = "friend_fifth_stage";
    private static final String FIFTH_STARTED = "friend_fifth_started";
    private static final String FIFTH_NEXT_CAT = "friend_fifth_next_cat";
    private static final String FIFTH_CAT_COUNT = "friend_fifth_cat_count";
    private static final String FIFTH_VOICE_PLAYED = "friend_fifth_voice_played";
    private static final String FIFTH_LOCK_X = "friend_fifth_lock_x";
    private static final String FIFTH_LOCK_Y = "friend_fifth_lock_y";
    private static final String FIFTH_LOCK_Z = "friend_fifth_lock_z";
    private static final String FIFTH_STAGE_STARTED = "friend_fifth_stage_started";
    private static final String BAD_ENDING_LOCKED = "friend_bad_ending_locked";
    private static final String PARADISE_UNLOCKED = "friend_paradise_unlocked";
    private static final String PARADISE_DONE = "friend_paradise_done";
    private static final String FRIEND_DISABLED_AFTER_RETURN = "friend_disabled_after_return";
    private static final String RELEASE_ENDING_CHOSEN = "friend_release_ending_chosen";
    private static final String ENDING_STATE = "friend_ending_state";
    private static final String LAST_CHASE_SPEED = "friend_last_chase_speed";
    private static final String LAST_BLOCK_BREAK_REASON = "friend_last_block_break_reason";
    private static final String LAST_PATH_TYPE = "friend_last_path_type";
    private static final String LAST_TRAP_HAZARD = "friend_last_trap_hazard";
    private static final String ROUTE_POINTS = "friend_route_points";
    private static final String ROUTE_LAST_SAMPLE = "friend_route_last_sample";
    private static final String MEMORY_DOORS = "friend_memory_doors";
    private static final String MEMORY_CONTAINERS = "friend_memory_containers";
    private static final String MEMORY_WINDOWS = "friend_memory_windows";
    private static final String MEMORY_PEEK_COVERS = "friend_memory_peek_covers";
    private static final String MEMORY_PLAYER_BLOCKS = "friend_memory_player_blocks";
    private static final String PEEK_CHAIN_AT = "friend_peek_chain_at";
    private static final String PEEK_CHAIN_COUNT = "friend_peek_chain_count";
    private static final String PEEK_ATTEMPTS = "friend_peek_attempts";
    private static final String PEEK_SEEN_TOTAL = "friend_peek_seen_total";
    private static final String PEEK_MISSED_STREAK = "friend_peek_missed_streak";
    private static final String PEEK_LAST_MODE = "friend_peek_last_mode";
    private static final String PEEK_MODE = "friend_peek_mode";
    private static final String SILENCE_UNTIL = "friend_silence_until";
    private static final String SILENCE_PENDING_EVENT = "friend_silence_pending_event";
    private static final String DREAM_STARTED = "friend_dream_started";
    private static final String DREAM_CUTSCENE_UNTIL = "friend_dream_cutscene_until";
    private static final String DREAM_USED = "friend_dream_used";
    private static final String DREAM_BED_SEQUENCE = "friend_dream_bed_sequence";
    private static final String DREAM_BED_START = "friend_dream_bed_start";
    private static final String DREAM_BED_FRIEND = "friend_dream_bed_friend";
    private static final String DREAM_BED_X = "friend_dream_bed_x";
    private static final String DREAM_BED_Y = "friend_dream_bed_y";
    private static final String DREAM_BED_Z = "friend_dream_bed_z";
    private static final String DREAM_VARIANT = "friend_dream_variant";
    private static final String FIRST_CONTACT_SCHEDULED = "friend_first_contact_scheduled";
    private static final String FIRST_CONTACT_AT = "friend_first_contact_at";
    private static final String FIRST_CONTACT_DONE = "friend_first_contact_done";
    private static final String FIRST_CONTACT_TAG_CLEAR = "friend_first_contact_tag_clear";
    private static final String FIRST_CONTACT_CLIENT_TAG = "friend_first_contact_roar";
    private static final String SKY_DIALOG_STARTED = "friend_sky_dialog_started";
    private static final String SKY_DIALOG_STAGE = "friend_sky_dialog_stage";
    private static final String SKY_DIALOG_NEXT = "friend_sky_dialog_next";
    private static final String SKY_REASON = "friend_sky_reason";
    private static final String SKY_DAY100_DONE = "friend_sky_day100_done";
    private static final String CHAT_TRIGGER_COOLDOWN = "friend_chat_trigger_cooldown";
    private static final String CHAT_SECRET_COUNT = "friend_chat_secret_count";
    private static final long ATTACK_SCREAM_LENGTH = 20L * 100L;
    private static final int ATTACK_INTRO_TICKS = 6;
    private static final int ATTACK_MAX_TICKS = 20 * 26;
    private static final String[] SKY_DIALOG_CAT = {
            "Friend: you threw away a small warm thing.",
            "Friend: i ran because the world became loud.",
            "Friend: there are two exits here. one remembers you. one keeps you.",
            "Friend: choose while the sky is still pretending to be kind."
    };
    private static final String[] SKY_DIALOG_DAY100 = {
            "Friend: one hundred days and you still call this place yours.",
            "Friend: i learned every return, every door, every silence.",
            "Friend: i can give the house back. i can also take the road away.",
            "Friend: walk to the pale light if you want morning. walk to the dark glass if you want truth."
    };
    private static final String[] CHAT_TRIGGER_PATTERNS = {
            "hello","helo","helloo","helllo","hi friend","friend","frend","freind","are you awake","awake","awkae","awke","wake up",
            "alone","alon","i am alone","home","hom","house","base","safe","not safe","where are you","who are you","help","help me",
            "dream","drim","drem","nightmare","void","voyd","sky","skyy","cat","kitten","sorry","forgive","run","behind me","behind",
            "window","door","knock","knocking","open","close","chest","steps","footsteps","listen","silence","dark","darkness","cave",
            "mine","tunnel","hole","peek","look","watching","see you","i see you","do you see me","dont","don't","stop","leave",
            "come in","come here","friend is near","wake","sleep","bed","red","black eyes","mask","face","smile","scream","noise",
            "answer","talk","speak","yes","no","why","please","please stop","im scared","i'm scared","scared","fear","found home",
            "you found me","distance","near","far","not real","real","laryn","leryn13","curseforge","curse","phase","secret","void me",
            "goodbye","bye","open the door","are you still awake","still awake","is anyone there","anyone there","friend hello","hello friend",
            "where is my home","my house","my base","dont look","don't look","look away","stay away","come closer","i killed","i killed cat",
            "sorry cat","bad dream","let me out","exit","portal","the sky","the void","friend?"
    };
    private static final String[] EVENT_SUGGESTIONS = {
            "window", "dream", "sky", "attack", "peek", "stalk", "stalk_corner", "stalk_tree", "stalk_window",
            "stalk_doorframe", "stalk_ceiling", "stalk_floor", "stalk_low", "stalk_cave", "stalk_distant",
            "visible", "hand", "hole", "ceiling", "floor", "low2", "low1", "far", "night", "route", "curse",
            "window_watch", "window_knock", "window_handprint", "bedside_watch", "doorway_lean", "route_ambush", "night_far_watch",
            "false_friend", "visible_peek", "corner_peek", "hand_peek", "hole_peek", "ceiling_hole_peek", "floor_hole_peek",
            "low_ceiling_2_peek", "low_ceiling_1_peek", "false_safe", "wrong_detail", "inventory_disturbance", "torch_line",
            "door_creak", "torch_removed", "double_appearance", "attack_emerge", "false_attack", "mimic_footsteps", "wall_scurry",
            "sprint_behind_wall", "learned_footsteps", "learned_door", "learned_chest", "door_open_far", "chest_open_far",
            "distant_scrape", "distant_knock", "breathing_behind", "dream_hint", "fake_footsteps", "no_event_fake_delay"
    };

    private FriendDirector() {
    }

    @SubscribeEvent
    public static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !FriendConfig.ENABLE_FRIEND.get()) {
            return;
        }
        CompoundTag data = state(player);
        if (data.getBoolean(CAT_SIN)) {
            player.addTag("friend_cat_sin");
        }
        if (data.getBoolean(BOOK_GIVEN)) {
            return;
        }
        data.putBoolean(BOOK_GIVEN, true);
        ItemStack book = createFriendBook();
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
    }

    @SubscribeEvent
    public static void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (player.isSpectator() || !FriendConfig.ENABLE_FRIEND.get()) {
            return;
        }

        ServerLevel level = player.serverLevel();
        long time = level.getGameTime();
        CompoundTag data = state(player);
        if (data.getBoolean(FIFTH_PENDING) && !level.dimension().equals(FriendDimensions.FIFTH)) {
            data.putBoolean(FIFTH_PENDING, false);
            data.putBoolean(BAD_ENDING_LOCKED, true);
            data.putString(ENDING_STATE, "fifth");
            FriendDimensions.sendToFifth(player);
            return;
        }

        if (level.dimension().equals(FriendDimensions.DREAM)) {
            tickDreamPlayer(level, player, data, time);
            return;
        }
        if (level.dimension().equals(FriendDimensions.VOID)) {
            tickVoidPlayer(level, player, time);
            return;
        }
        if (level.dimension().equals(FriendDimensions.SKY)) {
            tickSkyPlayer(level, player, data, time);
            return;
        }
        if (level.dimension().equals(FriendDimensions.FIFTH)) {
            tickFifthPlayer(level, player, data, time);
            return;
        }
        if (level.dimension().equals(FriendDimensions.PARADISE)) {
            // Paradise is removed from the release build. If an old save still contains this dimension,
            // push the player back to Overworld instead of running the old ending scene.
            ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
            if (overworld != null) {
                BlockPos spawn = overworld.getSharedSpawnPos();
                player.teleportTo(overworld, spawn.getX() + 0.5D, spawn.getY() + 1.0D, spawn.getZ() + 0.5D, player.getYRot(), player.getXRot());
            }
            return;
        }
        if (data.getBoolean(FRIEND_DISABLED_AFTER_RETURN) || data.getBoolean(RELEASE_ENDING_CHOSEN)) {
            return;
        }
        if (tryStartDreamFromActualSleep(level, player, data, time)) {
            return;
        }

        tickFirstContactRoar(level, player, data, time);
        if (time % 30L == 0L) {
            maybeExtinguishTorchBehind(level, player, data, time);
        }
        if (time % 10L == 0L) {
            tickDogFearEvent(level, player, data, time);
        }

        if (time % 20L == 0L) {
            updateInterest(level, player, data);
            updateSurvivalPressure(level, player, data, time);
            updatePhase(level, player, data);
            cleanupActiveFlag(level, player, data);
        }
        if (time % 40L == 0L) {
            updateRouteMemory(level, player, data, time);
            MineShortTermMemory.tickMovement(level, player, data, time);
        }
        if (time % 100L == 0L) {
            updateHome(level, player, data);
        }
        if (data.getBoolean(CAT_SIN)) {
            tickCatSinServerBackground(level, player, data, time);
        }
        if (data.getBoolean(PUNISHMENT_ACTIVE)) {
            tickCatPunishment(level, player, data, time);
            data.putDouble(LAST_X, player.getX());
            data.putDouble(LAST_Y, player.getY());
            data.putDouble(LAST_Z, player.getZ());
            return;
        }
        // Paradise ending removed from release pacing.
        if (tryDay100SkyEnding(level, player, data)) {
            return;
        }
        if (data.getBoolean(CAT_CURSE)) {
            applyCatCursePressure(level, player, data, time);
            data.putDouble(LAST_X, player.getX());
            data.putDouble(LAST_Y, player.getY());
            data.putDouble(LAST_Z, player.getZ());
            return;
        }
        if (tickDreamBedSequence(level, player, data, time)) {
            data.putDouble(LAST_X, player.getX());
            data.putDouble(LAST_Y, player.getY());
            data.putDouble(LAST_Z, player.getZ());
            return;
        }
        if (tickPeekChain(level, player, data, time)) {
            data.putDouble(LAST_X, player.getX());
            data.putDouble(LAST_Y, player.getY());
            data.putDouble(LAST_Z, player.getZ());
            return;
        }
        if ((data.getBoolean(DEBUG) || FriendConfig.DEBUG_MODE.get()) && time % 20L == 0L) {
            player.displayClientMessage(Component.literal("friend phase=" + data.getInt(PHASE)
                    + " rage=" + data.getBoolean(RAGE)
                    + " speed=" + round(data.getDouble(LAST_CHASE_SPEED))
                    + " variant=" + nearestVariant(level, player)
                    + " path=" + data.getString(LAST_PATH_TYPE)
                    + " break=" + data.getString(LAST_BLOCK_BREAK_REASON)
                    + " trap=" + data.getBoolean(LAST_TRAP_HAZARD)
                    + " cat_sin=" + data.getBoolean(CAT_SIN)
                    + " fifth=" + data.getBoolean(BAD_ENDING_LOCKED)
                    + " days=" + (level.getDayTime() / 24000L)
                    + " ending=" + data.getString(ENDING_STATE)
                    + " punish=" + data.getInt(PUNISHMENT_STAGE)
                    + " interest=" + round(data.getDouble(INTEREST))
                    + " route=" + routeSize(data)
                    + " curse=" + data.getBoolean(CAT_CURSE)
                    + " | " + FriendStalkingDirector.debugStatus(data)), true);
        }
        if (time >= data.getLong(EVENT_COOLDOWN)) {
            tryEvent(level, player, data, null);
        }

        data.putDouble(LAST_X, player.getX());
        data.putDouble(LAST_Y, player.getY());
        data.putDouble(LAST_Z, player.getZ());
    }


    private static void tickFirstContactRoar(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        long clearAt = data.getLong(FIRST_CONTACT_TAG_CLEAR);
        if (clearAt > 0L && time >= clearAt) {
            player.removeTag(FIRST_CONTACT_CLIENT_TAG);
            data.putLong(FIRST_CONTACT_TAG_CLEAR, 0L);
        }
        if (!level.dimension().equals(Level.OVERWORLD) || data.getBoolean(CAT_CURSE) || data.getBoolean(PUNISHMENT_ACTIVE)) {
            return;
        }
        if (data.getBoolean(FIRST_CONTACT_DONE)) {
            return;
        }
        if (!data.getBoolean(FIRST_CONTACT_SCHEDULED)) {
            data.putBoolean(FIRST_CONTACT_SCHEDULED, true);
            data.putLong(FIRST_CONTACT_AT, time + 20L * (120L + RANDOM.nextInt(181)));
            return;
        }
        if (time < data.getLong(FIRST_CONTACT_AT)) {
            return;
        }

        data.putBoolean(FIRST_CONTACT_DONE, true);
        data.putBoolean(FIRST_CONTACT_SCHEDULED, false);
        data.putLong(FIRST_CONTACT_TAG_CLEAR, time + 20L * 18L);
        player.addTag(FIRST_CONTACT_CLIENT_TAG);

        // Backup server-side sound at the player's current position. The client also starts a listener-anchored
        // one-shot from this tag, so the roar remains audible even if the player flies away in creative.
        level.playSound(null, player.blockPosition(), FriendSoundEvents.MINE_DISTANT_SCREAM.get(), SoundSource.HOSTILE, 1.35F, 0.68F + RANDOM.nextFloat() * 0.08F);
        data.putLong(EVENT_COOLDOWN, Math.max(data.getLong(EVENT_COOLDOWN), time + 20L * 45L));
    }

    private static void levelRoar(ServerLevel level, ServerPlayer player) {
        CompoundTag data = state(player);
        long time = level.getGameTime();

        data.putLong(FIRST_CONTACT_TAG_CLEAR, time + 20L * 18L);
        player.addTag(FIRST_CONTACT_CLIENT_TAG);

        // Command/manual roar: play a local backup and trigger the client-side listener-anchored sound.
        // This keeps the roar audible while flying fast and prevents it from behaving like a fixed block sound.
        level.playSound(null, player.blockPosition(), FriendSoundEvents.MINE_DISTANT_SCREAM.get(),
                SoundSource.HOSTILE, 1.45F, 0.66F + RANDOM.nextFloat() * 0.08F);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("friend")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    CompoundTag data = state(player);
                    ctx.getSource().sendSuccess(() -> Component.literal("Friend status: phase="
                            + (data.getBoolean(CAT_CURSE) ? "?" : data.getInt(PHASE))
                            + " interest=" + round(data.getDouble(INTEREST))
                            + " confidence=" + round(data.getDouble(CONFIDENCE))
                            + " seen=" + data.getInt(SEEN_COUNT)
                            + " active=" + data.getBoolean(ACTIVE)
                            + " home=" + data.getBoolean(HAS_HOME)
                            + " curse=" + data.getBoolean(CAT_CURSE)
                            + " | " + FriendStalkingDirector.debugStatus(data)), false);
                    return 1;
                }))
                .then(Commands.literal("reset").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    player.getPersistentData().remove(ROOT);
                    ctx.getSource().sendSuccess(() -> Component.literal("Friend reset"), false);
                    return 1;
                }))
                .then(Commands.literal("debug")
                        .then(Commands.literal("on").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            state(player).putBoolean(DEBUG, true);
                            ctx.getSource().sendSuccess(() -> Component.literal("Friend debug on"), false);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            state(player).putBoolean(DEBUG, false);
                            ctx.getSource().sendSuccess(() -> Component.literal("Friend debug off"), false);
                            return 1;
                        }))
                        .then(Commands.literal("peek").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            player.displayClientMessage(Component.literal(FriendDebugPeekService.debugLine(player.serverLevel(), player, state(player))), false);
                            return 1;
                        })))
                .then(Commands.literal("phase")
                        .then(Commands.argument("value", IntegerArgumentType.integer(1, MAX_RELEASE_PHASE)).executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            int value = IntegerArgumentType.getInteger(ctx, "value");
                            CompoundTag data = state(player);
                            data.putBoolean(CAT_CURSE, false);
                            data.putInt(PHASE, value);
                            data.putDouble(INTEREST, Math.max(data.getDouble(INTEREST), 16.0D * value));
                            data.putDouble(CONFIDENCE, Math.max(data.getDouble(CONFIDENCE), 10.0D * value));
                            ctx.getSource().sendSuccess(() -> Component.literal("Friend phase set to " + value), false);
                            return 1;
                        }))
                        .then(Commands.literal("?").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            igniteCatCurse(player.serverLevel(), player);
                            ctx.getSource().sendSuccess(() -> Component.literal("Friend phase set to ?"), false);
                            return 1;
                        })))
                .then(Commands.literal("event")
                        .then(Commands.literal("peek").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            cleanupOwnedFriends(player.serverLevel(), player);
                            boolean ok = FriendStalkingDirector.tryStart(player.serverLevel(), player, state(player), "corner");
                            if (ok) {
                                ctx.getSource().sendSuccess(() -> Component.literal("Friend peek/stand spawned | " + FriendStalkingDirector.debugStatus(state(player))), false);
                                return 1;
                            }
                            ctx.getSource().sendFailure(Component.literal("Friend peek failed after fallback search | " + FriendStalkingDirector.debugStatus(state(player))));
                            return 0;
                        }))
                        .then(Commands.literal("stand").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            cleanupOwnedFriends(player.serverLevel(), player);
                            boolean ok = FriendStalkingDirector.tryStart(player.serverLevel(), player, state(player), "distant");
                            if (ok) {
                                ctx.getSource().sendSuccess(() -> Component.literal("Friend distant stand spawned"), false);
                                return 1;
                            }
                            ctx.getSource().sendFailure(Component.literal("Friend distant stand failed"));
                            return 0;
                        }))
                        .then(Commands.literal("attack").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            cleanupOwnedFriends(player.serverLevel(), player);
                            boolean ok = spawnAttack(player.serverLevel(), player, state(player));
                            if (ok) {
                                ctx.getSource().sendSuccess(() -> Component.literal("Friend attack spawned"), false);
                                return 1;
                            }
                            ctx.getSource().sendFailure(Component.literal("Friend attack failed"));
                            return 0;
                        }))
                        .then(Commands.literal("roar").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            levelRoar(player.serverLevel(), player);
                            ctx.getSource().sendSuccess(() -> Component.literal("Friend roar played"), false);
                            return 1;
                        })))
                .then(Commands.literal("home")
                        .then(Commands.literal("clear").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            CompoundTag data = state(player);
                            data.putBoolean(HAS_HOME, false);
                            data.putLong(HOME_TIME, 0L);
                            data.remove(HOME_CANDIDATES);
                            data.putInt(HOME_INTERACTION_BOOST, 0);
                            ctx.getSource().sendSuccess(() -> Component.literal("Friend home cleared"), false);
                            return 1;
                        })))
                .then(Commands.literal("curse")
                        .then(Commands.literal("on").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            igniteCatCurse(player.serverLevel(), player);
                            ctx.getSource().sendSuccess(() -> Component.literal("Friend curse enabled"), false);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            CompoundTag data = state(player);
                            data.putBoolean(CAT_CURSE, false);
                            data.putLong(CAT_GRIEF_COOLDOWN, 0L);
                            data.putLong(CAT_BRIDGE_BREAK_COOLDOWN, 0L);
                            data.putLong(CAT_DISTANCE_GLITCH_COOLDOWN, 0L);
                            data.putInt(PHASE, Math.min(Math.max(data.getInt(PHASE), 1), MAX_RELEASE_PHASE));
                            ctx.getSource().sendSuccess(() -> Component.literal("Friend curse disabled"), false);
                            return 1;
                        }))));
    }


    @SubscribeEvent
    public static void livingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        Entity killer = event.getSource().getEntity();
        if (event.getEntity() instanceof Cat cat && FriendConfig.ENABLE_CAT_CURSE.get() && killer instanceof ServerPlayer player) {
            startCatSinPunishment(player.serverLevel(), player, cat.blockPosition());
            igniteCatCurse(player.serverLevel(), player);
            return;
        }
        if (event.getEntity() instanceof FriendEntity friend) {
            friendKilledByPlayer(friend, event.getSource());
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            CompoundTag data = state(player);
            if (data.getBoolean(CAT_SIN) || data.getBoolean(BAD_ENDING_LOCKED)) {
                data.putBoolean(FIFTH_PENDING, true);
                if (data.getBoolean(BAD_ENDING_LOCKED) || player.level().dimension().equals(FriendDimensions.FIFTH)) {
                    data.putBoolean(FIFTH_STARTED, true);
                    data.putInt(FIFTH_STAGE, Math.max(3, data.getInt(FIFTH_STAGE)));
                }
            }
        }
    }

    @SubscribeEvent
    public static void playerRespawn(PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CompoundTag data = state(player);
        if (data.getBoolean(FIFTH_PENDING) || data.getBoolean(BAD_ENDING_LOCKED)) {
            data.putBoolean(FIFTH_PENDING, false);
            data.putBoolean(BAD_ENDING_LOCKED, true);
            data.putBoolean(FIFTH_STARTED, true);
            data.putInt(FIFTH_STAGE, Math.max(3, data.getInt(FIFTH_STAGE)));
            data.putString(ENDING_STATE, "fifth");
            clearFifthCutsceneEffects(player);
            FriendDimensions.sendToFifth(player);
        }
    }

    @SubscribeEvent
    public static void playerClone(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CompoundTag oldRoot = event.getOriginal().getPersistentData().getCompound(ROOT);
        if (!oldRoot.isEmpty()) {
            player.getPersistentData().put(ROOT, oldRoot.copy());
            if (oldRoot.getBoolean(CAT_SIN)) {
                player.addTag("friend_cat_sin");
            }
        }
    }

    @SubscribeEvent
    public static void livingHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        if (event.getEntity() instanceof Cat cat && cat.level().dimension().equals(FriendDimensions.FIFTH)) {
            event.setCanceled(true);
            return;
        }
        if (event.getSource().getEntity() instanceof ServerPlayer player) {
            CompoundTag data = state(player);
        }
    }

    @SubscribeEvent
    public static void serverChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player.level().isClientSide || !FriendConfig.ENABLE_FRIEND.get()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        CompoundTag data = state(player);
        long time = level.getGameTime();
        String text = normalizeChat(event.getRawText());
        if (text.length() < 2 || time < data.getLong(CHAT_TRIGGER_COOLDOWN)) {
            return;
        }
        String matched = null;
        for (String pattern : CHAT_TRIGGER_PATTERNS) {
            if (text.contains(pattern)) {
                matched = pattern;
                break;
            }
        }
        if (matched == null) {
            return;
        }
        data.putLong(CHAT_TRIGGER_COOLDOWN, time + seconds(50, 150));
        data.putInt(CHAT_SECRET_COUNT, Math.min(1000, data.getInt(CHAT_SECRET_COUNT) + 1));
        add(data, INTEREST, 3.5D, 0, 100);
        add(data, CONFIDENCE, 1.25D, 0, 100);
        if (matched.contains("hello") || matched.equals("helo") || matched.contains("awake")) {
            player.displayClientMessage(Component.literal("Friend: i heard the spelling anyway."), false);
            data.putLong(SILENCE_UNTIL, time + seconds(5, 9));
            data.putString(SILENCE_PENDING_EVENT, "visible_peek");
        } else if (matched.contains("home") || matched.contains("house") || matched.contains("base")) {
            player.displayClientMessage(Component.literal("Friend: home is where you stop running."), false);
            boostHomeSignal(level, player, data, player.blockPosition(), 28);
        } else if (matched.contains("cat") || matched.contains("sorry") || matched.contains("forgive")) {
            player.displayClientMessage(Component.literal("Friend: some sorry sounds arrive late."), false);
            play(level, player.blockPosition(), FriendSoundEvents.LOW_BREATH, 0.22F, 0.42F, 0.7F);
        } else if (matched.contains("sky") || matched.contains("void") || matched.contains("exit")) {
            player.displayClientMessage(Component.literal("Friend: exits only work when they choose you."), false);
        } else if (matched.contains("window") || matched.contains("door") || matched.contains("knock")) {
            data.putLong(SILENCE_UNTIL, time + seconds(4, 8));
            data.putString(SILENCE_PENDING_EVENT, data.getBoolean(HAS_HOME) ? "window_watch" : "doorway_lean");
            player.displayClientMessage(Component.literal("Friend: do not answer the house."), false);
        } else {
            player.displayClientMessage(Component.literal("Friend: wrong words still make a shape."), false);
        }
        if (!data.getBoolean(CAT_CURSE) && RANDOM.nextFloat() < 0.22F) {
            data.putLong(EVENT_COOLDOWN, Math.min(data.getLong(EVENT_COOLDOWN), time + seconds(8, 20)));
        }
    }

    public static void friendKilledByPlayer(FriendEntity friend, DamageSource source) {
        if (!(friend.level() instanceof ServerLevel level)) {
            return;
        }
        Entity attacker = source.getEntity();
        ServerPlayer player = attacker instanceof ServerPlayer direct ? direct : findOwner(level, friend.ownerUuid());
        if (player == null) {
            return;
        }
        CompoundTag data = state(player);
        if (data.getBoolean(CAT_CURSE)) {
            add(data, CONFIDENCE, 8.0D, 0, 100);
            return;
        }

        /*
         * Killing Friend is now the hard trigger for phase ?.
         * This reuses the existing secret-phase/cat-curse infrastructure instead of only adding +1 phase.
         */
        igniteCatCurse(level, player);
        player.displayClientMessage(Component.literal("Friend: you should not have made me real."), false);
    }

    public static void friendHurtByPlayer(FriendEntity friend, DamageSource source) {
        if (!(friend.level() instanceof ServerLevel level)) {
            return;
        }
        Entity attacker = source.getEntity();
        ServerPlayer player = attacker instanceof ServerPlayer direct ? direct : findOwner(level, friend.ownerUuid());
        if (player == null) {
            return;
        }
        CompoundTag data = state(player);
        data.putBoolean(RAGE, true);
        add(data, CONFIDENCE, 16.0D, 0, 100);
        add(data, INTEREST, 10.0D, 0, 100);
        friend.setRage(true);
        friend.setRushing(true);
        friend.setCornerEscalated(true);
        if (!friend.eventId().equals("cat_curse") && !friend.eventId().equals("white_grief") && !friend.eventId().equals("sky_dialog")) {
            friend.setEventId("attack_emerge");
            friend.getPersistentData().putInt(FriendEntity.TAG_LIFETIME, 20 * 120);
        }
        friend.setDeltaMovement(0.0D, 0.0D, 0.0D);
        facePlayer(friend, player);
        play(level, friend.blockPosition(), FriendSoundEvents.FRIEND_SCREAM, 0.58F, 0.62F, 0.86F);
        data.putLong(EVENT_COOLDOWN, level.getGameTime() + seconds(90, 180));
        player.displayClientMessage(Component.literal("friend stopped hiding"), true);
    }

    @SubscribeEvent
    public static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.level().dimension().equals(FriendDimensions.DREAM)) {
            CompoundTag data = state(player);
            BlockPos pos = event.getPos();
            BlockState clicked = event.getLevel().getBlockState(pos);
            int variant = Math.max(0, data.getInt(DREAM_VARIANT));
            if (clicked.getBlock() instanceof DoorBlock && FriendDimensions.isDreamExitDoor(pos, variant)) {
                data.putBoolean(DREAM_STARTED, false);
                data.putLong(DREAM_CUTSCENE_UNTIL, 0L);
                cleanupOwnedFriends(player.serverLevel(), player);
                FriendDimensions.sendHomeFromDream(player, FriendDimensions.dreamReturnPos(player));
                event.setCanceled(true);
            }
            return;
        }
        if (player.level().dimension().equals(FriendDimensions.PARADISE)) {
            // Paradise removed from release build: no buttons, no books, no choices.
            event.setCanceled(true);
            return;
        }
        if (!player.level().dimension().equals(Level.OVERWORLD)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState clicked = player.level().getBlockState(pos);
        CompoundTag data = state(player);

        // Do not learn generated village houses as the player's home.
        // Interacting with a naturally generated chest/door/bed is not enough.
        // The block must be one the player placed, or be immediately attached to a player-built house part.
        if (!isPlayerPlacedNear(data, pos, 1)) {
            return;
        }

        int boost = homeBlockScore(clicked, player.serverLevel(), pos);
        if (clicked.getBlock() instanceof DoorBlock) {
            rememberBlockMemory(data, MEMORY_DOORS, pos, player.serverLevel().getGameTime());
        } else if (clicked.getBlock() instanceof ChestBlock || clicked.getBlock() instanceof BarrelBlock) {
            rememberBlockMemory(data, MEMORY_CONTAINERS, pos, player.serverLevel().getGameTime());
        } else if (clicked.getBlock() instanceof GlassBlock || clicked.getBlock() instanceof StainedGlassBlock || clicked.is(Blocks.GLASS_PANE)) {
            rememberBlockMemory(data, MEMORY_WINDOWS, pos, player.serverLevel().getGameTime());
        }
        if (boost > 0) {
            boostHomeSignal(player.serverLevel(), player, data, pos, Math.max(18, boost * 3));
        }
    }

    @SubscribeEvent
    public static void blockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.level().dimension().equals(Level.OVERWORLD)
                || player.level().isClientSide) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = event.getPos();
        BlockState placed = event.getPlacedBlock();
        CompoundTag data = state(player);
        rememberPlayerPlacedBlock(data, pos, level.getGameTime());
        MineShortTermMemory.recordPlaced(level, player, data, pos, placed, level.getGameTime());
        int boost = homeBlockScore(placed, level, pos);
        if (boost <= 0) {
            if (placed.isSolidRender(level, pos) && !isIgnoredHomeStructureBlock(placed)) {
                data.putInt(HOME_INTERACTION_BOOST, Mth.clamp(data.getInt(HOME_INTERACTION_BOOST) + 3, 0, 180));
            }
            return;
        }
        if (placed.getBlock() instanceof DoorBlock) {
            rememberBlockMemory(data, MEMORY_DOORS, pos, level.getGameTime());
        } else if (placed.getBlock() instanceof ChestBlock || placed.getBlock() instanceof BarrelBlock) {
            rememberBlockMemory(data, MEMORY_CONTAINERS, pos, level.getGameTime());
        } else if (isWindowBlock(placed)) {
            rememberBlockMemory(data, MEMORY_WINDOWS, pos, level.getGameTime());
        }
        boostHomeSignal(level, player, data, pos, Math.max(24, boost * 4));
    }

    @SubscribeEvent
    public static void blockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || player.level().isClientSide
                || !player.level().dimension().equals(Level.OVERWORLD)) {
            return;
        }
        MineShortTermMemory.recordMined(player.serverLevel(), player, state(player), event.getPos(), event.getState(),
                player.serverLevel().getGameTime());
    }

    public static void tickFriend(FriendEntity friend) {
        if (!(friend.level() instanceof ServerLevel level)) {
            return;
        }
        ServerPlayer owner = findOwner(level, friend.ownerUuid());
        String event = friend.eventId();
        float maxOwnerDistance = event.equals("cat_curse") || event.equals("void_watch") || event.equals("sky_dialog")
                || event.equals("paradise_guide") || event.equals("white_grief") || event.equals("final_sentence")
                || (event.equals("attack_emerge") || event.equals("false_attack")) || event.equals("night_far_watch")
                || (isPeekEvent(event) && friend.cornerEscalated()) ? 220.0F : 72.0F;
        if (owner == null || owner.level() != level || owner.distanceTo(friend) > maxOwnerDistance) {
            disappear(friend, "orphan", false);
            return;
        }

        facePlayer(friend, owner);
        scareMobsAroundFriend(level, friend, 40.0D);
        CompoundTag tag = friend.getPersistentData();
        int lifetime = tag.getInt(FriendEntity.TAG_LIFETIME) - 1;
        tag.putInt(FriendEntity.TAG_LIFETIME, lifetime);
        if (lifetime <= 0) {
            disappear(friend, "lifetime", true);
            return;
        }

        double distance = owner.distanceTo(friend);
        if (event.equals("cat_curse")) {
            handleCatCurseChaser(level, owner, friend, distance);
            return;
        }
        if (event.equals("dream_bed")) {
            handleDreamBedFriend(level, owner, friend, distance);
            return;
        }
        if (event.equals("dream_chase")) {
            handleDreamChase(level, owner, friend, distance);
            return;
        }
        if ((event.equals("attack_emerge") || event.equals("false_attack"))) {
            handleControlledAttack(level, owner, friend, distance, false);
            return;
        }
        if (event.equals("void_watch")) {
            friend.setGlowingTag(true);
            if (distance < 9.0D && friend.tickCount > 30) {
                disappear(friend, "void_close", true);
            }
            return;
        }
        if (event.equals("sky_dialog")) {
            if (friend.tickCount % 55 == 0) {
                play(level, friend.blockPosition(), FriendSoundEvents.LOW_BREATH, 0.08F, 0.54F, 0.75F);
            }
            return;
        }
        if (event.equals("paradise_guide") || event.equals("white_grief") || event.equals("final_sentence")) {
            if (event.equals("white_grief") && friend.tickCount % 34 == 0) {
                level.sendParticles(ParticleTypes.WHITE_ASH, friend.getX(), friend.getY() + 2.6D, friend.getZ(), 1, 0.08D, 0.15D, 0.08D, 0.005D);
            }
            return;
        }
        if (FriendStalkingDirector.tickScene(level, owner, friend, state(owner))) {
            return;
        }
        if (isPeekEvent(event) && handleCornerPeek(level, owner, friend, distance)) {
            return;
        }
        if (isMineEvent(event) && level.getBrightness(LightLayer.BLOCK, friend.blockPosition()) > 11 && friend.tickCount > 20
                && !(isPeekEvent(event) && friend.cornerEscalated())) {
            disappear(friend, "bright", true);
            return;
        }

        if (distance < approachDistance(event) && !(isPeekEvent(event) && friend.cornerEscalated())) {
            if (event.equals("final_close_presence") || event.equals("stare_event")) {
                punishIfAllowed(level, owner, friend);
            } else {
                disappear(friend, "approached", true);
            }
            return;
        }

        SightState sight = sightState(owner, friend, 35.0D);
        if (sight != SightState.NONE) {
            CompoundTag data = state(owner);
            data.putLong(LAST_SEEN_TICK, level.getGameTime());
            tag.putInt(FriendEntity.TAG_SEEN_TICKS, tag.getInt(FriendEntity.TAG_SEEN_TICKS) + 1);
            add(data, CONFIDENCE, owner.isSprinting() ? 0.08D : 0.025D, 0, 100);
            int delay = tag.getInt(FriendEntity.TAG_STARE_DELAY);
            if (delay > 0) {
                tag.putInt(FriendEntity.TAG_STARE_DELAY, delay - 1);
                return;
            }
            if (!(isPeekEvent(event) && friend.cornerEscalated())
                    && shouldDisappearFromSight(data.getInt(PHASE), event, sight, tag.getInt(FriendEntity.TAG_SEEN_TICKS))) {
                disappear(friend, "seen", true);
            }
        }
    }

    public static void disappear(FriendEntity friend, String reason, boolean sound) {
        if (!(friend.level() instanceof ServerLevel level)) {
            friend.discard();
            return;
        }
        ServerPlayer owner = findOwner(level, friend.ownerUuid());
        if (owner != null) {
            CompoundTag data = state(owner);
            data.putBoolean(ACTIVE, false);
            String event = friend.eventId();
            CompoundTag tag = friend.getPersistentData();
            if (isPeekEvent(event)) {
                if (reason.equals("peek_spotted") || reason.equals("seen")
                        || tag.getInt(FriendEntity.TAG_SEEN_TICKS) > 0
                        || tag.getInt(FriendEntity.TAG_PEEK_PANIC_TICKS) > 0) {
                    recordPeekSeen(data);
                } else if (shouldCountPeekAsMiss(reason)) {
                    recordPeekMissed(data);
                }
            }
            if (reason.equals("seen") || reason.equals("approached")) {
                data.putInt(SEEN_COUNT, data.getInt(SEEN_COUNT) + 1);
                add(data, CONFIDENCE, reason.equals("approached") ? 3.0D : 1.5D, 0, 100);
            }
        }
        if (sound) {
            play(level, friend.blockPosition(), FriendSoundEvents.DISAPPEAR_SOFT, 0.12F, 0.8F, 1.2F);
        }
        friend.discard();
    }

        private static void updateSurvivalPressure(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        boolean activeFriend = hasActiveOwnedFriend(level, player, 96.0D);
        boolean underground = isPlayerInMineContext(level, player);
        boolean night = !level.isDay();
        boolean nearHome = data.getBoolean(HAS_HOME)
                && player.blockPosition().distSqr(new BlockPos(data.getInt(HOME_X), data.getInt(HOME_Y), data.getInt(HOME_Z))) < 44.0D * 44.0D;

        // Threat debt solves the release problem where a player can survive 15-20 minutes and never see anything.
        // If Friend has not appeared recently, the debt forces visible presence before it forces attacks.
        int debtGain = activeFriend ? -18 : 4;
        if (underground) debtGain += 2;
        if (night) debtGain += 1;
        if (nearHome) debtGain += 2;
        if (player.tickCount < 20 * 120) debtGain = Math.max(1, debtGain - 2);
        add(data, THREAT_DEBT, debtGain, 0, 900);

        // Fear score is behavior-based. It is not displayed; it only makes Friend more confident.
        boolean panicMovement = player.isSprinting() || player.getDeltaMovement().horizontalDistanceSqr() > 0.045D;
        boolean dark = level.getBrightness(LightLayer.BLOCK, player.blockPosition()) <= 6;
        if (panicMovement && (activeFriend || dark || underground || night)) {
            add(data, FEAR_SCORE, 3, 0, 600);
            add(data, CONFIDENCE, 0.18D, 0, 100);
        } else if (time % 100L == 0L) {
            add(data, FEAR_SCORE, -1, 0, 600);
        }
    }

    private static boolean hasActiveOwnedFriend(ServerLevel level, ServerPlayer player, double radius) {
        return !level.getEntitiesOfClass(FriendEntity.class, player.getBoundingBox().inflate(radius),
                friend -> player.getUUID().toString().equals(friend.ownerUuid())
                        && !friend.isRemoved()
                        && friend.isAlive()
                        && !friend.eventId().equals("vanish")).isEmpty();
    }

    private static boolean isVisiblePressureEvent(String event) {
        return event.equals("visible_peek")
                || event.equals("corner_peek")
                || event.equals("hand_peek")
                || event.equals("hole_peek")
                || event.equals("ceiling_hole_peek")
                || event.equals("floor_hole_peek")
                || event.equals("low_ceiling_2_peek")
                || event.equals("low_ceiling_1_peek")
                || event.equals("stalk")
                || event.equals("stalking")
                || event.equals("peek")
                || event.equals("stalk_distant")
                || event.equals("night_far_watch")
                || event.equals("false_attack")
                || (event.equals("attack_emerge") || event.equals("false_attack"))
                || event.equals("window_watch")
                || event.equals("doorway_lean");
    }

private static void updateInterest(ServerLevel level, ServerPlayer player, CompoundTag data) {
        BlockPos pos = player.blockPosition();
        int light = level.getBrightness(LightLayer.BLOCK, pos);
        boolean dark = light <= 7;
        boolean veryDark = light <= 3;
        boolean underground = player.getY() <= 48.0D || !level.canSeeSky(pos);
        boolean alone = level.getPlayers(p -> p != player && !p.isSpectator() && p.distanceTo(player) < 64.0F).isEmpty();
        boolean nearHome = data.getBoolean(HAS_HOME) && pos.distSqr(new BlockPos(data.getInt(HOME_X), data.getInt(HOME_Y), data.getInt(HOME_Z))) < 36.0D * 36.0D;
        boolean nightHome = nearHome && !level.isDay();

        if (dark) {
            add(data, DARKNESS_TIME, 20, 0, Integer.MAX_VALUE);
        }
        if (underground) {
            add(data, UNDERGROUND_TIME, 20, 0, Integer.MAX_VALUE);
        }

        double delta = 0.0D;
        if (alone) delta += 0.6D;
        if (dark) delta += 0.7D;
        if (veryDark) delta += 0.4D;
        if (underground) delta += 0.9D;
        if (nightHome) delta += 0.7D;
        if (level.isDay() && level.canSeeSky(pos)) delta -= 0.5D;
        add(data, INTEREST, delta, 0, 100);
    }

        private static void updatePhase(ServerLevel level, ServerPlayer player, CompoundTag data) {
        int phase = data.getInt(PHASE);
        int target = phase;
        double interest = data.getDouble(INTEREST);
        long underground = data.getLong(UNDERGROUND_TIME);
        int seen = data.getInt(SEEN_COUNT);
        double confidence = data.getDouble(CONFIDENCE);
        int threatDebt = data.getInt(THREAT_DEBT);

        // Survival pacing: four public phases. Friend must become active in a real survival session,
        // not after hours of hidden counters.
        if (player.tickCount >= 20 * 180 || interest >= 8.0D || threatDebt >= 120) target = Math.max(target, 1);
        if (interest >= 22.0D || underground >= 20 * 180 || seen >= 1 || threatDebt >= 220) target = Math.max(target, 2);
        if (data.getBoolean(HAS_HOME) || interest >= 42.0D || seen >= 3 || threatDebt >= 340) target = Math.max(target, 3);
        if (interest >= 62.0D || confidence >= 35.0D || seen >= 5 || threatDebt >= 520) target = Math.max(target, 4);

        if (!data.getBoolean(CAT_CURSE)) {
            target = Math.min(target, MAX_RELEASE_PHASE);
        }

        if (target > phase) {
            data.putInt(PHASE, Math.min(phase + 1, target));
            if (phase + 1 == 3) playSubtitle(level, player, FriendSoundEvents.SUBTITLE_HOME);
        }
    }


    private static void updateHome(ServerLevel level, ServerPlayer player, CompoundTag data) {
        BlockPos center = player.blockPosition();
        HomeScan scan = scanHomeArea(level, center, data);
        int interactionBoost = data.getInt(HOME_INTERACTION_BOOST);
        if (interactionBoost > 0) {
            data.putInt(HOME_INTERACTION_BOOST, Math.max(0, interactionBoost - 6));
        }

        boolean stationary = isStationaryHomeSample(player, data, center);
        boolean night = !level.isDay();
        boolean hasUtility = scan.beds > 0 || scan.storage > 0 || scan.workstations > 0;
        boolean playerBuilt = scan.playerPlaced >= 18 && scan.enclosure >= 22;
        boolean realHouse = isStrictPlayerHouse(level, center, data, scan);
        boolean rememberedUtility = rememberedPlayerPlacedBlock(data, MEMORY_CONTAINERS, center, 18).isPresent()
                || rememberedPlayerPlacedBlock(data, MEMORY_DOORS, center, 18).isPresent()
                || rememberedPlayerPlacedBlock(data, MEMORY_WINDOWS, center, 18).isPresent();

        int behaviorScore = 0;
        if (stationary && realHouse) behaviorScore += 10;
        if (night && realHouse) behaviorScore += 16;
        if (night && scan.lights >= 2 && hasUtility && playerBuilt) behaviorScore += 10;
        if (rememberedUtility && realHouse) behaviorScore += 10;
        if (data.getBoolean(HAS_HOME) && center.distSqr(new BlockPos(data.getInt(HOME_X), data.getInt(HOME_Y), data.getInt(HOME_Z))) < 42.0D * 42.0D) {
            behaviorScore += 8;
        }

        int smartScore = scan.score + behaviorScore + (realHouse ? interactionBoost : Math.min(12, interactionBoost));
        if (scan.storage > 0 && scan.workstations > 0) smartScore += 22;
        if (scan.storage > 0 && scan.lights >= 2 && realHouse) smartScore += 14;
        if (scan.workstations > 0 && scan.lights >= 2 && realHouse) smartScore += 12;
        if (scan.beds > 0 && (scan.storage > 0 || scan.workstations > 0)) smartScore += 24;
        if (scan.storage >= 2) smartScore += 10;
        if (scan.interiorAir >= 28 && realHouse) smartScore += 8;
        if (scan.lights >= 3 && realHouse) smartScore += 6;
        if (scan.enclosure >= 30 && scan.playerPlaced >= 24) smartScore += 16;

        boolean strongCandidate = realHouse && (smartScore >= 72
                || (stationary && night && smartScore >= 58)
                || (scan.beds > 0 && scan.storage > 0 && scan.enclosure >= 24 && scan.playerPlaced >= 20));

        if (data.getBoolean(HAS_HOME)) {
            BlockPos stored = new BlockPos(data.getInt(HOME_X), data.getInt(HOME_Y), data.getInt(HOME_Z));
            if (center.distSqr(stored) < 20.0D * 20.0D) {
                HomeScan storedScan = scanHomeArea(level, stored, data);
                if (!isStrictPlayerHouse(level, stored, data, storedScan)) {
                    data.putBoolean(HAS_HOME, false);
                    data.putLong(HOME_TIME, 0L);
                }
            }
        }

        if (strongCandidate) {
            rememberHomeCandidate(level, player, data, center, Math.max(18, smartScore / 2), false);
        }
        selectBestHomeCandidate(level, player, data, center, smartScore);

        if (data.getBoolean(HAS_HOME) && center.distSqr(new BlockPos(data.getInt(HOME_X), data.getInt(HOME_Y), data.getInt(HOME_Z))) < 36.0D * 36.0D) {
            add(data, HOME_TIME, Math.max(40, smartScore), 0, 100000);
        } else {
            add(data, HOME_TIME, strongCandidate ? 20 : -12, 0, 100000);
        }
    }

    private static HomeScan scanHomeArea(ServerLevel level, BlockPos center, CompoundTag data) {
        int score = 0;
        int checked = 0;
        int beds = 0;
        int storage = 0;
        int workstations = 0;
        int doors = 0;
        int windows = 0;
        int lights = 0;
        int shelter = 0;
        int interiorAir = 0;
        int playerPlaced = 0;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-12, -5, -12), center.offset(12, 6, 12))) {
            if (++checked > 4200) break;
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            boolean placedByPlayer = isPlayerPlacedNear(data, pos, 0);

            // Critical rule: generated buildings are not the player's home.
            // Only player-placed blocks may contribute home score or home utilities.
            if (placedByPlayer) {
                int blockScore = homeBlockScore(state, level, pos);
                score += blockScore;

                if (isStrictPlayerHouseStructureBlock(state)) {
                    playerPlaced++;
                    score += 3;
                }

                if (block instanceof BedBlock) {
                    beds++;
                } else if (block instanceof ChestBlock || block instanceof BarrelBlock) {
                    storage++;
                } else if (isWorkstationBlock(state)) {
                    workstations++;
                } else if (block instanceof DoorBlock) {
                    doors++;
                } else if (isWindowBlock(state)) {
                    windows++;
                } else if (isLightBlock(state)) {
                    lights++;
                }

                if (pos.getY() >= center.getY() && pos.getY() <= center.getY() + 4
                        && isStrictPlayerHouseStructureBlock(state)
                        && state.isSolidRender(level, pos)) {
                    shelter++;
                }
            }

            if (Math.abs(pos.getY() - center.getY()) <= 1 && state.isAir()) {
                interiorAir++;
            }
        }

        int enclosure = enclosureScore(level, center, data);
        if (enclosure >= 16) score += enclosure;
        if (playerPlaced >= 12) score += Math.min(28, playerPlaced / 2);
        if (interiorAir >= 20 && enclosure >= 18) score += Math.min(10, interiorAir / 10);
        if (doors > 0 && enclosure >= 18) score += 8;
        if (windows > 0 && enclosure >= 18) score += 6;
        return new HomeScan(score, beds, storage, workstations, doors, windows, lights, shelter, interiorAir, playerPlaced, enclosure);
    }


    private static boolean isStrictPlayerHouse(ServerLevel level, BlockPos center, CompoundTag data, HomeScan scan) {
        boolean hasPlayerDoor = scan.doors > 0 || rememberedPlayerPlacedBlock(data, MEMORY_DOORS, center, 16).isPresent();
        boolean hasPlayerWindow = scan.windows > 0 || rememberedPlayerPlacedBlock(data, MEMORY_WINDOWS, center, 16).isPresent();
        boolean hasPlayerUtility = scan.beds > 0
                || scan.storage > 0
                || scan.workstations > 0
                || rememberedPlayerPlacedBlock(data, MEMORY_CONTAINERS, center, 16).isPresent();

        boolean enoughShell = scan.playerPlaced >= 18 && scan.enclosure >= 22;
        boolean hasRoof = scan.shelter >= 10 || !level.canSeeSky(center);
        boolean hasInteriorSpace = scan.interiorAir >= 16;
        boolean looksLikeHouse = hasPlayerDoor || hasPlayerWindow || (scan.lights >= 2 && hasPlayerUtility);

        return enoughShell && hasRoof && hasInteriorSpace && hasPlayerUtility && looksLikeHouse;
    }

    private static boolean isPlayerHomeStructureBlock(ServerLevel level, CompoundTag data, BlockPos pos) {
        return isPlayerPlacedNear(data, pos, 0)
                && isStrictPlayerHouseStructureBlock(level.getBlockState(pos));
    }

    private static boolean isStrictPlayerHouseStructureBlock(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        Block block = state.getBlock();
        if (block instanceof LeavesBlock || block instanceof BushBlock
                || block instanceof TorchBlock || block instanceof WallTorchBlock
                || block instanceof CampfireBlock
                || state.is(BlockTags.LEAVES)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.SAND)
                || state.is(Blocks.GRAVEL)) {
            return false;
        }
        return block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof GlassBlock
                || block instanceof StainedGlassBlock
                || state.is(Blocks.GLASS_PANE)
                || block instanceof SlabBlock
                || block instanceof StairBlock
                || block instanceof WallBlock
                || state.is(BlockTags.LOGS)
                || state.blocksMotion();
    }

    private static int homeBlockScore(BlockState state, ServerLevel level, BlockPos pos) {
        Block block = state.getBlock();
        if (block instanceof BedBlock) return 34;
        if (block instanceof ChestBlock || block instanceof BarrelBlock) return 18;
        if (isWorkstationBlock(state)) return 14;
        if (block instanceof DoorBlock) return 6;
        if (isWindowBlock(state)) return 5;
        if (isLightBlock(state)) return 4;
        if (block instanceof CampfireBlock) return 5;
        return 0;
    }

    private static int enclosureScore(ServerLevel level, BlockPos center, CompoundTag data) {
        int best = 0;
        for (int yOffset = -1; yOffset <= 1; yOffset++) {
            BlockPos base = center.offset(0, yOffset, 0);
            int floor = 0;
            int ceiling = 0;
            int walls = 0;
            int playerBlocks = 0;
            int airInside = 0;

            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos floorPos = base.offset(dx, -1, dz);
                    BlockPos ceilingPos = base.offset(dx, 3, dz);
                    if (isPlayerHomeStructureBlock(level, data, floorPos)) {
                        floor++;
                        playerBlocks++;
                    }
                    if (isPlayerHomeStructureBlock(level, data, ceilingPos)) {
                        ceiling++;
                        playerBlocks++;
                    }
                    for (int dy = 0; dy <= 2; dy++) {
                        BlockPos inside = base.offset(dx, dy, dz);
                        if (Math.abs(dx) < 2 && Math.abs(dz) < 2 && level.getBlockState(inside).isAir()) {
                            airInside++;
                        }
                        if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                            if (isPlayerHomeStructureBlock(level, data, inside)) {
                                walls++;
                                playerBlocks++;
                            }
                        }
                    }
                }
            }

            // Not enough player-built shell blocks = not a house.
            // This blocks caves, village houses, trees, and temporary shelters.
            if (playerBlocks < 14 || walls < 12) {
                continue;
            }

            int score = 0;
            if (floor >= 8) score += 4;
            if (ceiling >= 7) score += 9;
            if (walls >= 18) score += 12;
            if (walls >= 28 && ceiling >= 10) score += 14;
            if (airInside >= 12) score += 4;
            if (playerBlocks >= 18) score += 14;
            if (playerBlocks >= 28) score += 18;
            best = Math.max(best, score);
        }
        return best;
    }

    private static boolean isHomeStructureBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return isStructureBlockForHome(state) && state.isSolidRender(level, pos);
    }

    private static boolean isStructureBlockForHome(BlockState state) {
        if (state.isAir() || isIgnoredHomeStructureBlock(state)) {
            return false;
        }
        return state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof GlassBlock
                || state.getBlock() instanceof StainedGlassBlock
                || state.is(Blocks.GLASS_PANE)
                || state.blocksMotion();
    }

    private static boolean isIgnoredHomeStructureBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof LeavesBlock
                || block instanceof TorchBlock
                || block instanceof WallTorchBlock
                || block instanceof FenceBlock
                || block instanceof FenceGateBlock
                || block instanceof LadderBlock
                || block instanceof CampfireBlock
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.LEAVES)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.STONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.SAND)
                || state.is(Blocks.GRAVEL);
    }

    private static boolean isWorkstationBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof FurnaceBlock
                || block instanceof CraftingTableBlock
                || state.is(Blocks.BLAST_FURNACE)
                || state.is(Blocks.SMOKER)
                || state.is(Blocks.ANVIL)
                || state.is(Blocks.CHIPPED_ANVIL)
                || state.is(Blocks.DAMAGED_ANVIL)
                || state.is(Blocks.ENCHANTING_TABLE)
                || state.is(Blocks.BREWING_STAND)
                || state.is(Blocks.LECTERN)
                || state.is(Blocks.BOOKSHELF)
                || state.is(Blocks.CARTOGRAPHY_TABLE)
                || state.is(Blocks.FLETCHING_TABLE)
                || state.is(Blocks.GRINDSTONE)
                || state.is(Blocks.LOOM)
                || state.is(Blocks.SMITHING_TABLE)
                || state.is(Blocks.STONECUTTER);
    }

    private static boolean isLightBlock(BlockState state) {
        return isRemovableLightSource(state);
    }

    private static boolean isRemovableLightSource(BlockState state) {
        Block block = state.getBlock();
        return block instanceof TorchBlock
                || block instanceof WallTorchBlock
                || block instanceof CandleBlock
                || block instanceof CampfireBlock
                || state.is(Blocks.LANTERN)
                || state.is(Blocks.SOUL_LANTERN)
                || state.is(Blocks.GLOWSTONE)
                || state.is(Blocks.SEA_LANTERN)
                || state.is(Blocks.SHROOMLIGHT)
                || state.is(Blocks.REDSTONE_LAMP)
                || state.is(Blocks.JACK_O_LANTERN)
                || state.is(Blocks.OCHRE_FROGLIGHT)
                || state.is(Blocks.VERDANT_FROGLIGHT)
                || state.is(Blocks.PEARLESCENT_FROGLIGHT)
                || state.is(Blocks.END_ROD);
    }

    private static int homeTorchRingScore(ServerLevel level, CompoundTag data, BlockPos playerPos) {
        BlockPos center = data.getBoolean(HAS_HOME)
                ? new BlockPos(data.getInt(HOME_X), data.getInt(HOME_Y), data.getInt(HOME_Z))
                : playerPos;
        int lights = 0;
        int outerLights = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-18, -4, -18), center.offset(18, 6, 18))) {
            BlockState state = level.getBlockState(pos);
            if (!isLightBlock(state)) {
                continue;
            }
            double dist = Math.sqrt(center.distSqr(pos));
            if (dist <= 18.0D) {
                lights++;
                if (dist >= 6.0D) {
                    outerLights++;
                }
            }
        }
        if (lights < 5) {
            return 0;
        }
        return Mth.clamp(lights / 2 + outerLights, 0, 24);
    }

    private static boolean isStationaryHomeSample(ServerPlayer player, CompoundTag data, BlockPos center) {
        boolean hasSample = data.contains(HOME_LAST_SAMPLE_X);
        BlockPos previous = new BlockPos(data.getInt(HOME_LAST_SAMPLE_X), data.getInt(HOME_LAST_SAMPLE_Y), data.getInt(HOME_LAST_SAMPLE_Z));
        data.putInt(HOME_LAST_SAMPLE_X, center.getX());
        data.putInt(HOME_LAST_SAMPLE_Y, center.getY());
        data.putInt(HOME_LAST_SAMPLE_Z, center.getZ());
        if (!hasSample) {
            return false;
        }
        return previous.distSqr(center) <= 4.0D * 4.0D && player.getDeltaMovement().horizontalDistanceSqr() < 0.012D;
    }

    private static void boostHomeSignal(ServerLevel level, ServerPlayer player, CompoundTag data, BlockPos pos, int amount) {
        // Home learning must be based on the player's own construction, not on generated village/cave loot.
        // If the interacted/placed point is not attached to player-placed blocks, it can raise attention a bit,
        // but it is not allowed to become a home candidate.
        if (!isPlayerPlacedNear(data, pos, 1)) {
            data.putInt(HOME_INTERACTION_BOOST, Mth.clamp(data.getInt(HOME_INTERACTION_BOOST) + Math.min(8, amount / 4), 0, 40));
            return;
        }

        data.putInt(HOME_INTERACTION_BOOST, Mth.clamp(data.getInt(HOME_INTERACTION_BOOST) + amount, 0, 180));
        HomeScan scan = scanHomeArea(level, pos, data);
        if (!isStrictPlayerHouse(level, pos, data, scan)) {
            return;
        }
        rememberHomeCandidate(level, player, data, pos, amount, false);
        selectBestHomeCandidate(level, player, data, player.blockPosition(), amount);
    }

    private static void rememberHomeCandidate(ServerLevel level, ServerPlayer player, CompoundTag data, BlockPos center, int gain, boolean force) {
        ListTag homes = data.getList(HOME_CANDIDATES, Tag.TAG_COMPOUND);
        long time = level.getGameTime();
        CompoundTag chosen = null;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < homes.size(); i++) {
            CompoundTag tag = homes.getCompound(i);
            BlockPos remembered = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            double dist = remembered.distSqr(center);
            if (dist < 34.0D * 34.0D && Math.abs(remembered.getY() - center.getY()) <= 18 && dist < bestDistance) {
                chosen = tag;
                bestDistance = dist;
            }
        }
        if (chosen == null) {
            chosen = new CompoundTag();
            chosen.putInt("x", center.getX());
            chosen.putInt("y", center.getY());
            chosen.putInt("z", center.getZ());
            chosen.putInt("score", Math.max(10, gain));
            chosen.putInt("visits", 1);
            homes.add(chosen);
        } else {
            int scoreGain = gain;
            if (chosen.getBoolean("away")) {
                scoreGain += 28;
                chosen.putBoolean("away", false);
            }
            chosen.putInt("score", Mth.clamp(chosen.getInt("score") + scoreGain, 0, 5000));
            chosen.putInt("visits", Math.min(999, chosen.getInt("visits") + 1));
            int smooth = force ? 1 : 6;
            chosen.putInt("x", (chosen.getInt("x") * smooth + center.getX()) / (smooth + 1));
            chosen.putInt("y", (chosen.getInt("y") * smooth + center.getY()) / (smooth + 1));
            chosen.putInt("z", (chosen.getInt("z") * smooth + center.getZ()) / (smooth + 1));
        }
        if (force) {
            chosen.putInt("score", Math.max(chosen.getInt("score"), 500));
        }
        chosen.putLong("last", time);
        if (!level.isDay()) {
            chosen.putInt("night", Math.min(999, chosen.getInt("night") + 1));
            chosen.putInt("score", Math.min(5000, chosen.getInt("score") + 8));
        }
        pruneHomeCandidates(homes, player.blockPosition(), time);
        data.put(HOME_CANDIDATES, homes);
    }

    private static void selectBestHomeCandidate(ServerLevel level, ServerPlayer player, CompoundTag data, BlockPos current, int currentScore) {
        ListTag homes = data.getList(HOME_CANDIDATES, Tag.TAG_COMPOUND);
        if (homes.isEmpty()) {
            return;
        }
        long time = level.getGameTime();
        CompoundTag best = null;
        int bestWeight = Integer.MIN_VALUE;
        for (int i = 0; i < homes.size(); i++) {
            CompoundTag tag = homes.getCompound(i);
            BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            double dist = current.distSqr(pos);
            int score = tag.getInt("score");
            if (dist > 72.0D * 72.0D) {
                tag.putBoolean("away", true);
                score = Math.max(0, score - 1);
            } else if (dist < 22.0D * 22.0D) {
                score = Math.min(5000, score + 2);
            }
            if (time - tag.getLong("last") > 20L * 60L * 12L) {
                score = Math.max(0, score - 2);
            }
            tag.putInt("score", score);
            int weight = score + tag.getInt("visits") * 6 + tag.getInt("night") * 8;
            if (dist < 40.0D * 40.0D) {
                weight += 20;
            }
            if (weight > bestWeight) {
                bestWeight = weight;
                best = tag;
            }
        }
        pruneHomeCandidates(homes, current, time);
        data.put(HOME_CANDIDATES, homes);
        if (best == null) {
            return;
        }
        int bestScore = best.getInt("score");
        BlockPos bestPos = new BlockPos(best.getInt("x"), best.getInt("y"), best.getInt("z"));
        HomeScan bestScan = scanHomeArea(level, bestPos, data);
        if (!isStrictPlayerHouse(level, bestPos, data, bestScan)) {
            best.putInt("score", Math.max(0, bestScore - 35));
            return;
        }

        boolean instant = currentScore >= 110 || bestScore >= 135 || (best.getInt("visits") >= 4 && bestScore >= 96);
        if (!instant) {
            return;
        }
        int x = best.getInt("x");
        int y = best.getInt("y");
        int z = best.getInt("z");
        if (!data.getBoolean(HAS_HOME)) {
            data.putInt(HOME_X, x);
            data.putInt(HOME_Y, y);
            data.putInt(HOME_Z, z);
        } else {
            BlockPos old = new BlockPos(data.getInt(HOME_X), data.getInt(HOME_Y), data.getInt(HOME_Z));
            int blend = old.distSqr(new BlockPos(x, y, z)) > 64.0D * 64.0D && bestScore > 140 ? 2 : 9;
            data.putInt(HOME_X, (old.getX() * blend + x) / (blend + 1));
            data.putInt(HOME_Y, (old.getY() * blend + y) / (blend + 1));
            data.putInt(HOME_Z, (old.getZ() * blend + z) / (blend + 1));
        }
        data.putBoolean(HAS_HOME, true);
    }

    private static void pruneHomeCandidates(ListTag homes, BlockPos current, long time) {
        for (int i = homes.size() - 1; i >= 0; i--) {
            CompoundTag tag = homes.getCompound(i);
            if (tag.getInt("score") <= 0 && time - tag.getLong("last") > 20L * 60L * 8L) {
                homes.remove(i);
            }
        }
        while (homes.size() > 12) {
            int weakest = 0;
            int weakestWeight = Integer.MAX_VALUE;
            for (int i = 0; i < homes.size(); i++) {
                CompoundTag tag = homes.getCompound(i);
                BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                int weight = tag.getInt("score") + tag.getInt("visits") * 4 - Mth.floor(Math.sqrt(current.distSqr(pos)) / 8.0D);
                if (weight < weakestWeight) {
                    weakestWeight = weight;
                    weakest = i;
                }
            }
            homes.remove(weakest);
        }
    }

    private static void igniteCatCurse(ServerLevel level, ServerPlayer player) {
        if (!FriendConfig.ENABLE_CAT_CURSE.get()) {
            return;
        }
        CompoundTag data = state(player);
        data.putBoolean(CAT_CURSE, true);
        data.putInt(PHASE, 7);
        data.putDouble(INTEREST, 100.0D);
        data.putDouble(CONFIDENCE, 100.0D);
        data.putDouble(CAT_DISTANCE, 1000.0D + RANDOM.nextInt(121) - 60.0D);
        data.putLong(EVENT_COOLDOWN, level.getGameTime() + 5L);
        data.putLong(SOUND_COOLDOWN, 0L);
        data.putLong(CAT_GRIEF_COOLDOWN, level.getGameTime() + seconds(2, 5));
        data.putLong(CAT_BRIDGE_BREAK_COOLDOWN, level.getGameTime() + 20L);
        data.putLong(CAT_ROAR_COOLDOWN, 0L);
        data.putLong(CAT_DISTANCE_GLITCH_COOLDOWN, level.getGameTime() + seconds(18, 45));
        cleanupOwnedFriends(level, player);
        play(level, player.blockPosition(), FriendSoundEvents.CAT_CURSE_ROAR, 0.65F, 0.50F, 0.72F);
        playSubtitle(level, player, FriendSoundEvents.SUBTITLE_HEARD);
        player.displayClientMessage(Component.translatable("commands.friend.curse_on"), true);
    }

    private static void applyCatCursePressure(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        data.putInt(PHASE, 7);
        data.putDouble(INTEREST, 100.0D);
        data.putDouble(CONFIDENCE, 100.0D);
        double distance = data.getDouble(CAT_DISTANCE);
        if (distance <= 0.0D) {
            distance = 1000.0D;
        }
        FriendEntity chaser = findOwnedEvent(level, player, "cat_curse", 260.0D);
        if (chaser != null) {
            maybeGlitchVisibleDistance(level, player, data, chaser, time);
            distance = player.distanceTo(chaser);
        } else {
            distance = Math.max(72.0D, distance - (0.32D + RANDOM.nextDouble() * 0.28D));
            if (time >= data.getLong(CAT_DISTANCE_GLITCH_COOLDOWN) && distance > 140.0D) {
                double drop = Mth.clamp(distance * (0.18D + RANDOM.nextDouble() * 0.26D), 34.0D, 230.0D);
                distance = Math.max(74.0D, distance - drop);
                data.putLong(CAT_DISTANCE_GLITCH_COOLDOWN, time + seconds(35, 95));
                play(level, player.blockPosition(), FriendSoundEvents.LOW_BREATH, 0.20F, 0.48F, 0.72F);
            }
            if (distance <= 92.0D) {
                spawnCatCurseChaser(level, player, data);
            }
        }
        data.putDouble(CAT_DISTANCE, distance);
        if (time % 10L == 0L) {
            player.displayClientMessage(Component.literal("Friend is " + Mth.floor(distance) + " blocks away from you"), true);
        }
        if (time >= data.getLong(CAT_ROAR_COOLDOWN)) {
            data.putLong(CAT_ROAR_COOLDOWN, Long.MAX_VALUE);
        }
        if (time >= data.getLong(CAT_GRIEF_COOLDOWN)) {
            boolean brokeSomething = FriendConfig.ENABLE_BLOCK_DESTRUCTION.get() && catCurseGrief(level, player, data);
            data.putLong(CAT_GRIEF_COOLDOWN, time + (brokeSomething ? seconds(5, 14) : seconds(2, 5)));
        }
    }

    private static void maybeGlitchVisibleDistance(ServerLevel level, ServerPlayer player, CompoundTag data, FriendEntity chaser, long time) {
        if (time < data.getLong(CAT_DISTANCE_GLITCH_COOLDOWN) || player.distanceTo(chaser) < 34.0D) {
            return;
        }
        Vec3 eye = chaser.position().add(0.0D, chaser.getBbHeight() * 0.68D, 0.0D);
        if (hasLineOfSight(player, eye) || sightState(player, eye, 150.0D) != SightState.NONE) {
            data.putLong(CAT_DISTANCE_GLITCH_COOLDOWN, time + 40L);
            return;
        }
        Optional<BlockPos> closer = findHiddenCatGlitchPosition(level, player, chaser);
        if (closer.isEmpty()) {
            data.putLong(CAT_DISTANCE_GLITCH_COOLDOWN, time + seconds(12, 28));
            return;
        }
        BlockPos feet = closer.get();
        chaser.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, chaser.getYRot(), chaser.getXRot());
        facePlayer(chaser, player);
        data.putLong(CAT_DISTANCE_GLITCH_COOLDOWN, time + seconds(26, 75));
        play(level, feet, FriendSoundEvents.DISAPPEAR_SOFT, 0.16F, 0.55F, 0.82F);
    }

    private static Optional<BlockPos> findHiddenCatGlitchPosition(ServerLevel level, ServerPlayer player, FriendEntity chaser) {
        Vec3 fromPlayer = chaser.position().subtract(player.position()).multiply(1.0D, 0.0D, 1.0D);
        if (fromPlayer.lengthSqr() < 0.001D) {
            fromPlayer = player.getLookAngle().multiply(-1.0D, 0.0D, -1.0D);
        }
        Vec3 base = fromPlayer.normalize();
        for (int i = 0; i < 40; i++) {
            Vec3 direction = rotateY(base, RANDOM.nextInt(121) - 60);
            double dist = 24.0D + RANDOM.nextInt(34);
            BlockPos rough = BlockPos.containing(player.position().add(direction.scale(dist))).offset(0, RANDOM.nextInt(7) - 3, 0);
            BlockPos feet = findFloor(level, rough);
            if (feet == null || !isValid(level, player, feet, "cat_curse", 7)) {
                continue;
            }
            Vec3 face = feet.getCenter().add(0.0D, 1.6D, 0.0D);
            if (!hasLineOfSight(player, face) && player.blockPosition().distSqr(feet) < chaser.blockPosition().distSqr(player.blockPosition())) {
                return Optional.of(feet.immutable());
            }
        }
        return Optional.empty();
    }

    private static boolean catCurseGrief(ServerLevel level, ServerPlayer player, CompoundTag data) {
        BlockPos home = new BlockPos(data.getInt(HOME_X), data.getInt(HOME_Y), data.getInt(HOME_Z));
        boolean useHome = data.getBoolean(HAS_HOME) && player.blockPosition().distSqr(home) < 56.0D * 56.0D;
        BlockPos center = useHome ? home : player.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-14, -5, -14), center.offset(14, 7, 14))) {
            if (pos.distSqr(player.blockPosition()) < 5.0D) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (isCatCurseGriefTarget(level, pos, state, useHome)) {
                candidates.add(pos.immutable());
            }
        }
        if (candidates.isEmpty()) {
            playSoundOnly(level, player, "breathing_behind", data);
            return false;
        }
        int breaks = 1 + RANDOM.nextInt(3);
        for (int i = 0; i < breaks && !candidates.isEmpty(); i++) {
            BlockPos pos = candidates.remove(RANDOM.nextInt(candidates.size()));
            if (level.destroyBlock(pos, true, player)) {
                play(level, pos, FriendSoundEvents.DISTANT_STONE_SCRAPE, 0.34F, 0.55F, 0.85F);
            }
        }
        return true;
    }

    private static boolean isCatCurseGriefTarget(ServerLevel level, BlockPos pos, BlockState state, boolean rememberedHomeArea) {
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F || level.getBlockEntity(pos) != null) {
            return false;
        }
        if (isProtectedBlock(state)) {
            return false;
        }
        Block block = state.getBlock();
        if (block instanceof TorchBlock || block instanceof WallTorchBlock || block instanceof DoorBlock || block instanceof TrapDoorBlock
                || block instanceof FenceBlock || block instanceof FenceGateBlock || block instanceof StairBlock || block instanceof SlabBlock
                || block instanceof LadderBlock || block instanceof GlassBlock || block instanceof StainedGlassBlock
                || block instanceof WallBlock) {
            return true;
        }
        return rememberedHomeArea && state.isSolidRender(level, pos) && state.getDestroySpeed(level, pos) <= 4.0F;
    }

    private static void tryEvent(ServerLevel level, ServerPlayer player, CompoundTag data, String forcedEvent) {
        if (!FriendConfig.ENABLE_FRIEND.get()) {
            return;
        }
        if (forcedEvent != null) {
            forcedEvent = normalizeForcedEvent(forcedEvent);
        }
        if (hasActiveFriend(level, player)) {
            data.putBoolean(ACTIVE, true);
            data.putLong(EVENT_COOLDOWN, level.getGameTime() + (data.getBoolean(CAT_CURSE) ? seconds(12, 25) : 100L));
            return;
        }
        data.putBoolean(ACTIVE, false);
        int phase = data.getInt(PHASE);
        long now = level.getGameTime();
        if (forcedEvent == null && now < data.getLong(SILENCE_UNTIL)) {
            data.putLong(EVENT_COOLDOWN, data.getLong(SILENCE_UNTIL));
            data.putLong(SOUND_COOLDOWN, Math.max(data.getLong(SOUND_COOLDOWN), data.getLong(SILENCE_UNTIL)));
            return;
        }
        String pending = data.getString(SILENCE_PENDING_EVENT);
        String event;
        if (forcedEvent == null && !pending.isBlank() && now >= data.getLong(SILENCE_UNTIL)) {
            event = pending;
            data.putString(SILENCE_PENDING_EVENT, "");
            data.putLong(SILENCE_UNTIL, 0L);
        } else {
            event = forcedEvent == null ? selectEvent(level, player, data) : forcedEvent;
        }
        data.putString(LAST_EVENT, event);

        if (event.equals("no_event_fake_delay")) {
            data.putLong(EVENT_COOLDOWN, level.getGameTime() + (phase >= 7 && !data.getBoolean(CAT_CURSE) ? seconds(70, 170) : seconds(25, 60)));
            return;
        }

        if (forcedEvent == null && pending.isBlank() && shouldUsePreEventSilence(data, phase, event)) {
            long silenceUntil = level.getGameTime() + seconds(8, 18);
            data.putString(SILENCE_PENDING_EVENT, event);
            data.putLong(SILENCE_UNTIL, silenceUntil);
            data.putLong(EVENT_COOLDOWN, silenceUntil);
            data.putLong(SOUND_COOLDOWN, silenceUntil + seconds(18, 36));
            return;
        }

        if (isSoundOnly(event)) {
            playSoundOnly(level, player, event, data);
            data.putLong(EVENT_COOLDOWN, level.getGameTime() + cooldown(data, phase));
            return;
        }

        boolean handled = switch (event) {
            case "stalk", "stalking", "peek" -> FriendStalkingDirector.tryStart(level, player, data, null);
            case "stalk_corner" -> FriendStalkingDirector.tryStart(level, player, data, "corner");
            case "stalk_tree" -> FriendStalkingDirector.tryStart(level, player, data, "tree");
            case "stalk_window" -> FriendStalkingDirector.tryStart(level, player, data, "window");
            case "stalk_doorframe" -> FriendStalkingDirector.tryStart(level, player, data, "doorframe");
            case "stalk_ceiling" -> FriendStalkingDirector.tryStart(level, player, data, "ceiling");
            case "stalk_floor" -> FriendStalkingDirector.tryStart(level, player, data, "floor");
            case "stalk_low" -> FriendStalkingDirector.tryStart(level, player, data, "low");
            case "stalk_cave" -> FriendStalkingDirector.tryStart(level, player, data, "cave");
            case "stalk_distant" -> FriendStalkingDirector.tryStart(level, player, data, "distant");
            case "window_watch" -> FriendStalkingDirector.tryStart(level, player, data, "window");
            case "window_handprint" -> windowHandprint(level, player);
            case "bedside_watch" -> spawnBedsideWatch(level, player, data);
            case "doorway_lean" -> FriendStalkingDirector.tryStart(level, player, data, "doorframe");
            case "route_ambush" -> spawnRouteAmbush(level, player, data);
            case "night_far_watch" -> spawnNightFarWatch(level, player, data);
            case "false_friend" -> spawnFalseFriend(level, player, data);
            case "visible_peek" -> FriendStalkingDirector.tryStart(level, player, data, null);
            case "corner_peek", "hand_peek" -> FriendStalkingDirector.tryStart(level, player, data, "corner");
            case "hole_peek" -> FriendStalkingDirector.tryStart(level, player, data, "cave");
            case "ceiling_hole_peek" -> FriendStalkingDirector.tryStart(level, player, data, "ceiling");
            case "floor_hole_peek" -> FriendStalkingDirector.tryStart(level, player, data, "floor");
            case "low_ceiling_2_peek", "low_ceiling_1_peek" -> FriendStalkingDirector.tryStart(level, player, data, "low");
            case "false_safe" -> falseSafeEvent(level, player, data);
            case "wrong_detail" -> wrongDetailEvent(level, player, data);
            case "inventory_disturbance" -> inventoryDisturbance(level, player, data);
            case "torch_line" -> torchLine(level, player, data);
            case "attack_emerge" -> spawnAttack(level, player, data);
            case "false_attack" -> spawnFalseAttack(level, player, data);
            case "window_knock" -> {
                playWindowKnock(level, player, data);
                yield RANDOM.nextFloat() < 0.35F && spawnWindowWatch(level, player, data);
            }
            case "door_creak" -> {
                doorCreak(level, player, data);
                yield true;
            }
            case "torch_removed" -> {
                torchRemoved(level, player, data);
                yield true;
            }
            case "sky" -> {
                data.putString(SKY_REASON, "command");
                data.putBoolean(SKY_DIALOG_STARTED, false);
                cleanupOwnedFriends(level, player);
                yield FriendDimensions.sendToSky(player);
            }
            case "double_appearance" -> FriendStalkingDirector.tryStart(level, player, data, "corner");
            default -> spawnContextualPeek(level, player, data);
        };

        if (!handled) {
            add(data, SPAWN_FAIL_COUNT, 1, 0, 100000);
            // Release pacing rule: a failed peek search must not equal "nothing happened".
            // If no good cover exists, Friend falls back to a readable distant dweller-style stand.
            handled = FriendStalkingDirector.tryStart(level, player, data, "distant");
            if (!handled && RANDOM.nextBoolean()) {
                playSoundOnly(level, player, "fake_footsteps", data);
            }
        }
        if (handled && isVisiblePressureEvent(event)) {
            add(data, THREAT_DEBT, -110, 0, 900);
            data.putLong(LAST_VISIBLE_PRESSURE, level.getGameTime());
        } else if (!handled) {
            add(data, THREAT_DEBT, 35, 0, 900);
        }
        int nextCooldown = cooldown(data, phase);
        if (event.equals("attack_emerge")) {
            data.putLong(EVENT_COOLDOWN, Math.max(data.getLong(EVENT_COOLDOWN), data.getLong(ATTACK_LOCK_UNTIL)));
        } else if (event.equals("false_attack")) {
            data.putLong(EVENT_COOLDOWN, Math.max(data.getLong(EVENT_COOLDOWN), level.getGameTime() + seconds(95, 150)));
        } else {
            data.putLong(EVENT_COOLDOWN, level.getGameTime() + nextCooldown);
        }
    }

        private static String selectEvent(ServerLevel level, ServerPlayer player, CompoundTag data) {
        int phase = Math.min(Math.max(data.getInt(PHASE), 0), data.getBoolean(CAT_CURSE) ? 7 : MAX_RELEASE_PHASE);
        boolean underground = isPlayerInMineContext(level, player);
        boolean home = data.getBoolean(HAS_HOME);
        boolean nearHome = home && player.blockPosition().distSqr(new BlockPos(data.getInt(HOME_X), data.getInt(HOME_Y), data.getInt(HOME_Z))) < 40.0D * 40.0D;
        boolean night = !level.isDay();
        boolean cursed = data.getBoolean(CAT_CURSE);
        int torchSafety = home && nearHome ? homeTorchRingScore(level, data, player.blockPosition()) : 0;
        boolean falseSafeHome = torchSafety >= 12;
        int routeHabit = routeHabitScore(data);
        String last = data.getString(LAST_EVENT);
        int threatDebt = data.getInt(THREAT_DEBT);
        int fear = data.getInt(FEAR_SCORE);

        // Guaranteed readable presence before the player gets bored. At high debt, choose visible events,
        // not instant unfair attacks.
        if (!cursed && phase >= 1 && threatDebt >= 260 && level.getGameTime() > data.getLong(LAST_VISIBLE_PRESSURE) + 20L * 75L) {
            data.putLong(LAST_VISIBLE_PRESSURE, level.getGameTime());
            if (threatDebt >= 520 && RANDOM.nextFloat() < 0.22F) return "false_attack";
            if (underground && RANDOM.nextBoolean()) return "corner_peek";
            if (night && RANDOM.nextBoolean()) return "stalk_distant";
            return "visible_peek";
        }

        if (!cursed && shouldRollSurvivalAttack(level, player, data, phase, underground, night, nearHome)) {
            return "attack_emerge";
        }

        List<WeightedEvent> events = new ArrayList<>();
        addEvent(events, "no_event_fake_delay", cursed ? 12 : threatDebt > 220 ? 1 : 4);
        addEvent(events, "fake_footsteps", cursed ? 10 : 7 + Math.min(6, threatDebt / 120));
        addEvent(events, "distant_scrape", cursed ? 8 : 9);
        addEvent(events, "distant_knock", cursed ? 7 : 8);
        addEvent(events, "breathing_behind", 8 + Math.min(7, fear / 70));

        if (phase >= 1) {
            addEvent(events, "mimic_footsteps", underground ? 9 : 4);
            addEvent(events, "wall_scurry", underground ? 9 : 3);
            addEvent(events, "corner_peek", underground ? 22 : 12 + Math.min(8, threatDebt / 100));
            addEvent(events, "visible_peek", underground ? 16 : 9 + Math.min(9, threatDebt / 100));
            addEvent(events, "hand_peek", underground ? 12 : 5);
            addEvent(events, "stalk_distant", night || underground ? 12 : 7);
            addEvent(events, "false_attack", cursed || !falseAttackCooldownReady(data, level.getGameTime()) ? 0 : phase <= 2 ? 3 + Math.min(3, fear / 120) : 2);
            addEvent(events, "attack_emerge", cursed || !attackCooldownReady(data, level.getGameTime()) ? 0 : attackWeightForPhase(phase, underground, night, nearHome));
        }
        if (phase >= 2 && (underground || night)) {
            addEvent(events, "torch_removed", underground ? 10 : 5);
            addEvent(events, "sprint_behind_wall", underground ? 8 : 2);
        }
        if (phase >= 2 && underground) {
            addEvent(events, "hole_peek", 11);
            addEvent(events, "ceiling_hole_peek", 6);
            addEvent(events, "floor_hole_peek", 5);
            addEvent(events, "low_ceiling_2_peek", 6);
            addEvent(events, "low_ceiling_1_peek", 4);
        }
        if (phase >= 3 && home && (nearHome || night)) {
            addEvent(events, "window_watch", (cursed ? 26 : 14) + torchSafety);
            addEvent(events, "window_knock", (cursed ? 30 : 13) + torchSafety);
            addEvent(events, "door_open_far", falseSafeHome ? 0 : cursed ? 12 : 6);
            addEvent(events, "chest_open_far", falseSafeHome ? 0 : cursed ? 10 : 5);
            addEvent(events, "learned_door", data.getList(MEMORY_DOORS, Tag.TAG_COMPOUND).isEmpty() ? 0 : 7);
            addEvent(events, "learned_chest", falseSafeHome || data.getList(MEMORY_CONTAINERS, Tag.TAG_COMPOUND).isEmpty() ? 0 : 5);
            addEvent(events, "window_handprint", (cursed ? 18 : 6) + torchSafety / 2);
            addEvent(events, "doorway_lean", cursed ? 18 : 8);
            addEvent(events, "false_safe", (cursed ? 14 : 5) + torchSafety / 2);
            addEvent(events, "wrong_detail", nearHome ? 4 : 0);
            addEvent(events, "door_creak", falseSafeHome ? 2 : cursed ? 22 : 9);
            addEvent(events, "torch_removed", falseSafeHome ? 0 : cursed ? 24 : 7);
        }
        if (phase >= 4) {
            addEvent(events, "double_appearance", cursed ? 12 : 4);
            addEvent(events, "doorway_lean", home && (nearHome || night) ? 7 : 0);
            addEvent(events, "learned_footsteps", routeSize(data) > 8 ? 9 + routeHabit / 3 : 0);
            addEvent(events, "route_ambush", routeHabit >= 8 ? 5 + routeHabit / 4 : 0);
            addEvent(events, "night_far_watch", night && !underground ? 7 : 0);
            addEvent(events, "false_friend", !underground ? 5 : 2);
            addEvent(events, "inventory_disturbance", home && nearHome && !falseSafeHome ? 3 : 0);
            addEvent(events, "torch_line", underground ? 5 : 0);
            if (RANDOM.nextFloat() < dreamEventChance(phase, data) && FriendConfig.ENABLE_DREAMS.get()) {
                addEvent(events, "dream_hint", 3);
            }
            if (RANDOM.nextFloat() < 0.04F) playSubtitle(level, player, FriendSoundEvents.SUBTITLE_NEAR);
            if (RANDOM.nextFloat() < 0.03F) playSubtitle(level, player, FriendSoundEvents.SUBTITLE_HEARD);
        }
        if (cursed) {
            addEvent(events, "window_knock", (!home || nearHome || night) ? 5 : 0);
            addEvent(events, "torch_removed", 6);
            addEvent(events, "door_creak", 6);
        }

        if (events.size() > 4) {
            events.removeIf(event -> event.id.equals(last));
        }
        int total = events.stream().mapToInt(event -> event.weight).sum();
        int roll = RANDOM.nextInt(Math.max(total, 1));
        for (WeightedEvent event : events) {
            roll -= event.weight;
            if (roll < 0) return event.id;
        }
        return "visible_peek";
    }

    private static boolean shouldRollSurvivalAttack(ServerLevel level, ServerPlayer player, CompoundTag data,
                                                    int phase, boolean underground, boolean night, boolean nearHome) {
        if (phase < 1 || data.getBoolean(CAT_CURSE) || level.getDifficulty() == Difficulty.PEACEFUL) {
            return false;
        }
        long now = level.getGameTime();
        if (!attackCooldownReady(data, now)) {
            return false;
        }

        // Survival balance: attacks must be frightening, not spammy. Visible pressure and false attacks
        // can be common; real damage-rushes should be rare and protected by a hard cooldown.
        float chance = switch (Math.min(Math.max(phase, 1), MAX_RELEASE_PHASE)) {
            case 1 -> 0.025F;
            case 2 -> 0.045F;
            case 3 -> 0.075F;
            default -> 0.11F;
        };
        chance += Math.min(0.025F, data.getInt(FEAR_SCORE) / 11000.0F);
        chance += Math.min(0.020F, data.getInt(THREAT_DEBT) / 21000.0F);
        if (underground) chance += 0.015F;
        if (night) chance += 0.010F;
        if (nearHome) chance += 0.010F;
        return RANDOM.nextFloat() < Math.min(chance, 0.15F);
    }

    private static int attackWeightForPhase(int phase, boolean underground, boolean night, boolean nearHome) {
        int clamped = Math.min(Math.max(phase, 1), MAX_RELEASE_PHASE);
        int weight = switch (clamped) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 4;
            default -> 6;
        };
        if (underground) weight += 1;
        if (night && clamped >= 3) weight += 1;
        if (nearHome && clamped >= 4) weight += 1;
        return weight;
    }

    private static boolean shouldUsePreEventSilence(CompoundTag data, int phase, String event) {
        if (phase < 3 || data.getBoolean(CAT_CURSE) || isSoundOnly(event)
                || event.equals("no_event_fake_delay") || event.equals("dream_hint") || event.equals("false_attack")) {
            return false;
        }
        if (event.equals(data.getString(SILENCE_PENDING_EVENT))) {
            return false;
        }
        float chance = switch (event) {
            case "attack_emerge" -> 0.45F;
            case "false_friend", "night_far_watch", "window_watch" -> 0.28F;
            case "route_ambush", "wrong_detail" -> 0.22F;
            default -> 0.12F;
        };
        return RANDOM.nextFloat() < chance;
    }


    private static boolean spawnGeneral(ServerLevel level, ServerPlayer player, CompoundTag data, String event) {
        if (!(event.equals("attack_emerge") || event.equals("false_attack")) && !event.equals("cat_curse") && !event.equals("dream_chase")
                && !event.equals("dream_bed") && !event.equals("void_watch")) {
            return spawnContextualPeek(level, player, data);
        }
        Optional<BlockPos> pos = findPosition(level, player, event, data.getInt(PHASE));
        if (pos.isEmpty()) {
            return false;
        }
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return false;
        }
        BlockPos blockPos = pos.get();
        friend.moveTo(blockPos.getX() + 0.5D, blockPos.getY(), blockPos.getZ() + 0.5D, 0.0F, 0.0F);
        friend.configure(player.getUUID().toString(), event, lifetime(event), stareDelay(event, data.getInt(PHASE)));
        if (event.equals("corner_peek") && shouldEscalateCornerPeek(data)) {
            friend.setCornerEscalated(true);
            friend.getPersistentData().putInt(FriendEntity.TAG_LIFETIME, 20 * 80);
        }
        facePlayer(friend, player);
        level.addFreshEntity(friend);
        data.putBoolean(ACTIVE, true);
        playIntroSound(level, friend.blockPosition(), event);
        return true;
    }

    private static boolean spawnContextualPeek(ServerLevel level, ServerPlayer player, CompoundTag data) {
        return FriendStalkingDirector.tryStart(level, player, data, null);
    }

    private static boolean spawnWindowWatch(ServerLevel level, ServerPlayer player, CompoundTag data) {
        Optional<WindowSpot> spot = findBestWindowSpot(level, player, data, 24);
        if (spot.isEmpty()) {
            return spawnContextualPeek(level, player, data);
        }
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return false;
        }
        BlockPos feet = spot.get().feet();
        friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        friend.configure(player.getUUID().toString(), "window_watch", seconds(5, 10), 18);
        facePlayer(friend, player);
        level.addFreshEntity(friend);
        data.putBoolean(ACTIVE, true);
        rememberBlockMemory(data, MEMORY_WINDOWS, spot.get().glass(), level.getGameTime());
        play(level, spot.get().glass(), RANDOM.nextBoolean() ? FriendSoundEvents.WINDOW_KNOCK_1 : FriendSoundEvents.WINDOW_KNOCK_2, 0.30F, 0.92F, 1.06F);
        return true;
    }

    private static boolean spawnNightFarWatch(ServerLevel level, ServerPlayer player, CompoundTag data) {
        if (level.isDay() || player.getY() <= 40.0D) {
            return false;
        }
        Optional<BlockPos> spot = findNightFarWatchSpot(level, player);
        if (spot.isEmpty()) {
            return false;
        }
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return false;
        }
        BlockPos feet = spot.get();
        friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        friend.configure(player.getUUID().toString(), "night_far_watch", seconds(9, 18), 999);
        facePlayer(friend, player);
        level.addFreshEntity(friend);
        data.putBoolean(ACTIVE, true);
        if (RANDOM.nextFloat() < 0.35F) {
            play(level, feet, FriendSoundEvents.LOW_DRONE, 0.055F, 0.55F, 0.78F);
        }
        return true;
    }

    private static boolean spawnFalseFriend(ServerLevel level, ServerPlayer player, CompoundTag data) {
        Optional<BlockPos> route = rememberedRouteCandidate(player, data);
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        if (look.lengthSqr() < 0.001D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        for (int i = 0; i < 46; i++) {
            BlockPos rough;
            if (route.isPresent() && i < 18) {
                rough = route.get().offset(RANDOM.nextInt(15) - 7, RANDOM.nextInt(5) - 2, RANDOM.nextInt(15) - 7);
            } else {
                Vec3 direction = rotateY(look.normalize(), RANDOM.nextInt(90) - 45);
                rough = BlockPos.containing(player.position().add(direction.scale(28.0D + RANDOM.nextInt(30)))).offset(0, RANDOM.nextInt(7) - 3, 0);
            }
            BlockPos feet = findFloor(level, rough);
            if (feet == null || !isValid(level, player, feet, "false_friend", data.getInt(PHASE))) {
                continue;
            }
            Vec3 face = feet.getCenter().add(0.0D, 1.65D, 0.0D);
            if (!hasLineOfSight(player, face)) {
                continue;
            }
            FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
            if (friend == null) {
                return false;
            }
            friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
            friend.configure(player.getUUID().toString(), "false_friend", seconds(5, 12), 45);
            friend.setCustomName(Component.literal(player.getGameProfile().getName()));
            facePlayer(friend, player);
            level.addFreshEntity(friend);
            data.putBoolean(ACTIVE, true);
            if (RANDOM.nextFloat() < 0.45F) {
                play(level, feet, FriendSoundEvents.LOW_DRONE, 0.045F, 0.48F, 0.72F);
            }
            return true;
        }
        return false;
    }

    private static boolean spawnVisiblePeek(ServerLevel level, ServerPlayer player, CompoundTag data) {
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        if (look.lengthSqr() < 0.001D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        look = look.normalize();
        Vec3 side = new Vec3(-look.z, 0.0D, look.x).normalize();
        for (int i = 0; i < 52; i++) {
            double forward = 10.0D + RANDOM.nextInt(20);
            double lateral = (RANDOM.nextBoolean() ? 1.0D : -1.0D) * RANDOM.nextInt(5);
            BlockPos rough = BlockPos.containing(player.position().add(look.scale(forward)).add(side.scale(lateral))).offset(0, RANDOM.nextInt(5) - 2, 0);
            BlockPos feet = findFloor(level, rough);
            if (feet == null || !isValid(level, player, feet, "visible_peek", data.getInt(PHASE))) {
                continue;
            }
            Vec3 face = feet.getCenter().add(0.0D, 1.85D, 0.0D);
            if (sightState(player, face, 38.0D) == SightState.NONE || !hasLineOfSight(player, face)) {
                continue;
            }
            FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
            if (friend == null) {
                return false;
            }
            friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
            friend.configure(player.getUUID().toString(), "visible_peek", seconds(2, 4), 0);
            facePlayer(friend, player);
            level.addFreshEntity(friend);
            data.putBoolean(ACTIVE, true);
            play(level, feet, FriendSoundEvents.QUIET_BREATH, 0.07F, 0.86F, 1.02F);
            return true;
        }
        return false;
    }

    private static boolean spawnRouteAmbush(ServerLevel level, ServerPlayer player, CompoundTag data) {
        Optional<BlockPos> remembered = rememberedRouteCandidate(player, data);
        if (remembered.isEmpty()) {
            return spawnContextualPeek(level, player, data);
        }
        for (int i = 0; i < 18; i++) {
            BlockPos rough = remembered.get().offset(RANDOM.nextInt(11) - 5, RANDOM.nextInt(5) - 2, RANDOM.nextInt(11) - 5);
            BlockPos feet = findFloor(level, rough);
            if (feet == null || !isValid(level, player, feet, "route_ambush", data.getInt(PHASE))) {
                continue;
            }
            FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
            if (friend == null) {
                return false;
            }
            friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
            friend.configure(player.getUUID().toString(), "route_ambush", seconds(4, 8), 18);
            facePlayer(friend, player);
            level.addFreshEntity(friend);
            data.putBoolean(ACTIVE, true);
            play(level, feet, FriendSoundEvents.TUNNEL_STEP_1, 0.16F, 0.74F, 1.0F);
            return true;
        }
        return spawnContextualPeek(level, player, data);
    }

    private static boolean spawnBedsideWatch(ServerLevel level, ServerPlayer player, CompoundTag data) {
        List<BlockPos> beds = findBlocks(level, player.blockPosition(), 18, state -> state.getBlock() instanceof BedBlock);
        if (beds.isEmpty()) {
            return spawnContextualPeek(level, player, data);
        }
        Collections.shuffle(beds, RANDOM);
        for (BlockPos bed : beds) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos feet = findFloor(level, bed.relative(direction, 2));
                if (feet != null && isValid(level, player, feet, "bedside_watch", data.getInt(PHASE))) {
                    FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
                    if (friend == null) return false;
                    friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
                    friend.configure(player.getUUID().toString(), "bedside_watch", seconds(5, 10), 35);
                    facePlayer(friend, player);
                    level.addFreshEntity(friend);
                    data.putBoolean(ACTIVE, true);
                    play(level, feet, FriendSoundEvents.LOW_BREATH, 0.14F, 0.62F, 0.86F);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean spawnDoorwayLean(ServerLevel level, ServerPlayer player, CompoundTag data) {
        List<BlockPos> doors = findBlocks(level, player.blockPosition(), 18, state -> state.getBlock() instanceof DoorBlock);
        if (doors.isEmpty()) {
            return spawnContextualPeek(level, player, data);
        }
        Collections.shuffle(doors, RANDOM);
        for (BlockPos door : doors) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos feet = findFloor(level, door.relative(direction, 2));
                if (feet != null && isValid(level, player, feet, "doorway_lean", data.getInt(PHASE))) {
                    FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
                    if (friend == null) return false;
                    friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
                    friend.configure(player.getUUID().toString(), "doorway_lean", seconds(4, 8), 18);
                    facePlayer(friend, player);
                    level.addFreshEntity(friend);
                    data.putBoolean(ACTIVE, true);
                    play(level, door, FriendSoundEvents.WOOD_CREAK, 0.24F, 0.72F, 0.96F);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean spawnCornerPeek(ServerLevel level, ServerPlayer player, CompoundTag data, String eventId) {
        String mode = choosePeekMode(data);
        Optional<PeekSpot> spot = findCornerPeekSpot(level, player, data, data.getInt(PHASE), mode);

        /*
         * Peek rules for release:
         * 1) First priority is a guaranteed readable peek: Friend only spawns where the face/hand is
         *    already in the player's central view cone and line of sight.
         * 2) If the player keeps missing him, try a deliberate "turnaround" peek behind/side of the player.
         *    It is not cheap teleport-horror; it is bait. The player must turn and catch it.
         * 3) After repeated missed peeks, Friend becomes bolder: closer, longer, and less perfectly hidden.
         */
        if (spot.isEmpty() && mode.equals("guaranteed")) {
            mode = "turnaround";
            spot = findCornerPeekSpot(level, player, data, data.getInt(PHASE), mode);
        }
        if (spot.isEmpty() && !mode.equals("bold")) {
            mode = "bold";
            spot = findCornerPeekSpot(level, player, data, data.getInt(PHASE), mode);
        }
        if (spot.isEmpty()) {
            return false;
        }

        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return false;
        }
        BlockPos feet = spot.get().feet();
        friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        int lifetime = switch (mode) {
            case "turnaround" -> seconds(7, 12);
            case "bold" -> seconds(8, 14);
            default -> seconds(4, 7);
        };
        friend.configure(player.getUUID().toString(), eventId, lifetime, 0);
        friend.noPhysics = false;
        friend.setNoGravity(false);
        CompoundTag tag = friend.getPersistentData();
        BlockPos cover = spot.get().cover();
        tag.putInt(FriendEntity.TAG_COVER_X, cover.getX());
        tag.putInt(FriendEntity.TAG_COVER_Y, cover.getY());
        tag.putInt(FriendEntity.TAG_COVER_Z, cover.getZ());
        tag.putInt(FriendEntity.TAG_PEEK_PANIC_TICKS, 0);
        tag.putString(PEEK_MODE, mode);
        if (eventId.equals("corner_peek") && mode.equals("bold") && shouldEscalateCornerPeek(data)) {
            friend.setCornerEscalated(true);
            tag.putInt(FriendEntity.TAG_LIFETIME, 20 * 80);
        }
        facePlayer(friend, player);
        level.addFreshEntity(friend);
        data.putBoolean(ACTIVE, true);
        recordPeekSpawned(data, mode);
        float breathVolume = mode.equals("turnaround") ? 0.075F : mode.equals("bold") ? 0.13F : 0.09F;
        play(level, friend.blockPosition(), FriendSoundEvents.QUIET_BREATH, breathVolume, 0.82F, 1.04F);
        return true;
    }

    private static boolean spawnHolePeek(ServerLevel level, ServerPlayer player, CompoundTag data, String eventId) {
        if (!isPlayerInMineContext(level, player)) {
            return false;
        }
        Optional<PeekSpot> spot = switch (eventId) {
            case "ceiling_hole_peek" -> findCeilingHolePeekSpot(level, player);
            case "floor_hole_peek" -> findFloorHolePeekSpot(level, player);
            case "low_ceiling_2_peek" -> findLowCeilingPeekSpot(level, player, 2);
            case "low_ceiling_1_peek" -> findLowCeilingPeekSpot(level, player, 1);
            default -> findWallHolePeekSpot(level, player);
        };
        if (spot.isEmpty()) {
            return false;
        }
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return false;
        }
        BlockPos feet = spot.get().feet();
        friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        friend.configure(player.getUUID().toString(), eventId, seconds(2, 5), 0);
        friend.setNoGravity(false);
        friend.noPhysics = false;
        CompoundTag tag = friend.getPersistentData();
        BlockPos cover = spot.get().cover();
        tag.putInt(FriendEntity.TAG_COVER_X, cover.getX());
        tag.putInt(FriendEntity.TAG_COVER_Y, cover.getY());
        tag.putInt(FriendEntity.TAG_COVER_Z, cover.getZ());
        tag.putInt(FriendEntity.TAG_PEEK_PANIC_TICKS, 0);
        facePlayer(friend, player);
        level.addFreshEntity(friend);
        data.putBoolean(ACTIVE, true);
        play(level, cover, FriendSoundEvents.QUIET_BREATH, 0.075F, 0.92F, 1.08F);
        return true;
    }

    private static boolean spawnAttack(ServerLevel level, ServerPlayer player, CompoundTag data) {
        long now = level.getGameTime();
        if (!attackCooldownReady(data, now)) {
            data.putLong(EVENT_COOLDOWN, Math.max(data.getLong(EVENT_COOLDOWN), now + 20L * 35L));
            return false;
        }
        Optional<BlockPos> pos = findAttackStartPosition(level, player, data);
        if (pos.isEmpty()) {
            pos = findForcedAttackStartPosition(level, player);
        }
        if (pos.isEmpty()) {
            return false;
        }
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return false;
        }
        BlockPos blockPos = pos.get();
        friend.moveTo(blockPos.getX() + 0.5D, blockPos.getY(), blockPos.getZ() + 0.5D, 0.0F, 0.0F);
        friend.configure(player.getUUID().toString(), "attack_emerge", 20 * 80, 0);
        friend.setCornerEscalated(true);
        facePlayer(friend, player);
        level.addFreshEntity(friend);
        data.putBoolean(ACTIVE, true);
        beginAttackCooldown(data, now, false);
        play(level, friend.blockPosition(), FriendSoundEvents.LOW_BREATH, 0.18F, 0.55F, 0.8F);
        return true;
    }

    private static boolean spawnFalseAttack(ServerLevel level, ServerPlayer player, CompoundTag data) {
        long now = level.getGameTime();
        if (!falseAttackCooldownReady(data, now)) {
            return false;
        }
        Optional<BlockPos> pos = findAttackStartPosition(level, player, data);
        if (pos.isEmpty()) {
            pos = findForcedAttackStartPosition(level, player);
        }
        if (pos.isEmpty()) {
            return false;
        }
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return false;
        }
        BlockPos blockPos = pos.get();
        friend.moveTo(blockPos.getX() + 0.5D, blockPos.getY(), blockPos.getZ() + 0.5D, 0.0F, 0.0F);
        friend.configure(player.getUUID().toString(), "false_attack", seconds(4, 7), 0);
        friend.setCornerEscalated(true);
        friend.getPersistentData().putBoolean("friend_false_attack", true);
        facePlayer(friend, player);
        level.addFreshEntity(friend);
        data.putBoolean(ACTIVE, true);
        data.putLong(LAST_FALSE_ATTACK, now);
        data.putLong(EVENT_COOLDOWN, Math.max(data.getLong(EVENT_COOLDOWN), now + seconds(180, 260)));
        play(level, friend.blockPosition(), FriendSoundEvents.LOW_BREATH, 0.20F, 0.55F, 0.8F);
        return true;
    }

    private static Optional<BlockPos> findAttackStartPosition(ServerLevel level, ServerPlayer player, CompoundTag data) {
        int phase = Math.max(6, data.getInt(PHASE));
        return findCornerCoverPosition(level, player, "attack_emerge", phase);
    }

    private static Optional<BlockPos> findForcedAttackStartPosition(ServerLevel level, ServerPlayer player) {
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        if (look.lengthSqr() < 0.001D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        look = look.normalize();
        Vec3 side = new Vec3(-look.z, 0.0D, look.x).normalize();
        for (int i = 0; i < 80; i++) {
            double forward = 8.0D + RANDOM.nextInt(14);
            double lateral = (RANDOM.nextBoolean() ? 1.0D : -1.0D) * RANDOM.nextInt(7);
            BlockPos rough = BlockPos.containing(player.position().add(look.scale(forward)).add(side.scale(lateral))).offset(0, RANDOM.nextInt(7) - 3, 0);
            BlockPos feet = findFloor(level, rough);
            if (feet != null && isValid(level, player, feet, "attack_emerge", 7)) {
                return Optional.of(feet.immutable());
            }
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos feet = findFloor(level, player.blockPosition().relative(direction, 10));
            if (feet != null && isValid(level, player, feet, "attack_emerge", 7)) {
                return Optional.of(feet.immutable());
            }
        }
        return Optional.empty();
    }

    private static boolean spawnCatCurseChaser(ServerLevel level, ServerPlayer player, CompoundTag data) {
        if (findOwnedEvent(level, player, "cat_curse", 260.0D) != null) {
            return true;
        }
        for (int i = 0; i < 64; i++) {
            BlockPos origin = offsetAround(player.blockPosition(), 48, 76);
            BlockPos feet = findSafeFriendFloor(level, origin);
            if (feet == null || bad(level, feet.below()) || bad(level, feet)) {
                continue;
            }
            FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
            if (friend == null) {
                return false;
            }
            friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
            friend.configure(player.getUUID().toString(), "cat_curse", 20 * 60 * 60, 0);
            friend.setRushing(true);
            friend.setGlowingTag(true);
            facePlayer(friend, player);
            level.addFreshEntity(friend);
            data.putBoolean(ACTIVE, true);
            play(level, friend.blockPosition(), FriendSoundEvents.CAT_CURSE_ROAR, 0.8F, 0.46F, 0.7F);
            return true;
        }
        return false;
    }

    private static boolean spawnDreamChase(ServerLevel level, ServerPlayer player, CompoundTag data) {
        // Dream chase is intentionally removed. Dream is now an exploration/escape space, not a corridor chase.
        cleanupOwnedFriends(level, player);
        data.putBoolean(ACTIVE, false);
        data.putBoolean(DREAM_STARTED, true);
        return false;
    }

    private static boolean spawnDreamBedFriend(ServerLevel level, ServerPlayer player, CompoundTag data, BlockPos bed) {
        // Removed old bedside cutscene. No Friend appears when the player falls asleep.
        cleanupOwnedFriends(level, player);
        data.putBoolean(DREAM_BED_FRIEND, false);
        data.putBoolean(ACTIVE, false);
        return false;
    }

    private static boolean tickPeekChain(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        long chainAt = data.getLong(PEEK_CHAIN_AT);
        if (chainAt <= 0L || time < chainAt) {
            return false;
        }
        data.putLong(PEEK_CHAIN_AT, 0L);
        if (data.getInt(PEEK_CHAIN_COUNT) <= 0 || hasActiveFriend(level, player) || data.getBoolean(CAT_CURSE)) {
            data.putInt(PEEK_CHAIN_COUNT, 0);
            return false;
        }
        data.putInt(PEEK_CHAIN_COUNT, data.getInt(PEEK_CHAIN_COUNT) - 1);
        boolean handFirst = data.getInt(PHASE) >= 4 && RANDOM.nextFloat() < 0.45F;
        if (spawnCornerPeek(level, player, data, handFirst ? "hand_peek" : "corner_peek")) {
            data.putLong(EVENT_COOLDOWN, time + seconds(65, 150));
            return true;
        }
        return false;
    }

    private static void schedulePeekChain(ServerLevel level, ServerPlayer player, CompoundTag data, BlockPos oldCover) {
        int phase = data.getInt(PHASE);
        if (phase < 3 || data.getInt(PEEK_CHAIN_COUNT) > 0 || data.getBoolean(CAT_CURSE)) {
            return;
        }
        float chance = phase >= 7 ? 0.55F : phase >= 5 ? 0.38F : 0.22F;
        if (RANDOM.nextFloat() >= chance) {
            return;
        }
        data.putInt(PEEK_CHAIN_COUNT, 1);
        data.putLong(PEEK_CHAIN_AT, level.getGameTime() + seconds(1, 2));
        rememberBlockMemory(data, MEMORY_PEEK_COVERS, oldCover, level.getGameTime());
    }

    private static String choosePeekMode(CompoundTag data) {
        int missed = data.getInt(PEEK_MISSED_STREAK);
        int attempts = data.getInt(PEEK_ATTEMPTS);
        if (missed >= 2) {
            return "bold";
        }
        if (missed == 1 || attempts % 4 == 1) {
            return "turnaround";
        }
        return "guaranteed";
    }

    private static void recordPeekSpawned(CompoundTag data, String mode) {
        data.putInt(PEEK_ATTEMPTS, data.getInt(PEEK_ATTEMPTS) + 1);
        data.putString(PEEK_LAST_MODE, mode);
    }

    private static void recordPeekSeen(CompoundTag data) {
        data.putInt(PEEK_SEEN_TOTAL, data.getInt(PEEK_SEEN_TOTAL) + 1);
        data.putInt(PEEK_MISSED_STREAK, 0);
        add(data, CONFIDENCE, 1.4D, 0, 100);
    }

    private static void recordPeekMissed(CompoundTag data) {
        data.putInt(PEEK_MISSED_STREAK, Math.min(6, data.getInt(PEEK_MISSED_STREAK) + 1));
        add(data, CONFIDENCE, 0.35D, 0, 100);
    }

    private static boolean shouldCountPeekAsMiss(String reason) {
        return reason.equals("peek_done") || reason.equals("lifetime") || reason.equals("peek_cover_lost")
                || reason.equals("peek_exposed") || reason.equals("orphan") || reason.equals("bright");
    }

    private static boolean tryStartDreamFromActualSleep(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        if (!FriendConfig.ENABLE_DREAMS.get() || !level.dimension().equals(Level.OVERWORLD)) {
            return false;
        }
        if (!player.isSleeping() || data.getBoolean(CAT_CURSE)) {
            return false;
        }

        /*
         * First sleep always sends the player to Dream so the mod proves itself early.
         * After that every sleep has a clean 50% chance. If the roll fails, vanilla sleep continues.
         */
        boolean alreadyDreamed = data.getBoolean(DREAM_USED);
        if (alreadyDreamed && RANDOM.nextFloat() >= 0.50F) {
            return false;
        }

        data.putBoolean(DREAM_USED, true);
        data.putBoolean(DREAM_BED_SEQUENCE, false);
        data.putBoolean(DREAM_BED_FRIEND, false);
        data.putLong(DREAM_BED_START, 0L);
        cleanupOwnedFriends(level, player);
        player.stopSleepInBed(true, true);
        return startDream(player, false);
    }

    private static Optional<BlockPos> findDreamBedCinematicPosition(ServerLevel level, ServerPlayer player, BlockPos bed) {
        BlockState bedState = level.getBlockState(bed);
        Vec3 look = Vec3.ZERO;
        if (bedState.hasProperty(BedBlock.FACING)) {
            Direction facing = bedState.getValue(BedBlock.FACING);
            look = new Vec3(facing.getStepX(), 0.0D, facing.getStepZ());
        }
        if (look.lengthSqr() < 0.001D) {
            look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        }
        if (look.lengthSqr() < 0.001D) {
            look = player.position().subtract(bed.getCenter()).multiply(1.0D, 0.0D, 1.0D);
        }
        if (look.lengthSqr() < 0.001D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        look = look.normalize();
        Vec3 side = new Vec3(-look.z, 0.0D, look.x).normalize();
        for (int distance = 4; distance <= 9; distance++) {
            for (int lateral : new int[]{0, -1, 1, -2, 2, -3, 3}) {
                BlockPos rough = BlockPos.containing(player.position().add(look.scale(distance)).add(side.scale(lateral)));
                BlockPos feet = findFloor(level, rough);
                if (feet == null || !isValid(level, player, feet, "dream_bed", 7)) {
                    continue;
                }
                Vec3 target = feet.getCenter().add(0.0D, 1.45D, 0.0D);
                if (hasLineOfSight(player, target)) {
                    return Optional.of(feet);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean startDream(ServerPlayer player, boolean forced) {
        if (!forced && !FriendConfig.ENABLE_DREAMS.get()) {
            return false;
        }
        CompoundTag data = state(player);
        data.putBoolean(DREAM_STARTED, false);
        data.putLong(DREAM_CUTSCENE_UNTIL, 0L);
        cleanupOwnedFriends(player.serverLevel(), player);
        int variant = 0;
        data.putInt(DREAM_VARIANT, variant);
        return FriendDimensions.sendToDream(player, variant);
    }

    private static int randomDreamVariant(int phase) {
        if (phase < 5 && RANDOM.nextFloat() < 0.65F) {
            return 0;
        }
        if (RANDOM.nextFloat() < 0.22F) {
            return 0;
        }
        return 1 + RANDOM.nextInt(10);
    }

    private static void beginDreamBedSequence(ServerLevel level, ServerPlayer player, CompoundTag data, BlockPos bed, long time) {
        // Compatibility only. Old code used a cinematic bedside Friend sequence.
        // New design sends the player straight into Dream.
        data.putBoolean(DREAM_USED, true);
        data.putBoolean(DREAM_BED_SEQUENCE, false);
        data.putBoolean(DREAM_BED_FRIEND, false);
        data.putLong(DREAM_BED_START, 0L);
        cleanupOwnedFriends(level, player);
        player.stopSleepInBed(true, true);
        startDream(player, false);
    }

    private static boolean tickDreamBedSequence(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        if (!data.getBoolean(DREAM_BED_SEQUENCE) && !data.getBoolean(DREAM_BED_FRIEND)) {
            return false;
        }
        // Clear leftovers from old saves/mod builds.
        data.putBoolean(DREAM_BED_SEQUENCE, false);
        data.putBoolean(DREAM_BED_FRIEND, false);
        data.putLong(DREAM_BED_START, 0L);
        cleanupOwnedFriends(level, player);
        if (!player.level().dimension().equals(FriendDimensions.DREAM)) {
            startDream(player, false);
        }
        return true;
    }

    private static void tickDreamPlayer(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        data.putBoolean(DREAM_STARTED, true);
        data.putLong(DREAM_CUTSCENE_UNTIL, 0L);
        level.setDayTime(6000L);
        level.setWeatherParameters(20 * 60 * 20, 0, false, false);
        cleanupOwnedFriends(level, player);

        FriendDimensions.tickDreamField(player);

        if (time % 80L == 0L) {
            play(level, player.blockPosition(), FriendSoundEvents.DREAM_AMBIENT, 0.10F, 0.72F, 0.92F);
        }
        if (time % 140L == 20L) {
            dreamRealmWhisper(player, Mth.clamp(data.getInt(DREAM_VARIANT), 0, 10));
        }

        if (player.getY() < FriendDimensions.DREAM_START.getY() - 8.0D) {
            player.teleportTo(level, player.getX(), FriendDimensions.DREAM_START.getY() + 1.0D, player.getZ(), player.getYRot(), player.getXRot());
        }

        if (FriendDimensions.shouldAutoLeaveDream(player)) {
            data.putBoolean(DREAM_STARTED, false);
            data.putLong(DREAM_CUTSCENE_UNTIL, 0L);
            data.putInt(DREAM_VARIANT, 0);
            cleanupOwnedFriends(level, player);
            FriendDimensions.sendHomeFromDream(player, FriendDimensions.dreamReturnPos(player));
        }
    }

    private static void tickDreamRealm(ServerLevel level, ServerPlayer player, CompoundTag data, long time, int variant) {
        // Compatibility wrapper for old variant-based calls. Variants are now infinite dream field offsets.
        tickDreamPlayer(level, player, data, time);
    }

    private static void tickVoidPlayer(ServerLevel level, ServerPlayer player, long time) {
        FriendDimensions.ensureVoidCorridor(level);
        if (player.getY() < 76.0D || player.getY() > 92.0D || Math.abs(player.getX()) > 8.0D
                || player.getZ() < FriendDimensions.VOID_START.getZ() - 16.0D || player.getZ() > FriendDimensions.VOID_LENGTH + 4.0D) {
            player.teleportTo(level, FriendDimensions.VOID_START.getX() + 0.5D, FriendDimensions.VOID_START.getY() + 1.0D,
                    Mth.clamp(player.getZ(), 1.5D, FriendDimensions.VOID_LENGTH - 8.0D), 180.0F, 0.0F);
        }
        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, false, false));
        if (time % 90L == 0L) {
            play(level, player.blockPosition(), FriendSoundEvents.VOID_AMBIENT, 0.18F, 0.55F, 0.8F);
        }
        if (time % 160L == 20L) {
            spawnVoidWatch(level, player);
        }
        if (time % 20L == 0L) {
            player.displayClientMessage(Component.literal("there is no way back"), true);
        }
    }

    private static void tickSkyPlayer(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        FriendDimensions.ensureSky(level);
        double limit = FriendDimensions.SKY_RADIUS - 3.0D;
        if (player.getY() < FriendDimensions.SKY_START.getY() - 8.0D || Math.abs(player.getX()) > limit || Math.abs(player.getZ()) > limit) {
            player.teleportTo(level, Mth.clamp(player.getX(), -limit, limit), FriendDimensions.SKY_START.getY() + 1.0D,
                    Mth.clamp(player.getZ(), -limit, limit), player.getYRot(), player.getXRot());
        }
        if (!data.getBoolean(SKY_DIALOG_STARTED)) {
            data.putBoolean(SKY_DIALOG_STARTED, true);
            data.putInt(SKY_DIALOG_STAGE, 0);
            data.putLong(SKY_DIALOG_NEXT, time + 35L);
            spawnSkyFriend(level, player);
            player.displayClientMessage(Component.literal("the sky has no horizon"), true);
        }
        if (time % 120L == 0L) {
            play(level, player.blockPosition(), FriendSoundEvents.LOW_DRONE, 0.07F, 0.55F, 0.8F);
        }
        tickSkyDialogue(level, player, data, time);
        if (player.getZ() > 51.0D && Math.abs(player.getX() + 36.0D) < 8.0D) {
            cleanupOwnedFriends(level, player);
            data.putBoolean(CAT_CURSE, false);
            data.putBoolean(SKY_DIALOG_STARTED, false);
            FriendDimensions.sendHomeFromDream(player, dreamReturnTarget(data));
        } else if (player.getZ() > 51.0D && Math.abs(player.getX() - 36.0D) < 8.0D) {
            cleanupOwnedFriends(level, player);
            data.putBoolean(SKY_DIALOG_STARTED, false);
            play(level, player.blockPosition(), FriendSoundEvents.VOID_TRANSITION, 0.95F, 0.85F, 1.0F);
            FriendDimensions.sendToVoid(player);
        }
    }

    private static void tickSkyDialogue(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        if (time < data.getLong(SKY_DIALOG_NEXT)) {
            return;
        }
        String reason = data.getString(SKY_REASON);
        String[] lines = reason.equals("day100") ? SKY_DIALOG_DAY100 : SKY_DIALOG_CAT;
        int stage = data.getInt(SKY_DIALOG_STAGE);
        if (stage < lines.length) {
            player.displayClientMessage(Component.literal(lines[stage]), false);
            data.putInt(SKY_DIALOG_STAGE, stage + 1);
            data.putLong(SKY_DIALOG_NEXT, time + 70L);
            play(level, player.blockPosition(), FriendSoundEvents.LOW_BREATH, 0.14F, 0.58F, 0.82F);
        } else if (stage == lines.length) {
            player.displayClientMessage(Component.literal("Friend: left is morning. right is forever."), false);
            data.putInt(SKY_DIALOG_STAGE, stage + 1);
            data.putLong(SKY_DIALOG_NEXT, time + 200L);
        }
    }

    private static boolean spawnSkyFriend(ServerLevel level, ServerPlayer player) {
        cleanupOwnedFriends(level, player);
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return false;
        }
        BlockPos feet = findSafeFriendFloor(level, FriendDimensions.SKY_START.offset(0, 1, 12));
        if (feet == null) {
            return false;
        }
        friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        friend.configure(player.getUUID().toString(), "sky_dialog", 20 * 180, 9999);
        facePlayer(friend, player);
        level.addFreshEntity(friend);
        return true;
    }

    private static boolean tryDay100SkyEnding(ServerLevel level, ServerPlayer player, CompoundTag data) {
        if (!level.dimension().equals(Level.OVERWORLD) || data.getBoolean(SKY_DAY100_DONE) || data.getBoolean(CAT_CURSE)) {
            return false;
        }
        long day = level.getDayTime() / 24000L;
        if (day < 100L) {
            return false;
        }
        data.putBoolean(SKY_DAY100_DONE, true);
        data.putString(SKY_REASON, "day100");
        data.putBoolean(SKY_DIALOG_STARTED, false);
        cleanupOwnedFriends(level, player);
        return FriendDimensions.sendToSky(player);
    }

    private static boolean tryDay1000ParadiseEnding(ServerLevel level, ServerPlayer player, CompoundTag data) {
        // Paradise is intentionally removed from the release build.
        return false;
    }

    private static void tickParadisePlayer(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        FriendDimensions.ensureParadise(level);
        FriendDimensions.tickParadiseField(player);
        String ending = data.getString(ENDING_STATE);
        if (!ending.equals("return") && !ending.equals("release")) {
            FriendDimensions.ensureParadiseChoice(level);
        }
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(10.0F);
        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 240, 0, false, false));
        if (findOwnedEvent(level, player, "paradise_guide", 120.0D) == null) {
            spawnParadiseGuide(level, player);
        }
        if (time % 120L == 0L) {
            play(level, player.blockPosition(), FriendSoundEvents.LOW_DRONE, 0.035F, 1.25F, 1.5F);
        }
    }

    private static boolean spawnParadiseGuide(ServerLevel level, ServerPlayer player) {
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return false;
        }
        BlockPos feet = findSafeFriendFloor(level, FriendDimensions.PARADISE_START.offset(0, 1, 8));
        if (feet == null) {
            return false;
        }
        friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        friend.configure(player.getUUID().toString(), "paradise_guide", 20 * 60 * 60, 9999);
        friend.setVisualVariant("white");
        facePlayer(friend, player);
        level.addFreshEntity(friend);
        return true;
    }

    private static void chooseParadiseReturn(ServerLevel level, ServerPlayer player, CompoundTag data) {
        data.putBoolean(FRIEND_DISABLED_AFTER_RETURN, true);
        data.putBoolean(CAT_CURSE, false);
        data.putBoolean(RAGE, false);
        data.putString(ENDING_STATE, "return");
        FriendDimensions.clearParadiseChoice(level);
        cleanupOwnedFriends(level, player);
        player.displayClientMessage(Component.literal("Friend: then go. i will close the door behind you."), false);
        play(level, player.blockPosition(), FriendSoundEvents.LOW_BREATH, 0.18F, 0.72F, 0.92F);
        FriendDimensions.sendHomeFromDream(player, dreamReturnTarget(data));
    }

    private static void chooseParadiseRelease(ServerLevel level, ServerPlayer player, CompoundTag data) {
        data.putBoolean(RELEASE_ENDING_CHOSEN, true);
        data.putBoolean(FRIEND_DISABLED_AFTER_RETURN, true);
        data.putString(ENDING_STATE, "release");
        FriendDimensions.clearParadiseChoice(level);
        cleanupOwnedFriends(level, player);
        player.displayClientMessage(Component.literal("Friend: stay. the world outside will forget how to reach you."), false);
        play(level, player.blockPosition(), FriendSoundEvents.LOW_DRONE, 0.14F, 0.85F, 1.05F);
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 20, 1, false, false));
        award(player, new ResourceLocation(FriendMod.MOD_ID, "no_more_running"));
    }

    private static void tickFifthPlayer(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        FriendDimensions.ensureFifth(level);
        // No generated-by-code platforms here. The fifth/bad-ending terrain must come from worldgen.
        data.putBoolean(BAD_ENDING_LOCKED, true);
        data.putString(ENDING_STATE, "fifth");
        player.getFoodData().setFoodLevel(20);
        if (time % 160L == 0L || !player.hasEffect(MobEffects.NIGHT_VISION)) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 60 * 60, 0, false, false));
        }

        if (!data.getBoolean(FIFTH_STARTED)) {
            data.putBoolean(FIFTH_STARTED, true);
            data.putInt(FIFTH_STAGE, 0);
            data.putLong(FIFTH_STAGE_STARTED, time);
            data.putLong(FIFTH_NEXT_CAT, time + 20L);
            data.putInt(FIFTH_CAT_COUNT, 0);
            data.putBoolean(FIFTH_VOICE_PLAYED, false);
            data.putDouble(FIFTH_LOCK_X, player.getX());
            data.putDouble(FIFTH_LOCK_Y, player.getY());
            data.putDouble(FIFTH_LOCK_Z, player.getZ());
            award(player, new ResourceLocation(FriendMod.MOD_ID, "the_end"));
        }

        int stage = data.getInt(FIFTH_STAGE);
        long stageStarted = data.getLong(FIFTH_STAGE_STARTED);

        if (stage < 3) {
            freezeFifthPlayer(player, data);
        } else {
            clearFifthCutsceneEffects(player);
        }

        if (stage == 0) {
            // Thirty seconds of cats silently judging the player before the two Friends appear.
            if (time >= data.getLong(FIFTH_NEXT_CAT) && data.getInt(FIFTH_CAT_COUNT) < 26) {
                spawnJudgementCat(level, player, 6 + RANDOM.nextInt(10));
                data.putInt(FIFTH_CAT_COUNT, data.getInt(FIFTH_CAT_COUNT) + 1);
                data.putLong(FIFTH_NEXT_CAT, time + 18L + RANDOM.nextInt(16));
            }
            if (time - stageStarted >= 20L * 30L) {
                spawnFifthFinalFriends(level, player);
                data.putInt(FIFTH_STAGE, 1);
                data.putLong(FIFTH_STAGE_STARTED, time);
                data.putLong(FIFTH_NEXT_CAT, time + 40L);
            }
            return;
        }

        if (stage == 1 && time >= data.getLong(FIFTH_NEXT_CAT)) {
            data.putBoolean(FIFTH_VOICE_PLAYED, true);
            data.putInt(FIFTH_STAGE, 2);
            data.putLong(FIFTH_STAGE_STARTED, time);
            play(level, player.blockPosition(), FriendSoundEvents.FINAL_SENTENCE, 1.0F, 1.0F, 1.0F);
            player.displayClientMessage(Component.literal("Friend: there is no exit here. only consequence."), false);
            // Approximate spoken line duration plus 4 seconds of silence before they disappear.
            data.putLong(FIFTH_NEXT_CAT, time + 20L * 24L);
            return;
        }

        if (stage == 2 && time >= data.getLong(FIFTH_NEXT_CAT)) {
            cleanupOwnedFriends(level, player);
            level.getEntitiesOfClass(Cat.class, player.getBoundingBox().inflate(72.0D), Cat::isInvulnerable).forEach(Entity::discard);
            data.putInt(FIFTH_STAGE, 3);
            data.putLong(FIFTH_STAGE_STARTED, time);
            player.displayClientMessage(Component.literal("there is only dirt now"), true);
        }
    }

    private static void freezeFifthPlayer(ServerPlayer player, CompoundTag data) {
        double x = data.contains(FIFTH_LOCK_X) ? data.getDouble(FIFTH_LOCK_X) : player.getX();
        double y = data.contains(FIFTH_LOCK_Y) ? data.getDouble(FIFTH_LOCK_Y) : player.getY();
        double z = data.contains(FIFTH_LOCK_Z) ? data.getDouble(FIFTH_LOCK_Z) : player.getZ();

        player.setDeltaMovement(0.0D, 0.0D, 0.0D);

        // Keep the player in place without fighting the mouse every tick.
        // Teleport only when they actually drift away; preserve current yaw/pitch.
        double dx = player.getX() - x;
        double dy = player.getY() - y;
        double dz = player.getZ() - z;
        if (dx * dx + dy * dy + dz * dz > 0.045D) {
            player.teleportTo(player.serverLevel(), x, y, z, player.getYRot(), player.getXRot());
        }
    }

    private static void clearFifthCutsceneEffects(ServerPlayer player) {
        // After the bad-ending cutscene is over, death/respawn in the mud world must not
        // reapply the old cutscene-control effects. Keep Night Vision only for readability.
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        player.removeEffect(MobEffects.DARKNESS);
        player.removeEffect(MobEffects.BLINDNESS);
        player.removeEffect(MobEffects.CONFUSION);
    }

    private static void spawnJudgementCat(ServerLevel level, ServerPlayer player, int distance) {
        Cat cat = new Cat(net.minecraft.world.entity.EntityType.CAT, level);
        double angle = RANDOM.nextDouble() * Math.PI * 2.0D;
        BlockPos rough = BlockPos.containing(player.getX() + Math.cos(angle) * distance, player.getY(), player.getZ() + Math.sin(angle) * distance);
        BlockPos floor = findFloor(level, rough);
        if (floor == null) {
            floor = player.blockPosition();
        }
        Vec3 catPos = new Vec3(floor.getX() + 0.5D, floor.getY(), floor.getZ() + 0.5D);
        float yaw = yawToward(catPos, player.position());
        cat.moveTo(catPos.x, catPos.y, catPos.z, yaw, 0.0F);
        cat.setYHeadRot(yaw);
        cat.setYBodyRot(yaw);
        cat.setInvulnerable(true);
        cat.setNoAi(true);
        level.addFreshEntity(cat);
    }

    private static void spawnFifthFinalFriends(ServerLevel level, ServerPlayer player) {
        cleanupOwnedFriends(level, player);
        spawnFinalFriend(level, player, -0.82D, "white");
        spawnFinalFriend(level, player, 0.82D, "black");
    }

    private static void spawnFinalFriend(ServerLevel level, ServerPlayer player, double side, String variant) {
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return;
        }
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        if (look.lengthSqr() < 0.001D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        look = look.normalize();
        Vec3 lateral = new Vec3(-look.z, 0.0D, look.x).normalize();
        BlockPos rough = BlockPos.containing(player.position().add(look.scale(9.0D)).add(lateral.scale(side)));
        BlockPos floor = findSafeFriendFloor(level, rough);
        if (floor == null) {
            return;
        }
        Vec3 friendPos = new Vec3(floor.getX() + 0.5D, floor.getY(), floor.getZ() + 0.5D);
        float yaw = yawToward(friendPos, player.position());
        friend.moveTo(friendPos.x, friendPos.y, friendPos.z, yaw, 0.0F);
        friend.configure(player.getUUID().toString(), "final_sentence", 20 * 90, 9999);
        friend.setVisualVariant(variant);
        friend.setYHeadRot(yaw);
        friend.setYBodyRot(yaw);
        level.addFreshEntity(friend);
    }

    private static float yawToward(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) (Mth.atan2(dz, dx) * (180F / Math.PI)) - 90.0F;
    }

    private static void startCatSinPunishment(ServerLevel level, ServerPlayer player, BlockPos catDeathPos) {
        CompoundTag data = state(player);
        data.putBoolean(CAT_SIN, true);
        player.addTag("friend_cat_sin");
        player.addTag("friend_grief_silence");
        data.putBoolean(RAGE, true);
        data.putBoolean(PUNISHMENT_ACTIVE, true);
        data.putLong(PUNISHMENT_START, level.getGameTime());
        data.putInt(PUNISHMENT_STAGE, 0);
        data.putBoolean(PUNISHMENT_VOICE_PLAYED, false);
        data.putLong(PUNISHMENT_NEXT_CRY, level.getGameTime());
        data.putLong(CAT_BACKGROUND_NEXT, level.getGameTime());
        data.putLong(PUNISHMENT_NEXT_WITNESS, level.getGameTime() + 20L);
        data.putInt(PUNISHMENT_WITNESS_COUNT, 0);
        data.putInt(PUNISHMENT_CAT_X, catDeathPos.getX());
        data.putInt(PUNISHMENT_CAT_Y, catDeathPos.getY());
        data.putInt(PUNISHMENT_CAT_Z, catDeathPos.getZ());
        data.putInt(PHASE, Math.max(7, data.getInt(PHASE)));
        cleanupOwnedFriends(level, player);
        level.setWeatherParameters(0, 20 * 60 * 20, true, true);
        // During the white-grief scene, keep custom mod ambience silent.
        // Only WHITE_GRIEF_SPEECH is allowed to play when the white Friend appears.
        clearFifthCutsceneEffects(player);
    }

    private static void tickCatSinServerBackground(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        if (level.dimension().equals(FriendDimensions.FIFTH) || data.getBoolean(PUNISHMENT_ACTIVE) || player.getTags().contains("friend_grief_silence")) {
            return;
        }
        if (time >= data.getLong(CAT_BACKGROUND_NEXT)) {
            play(level, behind(player, 4), FriendSoundEvents.CAT_SIN_BACKGROUND, 0.045F, 1.0F, 1.0F);
            data.putLong(CAT_BACKGROUND_NEXT, time + 20L * 11L);
        }
    }

    private static void tickCatPunishment(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        long elapsed = time - data.getLong(PUNISHMENT_START);
        BlockPos catPos = new BlockPos(data.getInt(PUNISHMENT_CAT_X), data.getInt(PUNISHMENT_CAT_Y), data.getInt(PUNISHMENT_CAT_Z));

        cleanupOldWhiteArmorStands(level, player);

        int stage = data.getInt(PUNISHMENT_STAGE);

        if (elapsed < 20 * 5) {
            player.displayClientMessage(Component.literal("the world became quiet"), true);
            return;
        }

        if (stage == 0) {
            cleanupPunishmentWitnesses(level, player);
            if (spawnWhiteGrief(level, player, catPos)) {
                FriendEntity grief = findOwnedEvent(level, player, "white_grief", 96.0D);
                if (grief != null) {
                    playEntity(level, grief, FriendSoundEvents.WHITE_GRIEF_SPEECH, 9.5F, 0.94F, 0.94F);
                    player.displayClientMessage(Component.literal("Friend: you left something small behind, and it learned your name."), false);
                }
                data.putBoolean(PUNISHMENT_VOICE_PLAYED, true);
            }
            data.putInt(PUNISHMENT_STAGE, 1);
            data.putLong(PUNISHMENT_START, time);
            data.putLong(PUNISHMENT_NEXT_WITNESS, time + 20L * 36L);
            return;
        }

        if (stage == 1) {
            // White Friend is grieving. No witness movement, no custom ambience, only the spoken grief line.
            player.setDeltaMovement(0.0D, 0.0D, 0.0D);
            if (elapsed > 20 * 36) {
                award(player, new ResourceLocation(FriendMod.MOD_ID, "crime_and_punishment"));
                data.putInt(PUNISHMENT_STAGE, 2);
                data.putLong(PUNISHMENT_START, time);
                data.putLong(PUNISHMENT_NEXT_WITNESS, time + 8L);
                cleanupOwnedFriends(level, player);
                player.removeTag("friend_grief_silence");
                player.displayClientMessage(Component.literal("the world noticed what you did"), true);
            }
            return;
        }

        // Stage 2: the white witnesses are released. They keep spawning until they kill the player.
        if (time >= data.getLong(PUNISHMENT_NEXT_WITNESS)) {
            if (countWhiteWitnesses(level, player) < 42 && spawnWhitePlayerWitness(level, player, data.getInt(PUNISHMENT_WITNESS_COUNT))) {
                data.putInt(PUNISHMENT_WITNESS_COUNT, Math.min(100000, data.getInt(PUNISHMENT_WITNESS_COUNT) + 1));
            }
            data.putLong(PUNISHMENT_NEXT_WITNESS, time + seconds(1, 3));
        }

        tickWhitePlayerWitnesses(level, player, true);

        List<LivingEntity> witnesses = level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(48.0D),
                entity -> entity != player && isPunishmentMob(entity));
        for (LivingEntity witness : witnesses) {
            witness.addTag("friend_wrong_texture");
            witness.setGlowingTag(true);
            witness.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
            witness.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 40, 2, false, false));
            if (witness instanceof PathfinderMob mob) {
                mob.setNoAi(false);
                mob.setTarget(player);
                mob.getNavigation().moveTo(player, 1.18D);
                Vec3 toPlayer = player.position().subtract(mob.position()).multiply(1.0D, 0.0D, 1.0D);
                if (toPlayer.lengthSqr() > 2.5D) {
                    mob.setDeltaMovement(mob.getDeltaMovement().add(toPlayer.normalize().scale(0.13D)));
                }
            }
            if (witness.distanceTo(player) < 2.65D && time % 10L == 0L && level.getDifficulty() != Difficulty.PEACEFUL) {
                player.hurt(player.damageSources().mobAttack(witness), witness.getTags().contains("friend_white_player_witness") ? 5.0F : 3.0F);
            }
        }

        if (elapsed > 20 * 90) {
            data.putLong(PUNISHMENT_START, time - 20L * 70L);
        }
    }

    private static boolean spawnWhiteGrief(ServerLevel level, ServerPlayer player, BlockPos catPos) {
        FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
        if (friend == null) {
            return false;
        }
        BlockPos feet = griefSurface(level, catPos);
        if (!hasFriendStandingSpace(level, feet)) {
            BlockPos raised = findSafeFriendFloor(level, feet);
            if (raised == null) {
                return false;
            }
            feet = raised;
        }
        friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        friend.configure(player.getUUID().toString(), "white_grief", 20 * 45, 9999);
        friend.setVisualVariant("white");
        facePlayer(friend, player);
        level.addFreshEntity(friend);
        return true;
    }

    private static BlockPos griefSurface(ServerLevel level, BlockPos catPos) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int radius = 0; radius <= 6; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = catPos.getX() + dx;
                    int z = catPos.getZ() + dz;
                    for (int y = catPos.getY() + 4; y >= Math.max(level.getMinBuildHeight() + 2, catPos.getY() - 9); y--) {
                        BlockPos floor = new BlockPos(x, y - 1, z);
                        BlockPos feet = floor.above();
                        if (!level.hasChunkAt(feet) || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
                                || !hasFriendStandingSpace(level, feet)) {
                            continue;
                        }
                        double score = feet.distSqr(catPos);
                        if (best == null || score < bestScore) {
                            best = feet.immutable();
                            bestScore = score;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        BlockPos found = findFloor(level, catPos);
        if (found != null && hasFriendStandingSpace(level, found)) {
            return found.immutable();
        }
        return catPos.above().immutable();
    }

    private static boolean hasFriendStandingSpace(ServerLevel level, BlockPos feet) {
        if (!level.hasChunkAt(feet) || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) {
            return false;
        }
        for (int i = 0; i < FRIEND_AIR_BLOCKS; i++) {
            BlockPos pos = feet.above(i);
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        double x = feet.getX() + 0.5D;
        double y = feet.getY();
        double z = feet.getZ() + 0.5D;
        AABB box = new AABB(x - FRIEND_COLLISION_RADIUS, y, z - FRIEND_COLLISION_RADIUS,
                x + FRIEND_COLLISION_RADIUS, y + FRIEND_COLLISION_HEIGHT, z + FRIEND_COLLISION_RADIUS);
        return level.noCollision(box);
    }

    private static boolean spawnWhitePlayerWitness(ServerLevel level, ServerPlayer player, int index) {
        for (int attempt = 0; attempt < 28; attempt++) {
            double distance = 12.0D + RANDOM.nextDouble() * 42.0D;
            double angle = RANDOM.nextDouble() * Math.PI * 2.0D + index * 0.73D;
            BlockPos rough = BlockPos.containing(player.getX() + Math.cos(angle) * distance, player.getY() + RANDOM.nextInt(15) - 7,
                    player.getZ() + Math.sin(angle) * distance);
            BlockPos floor = findFloor(level, rough);
            if (floor == null || !level.hasChunkAt(floor) || !hasFriendStandingSpace(level, floor) || floor.distSqr(player.blockPosition()) < 9.0D * 9.0D) {
                continue;
            }

            net.minecraft.world.entity.monster.Zombie witness = new net.minecraft.world.entity.monster.Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, level);
            witness.addTag("friend_white_player_witness");
            witness.addTag("friend_wrong_texture");
            witness.setPersistenceRequired();
            witness.setSilent(true);
            witness.setCanPickUpLoot(false);
            witness.setBaby(false);
            witness.setNoAi(false);
            witness.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.38D);
            witness.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64.0D);
            witness.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(5.0D);
            witness.setItemSlot(EquipmentSlot.HEAD, whiteArmor(Items.LEATHER_HELMET));
            witness.setItemSlot(EquipmentSlot.CHEST, whiteArmor(Items.LEATHER_CHESTPLATE));
            witness.setItemSlot(EquipmentSlot.LEGS, whiteArmor(Items.LEATHER_LEGGINGS));
            witness.setItemSlot(EquipmentSlot.FEET, whiteArmor(Items.LEATHER_BOOTS));
            witness.setDropChance(EquipmentSlot.HEAD, 0.0F);
            witness.setDropChance(EquipmentSlot.CHEST, 0.0F);
            witness.setDropChance(EquipmentSlot.LEGS, 0.0F);
            witness.setDropChance(EquipmentSlot.FEET, 0.0F);

            Vec3 pos = new Vec3(floor.getX() + 0.5D, floor.getY(), floor.getZ() + 0.5D);
            float yaw = yawToward(pos, player.position());
            witness.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
            witness.setYHeadRot(yaw);
            witness.setYBodyRot(yaw);
            witness.setTarget(player);
            level.addFreshEntity(witness);
            return true;
        }
        return false;
    }

    private static int countWhiteWitnesses(ServerLevel level, ServerPlayer player) {
        return level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(96.0D),
                entity -> entity.getTags().contains("friend_white_player_witness")).size();
    }

    private static void cleanupPunishmentWitnesses(ServerLevel level, ServerPlayer player) {
        level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(128.0D),
                entity -> entity.getTags().contains("friend_white_player_witness")).forEach(Entity::discard);
        cleanupOldWhiteArmorStands(level, player);
    }

    private static void cleanupOldWhiteArmorStands(ServerLevel level, ServerPlayer player) {
        level.getEntitiesOfClass(ArmorStand.class, player.getBoundingBox().inflate(128.0D),
                stand -> stand.getTags().contains("friend_white_player_witness")).forEach(Entity::discard);
    }

    private static void tickWhitePlayerWitnesses(ServerLevel level, ServerPlayer player, boolean released) {
        List<LivingEntity> witnesses = level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(96.0D),
                entity -> entity.getTags().contains("friend_white_player_witness"));
        for (LivingEntity witness : witnesses) {
            witness.setGlowingTag(true);
            witness.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
            if (witness instanceof PathfinderMob mob) {
                mob.setNoAi(!released);
                if (released) {
                    mob.setTarget(player);
                    mob.getNavigation().moveTo(player, 1.22D);
                } else {
                    mob.getNavigation().stop();
                    mob.setDeltaMovement(Vec3.ZERO);
                }
            }
        }
    }

    private static ItemStack whiteArmor(net.minecraft.world.item.Item item) {
        ItemStack stack = new ItemStack(item);
        CompoundTag display = new CompoundTag();
        display.putInt("color", 0xF8F8F8);
        stack.getOrCreateTag().put("display", display);
        return stack;
    }

    private static boolean isPunishmentMob(Entity entity) {
        if (!(entity instanceof LivingEntity) || entity instanceof Player || entity instanceof FriendEntity || entity instanceof ArmorStand) {
            return false;
        }
        if (entity.getTags().contains("friend_white_player_witness")) {
            return true;
        }
        return !(entity instanceof Animal) && !(entity instanceof WaterAnimal) && entity instanceof PathfinderMob;
    }

    private static boolean spawnVoidWatch(ServerLevel level, ServerPlayer player) {
        if (findOwnedEvent(level, player, "void_watch", 96.0D) != null) {
            return true;
        }
        for (int i = 0; i < 20; i++) {
            double z = Math.min(FriendDimensions.VOID_LENGTH - 18.0D, player.getZ() + 26.0D + RANDOM.nextInt(36));
            double x = RANDOM.nextBoolean() ? -3.7D : 3.7D;
            BlockPos feet = BlockPos.containing(x, FriendDimensions.VOID_START.getY() + 1, z);
            if (!hasFriendStandingSpace(level, feet)) {
                continue;
            }
            FriendEntity friend = FriendEntityTypes.FRIEND.get().create(level);
            if (friend == null) return false;
            friend.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
            friend.configure(player.getUUID().toString(), "void_watch", seconds(8, 14), 999);
            friend.setGlowingTag(true);
            facePlayer(friend, player);
            level.addFreshEntity(friend);
            play(level, feet, FriendSoundEvents.LOW_BREATH, 0.12F, 0.5F, 0.78F);
            return true;
        }
        return false;
    }

    private static void updateRouteMemory(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        if (!level.dimension().equals(Level.OVERWORLD) || time < data.getLong(ROUTE_LAST_SAMPLE) + 40L) {
            return;
        }
        data.putLong(ROUTE_LAST_SAMPLE, time);
        ListTag routes = data.getList(ROUTE_POINTS, Tag.TAG_COMPOUND);
        BlockPos current = player.blockPosition();
        int matched = -1;
        double matchedDistance = Double.MAX_VALUE;
        for (int i = 0; i < routes.size(); i++) {
            CompoundTag route = routes.getCompound(i);
            BlockPos routePos = new BlockPos(route.getInt("x"), route.getInt("y"), route.getInt("z"));
            double dist = routePos.distSqr(current);
            if (dist < 11.0D * 11.0D && dist < matchedDistance) {
                matched = i;
                matchedDistance = dist;
            }
        }
        if (matched >= 0) {
            CompoundTag route = routes.getCompound(matched);
            int gain = 1;
            if (player.isSprinting()) gain++;
            if (player.getY() <= 48.0D || !level.canSeeSky(current)) gain++;
            if (level.getBrightness(LightLayer.BLOCK, current) <= 7) gain++;
            if (matched < routes.size() - 1) {
                gain += 3;
                route.putInt("habit", Math.min(1000, route.getInt("habit") + 1));
            }
            route.putInt("score", Math.min(3000, route.getInt("score") + gain));
            route.putBoolean("underground", player.getY() <= 48.0D || !level.canSeeSky(current));
            route.putBoolean("dark", level.getBrightness(LightLayer.BLOCK, current) <= 7);
            route.putBoolean("sprint", player.isSprinting());
            route.putFloat("yaw", player.getYRot());
            route.putLong("last", time);
            data.put(ROUTE_POINTS, routes);
            return;
        }
        CompoundTag point = new CompoundTag();
        point.putInt("x", current.getX());
        point.putInt("y", current.getY());
        point.putInt("z", current.getZ());
        point.putInt("score", 1);
        point.putInt("habit", 0);
        point.putLong("last", time);
        point.putBoolean("underground", player.getY() <= 48.0D || !level.canSeeSky(current));
        point.putBoolean("dark", level.getBrightness(LightLayer.BLOCK, current) <= 7);
        point.putBoolean("sprint", player.isSprinting());
        point.putFloat("yaw", player.getYRot());
        routes.add(point);
        while (routes.size() > 96) {
            routes.remove(0);
        }
        data.put(ROUTE_POINTS, routes);
    }

    private static Optional<BlockPos> rememberedRouteCandidate(ServerPlayer player, CompoundTag data) {
        ListTag routes = data.getList(ROUTE_POINTS, Tag.TAG_COMPOUND);
        if (routes.isEmpty() || RANDOM.nextFloat() > 0.35F) {
            return Optional.empty();
        }
        CompoundTag best = null;
        int bestWeight = -1;
        for (int tries = 0; tries < 12; tries++) {
            CompoundTag tag = routes.getCompound(RANDOM.nextInt(routes.size()));
            BlockPos route = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            double dist = Math.sqrt(player.blockPosition().distSqr(route));
            if (dist >= 10.0D && dist <= 48.0D) {
                int weight = Math.max(1, tag.getInt("score"));
                if (tag.getBoolean("underground")) weight += 8;
                if (tag.getBoolean("dark")) weight += 5;
                if (tag.getBoolean("sprint")) weight += 3;
                weight += tag.getInt("habit") * 9;
                if (weight > bestWeight || RANDOM.nextInt(Math.max(1, weight + bestWeight + 1)) < weight) {
                    bestWeight = weight;
                    best = tag;
                }
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        BlockPos route = new BlockPos(best.getInt("x"), best.getInt("y"), best.getInt("z"));
        return Optional.of(route.offset(RANDOM.nextInt(9) - 4, RANDOM.nextInt(3) - 1, RANDOM.nextInt(9) - 4));
    }

    private static int routeSize(CompoundTag data) {
        return data.getList(ROUTE_POINTS, Tag.TAG_COMPOUND).size();
    }

    private static int routeHabitScore(CompoundTag data) {
        ListTag routes = data.getList(ROUTE_POINTS, Tag.TAG_COMPOUND);
        int best = 0;
        for (int i = 0; i < routes.size(); i++) {
            CompoundTag route = routes.getCompound(i);
            best = Math.max(best, route.getInt("habit") * 6 + route.getInt("score") / 80);
        }
        return Math.min(40, best);
    }

    private static void rememberPlayerPlacedBlock(CompoundTag data, BlockPos pos, long time) {
        ListTag list = data.getList(MEMORY_PLAYER_BLOCKS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            BlockPos remembered = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            if (remembered.equals(pos)) {
                tag.putLong("last", time);
                data.put(MEMORY_PLAYER_BLOCKS, list);
                return;
            }
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putLong("last", time);
        list.add(tag);
        while (list.size() > 1024) {
            list.remove(0);
        }
        data.put(MEMORY_PLAYER_BLOCKS, list);
    }

    private static boolean isPlayerPlacedNear(CompoundTag data, BlockPos pos, int maxDistance) {
        ListTag list = data.getList(MEMORY_PLAYER_BLOCKS, Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            return false;
        }
        double maxSqr = maxDistance * maxDistance;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            BlockPos remembered = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            if (remembered.distSqr(pos) <= maxSqr) {
                return true;
            }
        }
        return false;
    }

    private static void rememberBlockMemory(CompoundTag data, String key, BlockPos pos, long time) {
        ListTag list = data.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            BlockPos remembered = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            if (remembered.equals(pos)) {
                tag.putInt("score", Math.min(500, tag.getInt("score") + 4));
                tag.putLong("last", time);
                data.put(key, list);
                return;
            }
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putInt("score", 4);
        tag.putLong("last", time);
        list.add(tag);
        while (list.size() > 32) {
            list.remove(0);
        }
        data.put(key, list);
    }

    private static Optional<BlockPos> rememberedBlock(CompoundTag data, String key, BlockPos near, int maxDistance) {
        ListTag list = data.getList(key, Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag best = null;
        int bestWeight = -1;
        double maxSqr = maxDistance * maxDistance;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            double dist = near.distSqr(pos);
            if (dist > maxSqr) {
                continue;
            }
            int weight = Math.max(1, tag.getInt("score")) + Math.max(0, 20 - Mth.floor(Math.sqrt(dist)));
            if (weight > bestWeight || RANDOM.nextInt(Math.max(1, weight + bestWeight + 1)) < weight) {
                bestWeight = weight;
                best = tag;
            }
        }
        return best == null ? Optional.empty() : Optional.of(new BlockPos(best.getInt("x"), best.getInt("y"), best.getInt("z")));
    }


    private static Optional<BlockPos> rememberedPlayerPlacedBlock(CompoundTag data, String key, BlockPos near, int maxDistance) {
        Optional<BlockPos> remembered = rememberedBlock(data, key, near, maxDistance);
        return remembered.filter(pos -> isPlayerPlacedNear(data, pos, 1));
    }

    private static BlockPos dreamReturnTarget(CompoundTag data) {
        if (!data.contains(DREAM_BED_X) || !data.contains(DREAM_BED_Y) || !data.contains(DREAM_BED_Z)) {
            return null;
        }
        return new BlockPos(data.getInt(DREAM_BED_X), data.getInt(DREAM_BED_Y), data.getInt(DREAM_BED_Z));
    }

    private static double dreamEventChance(int phase, CompoundTag data) {
        if (!FriendConfig.ENABLE_DREAMS.get() || data.getBoolean(CAT_CURSE)) {
            return 0.0D;
        }
        return Mth.clamp(0.02D + Math.max(0, phase - 4) * 0.035D + data.getDouble(CONFIDENCE) / 1000.0D, 0.0D, 0.18D);
    }

    private static boolean shouldEscalateCornerPeek(CompoundTag data) {
        int phase = data.getInt(PHASE);
        float chance = switch (phase) {
            case 0, 1 -> 0.0F;
            case 2 -> 0.025F;
            case 3 -> 0.06F;
            case 4 -> 0.12F;
            default -> 0.22F;
        };
        if (data.getBoolean(CAT_CURSE)) {
            chance = 0.45F;
        }
        return RANDOM.nextFloat() < chance;
    }

    private static boolean handleCornerPeek(ServerLevel level, ServerPlayer player, FriendEntity friend, double distance) {
        String event = friend.eventId();
        CompoundTag tag = friend.getPersistentData();

        if (friend.cornerEscalated()) {
            CompoundTag data = state(player);
            long now = level.getGameTime();

            // Peek-to-attack is still a real attack. It must obey the same hard cooldown as normal attacks,
            // otherwise a seen peek can instantly bypass the cooldown and cause back-to-back rushes.
            if (!attackCooldownReady(data, now)) {
                friend.setCornerEscalated(false);
                disappear(friend, "peek_attack_on_cooldown", true);
                return true;
            }
            if (!tag.getBoolean("friend_peek_attack_cooldown_started")) {
                beginAttackCooldown(data, now, true);
                tag.putBoolean("friend_peek_attack_cooldown_started", true);
            }
            handleControlledAttack(level, player, friend, distance, true);
            return true;
        }
        BlockPos cover = new BlockPos(tag.getInt(FriendEntity.TAG_COVER_X), tag.getInt(FriendEntity.TAG_COVER_Y), tag.getInt(FriendEntity.TAG_COVER_Z));
        if ((!isHolePeekEvent(event) && !isLowCeilingPeekEvent(event) && !isCoverBlock(level, cover))
                || distance < (isHolePeekEvent(event) || isLowCeilingPeekEvent(event) ? 4.0D : 5.5D)) {
            disappear(friend, "peek_cover_lost", true);
            return true;
        }
        Vec3 body = peekBodyPoint(friend, event);
        Vec3 face = peekFacePoint(friend, event);
        boolean bodyHidden = (isHolePeekEvent(event) || isLowCeilingPeekEvent(event)) ? isBlockedNearCover(player, body, cover) : isBlockedByCover(player, body, cover);
        boolean faceVisible = hasLineOfSight(player, face);
        if (!bodyHidden && friend.tickCount > 12) {
            disappear(friend, "peek_exposed", true);
            return true;
        }
        SightState sight = sightState(player, face, 30.0D);
        if (sight == SightState.DIRECT && faceVisible) {
            int panic = tag.getInt(FriendEntity.TAG_PEEK_PANIC_TICKS) + 1;
            tag.putInt(FriendEntity.TAG_PEEK_PANIC_TICKS, panic);
            int phase = state(player).getInt(PHASE);
            int threshold = (isHolePeekEvent(event) || isLowCeilingPeekEvent(event)) ? 1 : phase >= 7 ? 13 : phase >= 5 ? 9 : friend.tickCount < 14 ? 4 : 2;
            if (panic >= threshold) {
                add(state(player), CONFIDENCE, 0.8D, 0, 100);
                rememberBlockMemory(state(player), MEMORY_PEEK_COVERS, cover, level.getGameTime());
                if (!isHolePeekEvent(event) && !isLowCeilingPeekEvent(event)) {
                    schedulePeekChain(level, player, state(player), cover);
                }
                disappear(friend, "peek_spotted", true);
            }
            return true;
        }
        if (sight == SightState.PERIPHERAL && faceVisible) {
            tag.putInt(FriendEntity.TAG_PEEK_PANIC_TICKS, 0);
            if (friend.tickCount % 34 == 0) {
                play(level, friend.blockPosition(), FriendSoundEvents.QUIET_BREATH, 0.07F, 0.92F, 1.06F);
            }
            return true;
        }
        tag.putInt(FriendEntity.TAG_PEEK_PANIC_TICKS, 0);
        String mode = tag.getString(PEEK_MODE);
        int minHold = mode.equals("turnaround") ? 135 : mode.equals("bold") ? 115 : 80;
        float vanishChance = mode.equals("bold") ? 0.018F : mode.equals("turnaround") ? 0.022F : 0.035F;
        if (friend.tickCount > minHold && RANDOM.nextFloat() < vanishChance) {
            rememberBlockMemory(state(player), MEMORY_PEEK_COVERS, cover, level.getGameTime());
            disappear(friend, "peek_done", true);
        }
        return true;
    }

    private static Vec3 peekFacePoint(FriendEntity friend, String event) {
        if (event.equals("ceiling_hole_peek")) {
            return friend.position().add(0.0D, 0.25D, 0.0D);
        }
        if (event.equals("floor_hole_peek")) {
            return friend.position().add(0.0D, 2.4D, 0.0D);
        }
        if (event.equals("low_ceiling_1_peek")) {
            return friend.position().add(0.0D, 0.72D, 0.0D);
        }
        if (event.equals("low_ceiling_2_peek")) {
            return friend.position().add(0.0D, 1.32D, 0.0D);
        }
        return friend.position().add(0.0D, friend.getBbHeight() * 0.82D, 0.0D);
    }

    private static Vec3 peekBodyPoint(FriendEntity friend, String event) {
        if (event.equals("ceiling_hole_peek")) {
            return friend.position().add(0.0D, 1.3D, 0.0D);
        }
        if (event.equals("floor_hole_peek")) {
            return friend.position().add(0.0D, 1.0D, 0.0D);
        }
        if (event.equals("low_ceiling_1_peek")) {
            return friend.position().add(0.0D, 0.28D, 0.0D);
        }
        if (event.equals("low_ceiling_2_peek")) {
            return friend.position().add(0.0D, 0.82D, 0.0D);
        }
        return friend.position().add(0.0D, 1.05D, 0.0D);
    }

    private static void handleControlledAttack(ServerLevel level, ServerPlayer player, FriendEntity friend, double distance, boolean fromCornerPeek) {
        int sceneTicks = friend.tickCount;
        boolean falseAttack = friend.eventId().equals("false_attack") || friend.getPersistentData().getBoolean("friend_false_attack");
        int maxTicks = falseAttack ? 20 * 5 : ATTACK_MAX_TICKS;
        if (sceneTicks >= maxTicks) {
            disappear(friend, falseAttack ? "false_attack_vanish" : fromCornerPeek ? "corner_attack_timeout" : "attack_timeout", true);
            return;
        }

        if (sceneTicks < ATTACK_INTRO_TICKS) {
            friend.setRushing(false);
            facePlayer(friend, player);
            if (sceneTicks == 18) {
                playAttackScream(level, player, friend, false);
            }
            return;
        }

        playAttackScream(level, player, friend, true);
        drainChaseLights(level, friend, 10);
        scareMobsAroundFriend(level, friend, 40.0D);
        if (!hasCaughtPlayer(friend, player, 2.55D)) {
            friend.setRushing(true);
            double speed = matchingRushSpeed(player, friend.rage());
            state(player).putDouble(LAST_CHASE_SPEED, speed);
            if (isEntityPinned(level, friend)) {
                freeSelfFromBlocks(level, friend, friend.rage() ? 2 : 1);
            }
            chaseMove(level, friend, player, speed, true, friend.rage() ? 2 : 1);
            // No rhythmic bunny-hop here: it made Friend jump before grass/flowers and looked goofy in survival.
            // FriendMovementController now jumps only when a real collision obstacle blocks the route.
            facePlayer(friend, player);
            if (level.getGameTime() % 12L == 0L) {
                play(level, friend.blockPosition(), RANDOM.nextBoolean() ? FriendSoundEvents.CREAK_STEP : FriendSoundEvents.JOINT_CRACK, 0.38F, 0.55F, 0.82F);
            }
            return;
        }

        if (falseAttack) {
            add(state(player), FEAR_SCORE, 24, 0, 600);
            disappear(friend, "false_attack_close_vanish", true);
            return;
        }
        long now = level.getGameTime();
        CompoundTag tag = friend.getPersistentData();
        if (level.getDifficulty() != Difficulty.PEACEFUL && now >= tag.getLong(FriendEntity.TAG_ATTACK_COOLDOWN)) {
            tag.putLong(FriendEntity.TAG_ATTACK_COOLDOWN, now + 22L);
            player.hurt(player.damageSources().mobAttack(friend), 8.0F);
        }
        friend.setRushing(true);
        if (player.isDeadOrDying() || player.getHealth() <= 0.0F) {
            disappear(friend, fromCornerPeek ? "corner_attack_kill" : "attack_kill", true);
        }
    }

    private static void playAttackScream(ServerLevel level, ServerPlayer player, FriendEntity friend, boolean repeat) {
        CompoundTag tag = friend.getPersistentData();
        long now = level.getGameTime();
        if (now < tag.getLong(FriendEntity.TAG_ATTACK_SCREAM_COOLDOWN)) {
            return;
        }

        // Do not play a server positional scream here: that behaves like a block sound and gets left behind.
        // The client-side FriendClientSounds tickable sound follows the listener and stops when Friend dies.
        tag.putLong(FriendEntity.TAG_ATTACK_SCREAM_COOLDOWN, now + (repeat ? 20L * 5L : 20L * 12L));
    }

    private static double matchingRushSpeed(ServerPlayer player) {
        return matchingRushSpeed(player, false);
    }

    private static double matchingRushSpeed(ServerPlayer player, boolean rage) {
        Vec3 motion = player.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        double observedSpeed = motion.length();
        double sprintJumpSpeed = 7.127D / 20.0D;
        double expectedPlayerSpeed = player.isSprinting() ? sprintJumpSpeed : 0.130D;
        if (observedSpeed < 0.025D && !player.isSprinting()) {
            expectedPlayerSpeed = 0.095D;
        }
        double matchedSpeed = Math.max(observedSpeed, expectedPlayerSpeed) * (rage ? 1.62D : 1.38D);
        double configuredSpeed = matchedSpeed * FriendConfig.FRIEND_SPEED_MULTIPLIER.get();
        return Mth.clamp(configuredSpeed, 0.20D, rage ? 0.92D : 0.78D);
    }

    private static void bunnyHop(ServerLevel level, FriendEntity friend, double height, long rhythm) {
        if (friend.onGround() && level.getGameTime() % rhythm == 0L) {
            friend.setDeltaMovement(0.0D, height, 0.0D);
        }
    }

    private static void handleCatCurseChaser(ServerLevel level, ServerPlayer player, FriendEntity friend, double distance) {
        friend.setRushing(true);
        // Phase ? should be visible by eyes/silhouette, not the vanilla glowing outline.
        friend.setGlowingTag(false);
        drainChaseLights(level, friend, 10);
        scareMobsAroundFriend(level, friend, 40.0D);
        if (distance <= 2.05D) {
            if (FriendConfig.ENABLE_VOID.get()) {
                play(level, player.blockPosition(), FriendSoundEvents.VOID_TRANSITION, 1.0F, 0.92F, 1.0F);
                CompoundTag data = state(player);
                if (RANDOM.nextBoolean() && FriendDimensions.sendToSky(player)) {
                    data.putString(SKY_REASON, "cat");
                    data.putBoolean(SKY_DIALOG_STARTED, false);
                } else {
                    FriendDimensions.sendToVoid(player);
                }
                disappear(friend, "void_touch", false);
            }
            return;
        }

        Vec3 direction = player.position().subtract(friend.position()).multiply(1.0D, 0.0D, 1.0D);
        if (direction.lengthSqr() > 0.0001D) {
            double speed = Mth.clamp(0.62D * FriendConfig.FRIEND_SPEED_MULTIPLIER.get(), 0.24D, 0.92D);
            chaseMove(level, friend, player, speed, FriendConfig.ENABLE_BLOCK_DESTRUCTION.get(), 2);
        }
        catCurseBridgeBreak(level, player, friend);
        facePlayer(friend, player);
        if (level.getGameTime() % 10L == 0L) {
            play(level, friend.blockPosition(), RANDOM.nextBoolean() ? FriendSoundEvents.CREAK_STEP : FriendSoundEvents.JOINT_CRACK, 0.55F, 0.45F, 0.75F);
        }
        state(player).putDouble(CAT_DISTANCE, distance);
    }

    private static void catCurseBridgeBreak(ServerLevel level, ServerPlayer player, FriendEntity friend) {
        if (!FriendConfig.ENABLE_BLOCK_DESTRUCTION.get() || player.isCreative() || player.isSpectator()) {
            return;
        }
        CompoundTag data = state(player);
        long time = level.getGameTime();
        if (time < data.getLong(CAT_BRIDGE_BREAK_COOLDOWN)) {
            return;
        }
        double distance = player.distanceTo(friend);
        if (distance < 4.0D || distance > 110.0D) {
            data.putLong(CAT_BRIDGE_BREAK_COOLDOWN, time + 12L);
            return;
        }
        Vec3 awayFromFriend = player.position().subtract(friend.position()).multiply(1.0D, 0.0D, 1.0D);
        if (awayFromFriend.lengthSqr() < 0.001D) {
            return;
        }
        Vec3 away = awayFromFriend.normalize();
        Vec3 movement = player.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        if (movement.lengthSqr() > 0.0004D && movement.normalize().dot(away) < -0.2D) {
            data.putLong(CAT_BRIDGE_BREAK_COOLDOWN, time + 16L);
            return;
        }

        int broken = 0;
        for (BlockPos pos : bridgeBreakCandidates(player, away)) {
            if (!isCatBridgeSupport(level, pos)) {
                continue;
            }
            if (level.destroyBlock(pos, true, friend)) {
                broken++;
                Vec3 pull = friend.position().subtract(player.position()).multiply(1.0D, 0.0D, 1.0D);
                if (pull.lengthSqr() > 0.001D) {
                    player.setDeltaMovement(player.getDeltaMovement().add(pull.normalize().scale(0.10D)).add(0.0D, -0.05D, 0.0D));
                }
                level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 0.7D, pos.getZ() + 0.5D,
                        10, 0.25D, 0.18D, 0.25D, 0.02D);
                play(level, pos, FriendSoundEvents.DISTANT_SCRAPE, 0.36F, 0.48F, 0.72F);
                if (broken >= 2) {
                    break;
                }
            }
        }
        data.putLong(CAT_BRIDGE_BREAK_COOLDOWN, time + (broken > 0 ? 14L + RANDOM.nextInt(12) : 18L + RANDOM.nextInt(24)));
    }

    private static List<BlockPos> bridgeBreakCandidates(ServerPlayer player, Vec3 away) {
        List<BlockPos> result = new ArrayList<>();
        addUnique(result, supportUnder(player.position()));
        addUnique(result, supportUnder(player.position().add(away.scale(0.72D))));
        addUnique(result, supportUnder(player.position().add(away.scale(1.35D))));
        addUnique(result, supportUnder(player.position().subtract(away.scale(0.72D))));
        Vec3 side = new Vec3(-away.z, 0.0D, away.x);
        addUnique(result, supportUnder(player.position().add(side.scale(0.72D))));
        addUnique(result, supportUnder(player.position().subtract(side.scale(0.72D))));
        return result;
    }

    private static BlockPos supportUnder(Vec3 position) {
        return BlockPos.containing(position.x, position.y - 0.16D, position.z);
    }

    private static void addUnique(List<BlockPos> positions, BlockPos pos) {
        BlockPos immutable = pos.immutable();
        if (!positions.contains(immutable)) {
            positions.add(immutable);
        }
    }

    private static boolean isCatBridgeSupport(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F || level.getBlockEntity(pos) != null || isProtectedBlock(state)) {
            return false;
        }
        if (!state.isFaceSturdy(level, pos, Direction.UP)) {
            return false;
        }
        int openDepth = 0;
        for (int i = 1; i <= 5; i++) {
            if (isOpenDrop(level, pos.below(i))) {
                openDepth++;
            }
        }
        if (openDepth < 2) {
            return false;
        }
        int openSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            if (isOpenDrop(level, side) || isOpenDrop(level, side.below())) {
                openSides++;
            }
        }
        return openSides >= 2 || openDepth >= 4;
    }

    private static boolean isOpenDrop(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || !state.getFluidState().isEmpty() || state.getCollisionShape(level, pos).isEmpty();
    }

    private static void handleDreamChase(ServerLevel level, ServerPlayer player, FriendEntity friend, double distance) {
        // Removed: Dream no longer has a monster chase.
        disappear(friend, "dream_chase_removed", false);
    }

    private static boolean hasCaughtPlayer(FriendEntity friend, Player player, double horizontalRange) {
        if (friend.getBoundingBox().inflate(0.85D, 0.5D, 0.85D).intersects(player.getBoundingBox())) {
            return true;
        }
        double dx = friend.getX() - player.getX();
        double dz = friend.getZ() - player.getZ();
        return dx * dx + dz * dz <= horizontalRange * horizontalRange && Math.abs(friend.getY() - player.getY()) <= 3.2D;
    }

    private static void handleDreamBedFriend(ServerLevel level, ServerPlayer player, FriendEntity friend, double distance) {
        // Removed: old sleep cutscene entity should never remain active.
        disappear(friend, "dream_bed_removed", false);
    }

    private static void chaseMove(ServerLevel level, FriendEntity friend, Player target, double speed, boolean breakBlocks, int breakLimit) {
        if (breakBlocks && breakLimit > 0 && target instanceof ServerPlayer player
                && breakVerticalEscapeBlocks(level, friend, player, Math.max(1, breakLimit))) {
            state(player).putString(LAST_PATH_TYPE, "vertical_escape_break");
            state(player).putBoolean(LAST_TRAP_HAZARD, false);
            friend.getNavigation().recomputePath();
            return;
        }
        FriendMovementController.ChaseResult movement = FriendMovementController.tickChase(level, friend, target, speed);
        recoverFloatingChaser(level, friend);
        if (target instanceof ServerPlayer player) {
            state(player).putString(LAST_PATH_TYPE, movement.pathType());
            state(player).putBoolean(LAST_TRAP_HAZARD, false);
        }
        if (!movement.needsIntervention()) {
            return;
        }

        Vec3 desired = target.position().subtract(friend.position()).multiply(1.0D, 0.0D, 1.0D);
        if (desired.lengthSqr() < 0.0001D) {
            return;
        }
        Vec3 forward = desired.normalize();
        if (target instanceof ServerPlayer player && tryTrapBlink(level, friend, player, forward)) {
            state(player).putString(LAST_PATH_TYPE, "trap_blink");
            state(player).putBoolean(LAST_TRAP_HAZARD, true);
            return;
        }
        if (breakBlocks && breakLimit > 0 && target instanceof ServerPlayer player
                && breakStrategicChaseBlock(level, friend, player, forward, breakLimit)) {
            state(player).putString(LAST_PATH_TYPE, "minimal_break");
            state(player).putBoolean(LAST_TRAP_HAZARD, false);
            return;
        }
        if (target instanceof ServerPlayer player) {
            if (friend.getPersistentData().getInt(FriendEntity.TAG_STUCK_TICKS) > 80 && controlledRelocate(level, friend, player)) {
                state(player).putString(LAST_PATH_TYPE, "recovery_relocate");
                return;
            }
            state(player).putString(LAST_PATH_TYPE, "blocked_recalculate");
        }
    }

    private static void ensurePhysicalChaser(FriendEntity friend, double speed) {
        friend.noPhysics = false;
        friend.setNoGravity(false);
        friend.setNoAi(false);
        friend.setMaxUpStep(1.18F);
        var attribute = friend.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null) {
            attribute.setBaseValue(Mth.clamp(speed, 0.20D, friend.rage() ? 0.54D : 0.46D));
        }
    }

    private static boolean updateStuckState(ServerLevel level, FriendEntity friend, Player target) {
        CompoundTag tag = friend.getPersistentData();
        if (friend.tickCount % 10 != 0) {
            return tag.getInt(FriendEntity.TAG_STUCK_TICKS) > 20;
        }
        Vec3 last = new Vec3(tag.getDouble(FriendEntity.TAG_LAST_PROGRESS_X),
                tag.getDouble(FriendEntity.TAG_LAST_PROGRESS_Y),
                tag.getDouble(FriendEntity.TAG_LAST_PROGRESS_Z));
        double moved = friend.position().distanceTo(last);
        double distance = friend.distanceTo(target);
        double lastDistance = tag.getDouble(FriendEntity.TAG_LAST_DISTANCE);
        boolean noProgress = moved < 0.18D && distance >= lastDistance - 0.35D && distance > 2.8D;
        int stuck = noProgress ? tag.getInt(FriendEntity.TAG_STUCK_TICKS) + 10 : Math.max(0, tag.getInt(FriendEntity.TAG_STUCK_TICKS) - 12);
        tag.putInt(FriendEntity.TAG_STUCK_TICKS, stuck);
        tag.putDouble(FriendEntity.TAG_LAST_PROGRESS_X, friend.getX());
        tag.putDouble(FriendEntity.TAG_LAST_PROGRESS_Y, friend.getY());
        tag.putDouble(FriendEntity.TAG_LAST_PROGRESS_Z, friend.getZ());
        tag.putDouble(FriendEntity.TAG_LAST_DISTANCE, distance);
        if (stuck > 20) {
            friend.getNavigation().recomputePath();
        }
        return stuck > 20;
    }

    private static void recoverFloatingChaser(ServerLevel level, FriendEntity friend) {
        CompoundTag tag = friend.getPersistentData();
        if (friend.onGround()) {
            tag.putInt(FriendEntity.TAG_AIRBORNE_TICKS, 0);
            return;
        }
        int airborne = tag.getInt(FriendEntity.TAG_AIRBORNE_TICKS) + 1;
        tag.putInt(FriendEntity.TAG_AIRBORNE_TICKS, airborne);
        friend.noPhysics = false;
        friend.setNoGravity(false);
        if (airborne > 14 && Math.abs(friend.getDeltaMovement().y) < 0.015D) {
            friend.setDeltaMovement(friend.getDeltaMovement().x, -0.16D, friend.getDeltaMovement().z);
        }
        if (airborne > 60) {
            BlockPos floor = findSafeFriendFloor(level, friend.blockPosition());
            if (floor != null) {
                friend.teleportTo(floor.getX() + 0.5D, floor.getY(), floor.getZ() + 0.5D);
                tag.putInt(FriendEntity.TAG_AIRBORNE_TICKS, 0);
            }
        }
    }

    private static boolean tryJumpObstacle(ServerLevel level, FriendEntity friend, Vec3 forward) {
        if (!friend.onGround()) {
            return false;
        }
        Vec3 front = friend.position().add(forward.normalize().scale(0.78D));
        BlockPos feet = BlockPos.containing(front.x, friend.getY() + 0.08D, front.z);
        BlockPos head = feet.above();
        if (!level.getBlockState(feet).isFaceSturdy(level, feet, Direction.UP)
                && !level.getBlockState(feet).isSolidRender(level, feet)) {
            return false;
        }
        if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                || !level.getBlockState(head.above()).getCollisionShape(level, head.above()).isEmpty()) {
            return false;
        }
        friend.getJumpControl().jump();
        friend.setDeltaMovement(friend.getDeltaMovement().x + forward.x * 0.08D, 0.44D, friend.getDeltaMovement().z + forward.z * 0.08D);
        return true;
    }

    private static boolean controlledRelocate(ServerLevel level, FriendEntity friend, ServerPlayer player) {
        Vec3 toFriend = friend.position().subtract(player.position()).multiply(1.0D, 0.0D, 1.0D);
        if (toFriend.lengthSqr() < 0.01D || hasLineOfSight(player, friend.position().add(0.0D, friend.getBbHeight() * 0.65D, 0.0D))) {
            return false;
        }
        Vec3 dir = toFriend.normalize();
        for (int i = 0; i < 12; i++) {
            Vec3 side = rotateY(dir, RANDOM.nextBoolean() ? 28.0D : -28.0D);
            BlockPos rough = BlockPos.containing(player.position().add(side.scale(10.0D + RANDOM.nextInt(10))));
            BlockPos floor = findSafeFriendFloor(level, rough);
            if (floor != null && !hasLineOfSight(player, floor.getCenter().add(0.0D, 2.1D, 0.0D))) {
                friend.teleportTo(floor.getX() + 0.5D, floor.getY(), floor.getZ() + 0.5D);
                friend.getPersistentData().putInt(FriendEntity.TAG_STUCK_TICKS, 0);
                friend.getNavigation().moveTo(player, 1.0D);
                return true;
            }
        }
        return false;
    }

    private static boolean tryStepMove(ServerLevel level, FriendEntity friend, Vec3 step) {
        if (hasSafeLanding(level, friend.position().add(step), 3) && !isHazardAhead(level, friend, step) && !isFrontBlocked(level, friend, step)) {
            friend.move(MoverType.SELF, step);
            return true;
        }
        if (canHopOnto(level, friend, step) && hasSafeLanding(level, friend.position().add(step).add(0.0D, 0.46D, 0.0D), 3)) {
            friend.move(MoverType.SELF, step.add(0.0D, 0.46D, 0.0D));
            return true;
        }
        return false;
    }

    private static boolean isFrontBlocked(ServerLevel level, FriendEntity friend, Vec3 step) {
        Vec3 front = friend.position().add(step.normalize().scale(0.82D));
        BlockPos feet = BlockPos.containing(front.x, friend.getY() + 0.15D, front.z);
        return level.getBlockState(feet).isSolidRender(level, feet) || level.getBlockState(feet.above()).isSolidRender(level, feet.above());
    }

    private static boolean canHopOnto(ServerLevel level, FriendEntity friend, Vec3 step) {
        Vec3 front = friend.position().add(step.normalize().scale(0.95D));
        BlockPos feet = BlockPos.containing(front.x, friend.getY() + 0.15D, front.z);
        BlockPos above = feet.above();
        return level.getBlockState(feet).isSolidRender(level, feet)
                && level.getBlockState(above).isAir()
                && level.getBlockState(above.above()).isAir();
    }

    private static boolean hasSafeLanding(ServerLevel level, Vec3 position, int maxDrop) {
        BlockPos feet = BlockPos.containing(position.x, position.y, position.z);
        for (int i = 0; i <= maxDrop; i++) {
            BlockPos floor = feet.below(i + 1);
            BlockState state = level.getBlockState(floor);
            if (state.is(Blocks.LAVA) || state.getFluidState().is(FluidTags.LAVA)) {
                return false;
            }
            if (state.isFaceSturdy(level, floor, Direction.UP)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHazardAhead(ServerLevel level, FriendEntity friend, Vec3 step) {
        Vec3 front = friend.position().add(step.normalize().scale(1.15D));
        BlockPos feet = BlockPos.containing(front.x, friend.getY(), front.z);
        BlockState here = level.getBlockState(feet);
        BlockState floor = level.getBlockState(feet.below());
        if (here.getBlock() instanceof TrapDoorBlock || floor.getBlock() instanceof TrapDoorBlock) {
            return true;
        }
        if (here.getFluidState().is(FluidTags.LAVA) || floor.getFluidState().is(FluidTags.LAVA)) {
            return true;
        }
        int drop = 0;
        for (int i = 1; i <= 6; i++) {
            if (isOpenDrop(level, feet.below(i))) {
                drop++;
            }
        }
        return drop >= 4;
    }

    private static boolean tryTrapBlink(ServerLevel level, FriendEntity friend, ServerPlayer player, Vec3 forward) {
        CompoundTag tag = friend.getPersistentData();
        long now = level.getGameTime();
        if (now < tag.getLong(FriendEntity.TAG_TRAP_BLINK_COOLDOWN) || !isHazardAhead(level, friend, forward)) {
            return false;
        }
        for (double distance = 2.5D; distance <= 7.0D; distance += 0.75D) {
            Vec3 candidate = friend.position().add(forward.scale(distance));
            BlockPos feet = BlockPos.containing(candidate.x, candidate.y, candidate.z);
            BlockPos floor = findFloor(level, feet);
            if (floor != null && hasSafeLanding(level, floor.getCenter(), 3)
                    && level.getBlockState(floor).isAir() && level.getBlockState(floor.above()).isAir()) {
                friend.teleportTo(floor.getX() + 0.5D, floor.getY(), floor.getZ() + 0.5D);
                friend.setRage(true);
                state(player).putBoolean(RAGE, true);
                tag.putLong(FriendEntity.TAG_TRAP_BLINK_COOLDOWN, now + seconds(30, 90));
                return true;
            }
        }
        tag.putLong(FriendEntity.TAG_TRAP_BLINK_COOLDOWN, now + seconds(8, 14));
        return false;
    }

    private static Vec3 rotateY(Vec3 vector, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(vector.x * cos - vector.z * sin, 0.0D, vector.x * sin + vector.z * cos);
    }

    private static boolean breakVerticalEscapeBlocks(ServerLevel level, FriendEntity friend, ServerPlayer player, int maxBreaks) {
        if (player.isCreative() || player.isSpectator()) {
            return false;
        }
        CompoundTag data = state(player);
        double vertical = player.getY() - friend.getY();
        double horizontalSqr = player.position().multiply(1.0D, 0.0D, 1.0D)
                .distanceToSqr(friend.position().multiply(1.0D, 0.0D, 1.0D));
        int limit = Math.max(1, Math.min(3, maxBreaks + (friend.rage() ? 1 : 0)));

        if (vertical > 2.35D && horizontalSqr < 12.0D * 12.0D) {
            int broken = 0;
            BlockPos playerFeet = player.blockPosition();
            Vec3 away = player.position().subtract(friend.position()).multiply(1.0D, 0.0D, 1.0D);
            Vec3 side = away.lengthSqr() > 0.001D ? new Vec3(-away.z, 0.0D, away.x).normalize() : new Vec3(1.0D, 0.0D, 0.0D);
            List<BlockPos> candidates = new ArrayList<>();
            addUnique(candidates, playerFeet.below());
            addUnique(candidates, playerFeet.below(2));
            addUnique(candidates, BlockPos.containing(player.position().add(side.scale(0.55D))).below());
            addUnique(candidates, BlockPos.containing(player.position().subtract(side.scale(0.55D))).below());
            for (BlockPos pos : candidates) {
                if (broken >= limit) {
                    break;
                }
                BlockState state = level.getBlockState(pos);
                if (!shouldBreakEscapeBlock(level, player, data, pos, state, true)) {
                    continue;
                }
                if (level.destroyBlock(pos.immutable(), true, friend)) {
                    broken++;
                    data.putString(LAST_BLOCK_BREAK_REASON, "tower_support");
                    level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                            8, 0.18D, 0.16D, 0.18D, 0.015D);
                    play(level, pos.immutable(), FriendSoundEvents.DISTANT_SCRAPE, 0.30F, 0.48F, 0.80F);
                }
            }
            return broken > 0;
        }

        if (vertical < -1.35D && horizontalSqr < 18.0D * 18.0D) {
            Vec3 from = friend.position().add(0.0D, 0.65D, 0.0D);
            Vec3 to = player.position().add(0.0D, 0.45D, 0.0D);
            Vec3 delta = to.subtract(from);
            int samples = Math.max(4, Math.min(12, Mth.ceil(delta.length() * 1.2D)));
            int broken = 0;
            for (int i = 1; i <= samples && broken < limit; i++) {
                Vec3 p = from.add(delta.scale(i / (double) samples));
                BlockPos center = BlockPos.containing(p);
                for (BlockPos pos : new BlockPos[]{center, center.above(), center.below()}) {
                    if (broken >= limit) {
                        break;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!shouldBreakEscapeBlock(level, player, data, pos, state, false)) {
                        continue;
                    }
                    if (level.destroyBlock(pos.immutable(), true, friend)) {
                        broken++;
                        data.putString(LAST_BLOCK_BREAK_REASON, "digging_toward_player");
                        level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                                10, 0.22D, 0.18D, 0.22D, 0.02D);
                        play(level, pos.immutable(), FriendSoundEvents.DISTANT_SCRAPE, 0.32F, 0.44F, 0.78F);
                    }
                }
            }
            return broken > 0;
        }
        return false;
    }

    private static boolean shouldBreakEscapeBlock(ServerLevel level, ServerPlayer player, CompoundTag data, BlockPos pos, BlockState state, boolean towerSupport) {
        if (!level.hasChunkAt(pos) || state.isAir() || state.getCollisionShape(level, pos).isEmpty()
                || isProtectedBlock(state) || state.getDestroySpeed(level, pos) < 0.0F || level.getBlockEntity(pos) != null) {
            return false;
        }
        if (state.is(BlockTags.DOORS) || state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock) {
            return false;
        }
        boolean playerPlaced = isPlayerPlacedNear(data, pos, towerSupport ? 1 : 2);
        if (playerPlaced) {
            return true;
        }
        // In release survival, do not shred huge structures. Break only soft/common blocks when the player abuses vertical escape.
        float hardness = state.getDestroySpeed(level, pos);
        if (towerSupport) {
            return hardness <= 5.0F && pos.distSqr(player.blockPosition()) <= 7.0D;
        }
        return hardness <= 3.5F && pos.distSqr(player.blockPosition()) <= 18.0D * 18.0D;
    }

    private static boolean breakStrategicChaseBlock(ServerLevel level, FriendEntity friend, ServerPlayer player, Vec3 step, int maxBreaks) {
        if (player.isCreative() || player.isSpectator()) {
            return false;
        }
        BlockPos center = BlockPos.containing(friend.position().add(step.normalize().scale(1.25D))).above();
        int broken = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(0, -1, 0), center.offset(0, 1, 0))) {
            if (broken >= maxBreaks) {
                return true;
            }
            BlockState state = level.getBlockState(pos);
            if (!isStrategicChaseBreak(level, player, friend, pos, state)) {
                continue;
            }
            if (level.destroyBlock(pos.immutable(), true, friend)) {
                broken++;
                state(player).putString(LAST_BLOCK_BREAK_REASON, chaseBreakReason(level, player, pos));
                play(level, pos.immutable(), FriendSoundEvents.DISTANT_SCRAPE, 0.25F, 0.55F, 0.85F);
            }
        }
        return broken > 0;
    }

    private static boolean isStrategicChaseBreak(ServerLevel level, ServerPlayer player, FriendEntity friend, BlockPos pos, BlockState state) {
        if (state.isAir() || isProtectedBlock(state) || state.getDestroySpeed(level, pos) < 0.0F || level.getBlockEntity(pos) != null) {
            return false;
        }
        if (state.is(BlockTags.DOORS) || state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock) {
            return false;
        }
        if (pos.distSqr(player.blockPosition()) > 9.0D * 9.0D && pos.distSqr(friend.blockPosition()) > 4.0D * 4.0D) {
            return false;
        }
        boolean thinWall = isFrontBlocked(level, friend, player.position().subtract(friend.position()).multiply(1.0D, 0.0D, 1.0D));
        boolean pillar = Math.abs(player.getY() - friend.getY()) > 2.2D && pos.getY() < player.getY();
        boolean bridge = isCatBridgeSupport(level, pos);
        return thinWall || pillar || bridge;
    }

    private static String chaseBreakReason(ServerLevel level, ServerPlayer player, BlockPos pos) {
        if (isCatBridgeSupport(level, pos)) {
            return "bridge_support";
        }
        if (pos.getY() < player.getY() - 1) {
            return "pillar_base";
        }
        return "thin_wall";
    }

    private static void freeSelfFromBlocks(ServerLevel level, FriendEntity friend, int maxBreaks) {
        int broken = 0;
        BlockPos base = friend.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-1, 0, -1), base.offset(1, 3, 1))) {
            if (broken >= maxBreaks) {
                break;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getCollisionShape(level, pos).isEmpty() || isProtectedBlock(state)
                    || state.getDestroySpeed(level, pos) < 0.0F || level.getBlockEntity(pos) != null) {
                continue;
            }
            if (level.destroyBlock(pos.immutable(), true, friend)) {
                broken++;
                play(level, pos.immutable(), FriendSoundEvents.DISTANT_SCRAPE, 0.22F, 0.50F, 0.78F);
            }
        }
        if (broken > 0) {
            friend.setDeltaMovement(friend.getDeltaMovement().add(0.0D, 0.34D, 0.0D));
        }
    }

    private static boolean isEntityPinned(ServerLevel level, FriendEntity friend) {
        BlockPos base = friend.blockPosition();
        return !level.getBlockState(base).getCollisionShape(level, base).isEmpty()
                || !level.getBlockState(base.above()).getCollisionShape(level, base.above()).isEmpty();
    }

    private static boolean isProtectedBlock(BlockState state) {
        return state.is(Blocks.BEDROCK)
                || state.is(Blocks.BARRIER)
                || state.is(Blocks.COMMAND_BLOCK)
                || state.is(Blocks.CHAIN_COMMAND_BLOCK)
                || state.is(Blocks.REPEATING_COMMAND_BLOCK)
                || state.is(Blocks.STRUCTURE_BLOCK)
                || state.is(Blocks.JIGSAW)
                || state.is(Blocks.REINFORCED_DEEPSLATE)
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.END_PORTAL_FRAME)
                || state.is(Blocks.NETHER_PORTAL);
    }

    private static void playSoundOnly(ServerLevel level, ServerPlayer player, String event, CompoundTag data) {
        if (level.getGameTime() < data.getLong(SOUND_COOLDOWN)) {
            return;
        }
        BlockPos pos = offsetAround(player.blockPosition(), 16, 42);
        switch (event) {
            case "distant_scrape" -> play(level, pos, FriendSoundEvents.DISTANT_STONE_SCRAPE, 0.24F, 0.85F, 1.12F);
            case "distant_knock" -> play(level, pos, FriendSoundEvents.DISTANT_METAL_HIT, 0.20F, 0.75F, 1.05F);
            case "breathing_behind" -> play(level, behind(player, 3), FriendSoundEvents.QUIET_BREATH, 0.10F, 0.90F, 1.08F);
            case "mimic_footsteps" -> {
                BlockPos route = rememberedRouteCandidate(player, data).orElse(behind(player, 7 + RANDOM.nextInt(8)));
                play(level, route, RANDOM.nextBoolean() ? FriendSoundEvents.CREAK_STEP : FriendSoundEvents.JOINT_CRACK, 0.20F, 0.56F, 0.84F);
            }
            case "wall_scurry" -> {
                BlockPos sidePos = side(player, 6 + RANDOM.nextInt(8));
                play(level, sidePos, RANDOM.nextBoolean() ? FriendSoundEvents.TUNNEL_STEP_1 : FriendSoundEvents.TUNNEL_STEP_2, 0.18F, 0.62F, 0.86F);
                play(level, sidePos, FriendSoundEvents.JOINT_CRACK, 0.10F, 0.72F, 0.96F);
            }
            case "sprint_behind_wall" -> {
                BlockPos runStart = side(player, 7 + RANDOM.nextInt(10));
                BlockPos runEnd = behind(player, 10 + RANDOM.nextInt(8));
                play(level, runStart, FriendSoundEvents.TUNNEL_STEP_1, 0.25F, 0.54F, 0.76F);
                play(level, runEnd, FriendSoundEvents.TUNNEL_STEP_2, 0.26F, 0.50F, 0.72F);
                play(level, runEnd, FriendSoundEvents.CREAK_STEP, 0.20F, 0.58F, 0.82F);
            }
            case "learned_footsteps" -> {
                BlockPos route = rememberedRouteCandidate(player, data).orElse(behind(player, 8 + RANDOM.nextInt(9)));
                play(level, route, RANDOM.nextBoolean() ? FriendSoundEvents.TUNNEL_STEP_1 : FriendSoundEvents.TUNNEL_STEP_2, 0.30F, 0.86F, 1.05F);
                play(level, route.offset(RANDOM.nextInt(5) - 2, 0, RANDOM.nextInt(5) - 2), FriendSoundEvents.CREAK_STEP, 0.22F, 0.82F, 1.0F);
            }
            case "learned_door" -> {
                BlockPos door = rememberedBlock(data, MEMORY_DOORS, player.blockPosition(), 42)
                        .orElseGet(() -> offsetAround(player.blockPosition(), 8, 20));
                play(level, door, FriendSoundEvents.DOOR_OPEN_CLOSE, 0.42F, 0.86F, 1.03F);
            }
            case "learned_chest" -> {
                BlockPos chest = rememberedBlock(data, MEMORY_CONTAINERS, player.blockPosition(), 42)
                        .orElseGet(() -> offsetAround(player.blockPosition(), 7, 18));
                play(level, chest, FriendSoundEvents.CHEST_OPEN, 0.40F, 0.86F, 1.02F);
            }
            case "door_open_far" -> play(level, rememberedBlock(data, MEMORY_DOORS, player.blockPosition(), 42)
                    .orElseGet(() -> offsetAround(player.blockPosition(), 8, 20)), FriendSoundEvents.DOOR_OPEN_CLOSE, 0.38F, 0.88F, 1.04F);
            case "chest_open_far" -> {
                BlockPos chestPos = rememberedBlock(data, MEMORY_CONTAINERS, player.blockPosition(), 42)
                        .orElseGet(() -> offsetAround(player.blockPosition(), 7, 18));
                play(level, chestPos, FriendSoundEvents.CHEST_OPEN, 0.36F, 0.88F, 1.02F);
            }
            case "window_knock" -> playWindowKnock(level, player, data);
            case "dream_hint" -> play(level, pos, FriendSoundEvents.DREAM_AMBIENT, 0.10F, 0.72F, 0.92F);
            default -> play(level, pos, RANDOM.nextBoolean() ? FriendSoundEvents.TUNNEL_STEP_1 : FriendSoundEvents.TUNNEL_STEP_2, 0.16F, 0.80F, 1.10F);
        }
        data.putLong(SOUND_COOLDOWN, level.getGameTime() + (data.getBoolean(CAT_CURSE) ? seconds(28, 70) : seconds(95, 240)));
    }

    private static void playWindowKnock(ServerLevel level, ServerPlayer player, CompoundTag data) {
        Optional<WindowSpot> spot = findBestWindowSpot(level, player, data, 22);
        Optional<BlockPos> glass = spot.map(WindowSpot::glass).or(() -> findNearestGlass(level, player.blockPosition(), 16));
        BlockPos pos = glass.orElse(player.blockPosition());
        glass.ifPresent(value -> rememberBlockMemory(data, MEMORY_WINDOWS, value, level.getGameTime()));
        play(level, pos, RANDOM.nextBoolean() ? FriendSoundEvents.WINDOW_KNOCK_1 : FriendSoundEvents.WINDOW_KNOCK_2, 0.32F, 0.88F, 1.08F);
        data.putLong(SOUND_COOLDOWN, level.getGameTime() + (data.getBoolean(CAT_CURSE) ? seconds(24, 60) : seconds(110, 260)));
    }

    private static boolean falseSafeEvent(ServerLevel level, ServerPlayer player, CompoundTag data) {
        if (data.getBoolean(HAS_HOME) && homeTorchRingScore(level, data, player.blockPosition()) >= 12) {
            if (windowHandprint(level, player)) {
                return true;
            }
            playWindowKnock(level, player, data);
            return true;
        }
        int roll = RANDOM.nextInt(4);
        if (roll == 0 && windowHandprint(level, player)) return true;
        if (roll == 1) {
            doorCreak(level, player, data);
            play(level, player.blockPosition(), FriendSoundEvents.LOW_BREATH, 0.09F, 0.62F, 0.86F);
            return true;
        }
        if (roll == 2 && inventoryDisturbance(level, player, data)) return true;
        return true;
    }

    private static boolean wrongDetailEvent(ServerLevel level, ServerPlayer player, CompoundTag data) {
        int roll = RANDOM.nextInt(5);
        if (roll == 0 && windowHandprint(level, player)) {
            return true;
        }
        if (roll == 1 && inventoryDisturbance(level, player, data)) {
            return true;
        }
        if (roll == 2) {
            doorCreak(level, player, data);
            return true;
        }
        List<BlockPos> lights = findBlocks(level, player.blockPosition(), 16, FriendDirector::isLightBlock);
        if (!lights.isEmpty()) {
            BlockPos pos = lights.get(RANDOM.nextInt(lights.size()));
            level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 0.42D, pos.getZ() + 0.5D,
                    7, 0.05D, 0.06D, 0.05D, 0.001D);
            play(level, pos, FriendSoundEvents.DISAPPEAR_SOFT, 0.055F, 0.62F, 0.84F);
            return true;
        }
        Optional<BlockPos> remembered = rememberedBlock(data, MEMORY_CONTAINERS, player.blockPosition(), 24)
                .or(() -> rememberedBlock(data, MEMORY_DOORS, player.blockPosition(), 24))
                .or(() -> rememberedBlock(data, MEMORY_WINDOWS, player.blockPosition(), 24));
        if (remembered.isPresent()) {
            BlockPos pos = remembered.get();
            level.sendParticles(ParticleTypes.SOUL, pos.getX() + 0.5D, pos.getY() + 0.65D, pos.getZ() + 0.5D,
                    3, 0.10D, 0.10D, 0.10D, 0.0D);
            return true;
        }
        return falseSafeEvent(level, player, data);
    }

    private static boolean windowHandprint(ServerLevel level, ServerPlayer player) {
        CompoundTag data = state(player);
        Optional<WindowSpot> spot = findBestWindowSpot(level, player, data, 22);
        Optional<BlockPos> glass = spot.map(WindowSpot::glass).or(() -> findNearestGlass(level, player.blockPosition(), 18));
        if (glass.isEmpty()) {
            return false;
        }
        BlockPos pos = glass.get();
        rememberBlockMemory(data, MEMORY_WINDOWS, pos, level.getGameTime());
        level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 0.58D, pos.getZ() + 0.5D,
                18, 0.23D, 0.27D, 0.035D, 0.001D);
        level.sendParticles(ParticleTypes.SOUL, pos.getX() + 0.5D, pos.getY() + 0.52D, pos.getZ() + 0.5D,
                6, 0.11D, 0.14D, 0.02D, 0.0D);
        play(level, pos, FriendSoundEvents.WINDOW_PRESS, 0.22F, 0.78F, 0.95F);
        return true;
    }

    private static boolean inventoryDisturbance(ServerLevel level, ServerPlayer player, CompoundTag data) {
        List<BlockPos> containers = findBlocks(level, player.blockPosition(), 14, state ->
                state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock);
        Collections.shuffle(containers, RANDOM);
        for (BlockPos pos : containers) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (!(entity instanceof Container container) || container.getContainerSize() < 2) {
                continue;
            }
            int first = -1;
            int second = -1;
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (!container.getItem(i).isEmpty()) {
                    if (first == -1) first = i;
                    else {
                        second = i;
                        break;
                    }
                }
            }
            if (first == -1) {
                continue;
            }
            if (second == -1) {
                second = RANDOM.nextInt(container.getContainerSize());
                if (second == first) second = (second + 1) % container.getContainerSize();
            }
            ItemStack a = container.getItem(first).copy();
            ItemStack b = container.getItem(second).copy();
            container.setItem(first, b);
            container.setItem(second, a);
            entity.setChanged();
            play(level, pos, FriendSoundEvents.WOOD_CREAK, 0.18F, 0.72F, 1.02F);
            return true;
        }
        playSoundOnly(level, player, "mimic_footsteps", data);
        return false;
    }

    private static boolean torchLine(ServerLevel level, ServerPlayer player, CompoundTag data) {
        List<BlockPos> torches = findBlocks(level, player.blockPosition(), 20,
                state -> state.getBlock() instanceof TorchBlock || state.getBlock() instanceof WallTorchBlock);
        if (torches.size() < 6) {
            return false;
        }
        torches.sort(Comparator.comparingDouble(pos -> -pos.distSqr(player.blockPosition())));
        int removed = 0;
        for (BlockPos pos : torches) {
            if (removed >= 4) break;
            if (RANDOM.nextFloat() < 0.75F) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                        5, 0.08D, 0.08D, 0.08D, 0.001D);
                removed++;
            }
        }
        if (removed > 0) {
            play(level, torches.get(0), FriendSoundEvents.DISAPPEAR_SOFT, 0.13F, 0.65F, 0.95F);
            return true;
        }
        playSoundOnly(level, player, "fake_footsteps", data);
        return false;
    }

    private static void doorCreak(ServerLevel level, ServerPlayer player, CompoundTag data) {
        List<BlockPos> doors = findBlocks(level, player.blockPosition(), 12, state -> state.getBlock() instanceof DoorBlock);
        if (doors.isEmpty()) {
            playSoundOnly(level, player, "fake_footsteps", data);
            return;
        }
        BlockPos pos = doors.get(RANDOM.nextInt(doors.size()));
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(DoorBlock.OPEN) && state.hasProperty(DoorBlock.HALF) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
            level.setBlock(pos, state.setValue(DoorBlock.OPEN, !state.getValue(DoorBlock.OPEN)), 10);
            play(level, pos, FriendSoundEvents.WOOD_CREAK, 0.26F, 0.85F, 1.15F);
        }
    }

    private static void torchRemoved(ServerLevel level, ServerPlayer player, CompoundTag data) {
        Optional<BlockPos> behind = findTorchBehindPlayer(level, player, 20);
        if (behind.isPresent()) {
            extinguishTorch(level, behind.get(), 0.12F);
            return;
        }
        List<BlockPos> torches = findBlocks(level, player.blockPosition(), 14, FriendDirector::isTorchLike);
        if (torches.size() < 2) {
            playSoundOnly(level, player, "fake_footsteps", data);
            return;
        }
        torches.sort(Comparator.comparingDouble(pos -> pos.distSqr(player.blockPosition())));
        BlockPos pos = torches.get(Math.min(torches.size() - 1, 1 + RANDOM.nextInt(Math.max(1, torches.size() - 1))));
        extinguishTorch(level, pos, 0.09F);
    }

    private static void maybeExtinguishTorchBehind(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        int phase = data.getInt(PHASE);
        if (phase < 2 || data.getBoolean(CAT_CURSE) || data.getBoolean(PUNISHMENT_ACTIVE) || time < data.getLong(TORCH_BACK_COOLDOWN)) {
            return;
        }
        boolean underground = isPlayerInMineContext(level, player);
        boolean night = !level.isDay();
        boolean nearHome = data.getBoolean(HAS_HOME)
                && player.blockPosition().distSqr(new BlockPos(data.getInt(HOME_X), data.getInt(HOME_Y), data.getInt(HOME_Z))) < 48.0D * 48.0D;
        if (!underground && !night && !nearHome) {
            data.putLong(TORCH_BACK_COOLDOWN, time + seconds(45, 90));
            return;
        }
        float chance = underground ? 0.34F : night ? 0.22F : 0.16F;
        if (phase >= 5) chance += 0.10F;
        if (RANDOM.nextFloat() > chance) {
            data.putLong(TORCH_BACK_COOLDOWN, time + seconds(28, 65));
            return;
        }
        Optional<BlockPos> torch = findTorchBehindPlayer(level, player, underground ? 24 : 18);
        if (torch.isPresent()) {
            extinguishTorch(level, torch.get(), 0.11F);
            data.putLong(TORCH_BACK_COOLDOWN, time + seconds(35, 90));
        } else {
            data.putLong(TORCH_BACK_COOLDOWN, time + seconds(30, 70));
        }
    }

    private static Optional<BlockPos> findTorchBehindPlayer(ServerLevel level, ServerPlayer player, int radius) {
        List<BlockPos> torches = findBlocks(level, player.blockPosition(), radius, FriendDirector::isTorchLike);
        if (torches.isEmpty()) {
            return Optional.empty();
        }
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        if (look.lengthSqr() < 0.001D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        Vec3 back = look.normalize().scale(-1.0D);
        Vec3 eye = player.getEyePosition();
        return torches.stream()
                .filter(pos -> {
                    Vec3 toTorch = pos.getCenter().subtract(player.position()).multiply(1.0D, 0.0D, 1.0D);
                    if (toTorch.lengthSqr() < 1.0D) return false;
                    double dot = toTorch.normalize().dot(back);
                    return dot > 0.28D && player.blockPosition().distSqr(pos) > 9.0D;
                })
                .sorted(Comparator.comparingDouble((BlockPos pos) -> {
                    Vec3 toTorch = pos.getCenter().subtract(player.position()).multiply(1.0D, 0.0D, 1.0D);
                    double dot = toTorch.lengthSqr() < 0.001D ? 0.0D : toTorch.normalize().dot(back);
                    return -dot * 1000.0D + eye.distanceTo(pos.getCenter());
                }))
                .findFirst();
    }

    private static boolean isTorchLike(BlockState state) {
        return isRemovableLightSource(state);
    }

    private static void extinguishTorch(ServerLevel level, BlockPos pos, float volume) {
        if (!isRemovableLightSource(level.getBlockState(pos))) {
            return;
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                5, 0.08D, 0.08D, 0.08D, 0.001D);
        play(level, pos, FriendSoundEvents.DISAPPEAR_SOFT, volume, 0.75F, 1.12F);
    }


    private static void drainChaseLights(ServerLevel level, FriendEntity friend, int radius) {
        CompoundTag tag = friend.getPersistentData();
        long now = level.getGameTime();
        if (now < tag.getLong(LAST_LIGHT_DRAIN)) {
            return;
        }
        tag.putLong(LAST_LIGHT_DRAIN, now + 8L);

        BlockPos center = friend.blockPosition();
        int removed = 0;
        int radiusSq = radius * radius;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -3, -radius), center.offset(radius, 4, radius))) {
            if (removed >= 18) {
                break;
            }
            if (center.distSqr(pos) > radiusSq || !level.hasChunkAt(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!isRemovableLightSource(state)) {
                continue;
            }
            level.setBlock(pos.immutable(), Blocks.AIR.defaultBlockState(), 3);
            if (removed < 7) {
                level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                        5, 0.08D, 0.08D, 0.08D, 0.001D);
            }
            removed++;
        }
        if (removed > 0) {
            play(level, center, FriendSoundEvents.DISAPPEAR_SOFT, 0.18F, 0.62F, 0.88F);
        }
    }

    private static void scareMobsAroundFriend(ServerLevel level, FriendEntity friend, double radius) {
        if (friend.tickCount % 10 != 0) {
            return;
        }
        AABB box = friend.getBoundingBox().inflate(radius);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, box, entity ->
                entity.isAlive()
                        && entity != friend
                        && !(entity instanceof Player)
                        && !(entity instanceof Cat)
                        && !(entity instanceof FriendEntity));
        for (LivingEntity entity : entities) {
            Vec3 away = entity.position().subtract(friend.position()).multiply(1.0D, 0.0D, 1.0D);
            if (away.lengthSqr() < 0.001D) {
                away = new Vec3(RANDOM.nextDouble() - 0.5D, 0.0D, RANDOM.nextDouble() - 0.5D);
            }
            away = away.normalize();
            if (entity instanceof PathfinderMob mob) {
                mob.setTarget(null);
                mob.setLastHurtByMob(null);
                mob.getNavigation().moveTo(entity.getX() + away.x * 18.0D, entity.getY(), entity.getZ() + away.z * 18.0D, 1.45D);
            }
            entity.setDeltaMovement(entity.getDeltaMovement().add(away.x * 0.12D, 0.02D, away.z * 0.12D));
            entity.hurtMarked = true;
        }
    }

    private static void tickDogFearEvent(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        if (data.getBoolean(CAT_CURSE) || data.getBoolean(PUNISHMENT_ACTIVE) || !level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        if (data.getBoolean(DOG_FEAR_ACTIVE)) {
            tickActiveDogFear(level, player, data, time);
            return;
        }
        if (time < data.getLong(DOG_FEAR_COOLDOWN)) {
            return;
        }
        List<Wolf> wolves = level.getEntitiesOfClass(Wolf.class, player.getBoundingBox().inflate(34.0D), wolf ->
                wolf.isAlive() && wolf.isTame() && wolf.isOwnedBy(player) && !wolf.isBaby());
        if (wolves.isEmpty()) {
            data.putLong(DOG_FEAR_COOLDOWN, time + seconds(90, 180));
            return;
        }
        int phase = Math.max(1, data.getInt(PHASE));
        float chance = switch (Math.min(phase, MAX_RELEASE_PHASE)) {
            case 1 -> 0.025F;
            case 2 -> 0.045F;
            case 3 -> 0.070F;
            default -> 0.095F;
        };
        if (RANDOM.nextFloat() > chance) {
            data.putLong(DOG_FEAR_COOLDOWN, time + seconds(45, 105));
            return;
        }
        Wolf wolf = wolves.get(RANDOM.nextInt(wolves.size()));
        CompoundTag wolfTag = wolf.getPersistentData();
        wolfTag.putUUID(DOG_FEAR_OWNER, player.getUUID());
        wolfTag.putLong(DOG_FEAR_START, time);
        wolfTag.putInt(DOG_FEAR_STAGE, 0);
        data.putBoolean(DOG_FEAR_ACTIVE, true);
        data.putUUID(DOG_FEAR_WOLF_ID, wolf.getUUID());
        data.putLong(DOG_FEAR_COOLDOWN, time + seconds(360, 620));
    }

    private static void tickActiveDogFear(ServerLevel level, ServerPlayer player, CompoundTag data, long time) {
        UUID id = data.hasUUID(DOG_FEAR_WOLF_ID) ? data.getUUID(DOG_FEAR_WOLF_ID) : null;
        if (id == null) {
            data.putBoolean(DOG_FEAR_ACTIVE, false);
            return;
        }
        Wolf wolf = null;
        for (Wolf candidate : level.getEntitiesOfClass(Wolf.class, player.getBoundingBox().inflate(80.0D), w -> w.getUUID().equals(id))) {
            wolf = candidate;
            break;
        }
        if (wolf == null || !wolf.isAlive()) {
            data.putBoolean(DOG_FEAR_ACTIVE, false);
            return;
        }
        CompoundTag wolfTag = wolf.getPersistentData();
        long start = wolfTag.getLong(DOG_FEAR_START);
        int age = (int) (time - start);
        if (age < 0 || age > 20 * 22) {
            clearDogFear(data, wolf);
            return;
        }

        wolf.setTarget(null);
        wolf.setLastHurtByMob(null);
        wolf.setOrderedToSit(false);
        wolf.getLookControl().setLookAt(player, 90.0F, 90.0F);

        if (age < 20 * 5) {
            wolf.getNavigation().stop();
            wolf.setDeltaMovement(wolf.getDeltaMovement().multiply(0.18D, 1.0D, 0.18D));
            faceEntity(wolf, player);
            if (age % 12 == 0) {
                level.sendParticles(ParticleTypes.ASH, wolf.getX(), wolf.getY() + 0.65D, wolf.getZ(), 1, 0.05D, 0.05D, 0.05D, 0.001D);
            }
            return;
        }

        int shake = age - 20 * 5;
        if (shake < 20 * 4) {
            wolf.getNavigation().stop();
            faceEntity(wolf, player);
            float wobble = (shake % 4 < 2 ? 1.0F : -1.0F) * (18.0F + RANDOM.nextFloat() * 10.0F);
            wolf.setYHeadRot(wolf.getYHeadRot() + wobble);
            wolf.setYRot(wolf.getYRot() - wobble * 0.35F);
            if (shake % 6 == 0) {
                play(level, wolf.blockPosition(), FriendSoundEvents.JOINT_CRACK, 0.10F, 1.2F, 1.55F);
            }
            return;
        }

        Vec3 away = wolf.position().subtract(player.position()).multiply(1.0D, 0.0D, 1.0D);
        if (away.lengthSqr() < 0.001D) {
            away = player.getLookAngle().multiply(-1.0D, 0.0D, -1.0D);
        }
        away = away.normalize();
        wolf.getNavigation().moveTo(wolf.getX() + away.x * 22.0D, wolf.getY(), wolf.getZ() + away.z * 22.0D, 1.45D);
        wolf.setDeltaMovement(wolf.getDeltaMovement().add(away.x * 0.08D, 0.0D, away.z * 0.08D));

        if (player.distanceTo(wolf) > 32.0F || !hasLineOfSight(player, wolf.getEyePosition())) {
            clearDogFear(data, wolf);
        }
    }

    private static void clearDogFear(CompoundTag data, Wolf wolf) {
        data.putBoolean(DOG_FEAR_ACTIVE, false);
        data.remove(DOG_FEAR_WOLF_ID);
        CompoundTag wolfTag = wolf.getPersistentData();
        wolfTag.remove(DOG_FEAR_OWNER);
        wolfTag.remove(DOG_FEAR_START);
        wolfTag.remove(DOG_FEAR_STAGE);
    }

    private static void faceEntity(LivingEntity entity, LivingEntity target) {
        Vec3 direction = target.position().subtract(entity.position()).multiply(1.0D, 0.0D, 1.0D);
        if (direction.lengthSqr() < 0.001D) {
            return;
        }
        float yaw = (float) (Mth.atan2(direction.z, direction.x) * (180F / Math.PI)) - 90.0F;
        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
    }

    private static Optional<BlockPos> findPosition(ServerLevel level, ServerPlayer player, String event, int phase) {
        if (event.equals("corner_peek") || (event.equals("attack_emerge") || event.equals("false_attack"))) {
            return findCornerCoverPosition(level, player, event, phase);
        }
        for (int i = 0; i < 56; i++) {
            BlockPos candidate = switch (event) {
                case "behind_player_presence", "breathing_behind" -> behind(player, 10 + RANDOM.nextInt(13));
                case "tunnel_silhouette", "tunnel_blocking_silhouette", "stare_event" -> ahead(player, 12 + RANDOM.nextInt(24));
                case "side_tunnel_watch", "corner_peek", "attack_emerge", "vanished_when_approached" -> side(player, 10 + RANDOM.nextInt(20));
                case "final_close_presence" -> behind(player, 3 + RANDOM.nextInt(3));
                case "home_observation" -> offsetAround(player.blockPosition(), 10, 22);
                case "route_ambush" -> rememberedRouteCandidate(player, state(player)).orElse(offsetAround(player.blockPosition(), 12, 35));
                case "bedside_watch", "doorway_lean" -> offsetAround(player.blockPosition(), 5, 14);
                default -> rememberedRouteCandidate(player, state(player)).orElse(offsetAround(player.blockPosition(), 12, 35));
            };
            BlockPos floor = findFloor(level, candidate);
            if (floor != null && isValid(level, player, floor, event, phase)) {
                return Optional.of(floor);
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> findCornerCoverPosition(ServerLevel level, ServerPlayer player, String event, int phase) {
        Optional<PeekSpot> peek = findCornerPeekSpot(level, player, state(player), phase);
        if (peek.isPresent()) {
            return Optional.of(peek.get().feet());
        }
        if (event.equals("corner_peek")) {
            return Optional.empty();
        }
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize();
        Vec3 side = new Vec3(-look.z, 0, look.x).normalize();
        for (int i = 0; i < 96; i++) {
            double lateral = (RANDOM.nextBoolean() ? 1.0D : -1.0D) * (7.0D + RANDOM.nextInt(12));
            double forward = -2.0D + RANDOM.nextInt(18);
            BlockPos rough = BlockPos.containing(player.position().add(side.scale(lateral)).add(look.scale(forward)));
            BlockPos feet = findFloor(level, rough);
            if (feet == null || !isValid(level, player, feet, event, phase)) {
                continue;
            }
            Direction coverDir = coverDirection(level, feet);
            if (coverDir == null) {
                continue;
            }
            Vec3 coverCenter = feet.relative(coverDir).getCenter();
            Vec3 toPlayer = player.position().subtract(coverCenter).multiply(1.0D, 0.0D, 1.0D).normalize();
            Vec3 coverNormal = new Vec3(coverDir.getStepX(), 0.0D, coverDir.getStepZ()).normalize();
            boolean bodyHidden = !hasLineOfSight(player, feet.getCenter().add(0.0D, 1.1D, 0.0D));
            boolean headCanPeek = hasLineOfSight(player, feet.getCenter().add(0.0D, 2.35D, 0.0D));
            if (coverNormal.dot(toPlayer) > 0.35D && ((event.equals("attack_emerge") || event.equals("false_attack")) || bodyHidden || headCanPeek)) {
                return Optional.of(feet);
            }
        }
        return Optional.empty();
    }

    private static Optional<PeekSpot> findCornerPeekSpot(ServerLevel level, ServerPlayer player, CompoundTag data, int phase) {
        return findCornerPeekSpot(level, player, data, phase, choosePeekMode(data));
    }

    private static Optional<PeekSpot> findCornerPeekSpot(ServerLevel level, ServerPlayer player, CompoundTag data, int phase, String mode) {
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        if (look.lengthSqr() < 0.001D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        look = look.normalize();
        Vec3 side = new Vec3(-look.z, 0.0D, look.x).normalize();
        int attempts = mode.equals("guaranteed") ? 240 : mode.equals("turnaround") ? 190 : 220;
        for (int i = 0; i < attempts; i++) {
            double forward;
            double lateral;
            if (mode.equals("turnaround")) {
                // Not in the current view. Place him behind or far to the side and give the player a chance to turn.
                boolean behind = RANDOM.nextFloat() < 0.62F;
                forward = behind ? -(7.0D + RANDOM.nextInt(15)) : (1.0D + RANDOM.nextInt(10));
                lateral = (RANDOM.nextBoolean() ? 1.0D : -1.0D) * (behind ? RANDOM.nextInt(8) : (10.0D + RANDOM.nextInt(10)));
            } else if (mode.equals("bold")) {
                // After missed peeks he gets less careful: closer, more central, and less perfectly hidden.
                forward = 5.0D + RANDOM.nextInt(18);
                lateral = (RANDOM.nextBoolean() ? 1.0D : -1.0D) * RANDOM.nextInt(8);
            } else {
                // Guaranteed readability: front arc, not too far, not too peripheral.
                forward = 9.0D + RANDOM.nextInt(18);
                lateral = (RANDOM.nextBoolean() ? 1.0D : -1.0D) * (2.0D + RANDOM.nextInt(6));
            }

            BlockPos rough = BlockPos.containing(player.position().add(look.scale(forward)).add(side.scale(lateral)));
            BlockPos feet = findFloor(level, rough);
            if (feet == null || !isVisualPeekValid(level, player, feet, "corner_peek", phase)) {
                continue;
            }
            Direction coverDir = coverDirection(level, feet);
            if (coverDir == null) {
                continue;
            }
            BlockPos cover = feet.relative(coverDir);
            if (rememberedBlock(data, MEMORY_PEEK_COVERS, cover, 3).isPresent() && RANDOM.nextFloat() < 0.82F) {
                continue;
            }
            Vec3 coverNormal = new Vec3(coverDir.getStepX(), 0.0D, coverDir.getStepZ()).normalize();
            Vec3 toPlayer = player.position().subtract(cover.getCenter()).multiply(1.0D, 0.0D, 1.0D);
            if (toPlayer.lengthSqr() < 0.001D || coverNormal.dot(toPlayer.normalize()) <= 0.42D) {
                continue;
            }
            Vec3 body = feet.getCenter().add(0.0D, 1.05D, 0.0D);
            Vec3 face = feet.getCenter().add(0.0D, 2.25D, 0.0D);
            Vec3 hand = feet.getCenter().add(0.0D, 1.45D, 0.0D);
            boolean bodyHidden = isBlockedByCover(player, body, cover);
            boolean faceCanPeek = canNoticePeekPoint(player, face, 34.0D);
            boolean handCanPeek = canNoticePeekPoint(player, hand, 30.0D);
            if (!faceCanPeek && !handCanPeek && !mode.equals("turnaround")) {
                continue;
            }
            if (mode.equals("guaranteed") && !isGuaranteedPeekReadable(player, face, hand, bodyHidden)) {
                continue;
            }
            if (mode.equals("turnaround") && !isTurnaroundPeekReadable(player, face, hand, bodyHidden)) {
                continue;
            }
            if (mode.equals("bold") && !isBoldPeekReadable(player, face, hand, bodyHidden, phase, data)) {
                continue;
            }
            if (!bodyHidden && phase < 5 && !mode.equals("bold")) continue;
            return Optional.of(new PeekSpot(feet.immutable(), cover.immutable(), coverDir));
        }
        return Optional.empty();
    }

    private static Optional<PeekSpot> findWallHolePeekSpot(ServerLevel level, ServerPlayer player) {
        Vec3 look = horizontalLook(player);
        Vec3 side = new Vec3(-look.z, 0.0D, look.x).normalize();
        for (int i = 0; i < 140; i++) {
            double forward = 6.0D + RANDOM.nextInt(18);
            double lateral = (RANDOM.nextBoolean() ? 1.0D : -1.0D) * RANDOM.nextInt(8);
            BlockPos hole = BlockPos.containing(player.position().add(look.scale(forward)).add(side.scale(lateral))).offset(0, RANDOM.nextInt(5) - 2, 0);
            if (!isAirColumn(level, hole, 2) || !canNoticePeekPoint(player, hole.getCenter(), 28.0D)) {
                continue;
            }
            for (Direction toPlayerDir : Direction.Plane.HORIZONTAL) {
                if (!level.getBlockState(hole.relative(toPlayerDir)).isAir()) {
                    continue;
                }
                Direction back = toPlayerDir.getOpposite();
                BlockPos feet = findFloor(level, hole.relative(back, 2).below());
                if (feet == null || !isVisualPeekValid(level, player, feet, "hole_peek", state(player).getInt(PHASE))) {
                    continue;
                }
                if (!isHoleFrame(level, hole, toPlayerDir)) {
                    continue;
                }
                Vec3 face = feet.getCenter().add(0.0D, 2.2D, 0.0D);
                if (!canNoticePeekPoint(player, face, 30.0D) || !isBlockedNearCover(player, feet.getCenter().add(0.0D, 1.0D, 0.0D), hole)) {
                    continue;
                }
                return Optional.of(new PeekSpot(feet.immutable(), hole.immutable(), toPlayerDir));
            }
        }
        return Optional.empty();
    }

    private static Optional<PeekSpot> findCeilingHolePeekSpot(ServerLevel level, ServerPlayer player) {
        Vec3 look = horizontalLook(player);
        Vec3 side = new Vec3(-look.z, 0.0D, look.x).normalize();
        for (int i = 0; i < 120; i++) {
            double forward = 4.0D + RANDOM.nextInt(13);
            double lateral = (RANDOM.nextBoolean() ? 1.0D : -1.0D) * RANDOM.nextInt(6);
            BlockPos hole = BlockPos.containing(player.position().add(look.scale(forward)).add(side.scale(lateral))).offset(0, 3 + RANDOM.nextInt(4), 0);
            if (!level.getBlockState(hole).isAir() || !level.getBlockState(hole.below()).isAir()) {
                continue;
            }
            if (!isHorizontalHoleFrame(level, hole, true) || !canNoticePeekPoint(player, hole.getCenter(), 22.0D)) {
                continue;
            }
            BlockPos feet = hole.above(2);
            if (!hasAnyVisualPeekSpace(level, feet, "ceiling_hole_peek")) {
                continue;
            }
            return Optional.of(new PeekSpot(feet.immutable(), hole.immutable(), Direction.DOWN));
        }
        return Optional.empty();
    }

    private static Optional<PeekSpot> findFloorHolePeekSpot(ServerLevel level, ServerPlayer player) {
        Vec3 look = horizontalLook(player);
        Vec3 side = new Vec3(-look.z, 0.0D, look.x).normalize();
        for (int i = 0; i < 120; i++) {
            double forward = 4.0D + RANDOM.nextInt(13);
            double lateral = (RANDOM.nextBoolean() ? 1.0D : -1.0D) * RANDOM.nextInt(6);
            BlockPos hole = BlockPos.containing(player.position().add(look.scale(forward)).add(side.scale(lateral))).offset(0, -1 - RANDOM.nextInt(3), 0);
            if (!level.getBlockState(hole).isAir() || !level.getBlockState(hole.above()).isAir()) {
                continue;
            }
            if (!isHorizontalHoleFrame(level, hole, false) || !canNoticePeekPoint(player, hole.getCenter(), 22.0D)) {
                continue;
            }
            BlockPos feet = hole.below(3);
            if (!hasAnyVisualPeekSpace(level, feet, "floor_hole_peek")) {
                continue;
            }
            return Optional.of(new PeekSpot(feet.immutable(), hole.immutable(), Direction.UP));
        }
        return Optional.empty();
    }

    private static Optional<PeekSpot> findLowCeilingPeekSpot(ServerLevel level, ServerPlayer player, int height) {
        BlockPos base = player.blockPosition();
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        if (look.lengthSqr() < 0.001D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        look = look.normalize();
        Vec3 side = new Vec3(-look.z, 0.0D, look.x).normalize();
        for (int i = 0; i < 150; i++) {
            double forward = 6.0D + RANDOM.nextInt(16);
            double lateral = (RANDOM.nextBoolean() ? 1.0D : -1.0D) * RANDOM.nextInt(7);
            BlockPos rough = BlockPos.containing(player.position().add(look.scale(forward)).add(side.scale(lateral))).offset(0, RANDOM.nextInt(5) - 2, 0);
            BlockPos feet = findLowCeilingFloor(level, rough, height);
            if (feet == null) {
                continue;
            }
            BlockPos ceiling = feet.above(height);
            if (!isLowCeilingFrame(level, feet, height)) {
                continue;
            }
            Vec3 face = feet.getCenter().add(0.0D, height == 1 ? 0.72D : 1.32D, 0.0D);
            if (!canNoticePeekPoint(player, face, 26.0D)) {
                continue;
            }
            double dist = Math.sqrt(player.blockPosition().distSqr(feet));
            if (dist < 7.0D || dist > 28.0D) {
                continue;
            }
            return Optional.of(new PeekSpot(feet.immutable(), ceiling.immutable(), Direction.DOWN));
        }
        return Optional.empty();
    }

    private static BlockPos findLowCeilingFloor(ServerLevel level, BlockPos origin, int height) {
        int min = Math.max(level.getMinBuildHeight() + 2, origin.getY() - 6);
        int max = Math.min(level.getMaxBuildHeight() - 5, origin.getY() + 4);
        for (int y = max; y >= min; y--) {
            BlockPos feet = new BlockPos(origin.getX(), y, origin.getZ());
            if (!level.getBlockState(feet.below()).isSolidRender(level, feet.below())) {
                continue;
            }
            boolean air = true;
            for (int dy = 0; dy < height; dy++) {
                if (!level.getBlockState(feet.above(dy)).isAir()) {
                    air = false;
                    break;
                }
            }
            if (air && isCoverBlock(level, feet.above(height))) {
                return feet;
            }
        }
        return null;
    }

    private static boolean isLowCeilingFrame(ServerLevel level, BlockPos feet, int height) {
        int solidSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            if (isCoverBlock(level, side) || isCoverBlock(level, side.above(Math.max(0, height - 1)))) {
                solidSides++;
            }
        }
        return solidSides >= 1 && isCoverBlock(level, feet.above(height));
    }

    private static boolean isPeekBodySpaceClear(ServerLevel level, BlockPos feet) {
        for (int y = 0; y <= 3; y++) {
            if (!level.getBlockState(feet.above(y)).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isVisualPeekValid(ServerLevel level, ServerPlayer player, BlockPos feet, String event, int phase) {
        double dist = Math.sqrt(player.blockPosition().distSqr(feet));
        if (dist < 6.0D || dist > 34.0D) {
            return false;
        }
        if (bad(level, feet) || bad(level, feet.above())) {
            return false;
        }
        if (!hasAnyVisualPeekSpace(level, feet, event)) {
            return false;
        }
        if (isMineEvent(event) && level.getBrightness(LightLayer.BLOCK, feet) > 9) {
            return false;
        }
        Vec3 face = peekPointForEvent(feet, event);
        Vec3 hand = feet.getCenter().add(0.0D, 1.45D, 0.0D);
        if (event.equals("corner_peek") || event.equals("hand_peek")) {
            if (!hasLineOfSight(player, face) && !hasLineOfSight(player, hand)) {
                return false;
            }
        } else if (!canNoticePeekPoint(player, face, 36.0D) && !canNoticePeekPoint(player, hand, 32.0D)) {
            return false;
        }
        SightState sight = sightState(player, face, 38.0D);
        return phase >= 4 || sight != SightState.DIRECT || event.equals("visible_peek")
                || event.equals("corner_peek") || event.equals("hand_peek");
    }

    private static boolean isGuaranteedPeekReadable(Player player, Vec3 face, Vec3 hand, boolean bodyHidden) {
        boolean faceDirect = sightState(player, face, 32.0D) == SightState.DIRECT;
        boolean handDirect = sightState(player, hand, 28.0D) == SightState.DIRECT;
        return bodyHidden && (faceDirect || handDirect) && viewAngleDegrees(player, face) <= 28.0D;
    }

    private static boolean isTurnaroundPeekReadable(Player player, Vec3 face, Vec3 hand, boolean bodyHidden) {
        double faceAngle = viewAngleDegrees(player, face);
        double handAngle = viewAngleDegrees(player, hand);
        double distance = player.getEyePosition().distanceTo(face);
        boolean outOfViewButCatchable = (faceAngle >= 95.0D || handAngle >= 95.0D) && distance <= 26.0D;
        return bodyHidden && outOfViewButCatchable && (hasLineOfSight(player, face) || hasLineOfSight(player, hand));
    }

    private static boolean isBoldPeekReadable(Player player, Vec3 face, Vec3 hand, boolean bodyHidden, int phase, CompoundTag data) {
        int missed = data.getInt(PEEK_MISSED_STREAK);
        boolean visibleEnough = canNoticePeekPoint(player, face, 36.0D) || canNoticePeekPoint(player, hand, 34.0D);
        boolean closeEnough = player.getEyePosition().distanceTo(face) <= (missed >= 4 ? 30.0D : 24.0D);
        return visibleEnough && closeEnough && (bodyHidden || phase >= 4 || missed >= 2);
    }

    private static double viewAngleDegrees(Player player, Vec3 target) {
        Vec3 toTarget = target.subtract(player.getEyePosition());
        if (toTarget.lengthSqr() < 0.001D) {
            return 180.0D;
        }
        Vec3 look = player.getLookAngle().normalize();
        double dot = Mth.clamp(look.dot(toTarget.normalize()), -1.0D, 1.0D);
        return Math.toDegrees(Math.acos(dot));
    }

    private static boolean canNoticePeekPoint(Player player, Vec3 target, double maxDistance) {
        double distance = player.getEyePosition().distanceTo(target);
        if (distance > maxDistance || !hasLineOfSight(player, target)) {
            return false;
        }
        Vec3 toTarget = target.subtract(player.getEyePosition());
        if (toTarget.lengthSqr() < 0.001D) {
            return false;
        }
        Vec3 look = player.getLookAngle().normalize();
        double dot = Mth.clamp(look.dot(toTarget.normalize()), -1.0D, 1.0D);
        double angle = Math.toDegrees(Math.acos(dot));
        return angle <= 82.0D;
    }

    private static boolean isPlayerInMineContext(ServerLevel level, ServerPlayer player) {
        BlockPos center = player.blockPosition();
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return false;
        }
        if (center.getY() > 68 && level.canSeeSky(center)) {
            return false;
        }

        boolean caveBiome = level.getBiome(center).is(Biomes.LUSH_CAVES)
                || level.getBiome(center).is(Biomes.DRIPSTONE_CAVES)
                || level.getBiome(center).is(Biomes.DEEP_DARK);

        int enclosedAir = 0;
        int darkAir = 0;
        int naturalStone = 0;
        int openColumns = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        int radius = 9;
        int radiusSq = radius * radius;
        for (int x = -radius; x <= radius; x += 2) {
            for (int z = -radius; z <= radius; z += 2) {
                if (x * x + z * z > radiusSq) {
                    continue;
                }
                int columnAir = 0;
                for (int y = -5; y <= 5; y++) {
                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!level.hasChunkAt(cursor)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        columnAir++;
                        boolean noSky = !level.canSeeSky(cursor);
                        int blockLight = level.getBrightness(LightLayer.BLOCK, cursor);
                        if (noSky) {
                            enclosedAir++;
                        }
                        if (noSky && blockLight <= 7) {
                            darkAir++;
                        }
                    } else if (isNaturalMineBlock(state)) {
                        naturalStone++;
                    }
                }
                if (columnAir >= 5) {
                    openColumns++;
                }
            }
        }

        int skyLight = level.getBrightness(LightLayer.SKY, center);
        boolean enclosed = !level.canSeeSky(center) || skyLight <= 4;
        boolean mediumAirVolume = enclosedAir >= 34 && openColumns >= 4;
        boolean largeAirVolume = enclosedAir >= 54 && openColumns >= 7;
        boolean naturalEnough = naturalStone >= 24 || caveBiome || center.getY() <= 42;

        return enclosed && naturalEnough && (largeAirVolume || mediumAirVolume || (caveBiome && enclosedAir >= 20 && darkAir >= 10));
    }

    private static boolean isNaturalMineBlock(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.DRIPSTONE_BLOCK)
                || state.is(Blocks.SCULK)
                || state.is(Blocks.SCULK_VEIN);
    }

    private static Vec3 horizontalLook(Player player) {
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        if (look.lengthSqr() < 0.001D) {
            return new Vec3(0.0D, 0.0D, 1.0D);
        }
        return look.normalize();
    }

    private static boolean hasAnyVisualPeekSpace(ServerLevel level, BlockPos feet, String event) {
        Vec3[] points = new Vec3[]{
                peekPointForEvent(feet, event),
                feet.getCenter().add(0.0D, 1.2D, 0.0D),
                feet.getCenter().add(0.35D, 1.45D, 0.0D),
                feet.getCenter().add(-0.35D, 1.45D, 0.0D)
        };
        for (Vec3 point : points) {
            BlockPos pos = BlockPos.containing(point);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static Vec3 peekPointForEvent(BlockPos feet, String event) {
        if (event.equals("ceiling_hole_peek")) {
            return feet.getCenter().add(0.0D, 0.25D, 0.0D);
        }
        if (event.equals("floor_hole_peek")) {
            return feet.getCenter().add(0.0D, 2.4D, 0.0D);
        }
        if (event.equals("low_ceiling_1_peek")) {
            return feet.getCenter().add(0.0D, 0.72D, 0.0D);
        }
        if (event.equals("low_ceiling_2_peek")) {
            return feet.getCenter().add(0.0D, 1.32D, 0.0D);
        }
        return feet.getCenter().add(0.0D, 2.25D, 0.0D);
    }

    private static boolean isAirColumn(ServerLevel level, BlockPos base, int height) {
        for (int y = 0; y < height; y++) {
            if (!level.getBlockState(base.above(y)).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHoleFrame(ServerLevel level, BlockPos hole, Direction viewDirection) {
        Direction left = viewDirection.getClockWise();
        Direction right = viewDirection.getCounterClockWise();
        int frame = 0;
        if (isCoverBlock(level, hole.relative(left))) frame++;
        if (isCoverBlock(level, hole.relative(right))) frame++;
        if (isCoverBlock(level, hole.above(2))) frame++;
        if (isCoverBlock(level, hole.below())) frame++;
        return frame >= 3;
    }

    private static boolean isHorizontalHoleFrame(ServerLevel level, BlockPos hole, boolean ceiling) {
        int frame = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (isCoverBlock(level, hole.relative(direction))) {
                frame++;
            }
        }
        BlockPos backing = ceiling ? hole.above(3) : hole.below(3);
        return frame >= 3 && level.getBlockState(backing).isAir();
    }

    private static boolean isBlockedByCover(Player player, Vec3 target, BlockPos cover) {
        HitResult hit = player.level().clip(new ClipContext(player.getEyePosition(), target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit instanceof BlockHitResult blockHit) {
            BlockPos hitPos = blockHit.getBlockPos();
            return hitPos.equals(cover) || hitPos.equals(cover.above()) || hitPos.distSqr(cover) <= 2.0D;
        }
        return false;
    }

    private static boolean isBlockedNearCover(Player player, Vec3 target, BlockPos cover) {
        HitResult hit = player.level().clip(new ClipContext(player.getEyePosition(), target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit instanceof BlockHitResult blockHit) {
            return blockHit.getBlockPos().distSqr(cover) <= 4.0D;
        }
        return false;
    }

    private static Direction coverDirection(ServerLevel level, BlockPos feet) {
        List<Direction> directions = new ArrayList<>(List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST));
        Collections.shuffle(directions, RANDOM);
        for (Direction direction : directions) {
            if (isRealCoverColumn(level, feet.relative(direction), direction)) {
                return direction;
            }
        }
        return null;
    }

    private static boolean isRealCoverColumn(ServerLevel level, BlockPos base, Direction facing) {
        if (!isCoverBlock(level, base) || !isCoverBlock(level, base.above())) {
            return false;
        }
        Direction left = facing.getClockWise();
        Direction right = facing.getCounterClockWise();
        int connected = 0;
        if (isCoverBlock(level, base.relative(facing)) || isCoverBlock(level, base.relative(facing).above())) connected++;
        if (isCoverBlock(level, base.relative(left)) || isCoverBlock(level, base.relative(left).above())) connected++;
        if (isCoverBlock(level, base.relative(right)) || isCoverBlock(level, base.relative(right).above())) connected++;
        return connected > 0;
    }

    private static Optional<BlockPos> findNightFarWatchSpot(ServerLevel level, ServerPlayer player) {
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        if (look.lengthSqr() < 0.001D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        Vec3 base = look.normalize();
        for (int i = 0; i < 80; i++) {
            double angle = RANDOM.nextBoolean() ? RANDOM.nextInt(72) - 36 : 110 + RANDOM.nextInt(70);
            if (RANDOM.nextBoolean()) {
                angle = -angle;
            }
            Vec3 direction = rotateY(base, angle);
            double dist = 62.0D + RANDOM.nextInt(58);
            BlockPos rough = BlockPos.containing(player.position().add(direction.scale(dist))).offset(0, RANDOM.nextInt(9) - 4, 0);
            BlockPos feet = findFloor(level, rough);
            if (feet == null || !isValid(level, player, feet, "night_far_watch", 7)) {
                continue;
            }
            Vec3 face = feet.getCenter().add(0.0D, 1.7D, 0.0D);
            if (hasLineOfSight(player, face) && level.getBrightness(LightLayer.BLOCK, feet) <= 8) {
                return Optional.of(feet.immutable());
            }
        }
        return Optional.empty();
    }

    private static boolean isCoverBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (state.isAir() || block instanceof FenceBlock || block instanceof FenceGateBlock || block instanceof GlassBlock
                || block instanceof StainedGlassBlock || block instanceof TrapDoorBlock || block instanceof DoorBlock
                || block instanceof SlabBlock || block instanceof StairBlock || block instanceof TorchBlock
                || block instanceof WallTorchBlock || block instanceof LeavesBlock || block instanceof LadderBlock) {
            return false;
        }
        return state.isSolidRender(level, pos) && state.getCollisionShape(level, pos).isEmpty() == false;
    }

    private static boolean isValid(ServerLevel level, ServerPlayer player, BlockPos feet, String event, int phase) {
        double dist = Math.sqrt(player.blockPosition().distSqr(feet));
        if (event.equals("night_far_watch") && (dist < 58.0D || dist > 132.0D)) return false;
        if (event.equals("false_friend") && (dist < 24.0D || dist > 70.0D)) return false;
        if (event.equals("visible_peek") && (dist < 10.0D || dist > 38.0D)) return false;
        if (event.equals("cat_curse") && (dist < 16.0D || dist > 96.0D)) return false;
        if (event.equals("dream_bed") && (dist < 4.0D || dist > 11.0D)) return false;
        if ((event.equals("bedside_watch") || event.equals("doorway_lean")) && (dist < 3.0D || dist > 24.0D)) return false;
        if (!event.equals("final_close_presence") && !event.equals("window_watch") && !event.equals("cat_curse")
                && !event.equals("bedside_watch") && !event.equals("doorway_lean") && !event.equals("dream_bed")
                && !event.equals("night_far_watch") && !event.equals("false_friend") && !event.equals("visible_peek") && (dist < 8.0D || dist > 45.0D)) return false;
        if (event.equals("final_close_presence") && (dist < 2.0D || dist > 6.0D)) return false;
        if (event.equals("window_watch") && dist > 24.0D) return false;

        BlockState floor = level.getBlockState(feet.below());

        if (!floor.isFaceSturdy(level, feet.below(), Direction.UP)) return false;
        if (!hasFriendStandingSpace(level, feet)) return false;
        if (bad(level, feet.below()) || bad(level, feet) || bad(level, feet.above())) return false;
        if (isMineEvent(event) && level.getBrightness(LightLayer.BLOCK, feet) > 7) return false;

        SightState sight = sightState(player, feet.getCenter().add(0, 1.2D, 0), 45.0D);
        if (phase < 4 && sight == SightState.DIRECT && !event.equals("visible_peek")) return false;
        return true;
    }

    private static BlockPos findFloor(ServerLevel level, BlockPos origin) {
        int min = Math.max(level.getMinBuildHeight() + 2, origin.getY() - 8);
        int max = Math.min(level.getMaxBuildHeight() - 4, origin.getY() + 5);
        for (int y = max; y >= min; y--) {
            BlockPos feet = new BlockPos(origin.getX(), y, origin.getZ());
            if (level.getBlockState(feet.below()).isSolidRender(level, feet.below())
                    && level.getBlockState(feet).isAir()
                    && level.getBlockState(feet.above()).isAir()) {
                return feet;
            }
        }
        return null;
    }

    private static BlockPos findSafeFriendFloor(ServerLevel level, BlockPos origin) {
        int min = Math.max(level.getMinBuildHeight() + 2, origin.getY() - 10);
        int max = Math.min(level.getMaxBuildHeight() - FRIEND_AIR_BLOCKS - 1, origin.getY() + 8);
        for (int y = max; y >= min; y--) {
            BlockPos feet = new BlockPos(origin.getX(), y, origin.getZ());
            if (hasFriendStandingSpace(level, feet)) {
                return feet.immutable();
            }
        }
        return null;
    }

    private static boolean bad(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.LAVA) || state.getFluidState().is(FluidTags.WATER) || state.getFluidState().is(FluidTags.LAVA)
                || state.is(Blocks.POWDER_SNOW) || state.getBlock() instanceof CampfireBlock || state.is(Blocks.CACTUS);
    }

    private static SightState sightState(Player player, FriendEntity friend, double maxDistance) {
        return sightState(player, friend.position().add(0, friend.getBbHeight() * 0.65D, 0), maxDistance);
    }

    private static SightState sightState(Player player, Vec3 target, double maxDistance) {
        double distance = player.getEyePosition().distanceTo(target);
        if (distance > maxDistance) return SightState.NONE;
        Vec3 toTarget = target.subtract(player.getEyePosition()).normalize();
        Vec3 look = player.getLookAngle().normalize();
        double dot = Mth.clamp(look.dot(toTarget), -1.0D, 1.0D);
        double angle = Math.toDegrees(Math.acos(dot));
        if (!hasLineOfSight(player, target)) return SightState.NONE;
        if (angle < 35.0D) return SightState.DIRECT;
        if (angle < 70.0D) return SightState.PERIPHERAL;
        return SightState.NONE;
    }

    private static boolean hasLineOfSight(Player player, Vec3 target) {
        HitResult hit = player.level().clip(new ClipContext(player.getEyePosition(), target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(target) < 0.8D;
    }

    private static boolean shouldDisappearFromSight(int phase, String event, SightState sight, int seenTicks) {
        if (event.equals("visible_peek") && sight == SightState.DIRECT) return seenTicks > 1;
        if (event.equals("stare_event") && seenTicks < 40) return false;
        if (event.equals("final_close_presence") && seenTicks < 8) return false;
        if (sight == SightState.DIRECT) {
            if (phase <= 1) return RANDOM.nextFloat() < 0.80F || seenTicks > 10;
            if (phase <= 3) return seenTicks > 8 && RANDOM.nextFloat() < 0.55F;
            return seenTicks > 18 && RANDOM.nextFloat() < 0.35F;
        }
        return seenTicks > 30 && RANDOM.nextFloat() < 0.25F;
    }

    private static void punishIfAllowed(ServerLevel level, ServerPlayer player, FriendEntity friend) {
        CompoundTag data = state(player);
        long now = level.getGameTime();
        long punishCooldown = data.getBoolean(CAT_CURSE) ? 240L : 24000L;
        if (now - data.getLong(LAST_PUNISH) > punishCooldown && level.getDifficulty() != Difficulty.PEACEFUL) {
            data.putLong(LAST_PUNISH, now);
            float damage = data.getBoolean(CAT_CURSE) ? (level.getDifficulty() == Difficulty.EASY ? 4.0F : 8.0F) : (level.getDifficulty() == Difficulty.EASY ? 2.0F : 4.0F);
            player.hurt(player.damageSources().magic(), damage);
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, data.getBoolean(CAT_CURSE) ? 100 : 40, 0, false, false));
        }
        disappear(friend, "punish", true);
    }

    private static boolean hasActiveFriend(ServerLevel level, ServerPlayer player) {
        return !level.getEntitiesOfClass(FriendEntity.class, player.getBoundingBox().inflate(80.0D),
                friend -> player.getUUID().toString().equals(friend.ownerUuid())).isEmpty();
    }

    private static FriendEntity findOwnedEvent(ServerLevel level, ServerPlayer player, String event, double radius) {
        return level.getEntitiesOfClass(FriendEntity.class, player.getBoundingBox().inflate(radius),
                        friend -> player.getUUID().toString().equals(friend.ownerUuid()) && event.equals(friend.eventId()))
                .stream()
                .min(Comparator.comparingDouble(friend -> friend.distanceToSqr(player)))
                .orElse(null);
    }

    private static void cleanupOwnedFriends(ServerLevel level, ServerPlayer player) {
        level.getEntitiesOfClass(FriendEntity.class, player.getBoundingBox().inflate(260.0D),
                        friend -> player.getUUID().toString().equals(friend.ownerUuid()))
                .forEach(Entity::discard);
        state(player).putBoolean(ACTIVE, false);
    }

    private static String normalizeForcedEvent(String forcedEvent) {
        return switch (forcedEvent) {
            case "window" -> "window_watch";
            case "attack" -> "attack_emerge";
            case "dream" -> "dream_hint";
            case "peek" -> "stalk";
            case "corner" -> "stalk_corner";
            case "tree" -> "stalk_tree";
            case "hand" -> "stalk_corner";
            case "hole" -> "stalk_cave";
            case "ceiling" -> "stalk_ceiling";
            case "floor" -> "stalk_floor";
            case "low", "low2", "low1" -> "stalk_low";
            case "far", "night" -> "night_far_watch";
            case "route" -> "route_ambush";
            case "visible" -> "stalk";
            case "curse" -> "cat_curse";
            default -> forcedEvent;
        };
    }

    private static void cleanupActiveFlag(ServerLevel level, ServerPlayer player, CompoundTag data) {
        data.putBoolean(ACTIVE, hasActiveFriend(level, player));
    }

    private static ServerPlayer findOwner(ServerLevel level, String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        try {
            return level.getServer().getPlayerList().getPlayer(UUID.fromString(uuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void facePlayer(FriendEntity friend, Player player) {
        Vec3 delta = player.getEyePosition().subtract(friend.position().add(0, friend.getBbHeight() * 0.72D, 0));
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) (-(Mth.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)) * Mth.RAD_TO_DEG));
        friend.setYRot(yaw);
        friend.setXRot(pitch);
        friend.yBodyRot = yaw;
        friend.yHeadRot = yaw;
        friend.yHeadRotO = yaw;
    }

    private static BlockPos behind(Player player, int distance) {
        Vec3 look = player.getLookAngle().normalize();
        return BlockPos.containing(player.position().subtract(look.scale(distance)));
    }

    private static BlockPos ahead(Player player, int distance) {
        Vec3 look = player.getLookAngle().normalize();
        return BlockPos.containing(player.position().add(look.scale(distance)));
    }

    private static BlockPos side(Player player, int distance) {
        Vec3 look = player.getLookAngle().normalize();
        Vec3 side = new Vec3(-look.z, 0, look.x).normalize();
        if (RANDOM.nextBoolean()) side = side.scale(-1.0D);
        return BlockPos.containing(player.position().add(side.scale(distance)).add(look.scale(RANDOM.nextInt(9) - 4)));
    }

    private static BlockPos offsetAround(BlockPos center, int min, int max) {
        int distance = min + RANDOM.nextInt(Math.max(1, max - min + 1));
        double angle = RANDOM.nextDouble() * Math.PI * 2.0D;
        return center.offset(Mth.floor(Math.cos(angle) * distance), RANDOM.nextInt(7) - 3, Mth.floor(Math.sin(angle) * distance));
    }

    private static Optional<BlockPos> findNearestGlass(ServerLevel level, BlockPos center, int radius) {
        return findBlocks(level, center, radius,
                state -> state.getBlock() instanceof GlassBlock || state.getBlock() instanceof StainedGlassBlock)
                .stream().min(Comparator.comparingDouble(pos -> pos.distSqr(center)));
    }

    private static Optional<WindowSpot> findBestWindowSpot(ServerLevel level, ServerPlayer player, CompoundTag data, int radius) {
        List<BlockPos> windows = new ArrayList<>();
        rememberedBlock(data, MEMORY_WINDOWS, player.blockPosition(), radius + 12).ifPresent(windows::add);
        BlockPos home = data.getBoolean(HAS_HOME)
                ? new BlockPos(data.getInt(HOME_X), data.getInt(HOME_Y), data.getInt(HOME_Z))
                : player.blockPosition();
        windows.addAll(findBlocks(level, player.blockPosition(), radius, FriendDirector::isWindowBlock));
        if (data.getBoolean(HAS_HOME) && player.blockPosition().distSqr(home) < 56.0D * 56.0D) {
            windows.addAll(findBlocks(level, home, 18, FriendDirector::isWindowBlock));
        }
        WindowSpot best = null;
        int bestScore = -1;
        for (BlockPos glass : windows) {
            if (!isWindowBlock(level.getBlockState(glass)) || !canPlayerNoticeWindow(player, glass)) {
                continue;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos nearGlass = glass.relative(direction);
                if (!level.getBlockState(nearGlass).isAir()) {
                    continue;
                }
                if (!level.getBlockState(glass.relative(direction, 2)).isAir()) {
                    continue;
                }
                BlockPos feet = findFloor(level, glass.relative(direction, 3));
                if (feet == null || !isValid(level, player, feet, "window_watch", data.getInt(PHASE))) {
                    continue;
                }
                if (!isWindowStandingSpaceClear(level, feet, direction)) {
                    continue;
                }
                Vec3 dir = new Vec3(direction.getStepX(), 0.0D, direction.getStepZ());
                Vec3 toPlayer = player.position().subtract(glass.getCenter()).multiply(1.0D, 0.0D, 1.0D);
                double oppositeSide = toPlayer.lengthSqr() < 0.001D ? 0.0D : -dir.normalize().dot(toPlayer.normalize());
                int score = 20 - Mth.floor(Math.sqrt(player.blockPosition().distSqr(glass)));
                if (oppositeSide > 0.2D) score += 18;
                if (data.getBoolean(HAS_HOME) && glass.distSqr(home) < 18.0D * 18.0D) score += 12;
                if (rememberedBlock(data, MEMORY_WINDOWS, glass, 1).isPresent()) score += 12;
                if (level.getBrightness(LightLayer.BLOCK, feet) <= 7) score += 5;
                if (score > bestScore || RANDOM.nextInt(Math.max(1, score + bestScore + 1)) < score) {
                    bestScore = score;
                    best = new WindowSpot(glass.immutable(), feet.immutable());
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean isWindowStandingSpaceClear(ServerLevel level, BlockPos feet, Direction wallDirection) {
        for (int y = 0; y <= 3; y++) {
            BlockPos center = feet.above(y);
            if (!level.getBlockState(center).isAir()) {
                return false;
            }
            Direction sideA = wallDirection.getClockWise();
            Direction sideB = wallDirection.getCounterClockWise();
            if (y <= 2 && (!level.getBlockState(center.relative(sideA)).canBeReplaced()
                    || !level.getBlockState(center.relative(sideB)).canBeReplaced())) {
                return false;
            }
        }
        return true;
    }

    private static boolean canPlayerNoticeWindow(ServerPlayer player, BlockPos glass) {
        Vec3 target = glass.getCenter();
        HitResult hit = player.level().clip(new ClipContext(player.getEyePosition(), target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        if (hit instanceof BlockHitResult blockHit) {
            return blockHit.getBlockPos().equals(glass) || blockHit.getBlockPos().distSqr(glass) <= 1.0D;
        }
        return false;
    }

    private static boolean isWindowBlock(BlockState state) {
        return state.getBlock() instanceof GlassBlock || state.getBlock() instanceof StainedGlassBlock || state.is(Blocks.GLASS_PANE);
    }

    private static List<BlockPos> outsideWindowPositions(BlockPos glass) {
        return List.of(glass.north(2), glass.south(2), glass.east(2), glass.west(2), glass.north(3), glass.south(3), glass.east(3), glass.west(3));
    }

    private static List<BlockPos> findBlocks(ServerLevel level, BlockPos center, int radius, java.util.function.Predicate<BlockState> predicate) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -5, -radius), center.offset(radius, 5, radius))) {
            if (predicate.test(level.getBlockState(pos))) {
                result.add(pos.immutable());
            }
        }
        return result;
    }

    private static void playIntroSound(ServerLevel level, BlockPos pos, String event) {
        if (event.equals("corner_peek") || event.equals("breathing_behind")) {
            play(level, pos, FriendSoundEvents.QUIET_BREATH, 0.09F, 0.9F, 1.08F);
        } else if (event.equals("final_close_presence") || event.equals("bedside_watch")) {
            play(level, pos, FriendSoundEvents.CLOSE_PRESENCE, 0.28F, 0.72F, 1.0F);
        } else if (event.equals("stare_event") || event.equals("tunnel_blocking_silhouette")) {
            play(level, pos, FriendSoundEvents.LOW_DRONE, 0.08F, 0.65F, 0.95F);
        } else if (event.equals("night_far_watch")) {
            play(level, pos, FriendSoundEvents.LOW_DRONE, 0.055F, 0.55F, 0.78F);
        } else if (event.equals("side_tunnel_watch") || event.equals("route_ambush") || event.equals("doorway_lean")) {
            play(level, pos, FriendSoundEvents.TUNNEL_STEP_1, 0.12F, 0.8F, 1.1F);
        }
    }

    private static void play(ServerLevel level, BlockPos pos, RegistryObject<SoundEvent> sound, float volume, float minPitch, float maxPitch) {
        float pitch = minPitch + RANDOM.nextFloat() * (maxPitch - minPitch);
        float audibleVolume = Mth.clamp(volume, 0.05F, 1.0F);
        level.playSound(null, pos, sound.get(), SoundSource.HOSTILE, audibleVolume, pitch);
    }

    private static void playEntity(ServerLevel level, Entity entity, RegistryObject<SoundEvent> sound, float volume, float minPitch, float maxPitch) {
        float pitch = minPitch + RANDOM.nextFloat() * (maxPitch - minPitch);
        level.playSound(null, entity.getX(), entity.getY() + entity.getBbHeight() * 0.45D, entity.getZ(),
                sound.get(), SoundSource.VOICE, volume, pitch);
    }

    private static void dreamRealmWhisper(ServerPlayer player, int variant) {
        String line = switch (variant) {
            case 1 -> "the screens are off because they already saw you";
            case 2 -> "this room remembers furniture you never crafted";
            case 3 -> "the doors are wet on the wrong side";
            case 4 -> "the forest has no leaves, only witnesses";
            case 5 -> "the stairs keep counting your legs";
            case 6 -> "one reflection is late";
            case 7 -> "the office has your name in no drawer";
            case 8 -> "do not trust floors you cannot see";
            case 9 -> "every bell waits for the same second";
            default -> "the village locked its doors from outside";
        };
        player.displayClientMessage(Component.literal(line), true);
    }

    private static String normalizeChat(String raw) {
        String text = raw.toLowerCase(java.util.Locale.ROOT).trim();
        text = text.replace('\u0451', '\u0435');
        StringBuilder result = new StringBuilder(text.length());
        boolean lastSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '\'' || c == '?') {
                result.append(c);
                lastSpace = false;
            } else if (!lastSpace) {
                result.append(' ');
                lastSpace = true;
            }
        }
        return result.toString().replaceAll("\\s+", " ").trim();
    }

    private static void playSubtitle(ServerLevel level, ServerPlayer player, RegistryObject<SoundEvent> sound) {
        level.playSound(null, player.getX(), player.getY() + 1.0D, player.getZ(), sound.get(), SoundSource.HOSTILE, 0.32F, 1.0F);
    }

    private static void award(ServerPlayer player, ResourceLocation id) {
        var advancement = player.server.getAdvancements().getAdvancement(id);
        if (advancement != null) {
            for (String criterion : advancement.getCriteria().keySet()) {
                player.getAdvancements().award(advancement, criterion);
            }
        }
    }

        private static ItemStack createFriendBook() {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = new CompoundTag();
        ListTag pages = new ListTag();
        addBookPage(pages, "I started counting the sounds because counting felt safer.\n\nFirst: a branch behind me.\nSecond: a breath in the wall.\nThird: my own door opening while I was still outside.\n\nAfter that I stopped counting.");
        addBookPage(pages, "It does not live in the dark.\n\nIt uses the dark the way a hunter uses grass.\nIt waits where your torchlight becomes weak.\nIt stands where your eyes almost refuse to look.");
        addBookPage(pages, "If you see it peeking, do not feel lucky.\n\nThat means it chose a place where you could see it.\nIt wanted to know whether you would run, freeze, or pretend nothing happened.");
        addBookPage(pages, "A house is not a bed.\nA house is not a chest.\nA house is not a hole with a door.\n\nA house is the place you made with your own hands and returned to often enough for it to learn the route.");
        addBookPage(pages, "It hates shortcuts.\n\nBuild upward and it will take the bottom away.\nBury yourself and it will dig toward the sound of you.\nHide behind one block too many and it will learn which block matters.");
        addBookPage(pages, "Sometimes it only runs to make you move.\n\nSometimes it stops before touching you.\nThat is not mercy.\nIt is measurement.");
        addBookPage(pages, "The mines are worse.\n\nNot because it is closer there.\nBecause the stone carries it beside you.\nA scrape on the left wall may be a footstep above your head.");
        addBookPage(pages, "Do not hurt the small ones.\n\nEspecially the cat.\n\nThe thing that comes after that is still Friend,\nbut it no longer behaves as if the world deserves rules.");
        addBookPage(pages, "Dreams are short for a reason.\nA field. A door. Daylight that feels wrong.\n\nDo not stay to understand it.\nThat place is not a puzzle.\nIt is a pause before something decides what you are.");
        addBookPage(pages, "If it knocks, wait.\nIf a torch goes out behind you, do not turn immediately.\nIf you hear your own steps continue after you stop,\nleave the mine.");
        addBookPage(pages, "It is called Friend.\n\nNot because it protects you.\nNot because it forgives you.\n\nBecause after enough nights, every other sound in the world starts to feel less honest.");
        tag.putString("title", "Rules for the Thing Called Friend");
        tag.putString("author", "Unknown");
        tag.put("pages", pages);
        book.setTag(tag);
        return book;
    }


    private static void giveFinalBook(ServerPlayer player, String title, String text) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = new CompoundTag();
        ListTag pages = new ListTag();
        addBookPage(pages, text);
        tag.putString("title", title);
        tag.putString("author", "Friend");
        tag.put("pages", pages);
        book.setTag(tag);
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
    }

    private static int setNearestVariant(ServerPlayer player, String variant) {
        List<FriendEntity> friends = player.serverLevel().getEntitiesOfClass(FriendEntity.class, player.getBoundingBox().inflate(96.0D));
        if (friends.isEmpty()) {
            player.displayClientMessage(Component.literal("no Friend nearby"), false);
            return 0;
        }
        friends.stream().min(Comparator.comparingDouble(friend -> friend.distanceToSqr(player))).ifPresent(friend -> {
            friend.setVisualVariant(variant);
            player.displayClientMessage(Component.literal("friend variant " + variant), false);
        });
        return 1;
    }

    private static int forceStalk(ServerPlayer player, String type) {
        cleanupOwnedFriends(player.serverLevel(), player);
        boolean ok = FriendStalkingDirector.tryStart(player.serverLevel(), player, state(player), type);
        player.displayClientMessage(Component.literal((ok ? "friend stalk " : "friend stalk failed ") + type
                + " | " + FriendStalkingDirector.debugStatus(state(player))), false);
        return ok ? 1 : 0;
    }

    private static int setStalkIntensity(ServerPlayer player, int intensity) {
        FriendStalkingDirector.setIntensity(state(player), intensity);
        player.displayClientMessage(Component.literal("friend stalk intensity " + intensity), false);
        return 1;
    }

    private static int mineDebug(ServerPlayer player, String mode) {
        CompoundTag data = state(player);
        switch (mode) {
            case "anchors" -> player.displayClientMessage(Component.literal(MinePeekAdapter.debugAnchors(player.serverLevel(), player, data)), false);
            case "memory" -> player.displayClientMessage(Component.literal(MineShortTermMemory.debugSummary(data)), false);
            case "test" -> {
                cleanupOwnedFriends(player.serverLevel(), player);
                boolean ok = FriendStalkingDirector.tryStart(player.serverLevel(), player, data, "cave");
                player.displayClientMessage(Component.literal("friend mine test=" + ok + " | "
                        + MinePeekAdapter.debugAnchors(player.serverLevel(), player, data)
                        + " | " + FriendStalkingDirector.debugStatus(data)), false);
                return ok ? 1 : 0;
            }
            default -> {
                player.displayClientMessage(Component.literal(MineContextAdapter.classify(player.serverLevel(), player, data).environmentType()
                        + " | " + MineContextAdapter.classify(player.serverLevel(), player, data).features()
                        + " | " + MineShortTermMemory.debugSummary(data)), false);
            }
        }
        return 1;
    }

    private static int runForcedPeek(ServerPlayer player, PeekCommandMode mode) {
        FriendPeekCommandResult result = FriendDebugPeekService.forcePeek(player.serverLevel(), player, state(player), mode);
        player.displayClientMessage(Component.literal(result.chatLine(mode)), false);
        return result.success() ? 1 : 0;
    }

    private static int debugSpawn(ServerPlayer player) {
        FriendPeekCommandResult result = FriendDebugPeekService.debugSpawn(player.serverLevel(), player, state(player));
        player.displayClientMessage(Component.literal(result.chatLine(PeekCommandMode.FALLBACK_ONLY)), false);
        return result.success() ? 1 : 0;
    }

    private static int debugPeek(ServerPlayer player) {
        player.displayClientMessage(Component.literal(FriendDebugPeekService.debugLine(player.serverLevel(), player, state(player))), false);
        return 1;
    }

    private static int debugCategory(ServerPlayer player, String category) {
        FriendEntity friend = nearestFriend(player.serverLevel(), player, 260.0D);
        CompoundTag data = state(player);
        if (friend == null) {
            player.displayClientMessage(Component.literal("friend debug " + category + ": no Friend nearby | "
                    + FriendStalkingDirector.debugStatus(data)), false);
            return 0;
        }
        CompoundTag tag = friend.getPersistentData();
        String physics = "physics onGround=" + friend.onGround()
                + " noGravity=" + friend.isNoGravity()
                + " noPhysics=" + friend.noPhysics
                + " delta=" + compactVec(friend.getDeltaMovement())
                + " below=" + player.serverLevel().getBlockState(friend.blockPosition().below()).getBlock().getName().getString()
                + " box=" + compactBox(friend.getBoundingBox())
                + " size=" + round(friend.getBbWidth()) + "x" + round(friend.getBbHeight())
                + " step=" + round(friend.maxUpStep())
                + " nav=" + !friend.getNavigation().isDone()
                + " stuck=" + tag.getInt(FriendEntity.TAG_STUCK_TICKS)
                + " airborne=" + tag.getInt(FriendEntity.TAG_AIRBORNE_TICKS);
        String path = "path type=" + data.getString(LAST_PATH_TYPE)
                + " break=" + data.getString(LAST_BLOCK_BREAK_REASON)
                + " trap=" + data.getBoolean(LAST_TRAP_HAZARD)
                + " speed=" + round(data.getDouble(LAST_CHASE_SPEED))
                + " recalc=" + tag.getLong(FriendEntity.TAG_LAST_PATH_RECALC);
        String sound = "sound phase_shift=disabled attackCooldown=" + tag.getLong(FriendEntity.TAG_ATTACK_SCREAM_COOLDOWN)
                + " soundCooldown=" + data.getLong(SOUND_COOLDOWN);
        String placement = "placement event=" + friend.eventId()
                + " pos=" + friend.blockPosition().toShortString()
                + " cover=" + tag.getString("friend_cover_type")
                + " hidden=" + round(tag.getDouble("friend_visible_fraction_hidden"))
                + " peek=" + round(tag.getDouble("friend_visible_fraction_peek"));
        String movement = "movement event=" + friend.eventId()
                + " forcedPeek=" + friend.isDebugForcedPeekActive()
                + " forcedTicks=" + tag.getInt(FriendEntity.TAG_DEBUG_FORCED_PEEK_TICKS)
                + " target=" + (friend.getTarget() != null)
                + " navDone=" + friend.getNavigation().isDone()
                + " stuck=" + tag.getInt(FriendEntity.TAG_STUCK_TICKS)
                + " jumpCd=" + Math.max(0L, tag.getLong(FriendEntity.TAG_JUMP_COOLDOWN) - player.serverLevel().getGameTime())
                + " sideCd=" + Math.max(0L, tag.getLong(FriendEntity.TAG_SIDESTEP_COOLDOWN) - player.serverLevel().getGameTime())
                + " repathCd=" + Math.max(0L, tag.getLong(FriendEntity.TAG_REPATH_COOLDOWN) - player.serverLevel().getGameTime())
                + " speedAttr=" + (friend.getAttribute(Attributes.MOVEMENT_SPEED) == null ? "-" : round(friend.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue()));
        String message = switch (category) {
            case "physics" -> physics;
            case "path" -> path;
            case "sound" -> sound;
            case "placement" -> placement;
            case "movement" -> movement;
            case "stalking" -> FriendStalkingDirector.debugStatus(data);
            default -> physics + " | " + path + " | " + placement + " | " + movement + " | " + sound + " | " + FriendStalkingDirector.debugStatus(data);
        };
        player.displayClientMessage(Component.literal(message), false);
        return 1;
    }

    private static int testCategory(ServerPlayer player, String category) {
        CompoundTag data = state(player);
        switch (category) {
            case "physics", "ground" -> {
                FriendEntity friend = nearestFriend(player.serverLevel(), player, 260.0D);
                if (friend != null) {
                    ensurePhysicalChaser(friend, Math.max(0.28D, data.getDouble(LAST_CHASE_SPEED)));
                    recoverFloatingChaser(player.serverLevel(), friend);
                    player.displayClientMessage(Component.literal("friend test " + category + ": physics recovery applied"), false);
                    return 1;
                }
                player.displayClientMessage(Component.literal("friend test " + category + ": no Friend nearby"), false);
                return 0;
            }
            case "jump" -> {
                FriendEntity friend = nearestFriend(player.serverLevel(), player, 260.0D);
                if (friend != null) {
                    Vec3 forward = player.position().subtract(friend.position()).multiply(1.0D, 0.0D, 1.0D);
                    if (forward.lengthSqr() < 0.001D) {
                        forward = horizontalLook(player).scale(-1.0D);
                    }
                    boolean jumped = tryJumpObstacle(player.serverLevel(), friend, forward.normalize());
                    player.displayClientMessage(Component.literal("friend test jump: " + jumped), false);
                    return jumped ? 1 : 0;
                }
                player.displayClientMessage(Component.literal("friend test jump: no Friend nearby"), false);
                return 0;
            }
            case "chase" -> {
                cleanupOwnedFriends(player.serverLevel(), player);
                spawnAttack(player.serverLevel(), player, data);
                player.displayClientMessage(Component.literal("friend test chase started"), false);
                return 1;
            }
            case "placement", "visible" -> {
                cleanupOwnedFriends(player.serverLevel(), player);
                boolean ok = FriendStalkingDirector.tryStart(player.serverLevel(), player, data, null);
                player.displayClientMessage(Component.literal("friend test " + category + ": " + ok + " | "
                        + FriendStalkingDirector.debugStatus(data)), false);
                return ok ? 1 : 0;
            }
            default -> {
                return debugCategory(player, "all");
            }
        }
    }

    private static FriendEntity nearestFriend(ServerLevel level, ServerPlayer player, double radius) {
        return level.getEntitiesOfClass(FriendEntity.class, player.getBoundingBox().inflate(radius),
                        friend -> player.getUUID().toString().equals(friend.ownerUuid()))
                .stream()
                .min(Comparator.comparingDouble(friend -> friend.distanceToSqr(player)))
                .orElse(null);
    }

    private static String compactVec(Vec3 vec) {
        return "(" + round(vec.x) + "," + round(vec.y) + "," + round(vec.z) + ")";
    }

    private static String compactBox(AABB box) {
        return "(" + round(box.getXsize()) + "," + round(box.getYsize()) + "," + round(box.getZsize()) + ")";
    }

    private static String nearestVariant(ServerLevel level, ServerPlayer player) {
        return level.getEntitiesOfClass(FriendEntity.class, player.getBoundingBox().inflate(96.0D),
                        friend -> player.getUUID().toString().equals(friend.ownerUuid()))
                .stream()
                .min(Comparator.comparingDouble(friend -> friend.distanceToSqr(player)))
                .map(FriendEntity::visualVariant)
                .orElse("-");
    }

    private static void addBookPage(ListTag pages, String text) {
        pages.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(text))));
    }

    private static CompoundTag state(ServerPlayer player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(ROOT)) {
            root.put(ROOT, new CompoundTag());
        }
        return root.getCompound(ROOT);
    }

    private static void add(CompoundTag data, String key, double amount, double min, double max) {
        data.putDouble(key, Mth.clamp(data.getDouble(key) + amount, min, max));
    }

    private static void add(CompoundTag data, String key, long amount, long min, long max) {
        data.putLong(key, Math.max(min, Math.min(max, data.getLong(key) + amount)));
    }

    private static void addEvent(List<WeightedEvent> events, String id, int weight) {
        if (weight > 0) events.add(new WeightedEvent(id, weight));
    }

    private static boolean isSoundOnly(String event) {
        return event.equals("distant_scrape") || event.equals("distant_knock") || event.equals("fake_footsteps")
                || event.equals("breathing_behind") || event.equals("dream_hint") || event.equals("mimic_footsteps")
                || event.equals("wall_scurry") || event.equals("sprint_behind_wall")
                || event.equals("door_open_far") || event.equals("chest_open_far")
                || event.equals("learned_footsteps") || event.equals("learned_door") || event.equals("learned_chest");
    }

    private static boolean isPeekEvent(String event) {
        return event.equals("corner_peek") || event.equals("hand_peek") || isHolePeekEvent(event) || isLowCeilingPeekEvent(event);
    }

    private static boolean isHolePeekEvent(String event) {
        return event.equals("hole_peek") || event.equals("ceiling_hole_peek") || event.equals("floor_hole_peek");
    }

    private static boolean isLowCeilingPeekEvent(String event) {
        return event.equals("low_ceiling_2_peek") || event.equals("low_ceiling_1_peek");
    }

    private static boolean isMineEvent(String event) {
        return event.contains("tunnel") || event.contains("corner") || event.contains("side")
                || event.equals("hand_peek") || isHolePeekEvent(event) || isLowCeilingPeekEvent(event);
    }

    private static double approachDistance(String event) {
        if (event.equals("final_close_presence")) return 2.2D;
        if (event.equals("stare_event")) return 2.2D;
        if (event.equals("tunnel_blocking_silhouette")) return 6.0D;
        return 4.5D;
    }

    private static int cooldown(int phase) {
        return switch (phase) {
            case 1 -> seconds(22, 45);
            case 2 -> seconds(20, 50);
            case 3 -> seconds(18, 45);
            case 4 -> seconds(16, 42);
            case 5 -> seconds(20, 58);
            case 6 -> seconds(24, 72);
            case 7 -> seconds(35, 95);
            default -> seconds(25, 60);
        };
    }

    private static int cooldown(CompoundTag data, int phase) {
        int base = cooldown(phase);
        if (FriendConfig.SUBTLE_MODE.get() && !data.getBoolean(CAT_CURSE)) {
            base = Mth.floor(base * 1.35D);
        }
        base = Mth.floor(base * FriendConfig.EVENT_FREQUENCY_MULTIPLIER.get());
        return data.getBoolean(CAT_CURSE) ? seconds(35, 75) : Math.max(20, base);
    }

        private static boolean attackCooldownReady(CompoundTag data, long now) {
        if (data.getBoolean(CAT_CURSE)) {
            long lock = data.getLong(ATTACK_LOCK_UNTIL);
            return lock <= 0L || now >= lock;
        }
        long lock = data.getLong(ATTACK_LOCK_UNTIL);
        long lastAttack = data.getLong(LAST_ATTACK_TIME);
        if (lock > 0L && now < lock) {
            return false;
        }
        // Backward compatibility with old saves that only have LAST_ATTACK_TIME.
        return lastAttack <= 0L || now >= lastAttack + ATTACK_COOLDOWN_MIN_TICKS;
    }

    private static boolean falseAttackCooldownReady(CompoundTag data, long now) {
        long lastFalseAttack = data.getLong(LAST_FALSE_ATTACK);
        return lastFalseAttack <= 0L || now >= lastFalseAttack + seconds(240, 360);
    }

    private static void beginAttackCooldown(CompoundTag data, long now, boolean fromPeekAttack) {
        int cooldown = attackCooldown(data);
        if (fromPeekAttack && !data.getBoolean(CAT_CURSE)) {
            cooldown = Math.max(cooldown, seconds(7 * 60, 9 * 60));
        }
        data.putLong(LAST_ATTACK_TIME, now);
        data.putLong(ATTACK_LOCK_UNTIL, now + cooldown);
        data.putLong(EVENT_COOLDOWN, Math.max(data.getLong(EVENT_COOLDOWN), now + cooldown));
    }

    private static int attackCooldown(CompoundTag data) {
        if (data.getBoolean(CAT_CURSE)) {
            return seconds(90, 150);
        }
        int base = seconds(6 * 60, 8 * 60);
        base = Mth.floor(base * FriendConfig.ATTACK_FREQUENCY_MULTIPLIER.get());
        return Mth.clamp(base, ATTACK_COOLDOWN_MIN_TICKS, ATTACK_COOLDOWN_MAX_TICKS);
    }


    private static int lifetime(String event) {
        return switch (event) {
            case "window_watch" -> seconds(4, 9);
            case "attack_emerge" -> 20 * 42;
            case "false_attack" -> seconds(4, 7);
            case "cat_curse" -> 20 * 60 * 60;
            case "dream_chase" -> 20 * 90;
            case "bedside_watch" -> seconds(5, 10);
            case "night_far_watch" -> seconds(9, 18);
            case "doorway_lean" -> seconds(4, 8);
            case "route_ambush" -> seconds(3, 7);
            case "void_watch" -> seconds(8, 14);
            case "home_observation", "stare_event" -> seconds(3, 6);
            case "tunnel_blocking_silhouette", "double_appearance" -> seconds(5, 10);
            case "corner_peek", "hand_peek" -> seconds(3, 8);
            case "hole_peek", "ceiling_hole_peek", "floor_hole_peek" -> seconds(2, 5);
            case "low_ceiling_2_peek", "low_ceiling_1_peek" -> seconds(3, 6);
            case "final_close_presence" -> seconds(1, 2);
            default -> seconds(2, 4);
        };
    }

    private static int stareDelay(String event, int phase) {
        if (event.equals("stare_event")) return seconds(2, 3);
        if (event.equals("final_close_presence")) return 8;
        if (event.equals("delayed_disappear")) return 5 + RANDOM.nextInt(6);
        return phase >= 4 ? 10 + RANDOM.nextInt(16) : RANDOM.nextInt(8);
    }

    private static int seconds(int min, int max) {
        return (min + RANDOM.nextInt(Math.max(1, max - min + 1))) * 20;
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value * 10.0D) / 10.0D);
    }

    private enum SightState {
        NONE, PERIPHERAL, DIRECT
    }

    private record WeightedEvent(String id, int weight) {
    }

    private record WindowSpot(BlockPos glass, BlockPos feet) {
    }

    private record PeekSpot(BlockPos feet, BlockPos cover, Direction coverDirection) {
    }

    private record HomeScan(int score, int beds, int storage, int workstations, int doors, int windows, int lights, int shelter,
                            int interiorAir, int playerPlaced, int enclosure) {
    }
}
