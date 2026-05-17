package com.doxbi.friend.client;

import com.doxbi.friend.entity.FriendEntity;
import com.doxbi.friend.registry.FriendSoundEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Optional;

public final class FriendMineHorrorSoundManager {
    private static final EnumMap<MineSoundType, MineHorrorSound> ACTIVE_SOUNDS = new EnumMap<>(MineSoundType.class);
    private static final EnumMap<MineSoundType, Long> COOLDOWNS = new EnumMap<>(MineSoundType.class);
    private static final RandomSource RANDOM = RandomSource.create();
    private static long nextGlobalEventTick;
    private static long silenceUntil;
    private static int undergroundTicks;
    private static double globalDampen = 1.0D;

    private static final long MINE_ENTRY_SCREAM_COOLDOWN = 20L * 60L * 20L;
    private static boolean firstMineEntryScreamDone;
    private static boolean wasInMediumOrLargeMine;
    private static int mineEntryConfidenceTicks;
    private static int mineExitConfidenceTicks;
    private static long nextMineEntryScreamTick;
    private static long mineEventActiveUntil;

    private FriendMineHorrorSoundManager() {
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            stopAll();
            return;
        }

        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        cleanup(minecraft);

        boolean mediumOrLargeMine = isMediumOrLargeMine(level, player);
        tickMineEntranceScream(minecraft, level, player, mediumOrLargeMine);

        if (!isValidMineEnvironment(level, player)) {
            undergroundTicks = 0;
            globalDampen = 0.0D;
            fadeAll();
            nextGlobalEventTick = level.getGameTime() + 200L;
            return;
        }

        undergroundTicks = Math.min(20 * 60 * 20, undergroundTicks + 1);
        boolean busy = isBusyHorrorMoment(level, player);
        globalDampen = busy ? 0.45D : 1.0D;
        if (busy || level.getGameTime() < nextGlobalEventTick || level.getGameTime() < silenceUntil || ACTIVE_SOUNDS.size() >= 2) {
            return;
        }

        double chance = eventChance(level, player);
        if (RANDOM.nextDouble() > chance) {
            nextGlobalEventTick = level.getGameTime() + 35L + RANDOM.nextInt(90);
            return;
        }

        MineSoundType type = chooseType(level, player);
        if (type == null) {
            nextGlobalEventTick = level.getGameTime() + 45L + RANDOM.nextInt(120);
            return;
        }

        Optional<SoundAnchor> anchor = findAnchor(level, player, type);
        MineHorrorSound active = ACTIVE_SOUNDS.get(type);
        if (active != null && !active.isFinished()) {
            if (!active.isFadingOut() && anchor.isPresent()) {
                active.softRetarget(anchor.get());
            }
            nextGlobalEventTick = level.getGameTime() + 45L + RANDOM.nextInt(120);
            return;
        }
        if (level.getGameTime() < COOLDOWNS.getOrDefault(type, 0L)) {
            nextGlobalEventTick = level.getGameTime() + 45L + RANDOM.nextInt(120);
            return;
        }
        if (anchor.isEmpty()) {
            nextGlobalEventTick = level.getGameTime() + 60L + RANDOM.nextInt(150);
            return;
        }

        MineHorrorSound sound = new MineHorrorSound(level, player, type, anchor.get());
        ACTIVE_SOUNDS.put(type, sound);
        minecraft.getSoundManager().play(sound);
        COOLDOWNS.put(type, level.getGameTime() + type.cooldown());
        long pause = type == MineSoundType.DISTANT_SCREAM
                ? 20L * (70 + RANDOM.nextInt(130))
                : 20L * (18 + RANDOM.nextInt(48));
        nextGlobalEventTick = level.getGameTime() + pause;
        if (RANDOM.nextFloat() < 0.05F) {
            silenceUntil = level.getGameTime() + 20L * (45 + RANDOM.nextInt(95));
        }
    }

    static double globalDampen() {
        return globalDampen;
    }

    private static boolean isValidMineEnvironment(ClientLevel level, LocalPlayer player) {
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            return false;
        }
        return isMediumOrLargeMine(level, player) || isSmallMinePocket(level, player);
    }

    private static boolean isMediumOrLargeMine(ClientLevel level, LocalPlayer player) {
        BlockPos center = player.blockPosition();
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
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
        int caveStone = 0;
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
                        caveStone++;
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
        boolean naturalEnough = caveStone >= 24 || caveBiome || center.getY() <= 42;

        return enclosed && naturalEnough && (largeAirVolume || mediumAirVolume || (caveBiome && enclosedAir >= 20 && darkAir >= 10));
    }

    private static boolean isSmallMinePocket(ClientLevel level, LocalPlayer player) {
        BlockPos pos = player.blockPosition();
        if (pos.getY() > 58 && level.canSeeSky(pos)) {
            return false;
        }
        int stone = 0;
        int darkAir = 0;
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = -3; y <= 4; y++) {
                    cursor.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    BlockState state = level.getBlockState(cursor);
                    if (isNaturalMineBlock(state)) {
                        stone++;
                    }
                    if (state.isAir() && level.getBrightness(LightLayer.BLOCK, cursor) <= 5 && !level.canSeeSky(cursor)) {
                        darkAir++;
                    }
                }
            }
        }
        return pos.getY() <= 46 || (skyLight <= 4 && stone > 36) || darkAir > 18;
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

    private static void tickMineEntranceScream(Minecraft minecraft, ClientLevel level, LocalPlayer player, boolean mediumOrLargeMine) {
        long now = level.getGameTime();

        if (now >= mineEventActiveUntil && player.getTags().contains("friend_mine_event_active")) {
            player.removeTag("friend_mine_event_active");
        }

        if (mediumOrLargeMine) {
            mineEntryConfidenceTicks = Math.min(mineEntryConfidenceTicks + 1, 80);
            mineExitConfidenceTicks = 0;
        } else {
            mineExitConfidenceTicks++;
            if (mineExitConfidenceTicks >= 90) {
                wasInMediumOrLargeMine = false;
                mineEntryConfidenceTicks = 0;
            }
            return;
        }

        if (wasInMediumOrLargeMine || mineEntryConfidenceTicks < 35) {
            return;
        }

        wasInMediumOrLargeMine = true;
        if (now < nextMineEntryScreamTick) {
            return;
        }

        boolean shouldScream = !firstMineEntryScreamDone || RANDOM.nextFloat() < 0.40F;
        firstMineEntryScreamDone = true;
        nextMineEntryScreamTick = now + MINE_ENTRY_SCREAM_COOLDOWN;

        if (!shouldScream) {
            return;
        }

        Optional<SoundAnchor> anchor = findAnchor(level, player, MineSoundType.DISTANT_SCREAM);
        SoundAnchor chosen = anchor.orElseGet(() -> mineEntranceFallbackAnchor(level, player));
        MineHorrorSound active = ACTIVE_SOUNDS.get(MineSoundType.DISTANT_SCREAM);
        if (active != null && !active.isFinished()) {
            active.softRetarget(chosen);
        } else {
            MineHorrorSound sound = new MineHorrorSound(level, player, MineSoundType.DISTANT_SCREAM, chosen);
            ACTIVE_SOUNDS.put(MineSoundType.DISTANT_SCREAM, sound);
            minecraft.getSoundManager().play(sound);
        }

        COOLDOWNS.put(MineSoundType.DISTANT_SCREAM, now + MineSoundType.DISTANT_SCREAM.cooldown());
        nextGlobalEventTick = Math.max(nextGlobalEventTick, now + 20L * 70L);
        silenceUntil = Math.max(silenceUntil, now + 20L * 10L);
        mineEventActiveUntil = now + 20L * 180L;
        player.addTag("friend_mine_event_active");
    }

    private static SoundAnchor mineEntranceFallbackAnchor(ClientLevel level, LocalPlayer player) {
        Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
        if (look.lengthSqr() < 0.001D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        look = look.normalize();
        Vec3 raw = player.getEyePosition().add(look.scale(46.0D)).add(0.0D, -7.0D, 0.0D);
        BlockPos pocket = findNearbyAirPocket(level, BlockPos.containing(raw), 10);
        Vec3 pos = pocket == null ? raw : pocket.getCenter();
        return new SoundAnchor(pos, look.scale(0.004D), false);
    }

    private static boolean isBusyHorrorMoment(ClientLevel level, LocalPlayer player) {
        if (player.hurtTime > 0 || player.getTags().contains("friend_cat_sin")) {
            return true;
        }
        return !level.getEntitiesOfClass(FriendEntity.class, player.getBoundingBox().inflate(80.0D),
                friend -> friend.rushing()
                        || friend.cornerEscalated()
                        || friend.eventId().equals("attack_emerge")
                        || friend.eventId().equals("cat_curse")
                        || friend.eventId().equals("dream_chase")
                        || friend.eventId().equals("white_grief")).isEmpty();
    }

    private static double eventChance(ClientLevel level, LocalPlayer player) {
        double depth = Mth.clamp((60.0D - player.getY()) / 52.0D, 0.0D, 1.0D);
        double time = Mth.clamp(undergroundTicks / (20.0D * 360.0D), 0.0D, 1.0D);
        double darkness = Mth.clamp((10.0D - level.getBrightness(LightLayer.BLOCK, player.blockPosition())) / 10.0D, 0.0D, 1.0D);
        double movement = player.getDeltaMovement().horizontalDistance() > 0.035D ? 0.055D : 0.075D;
        if (Minecraft.getInstance().options.keyAttack.isDown()) {
            movement += 0.035D;
        }
        if (isLookingIntoDark(level, player)) {
            movement += 0.055D;
        }
        return Mth.clamp(0.085D + depth * 0.13D + time * 0.08D + darkness * 0.065D + movement, 0.10D, 0.44D);
    }

    private static MineSoundType chooseType(ClientLevel level, LocalPlayer player) {
        int depth = player.blockPosition().getY();
        int roll = RANDOM.nextInt(100);
        if (depth <= 24 && roll < 7) {
            return MineSoundType.DISTANT_SCREAM;
        }
        if (Minecraft.getInstance().options.keyAttack.isDown() && roll < 34) {
            return MineSoundType.WALL_SCRAPE;
        }
        if (player.getDeltaMovement().horizontalDistanceSqr() > 0.002D && roll < 66) {
            return MineSoundType.CRAWL;
        }
        return roll < 78 ? MineSoundType.THROAT_CLICKS : MineSoundType.WALL_SCRAPE;
    }

    private static boolean isLookingIntoDark(ClientLevel level, LocalPlayer player) {
        Vec3 look = player.getLookAngle().normalize();
        for (int i = 5; i <= 22; i += 3) {
            BlockPos pos = BlockPos.containing(player.getEyePosition().add(look.scale(i)));
            if (level.hasChunkAt(pos) && level.getBlockState(pos).isAir() && level.getBrightness(LightLayer.BLOCK, pos) <= 3 && !level.canSeeSky(pos)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<SoundAnchor> findAnchor(ClientLevel level, LocalPlayer player, MineSoundType type) {
        SoundAnchor best = null;
        double bestScore = -9999.0D;
        int minDistance = type == MineSoundType.DISTANT_SCREAM ? 30 : 8;
        int maxDistance = type == MineSoundType.DISTANT_SCREAM ? 62 : type == MineSoundType.WALL_SCRAPE ? 30 : 24;
        for (int i = 0; i < 96; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0D;
            double distance = minDistance + RANDOM.nextDouble() * (maxDistance - minDistance);
            int yOffset = switch (type) {
                case THROAT_CLICKS -> RANDOM.nextInt(9) - 2;
                case WALL_SCRAPE -> RANDOM.nextInt(7) - 3;
                case DISTANT_SCREAM -> RANDOM.nextInt(19) - 11;
                case CRAWL -> RANDOM.nextBoolean() ? 3 + RANDOM.nextInt(6) : -2 - RANDOM.nextInt(5);
            };
            BlockPos rough = BlockPos.containing(player.getX() + Math.cos(angle) * distance,
                    player.getY() + yOffset,
                    player.getZ() + Math.sin(angle) * distance);
            BlockPos pocket = findNearbyAirPocket(level, rough, type == MineSoundType.DISTANT_SCREAM ? 8 : 5);
            if (pocket == null) {
                continue;
            }
            Vec3 pos = pocket.getCenter();
            boolean lineOfSight = hasLineOfSight(level, player.getEyePosition(), pos);
            double wall = wallScore(level, pocket);
            double dark = Mth.clamp(10.0D - level.getBrightness(LightLayer.BLOCK, pocket), 0.0D, 10.0D);
            double vertical = Math.abs(pocket.getY() - player.blockPosition().getY());
            double occlusion = lineOfSight ? -8.0D : 18.0D;
            double typeBonus = switch (type) {
                case THROAT_CLICKS -> wall + vertical * 0.7D;
                case WALL_SCRAPE -> wall * 1.8D;
                case DISTANT_SCREAM -> dark * 1.5D + (pocket.getY() <= 30 ? 18.0D : -12.0D);
                case CRAWL -> wall + vertical * 1.4D;
            };
            double score = dark * 1.2D + occlusion + typeBonus - Math.abs(player.distanceToSqr(pos) - distance * distance) * 0.002D;
            if (score > bestScore) {
                bestScore = score;
                Vec3 drift = driftVector(level, pocket, player, type);
                best = new SoundAnchor(pos, drift, lineOfSight);
            }
        }
        return bestScore < 10.0D ? Optional.empty() : Optional.of(best);
    }

    private static BlockPos findNearbyAirPocket(ClientLevel level, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestScore = -9999.0D;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int radiusSq = radius * radius;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radiusSq) {
                    continue;
                }
                for (int y = -radius; y <= radius; y++) {
                    mutable.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!level.hasChunkAt(mutable) || !level.getBlockState(mutable).isAir()) {
                        continue;
                    }
                    if (level.getBrightness(LightLayer.BLOCK, mutable) > 7) {
                        continue;
                    }
                    double score = wallScore(level, mutable) * 2.0D + (10 - level.getBrightness(LightLayer.BLOCK, mutable));
                    if (score > bestScore) {
                        bestScore = score;
                        best = mutable.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static double wallScore(ClientLevel level, BlockPos pos) {
        double score = 0.0D;
        for (Direction direction : Direction.values()) {
            BlockPos side = pos.relative(direction);
            BlockState state = level.getBlockState(side);
            if (!state.getCollisionShape(level, side).isEmpty()) {
                score += direction.getAxis().isVertical() ? 1.2D : 2.0D;
            }
        }
        return score;
    }

    private static Vec3 driftVector(ClientLevel level, BlockPos pocket, LocalPlayer player, MineSoundType type) {
        Vec3 toPlayer = player.position().subtract(pocket.getCenter()).multiply(1.0D, 0.0D, 1.0D);
        if (toPlayer.lengthSqr() < 0.001D) {
            toPlayer = new Vec3(RANDOM.nextDouble() - 0.5D, 0.0D, RANDOM.nextDouble() - 0.5D);
        }
        Vec3 parallel = new Vec3(-toPlayer.z, 0.0D, toPlayer.x).normalize();
        if (RANDOM.nextBoolean()) {
            parallel = parallel.scale(-1.0D);
        }
        return switch (type) {
            case THROAT_CLICKS -> parallel.scale(0.010D).add(0.0D, RANDOM.nextBoolean() ? 0.004D : -0.004D, 0.0D);
            case WALL_SCRAPE -> parallel.scale(0.026D);
            case DISTANT_SCREAM -> parallel.scale(0.004D);
            case CRAWL -> parallel.scale(0.034D).add(0.0D, RANDOM.nextBoolean() ? 0.010D : -0.010D, 0.0D);
        };
    }

    private static boolean hasLineOfSight(ClientLevel level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).getType() == HitResult.Type.MISS;
    }

    private static void cleanup(Minecraft minecraft) {
        ACTIVE_SOUNDS.entrySet().removeIf(entry -> {
            MineHorrorSound sound = entry.getValue();
            return sound.isFinished() || sound.isStopped() || !minecraft.getSoundManager().isActive(sound);
        });
    }

    private static void fadeAll() {
        for (MineHorrorSound sound : ACTIVE_SOUNDS.values()) {
            sound.requestFadeOut();
        }
    }

    private static void stopAll() {
        for (MineHorrorSound sound : ACTIVE_SOUNDS.values()) {
            sound.forceStop();
        }
        ACTIVE_SOUNDS.clear();
        COOLDOWNS.clear();
        undergroundTicks = 0;
        globalDampen = 1.0D;
        nextGlobalEventTick = 0L;
        silenceUntil = 0L;
        wasInMediumOrLargeMine = false;
        mineEntryConfidenceTicks = 0;
        mineExitConfidenceTicks = 0;
        mineEventActiveUntil = 0L;
    }

    private enum MineSoundType {
        THROAT_CLICKS(0.18F, 0.34F, 42, 28, 170, 34, 20L * 42L),
        WALL_SCRAPE(0.23F, 0.35F, 44, 28, 230, 46, 20L * 52L),
        DISTANT_SCREAM(0.36F, 0.55F, 96, 42, 210, 58, 20L * 145L),
        CRAWL(0.25F, 0.36F, 46, 26, 178, 38, 20L * 48L);

        private final float baseVolume;
        private final float maxVolume;
        private final double maxDistance;
        private final int fadeInTicks;
        private final int durationTicks;
        private final int fadeOutTicks;
        private final long cooldown;

        MineSoundType(float baseVolume, float maxVolume, double maxDistance, int fadeInTicks, int durationTicks, int fadeOutTicks, long cooldown) {
            this.baseVolume = baseVolume;
            this.maxVolume = maxVolume;
            this.maxDistance = maxDistance;
            this.fadeInTicks = fadeInTicks;
            this.durationTicks = durationTicks;
            this.fadeOutTicks = fadeOutTicks;
            this.cooldown = cooldown;
        }

        private SoundEvent sound() {
            return switch (this) {
                case THROAT_CLICKS -> FriendSoundEvents.MINE_THROAT_CLICKS.get();
                case WALL_SCRAPE -> FriendSoundEvents.MINE_WALL_SCRAPE.get();
                case DISTANT_SCREAM -> FriendSoundEvents.MINE_DISTANT_SCREAM.get();
                case CRAWL -> FriendSoundEvents.MINE_CRAWL.get();
            };
        }

        private long cooldown() {
            return cooldown + RANDOM.nextInt(20 * 70);
        }

        private int fadeStartTick() {
            return Math.max(fadeInTicks + 4, durationTicks - fadeOutTicks - 4);
        }
    }

    private record SoundAnchor(Vec3 position, Vec3 drift, boolean directLineOfSight) {
    }

    private static final class MineHorrorSound extends AbstractTickableSoundInstance {
        private final MineSoundType type;
        private final ClientLevel level;
        private final LocalPlayer player;
        private Vec3 position;
        private Vec3 drift;
        private int age;
        private int fadeOutAge;
        private boolean fadingOut;
        private boolean finished;
        private float currentVolume;

        private MineHorrorSound(ClientLevel level, LocalPlayer player, MineSoundType type, SoundAnchor anchor) {
            super(type.sound(), SoundSource.HOSTILE, RandomSource.create());
            this.type = type;
            this.level = level;
            this.player = player;
            this.position = anchor.position();
            this.drift = anchor.drift();
            this.looping = false;
            this.delay = 0;
            this.pitch = pitchFor(type);
            this.volume = 0.001F;
            this.currentVolume = 0.001F;
            this.attenuation = SoundInstance.Attenuation.NONE;
            updateCoordinates();
        }

        @Override
        public void tick() {
            if (player.isRemoved() || level != player.level()) {
                stop();
                finished = true;
                return;
            }
            age++;
            if (!fadingOut && age >= type.fadeStartTick()) {
                requestFadeOut();
            }
            if (fadingOut) {
                fadeOutAge++;
                if (fadeOutAge >= type.fadeOutTicks) {
                    finished = true;
                    stop();
                    return;
                }
            }
            if (!level.hasChunkAt(BlockPos.containing(position))) {
                requestFadeOut();
            }
            updateMovement();
            updateCoordinates();
            updateVolume();
        }

        private boolean isFinished() {
            return finished;
        }

        private boolean isFadingOut() {
            return fadingOut;
        }

        private void softRetarget(SoundAnchor anchor) {
            if (fadingOut || finished) {
                return;
            }
            this.position = this.position.lerp(anchor.position(), 0.12D);
            this.drift = this.drift.lerp(anchor.drift(), 0.18D);
        }

        private void requestFadeOut() {
            if (!fadingOut) {
                fadingOut = true;
                fadeOutAge = 0;
            }
        }

        private void forceStop() {
            finished = true;
            stop();
        }

        private void updateMovement() {
            Vec3 next = position.add(drift);
            BlockPos nextBlock = BlockPos.containing(next);
            if (!level.hasChunkAt(nextBlock)) {
                requestFadeOut();
                return;
            }
            if (age % 36 == 0 || !level.getBlockState(nextBlock).isAir()) {
                BlockPos pocket = findNearbyAirPocket(level, BlockPos.containing(position), 4);
                if (pocket != null) {
                    drift = driftVector(level, pocket, player, type);
                    next = position.add(drift);
                } else {
                    drift = drift.scale(-0.35D);
                    next = position.add(drift);
                }
            }
            position = next;
        }

        private void updateCoordinates() {
            // Keep a virtual world anchor for direction/occlusion logic, but render the sound near the listener.
            // Long horror sounds must not behave like a block sound that disappears when the player flies away.
            Vec3 eye = player.getEyePosition();
            Vec3 direction = position.subtract(eye);
            if (direction.lengthSqr() < 0.0001D) {
                direction = player.getLookAngle();
            }
            direction = direction.normalize();
            double yOffset = Mth.clamp(position.y - eye.y, -3.0D, 3.0D);
            double audibleDistance = type == MineSoundType.DISTANT_SCREAM ? 18.0D : 8.0D;
            this.x = eye.x + direction.x * audibleDistance;
            this.y = eye.y + yOffset;
            this.z = eye.z + direction.z * audibleDistance;
        }

        private void updateVolume() {
            double fadeIn = Mth.clamp(age / (double) type.fadeInTicks, 0.0D, 1.0D);
            double fadeOut = fadingOut ? Mth.clamp(1.0D - fadeOutAge / (double) type.fadeOutTicks, 0.0D, 1.0D) : 1.0D;
            boolean occluded = !hasLineOfSight(level, player.getEyePosition(), position);
            double occlusion = occluded ? 0.88D : 1.0D;
            double creativeBoost = player.getAbilities().flying ? 1.12D : 1.0D;

            // No distance attenuation here. The anchor is virtual; the sound should survive fast movement.
            double value = type.baseVolume * fadeIn * fadeOut * occlusion * creativeBoost * FriendMineHorrorSoundManager.globalDampen();
            float targetVolume = (float) Mth.clamp(value, 0.0D, type.maxVolume);
            this.currentVolume = Mth.lerp(0.08F, this.currentVolume, targetVolume);
            this.volume = Mth.clamp(this.currentVolume, 0.0F, type.maxVolume);
        }

        private float pitchFor(MineSoundType type) {
            return switch (type) {
                case THROAT_CLICKS -> 0.78F + RANDOM.nextFloat() * 0.16F;
                case WALL_SCRAPE -> 0.72F + RANDOM.nextFloat() * 0.12F;
                case DISTANT_SCREAM -> 0.64F + RANDOM.nextFloat() * 0.10F;
                case CRAWL -> 0.82F + RANDOM.nextFloat() * 0.12F;
            };
        }
    }
}
