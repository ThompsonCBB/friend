package com.doxbi.friend;

import com.doxbi.friend.config.FriendConfig;
import com.doxbi.friend.registry.FriendEntityTypes;
import com.doxbi.friend.registry.FriendSoundEvents;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(FriendMod.MOD_ID)
public class FriendMod {
    public static final String MOD_ID = "friend";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FriendMod() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        FriendEntityTypes.ENTITY_TYPES.register(modBus);
        FriendSoundEvents.SOUND_EVENTS.register(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, FriendConfig.SPEC);
    }
}
