package com.doxbi.friend.entity;

import com.doxbi.friend.event.FriendDirector;
import com.doxbi.friend.stalking.FriendStalkingDirector;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;

public class FriendEntity extends PathfinderMob implements GeoEntity {
    private static final EntityDataAccessor<String> DATA_EVENT =
            SynchedEntityData.defineId(FriendEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DATA_CORNER_ESCALATED =
            SynchedEntityData.defineId(FriendEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_RUSHING =
            SynchedEntityData.defineId(FriendEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> DATA_VARIANT =
            SynchedEntityData.defineId(FriendEntity.class, EntityDataSerializers.STRING);

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("animation.friend.idle_stare");
    private static final RawAnimation PEEK_SLOW_ANIM = RawAnimation.begin().thenPlay("animation.friend.peek");
    private static final RawAnimation HAND_PEEK_ANIM = RawAnimation.begin().thenPlay("animation.friend.hand_first_peek");
    private static final RawAnimation CEILING_HOLE_PEEK_ANIM = RawAnimation.begin().thenPlay("animation.friend.ceiling_hole_peek");
    private static final RawAnimation FLOOR_HOLE_PEEK_ANIM = RawAnimation.begin().thenPlay("animation.friend.floor_hole_peek");
    private static final RawAnimation LOW_CEILING_2_PEEK_ANIM = RawAnimation.begin().thenPlay("animation.friend.low_ceiling_2_peek");
    private static final RawAnimation LOW_CEILING_1_PEEK_ANIM = RawAnimation.begin().thenPlay("animation.friend.low_ceiling_1_peek");
    private static final RawAnimation ATTACK_EMERGE_ANIM = RawAnimation.begin()
            .thenPlay("animation.friend.attack_emerge")
            .thenLoop("animation.friend.attack_hold");
    private static final RawAnimation CHASE_RUN_ANIM = RawAnimation.begin().thenLoop("animation.friend.run");
    private static final RawAnimation CAT_CURSE_SPRINT_ANIM = RawAnimation.begin().thenLoop("animation.friend.cat_curse_sprint");
    private static final RawAnimation WINDOW_PRESS_ANIM = RawAnimation.begin().thenLoop("animation.friend.window_press");
    private static final RawAnimation VANISH_ANIM = RawAnimation.begin().thenPlay("animation.friend.vanish");
    private static final RawAnimation LOW_CEILING_ANIM = RawAnimation.begin().thenLoop("animation.friend.low_ceiling_lean");
    private static final RawAnimation WHITE_GRIEF_ANIM = RawAnimation.begin().thenLoop("animation.friend.white_grief");

    public static final String TAG_OWNER = "friend_owner";
    public static final String TAG_EVENT = "friend_event";
    public static final String TAG_LIFETIME = "friend_lifetime";
    public static final String TAG_STARE_DELAY = "friend_stare_delay";
    public static final String TAG_SEEN_TICKS = "friend_seen_ticks";
    public static final String TAG_CORNER_ESCALATED = "friend_corner_escalated";
    public static final String TAG_RUSHING = "friend_rushing";
    public static final String TAG_ATTACK_COOLDOWN = "friend_attack_cooldown";
    public static final String TAG_ATTACK_SCREAM_COOLDOWN = "friend_attack_scream_cooldown";
    public static final String TAG_VARIANT = "friend_variant";
    public static final String TAG_RAGE = "friend_rage";
    public static final String TAG_TRAP_BLINK_COOLDOWN = "friend_trap_blink_cooldown";
    public static final String TAG_COVER_X = "friend_cover_x";
    public static final String TAG_COVER_Y = "friend_cover_y";
    public static final String TAG_COVER_Z = "friend_cover_z";
    public static final String TAG_PEEK_PANIC_TICKS = "friend_peek_panic_ticks";
    public static final String TAG_STUCK_TICKS = "friend_stuck_ticks";
    public static final String TAG_AIRBORNE_TICKS = "friend_airborne_ticks";
    public static final String TAG_LAST_PROGRESS_X = "friend_last_progress_x";
    public static final String TAG_LAST_PROGRESS_Y = "friend_last_progress_y";
    public static final String TAG_LAST_PROGRESS_Z = "friend_last_progress_z";
    public static final String TAG_LAST_DISTANCE = "friend_last_distance";
    public static final String TAG_LAST_PATH_RECALC = "friend_last_path_recalc";
    public static final String TAG_DEBUG_FORCED_PEEK = "friend_debug_forced_peek";
    public static final String TAG_DEBUG_FORCED_PEEK_TICKS = "friend_debug_forced_peek_ticks";
    public static final String TAG_JUMP_COOLDOWN = "friend_jump_cooldown";
    public static final String TAG_REPATH_COOLDOWN = "friend_repath_cooldown";
    public static final String TAG_SIDESTEP_COOLDOWN = "friend_sidestep_cooldown";

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public FriendEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setNoAi(false);
        this.setNoGravity(false);
        this.noPhysics = false;
        this.setMaxUpStep(1.45F);
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 80.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.56D)
                .add(Attributes.FOLLOW_RANGE, 96.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.ATTACK_DAMAGE, 8.0D);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation navigation = new GroundPathNavigation(this, level);
        navigation.setCanFloat(true);
        navigation.setCanOpenDoors(false);
        navigation.setCanPassDoors(true);
        return navigation;
    }

    @Override
    protected void registerGoals() {
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_EVENT, "");
        this.entityData.define(DATA_CORNER_ESCALATED, false);
        this.entityData.define(DATA_RUSHING, false);
        this.entityData.define(DATA_VARIANT, "black");
    }

    @Override
    public void tick() {
        super.tick();
        String event = eventId();
        if (!this.level().isClientSide) {
            this.setGlowingTag(false);
        }
        boolean chase = isPhysicalChaseEvent(event);
        this.noPhysics = false;
        this.setNoGravity(false);
        this.setNoAi(false);
        this.setMaxUpStep(chase ? 1.65F : 1.05F);
        tickDebugForcedPeekLock();
        if (!chase && !FriendStalkingDirector.isStalkingEvent(event)) {
            this.getNavigation().stop();
            this.setDeltaMovement(this.getDeltaMovement().multiply(0.22D, 1.0D, 0.22D));
        }
        if (this.level().isClientSide) {
            com.doxbi.friend.client.FriendClientSounds.tickLongScream(this);
        } else {
            FriendDirector.tickFriend(this);
        }
    }

    private boolean isPhysicalChaseEvent(String event) {
        return rushing() || event.equals("cat_curse") || event.equals("attack_emerge") || event.equals("false_attack");
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        String event = eventId();
        if (event.equals("cat_curse") || event.equals("white_grief") || event.equals("paradise_guide")
                || event.equals("final_sentence") || event.equals("sky_dialog") || event.equals("void_watch")) {
            return false;
        }
        if (!this.level().isClientSide) {
            FriendDirector.friendHurtByPlayer(this, source);
        }
        return super.hurt(source, amount);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Nullable
    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return null;
    }

    @Nullable
    @Override
    protected SoundEvent getDeathSound() {
        return null;
    }

    public void configure(String ownerUuid, String eventId, int lifetime, int stareDelay) {
        CompoundTag tag = this.getPersistentData();
        tag.putString(TAG_OWNER, ownerUuid);
        tag.putString(TAG_EVENT, eventId);
        tag.putInt(TAG_LIFETIME, lifetime);
        tag.putInt(TAG_STARE_DELAY, stareDelay);
        tag.putInt(TAG_SEEN_TICKS, 0);
        tag.putBoolean(TAG_CORNER_ESCALATED, false);
        tag.putBoolean(TAG_RUSHING, false);
        tag.putLong(TAG_ATTACK_COOLDOWN, 0L);
        tag.putLong(TAG_ATTACK_SCREAM_COOLDOWN, 0L);
        tag.putLong(TAG_TRAP_BLINK_COOLDOWN, 0L);
        tag.putLong(TAG_JUMP_COOLDOWN, 0L);
        tag.putLong(TAG_REPATH_COOLDOWN, 0L);
        tag.putLong(TAG_SIDESTEP_COOLDOWN, 0L);
        tag.putBoolean(TAG_DEBUG_FORCED_PEEK, false);
        tag.putInt(TAG_DEBUG_FORCED_PEEK_TICKS, 0);
        tag.putBoolean(TAG_RAGE, eventId.equals("cat_curse"));
        tag.putInt(TAG_PEEK_PANIC_TICKS, 0);
        tag.putInt(TAG_STUCK_TICKS, 0);
        tag.putInt(TAG_AIRBORNE_TICKS, 0);
        tag.putDouble(TAG_LAST_PROGRESS_X, this.getX());
        tag.putDouble(TAG_LAST_PROGRESS_Y, this.getY());
        tag.putDouble(TAG_LAST_PROGRESS_Z, this.getZ());
        tag.putDouble(TAG_LAST_DISTANCE, 99999.0D);
        tag.putLong(TAG_LAST_PATH_RECALC, 0L);
        String variant = eventId.equals("white_grief") || eventId.equals("paradise_guide") || eventId.equals("sky_dialog") ? "white" : "black";
        tag.putString(TAG_VARIANT, variant);
        this.entityData.set(DATA_EVENT, eventId);
        this.entityData.set(DATA_CORNER_ESCALATED, false);
        this.entityData.set(DATA_RUSHING, false);
        this.entityData.set(DATA_VARIANT, variant);
        this.setGlowingTag(false);
    }

    public void setEventId(String eventId) {
        this.getPersistentData().putString(TAG_EVENT, eventId);
        this.entityData.set(DATA_EVENT, eventId);
    }

    public String ownerUuid() {
        return this.getPersistentData().getString(TAG_OWNER);
    }

    public String eventId() {
        String syncedEvent = this.entityData.get(DATA_EVENT);
        return syncedEvent.isBlank() ? this.getPersistentData().getString(TAG_EVENT) : syncedEvent;
    }

    public boolean cornerEscalated() {
        return this.entityData.get(DATA_CORNER_ESCALATED) || this.getPersistentData().getBoolean(TAG_CORNER_ESCALATED);
    }

    public void setCornerEscalated(boolean value) {
        this.getPersistentData().putBoolean(TAG_CORNER_ESCALATED, value);
        this.entityData.set(DATA_CORNER_ESCALATED, value);
    }

    public boolean rushing() {
        return this.entityData.get(DATA_RUSHING) || this.getPersistentData().getBoolean(TAG_RUSHING);
    }

    public void setRushing(boolean value) {
        this.getPersistentData().putBoolean(TAG_RUSHING, value);
        this.entityData.set(DATA_RUSHING, value);
    }

    public boolean rage() {
        return this.getPersistentData().getBoolean(TAG_RAGE);
    }

    public void setRage(boolean value) {
        this.getPersistentData().putBoolean(TAG_RAGE, value);
    }

    public String visualVariant() {
        String synced = this.entityData.get(DATA_VARIANT);
        return synced == null || synced.isBlank() ? this.getPersistentData().getString(TAG_VARIANT) : synced;
    }

    public void setVisualVariant(String variant) {
        String normalized = "white".equals(variant) ? "white" : "black";
        this.getPersistentData().putString(TAG_VARIANT, normalized);
        this.entityData.set(DATA_VARIANT, normalized);
    }

    public void startDebugForcedPeek(int ticks) {
        CompoundTag tag = this.getPersistentData();
        tag.putBoolean(TAG_DEBUG_FORCED_PEEK, true);
        tag.putInt(TAG_DEBUG_FORCED_PEEK_TICKS, Math.max(20, ticks));
        this.noActionTime = 0;
        this.getNavigation().stop();
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        this.setInvisible(false);
    }

    public boolean isDebugForcedPeekActive() {
        CompoundTag tag = this.getPersistentData();
        return tag.getBoolean(TAG_DEBUG_FORCED_PEEK) && tag.getInt(TAG_DEBUG_FORCED_PEEK_TICKS) > 0;
    }

    private void tickDebugForcedPeekLock() {
        CompoundTag tag = this.getPersistentData();
        if (!tag.getBoolean(TAG_DEBUG_FORCED_PEEK)) {
            return;
        }
        int ticks = tag.getInt(TAG_DEBUG_FORCED_PEEK_TICKS);
        if (ticks <= 0) {
            tag.putBoolean(TAG_DEBUG_FORCED_PEEK, false);
            return;
        }
        tag.putInt(TAG_DEBUG_FORCED_PEEK_TICKS, ticks - 1);
        this.noActionTime = 0;
        if (FriendStalkingDirector.isStalkingEvent(eventId())) {
            this.getNavigation().stop();
            this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "friend_main", 4, state -> {
            String event = eventId();
            if (event.equals("cat_curse")) {
                return state.setAndContinue(CAT_CURSE_SPRINT_ANIM);
            }
            if (event.equals("white_grief") || event.equals("paradise_guide") || event.equals("final_sentence")) {
                return state.setAndContinue(event.equals("white_grief") ? WHITE_GRIEF_ANIM : IDLE_ANIM);
            }
            if (rushing()) {
                return state.setAndContinue(CHASE_RUN_ANIM);
            }
            if (event.equals("dream_chase") || event.equals("dream_bed")) {
                return state.setAndContinue(IDLE_ANIM);
            }
            if (event.equals("attack_emerge")) {
                return state.setAndContinue(ATTACK_EMERGE_ANIM);
            }
            if (event.equals("false_attack")) {
                return state.setAndContinue(CHASE_RUN_ANIM);
            }
            if (event.equals("vanish")) {
                return state.setAndContinue(VANISH_ANIM);
            }
            if (FriendStalkingDirector.isStalkingEvent(event)) {
                return state.setAndContinue(stalkingAnimation(event));
            }
            if (event.equals("corner_peek") || event.equals("hand_peek") || event.equals("hole_peek")
                    || event.equals("ceiling_hole_peek") || event.equals("floor_hole_peek")
                    || event.equals("low_ceiling_2_peek") || event.equals("low_ceiling_1_peek") || event.equals("doorway_lean")) {
                if (cornerEscalated()) {
                    return state.setAndContinue(ATTACK_EMERGE_ANIM);
                }
                if (event.equals("ceiling_hole_peek")) {
                    return state.setAndContinue(CEILING_HOLE_PEEK_ANIM);
                }
                if (event.equals("floor_hole_peek")) {
                    return state.setAndContinue(FLOOR_HOLE_PEEK_ANIM);
                }
                if (event.equals("low_ceiling_2_peek")) {
                    return state.setAndContinue(LOW_CEILING_2_PEEK_ANIM);
                }
                if (event.equals("low_ceiling_1_peek")) {
                    return state.setAndContinue(LOW_CEILING_1_PEEK_ANIM);
                }
                return state.setAndContinue(event.equals("hand_peek") || event.equals("hole_peek") ? HAND_PEEK_ANIM : PEEK_SLOW_ANIM);
            }
            if (event.equals("window_watch") || event.equals("home_observation")) {
                return state.setAndContinue(WINDOW_PRESS_ANIM);
            }
            if (hasLowCeiling()) {
                return state.setAndContinue(LOW_CEILING_ANIM);
            }
            if (event.equals("stare_event") || event.equals("final_close_presence") || event.equals("tunnel_blocking_silhouette")
                    || event.equals("bedside_watch") || event.equals("route_ambush") || event.equals("void_watch") || event.equals("sky_dialog")) {
                return state.setAndContinue(IDLE_ANIM);
            }
            return state.setAndContinue(IDLE_ANIM);
        })); 
    }

    private static RawAnimation stalkingAnimation(String event) {
        return switch (event) {
            case "stalk_slip_left" -> RawAnimation.begin().thenPlay("animation.friend.slip_left");
            case "stalk_slip_right" -> RawAnimation.begin().thenPlay("animation.friend.slip_right");
            default -> RawAnimation.begin().thenPlay(FriendStalkingDirector.animationName(event));
        };
    }

    private boolean hasLowCeiling() {
        BlockPos base = this.blockPosition();
        for (int y = 1; y <= 3; y++) {
            BlockPos check = base.above(y);
            if (!this.level().getBlockState(check).isAir()
                    || !this.level().getBlockState(check.relative(this.getDirection())).isAir()
                    || !this.level().getBlockState(check.relative(this.getDirection().getClockWise())).isAir()
                    || !this.level().getBlockState(check.relative(this.getDirection().getCounterClockWise())).isAir()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
