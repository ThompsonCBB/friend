package com.doxbi.friend.client;

import com.doxbi.friend.FriendMod;
import com.doxbi.friend.entity.FriendEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = FriendMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FriendClientForgeEvents {
    private FriendClientForgeEvents() {
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FriendClientSounds.tickScriptedOneShots();
            FriendClientSounds.tickCatSinBackground();
            FriendMineHorrorSoundManager.tick();
        }
    }

    @SubscribeEvent
    public static void cameraShake(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        double strongest = minecraft.level.getEntitiesOfClass(FriendEntity.class, minecraft.player.getBoundingBox().inflate(18.0D),
                        friend -> friend.rushing() && friend.distanceTo(minecraft.player) < 18.0F)
                .stream()
                .mapToDouble(friend -> 1.0D - Math.min(1.0D, friend.distanceTo(minecraft.player) / 18.0D))
                .max()
                .orElse(0.0D);
        if (strongest <= 0.0D) {
            return;
        }

        double ticks = minecraft.player.tickCount + event.getPartialTick();
        float shake = (float) (strongest * 1.8D);
        event.setPitch(event.getPitch() + (float) Math.sin(ticks * 3.7D) * shake * 0.45F);
        event.setYaw(event.getYaw() + (float) Math.cos(ticks * 4.1D) * shake * 0.35F);
        event.setRoll(event.getRoll() + (float) Math.sin(ticks * 5.3D) * shake);
    }

    @SubscribeEvent
    public static void catSinFog(ViewportEvent.ComputeFogColor event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !minecraft.level.dimension().equals(Level.OVERWORLD)
                || !minecraft.player.getTags().contains("friend_cat_sin")) {
            return;
        }
        Camera camera = event.getCamera();
        float pulse = (float) (0.72F + Math.sin((minecraft.player.tickCount + event.getPartialTick()) * 0.025D) * 0.08F);
        event.setRed(Math.max(event.getRed(), 0.58F * pulse));
        event.setGreen(event.getGreen() * 0.16F);
        event.setBlue(event.getBlue() * 0.12F);
        if (camera.getPosition().y > minecraft.level.getMinBuildHeight() + 10) {
            event.setRed(Math.max(event.getRed(), 0.72F * pulse));
        }
    }

    @SubscribeEvent
    public static void catSinFogDistance(ViewportEvent.RenderFog event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !minecraft.level.dimension().equals(Level.OVERWORLD)
                || !minecraft.player.getTags().contains("friend_cat_sin")) {
            return;
        }
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), 1.0F));
        event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), 58.0F));
    }

}
