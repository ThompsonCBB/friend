package com.doxbi.friend.client;

import com.doxbi.friend.FriendMod;
import com.doxbi.friend.client.renderer.FriendRenderer;
import com.doxbi.friend.registry.FriendEntityTypes;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = FriendMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FriendClientEvents {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(FriendEntityTypes.FRIEND.get(), FriendRenderer::new);
    }

    private FriendClientEvents() {
    }
}
