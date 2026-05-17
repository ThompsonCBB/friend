package com.doxbi.friend.client;

import com.doxbi.friend.entity.FriendEntity;
import com.doxbi.friend.registry.FriendSoundEvents;
import com.doxbi.friend.world.FriendDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public final class FriendClientSounds {
    private static final Map<Integer, FriendScreamSound> ACTIVE_SCREAMS = new HashMap<>();
    private static final String FIRST_CONTACT_CLIENT_TAG = "friend_first_contact_roar";
    private static CatSinBackgroundSound CAT_SIN_BACKGROUND;
    private static FirstContactRoarSound FIRST_CONTACT_ROAR;
    private static boolean firstContactTagSeen;

    private FriendClientSounds() {
    }

    public static void tickLongScream(FriendEntity friend) {
        cleanup();
        int id = friend.getId();
        FriendScreamSound active = ACTIVE_SCREAMS.get(id);
        if (!shouldScream(friend)) {
            if (active != null) {
                active.forceStop();
                ACTIVE_SCREAMS.remove(id);
            }
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (active == null || active.isStopped() || !minecraft.getSoundManager().isActive(active)) {
            FriendScreamSound sound = new FriendScreamSound(friend);
            ACTIVE_SCREAMS.put(id, sound);
            minecraft.getSoundManager().play(sound);
        }
    }

    public static void tickScriptedOneShots() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            firstContactTagSeen = false;
            if (FIRST_CONTACT_ROAR != null) {
                FIRST_CONTACT_ROAR.forceStop();
                FIRST_CONTACT_ROAR = null;
            }
            return;
        }

        boolean hasFirstContactTag = minecraft.player.getTags().contains(FIRST_CONTACT_CLIENT_TAG);
        if (hasFirstContactTag && !firstContactTagSeen) {
            if (FIRST_CONTACT_ROAR != null) {
                FIRST_CONTACT_ROAR.forceStop();
            }
            FIRST_CONTACT_ROAR = new FirstContactRoarSound();
            minecraft.getSoundManager().play(FIRST_CONTACT_ROAR);
            firstContactTagSeen = true;
        } else if (!hasFirstContactTag) {
            firstContactTagSeen = false;
        }

        if (FIRST_CONTACT_ROAR != null && (FIRST_CONTACT_ROAR.isStopped() || !minecraft.getSoundManager().isActive(FIRST_CONTACT_ROAR))) {
            FIRST_CONTACT_ROAR.forceStop();
            FIRST_CONTACT_ROAR = null;
        }
    }

    public static void tickCatSinBackground() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean shouldPlay = minecraft.level != null && minecraft.player != null
                && minecraft.player.getTags().contains("friend_cat_sin")
                && !minecraft.player.getTags().contains("friend_grief_silence")
                && !minecraft.level.dimension().equals(FriendDimensions.FIFTH);
        if (!shouldPlay) {
            if (CAT_SIN_BACKGROUND != null) {
                CAT_SIN_BACKGROUND.forceStop();
                CAT_SIN_BACKGROUND = null;
            }
            return;
        }
        if (CAT_SIN_BACKGROUND == null || CAT_SIN_BACKGROUND.isStopped() || !minecraft.getSoundManager().isActive(CAT_SIN_BACKGROUND)) {
            CAT_SIN_BACKGROUND = new CatSinBackgroundSound();
            minecraft.getSoundManager().play(CAT_SIN_BACKGROUND);
        }
    }

    private static void cleanup() {
        Minecraft minecraft = Minecraft.getInstance();
        Iterator<Map.Entry<Integer, FriendScreamSound>> iterator = ACTIVE_SCREAMS.entrySet().iterator();
        while (iterator.hasNext()) {
            FriendScreamSound sound = iterator.next().getValue();
            if (sound.isStopped() || !sound.isEntityValid() || !minecraft.getSoundManager().isActive(sound)) {
                sound.forceStop();
                iterator.remove();
            }
        }
    }

    private static boolean shouldScream(FriendEntity friend) {
        if (friend.isRemoved() || !friend.isAlive()) {
            return false;
        }
        String event = friend.eventId();
        return event.equals("cat_curse")
                || event.equals("dream_chase")
                || (event.equals("attack_emerge") || event.equals("false_attack"))
                || friend.rushing()
                || friend.cornerEscalated();
    }

    private static final class FriendScreamSound extends AbstractTickableSoundInstance {
        private final FriendEntity friend;

        private FriendScreamSound(FriendEntity friend) {
            super(FriendSoundEvents.ATTACK_SCREAM.get(), SoundSource.HOSTILE, RandomSource.create());
            this.friend = friend;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.01F;
            this.pitch = friend.eventId().equals("cat_curse") ? 0.88F : 0.96F;
            this.attenuation = SoundInstance.Attenuation.NONE;
            updatePosition();
        }

        @Override
        public void tick() {
            if (!isEntityValid() || !shouldScream(friend)) {
                stop();
                return;
            }

            // Ramp in, then keep screaming at full power while Friend is rushing.
            // Do not fade out during chase.
            float targetVolume = friend.eventId().equals("cat_curse") ? 2.55F : 2.25F;
            this.volume = Math.min(targetVolume, this.volume + 0.10F);
            updatePosition();
        }

        private boolean isEntityValid() {
            return !friend.isRemoved() && friend.isAlive();
        }

        private void forceStop() {
            stop();
        }

        private void updatePosition() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                this.x = friend.getX();
                this.y = friend.getY() + friend.getBbHeight() * 0.65D;
                this.z = friend.getZ();
                return;
            }

            // Listener-anchored scream: the sound is psychological/scene-wide, not a block at Friend's feet.
            // This keeps the attack scream audible even if the player flies away quickly in creative.
            var player = minecraft.player;
            var direction = friend.position().subtract(player.position()).multiply(1.0D, 0.0D, 1.0D);
            if (direction.lengthSqr() < 0.0001D) {
                direction = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
            }
            if (direction.lengthSqr() < 0.0001D) {
                direction = new net.minecraft.world.phys.Vec3(0.0D, 0.0D, 1.0D);
            }
            direction = direction.normalize();
            this.x = player.getX() + direction.x * 2.5D;
            this.y = player.getEyeY();
            this.z = player.getZ() + direction.z * 2.5D;
        }
    }

    private static final class FirstContactRoarSound extends AbstractTickableSoundInstance {
        private int age;

        private FirstContactRoarSound() {
            super(FriendSoundEvents.MINE_DISTANT_SCREAM.get(), SoundSource.HOSTILE, RandomSource.create());
            this.looping = false;
            this.delay = 0;
            this.volume = 1.65F;
            this.pitch = 0.68F + RandomSource.create().nextFloat() * 0.08F;
            this.attenuation = SoundInstance.Attenuation.NONE;
            updatePosition();
        }

        @Override
        public void tick() {
            age++;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || minecraft.player == null || age > 20 * 18) {
                stop();
                return;
            }
            updatePosition();
        }

        private void updatePosition() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return;
            }
            var player = minecraft.player;
            var look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
            if (look.lengthSqr() < 0.0001D) {
                look = new net.minecraft.world.phys.Vec3(0.0D, 0.0D, 1.0D);
            }
            look = look.normalize();
            this.x = player.getX() - look.x * 12.0D;
            this.y = player.getEyeY();
            this.z = player.getZ() - look.z * 12.0D;
        }

        private void forceStop() {
            stop();
        }
    }

    private static final class CatSinBackgroundSound extends AbstractTickableSoundInstance {
        private CatSinBackgroundSound() {
            super(FriendSoundEvents.CAT_SIN_BACKGROUND.get(), SoundSource.AMBIENT, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.065F;
            this.pitch = 1.0F;
            this.attenuation = SoundInstance.Attenuation.NONE;
            updatePosition();
        }

        @Override
        public void tick() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || minecraft.player == null || !minecraft.player.getTags().contains("friend_cat_sin")
                    || minecraft.player.getTags().contains("friend_grief_silence")
                    || minecraft.level.dimension().equals(FriendDimensions.FIFTH)) {
                stop();
                return;
            }
            updatePosition();
        }

        private void updatePosition() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return;
            }
            var look = minecraft.player.getLookAngle();
            this.x = minecraft.player.getX() - look.x * 4.0D;
            this.y = minecraft.player.getY() + 1.4D;
            this.z = minecraft.player.getZ() - look.z * 4.0D;
        }

        private void forceStop() {
            stop();
        }
    }
}
