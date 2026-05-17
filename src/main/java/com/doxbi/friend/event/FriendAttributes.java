package com.doxbi.friend.event;

import com.doxbi.friend.FriendMod;
import com.doxbi.friend.entity.FriendEntity;
import com.doxbi.friend.registry.FriendEntityTypes;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = FriendMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FriendAttributes {
    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(FriendEntityTypes.FRIEND.get(), FriendEntity.createAttributes().build());
    }

    private FriendAttributes() {
    }
}
