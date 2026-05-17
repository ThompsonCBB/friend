package com.doxbi.friend.registry;

import com.doxbi.friend.FriendMod;
import com.doxbi.friend.entity.FriendEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class FriendEntityTypes {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, FriendMod.MOD_ID);

    public static final RegistryObject<EntityType<FriendEntity>> FRIEND = ENTITY_TYPES.register("friend",
            () -> EntityType.Builder.of(FriendEntity::new, MobCategory.MONSTER)
                    .sized(0.72F, 1.95F)
                    .clientTrackingRange(64)
                    .updateInterval(3)
                    .fireImmune()
                    .build("friend:friend"));

    private FriendEntityTypes() {
    }
}
