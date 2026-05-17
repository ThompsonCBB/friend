package com.doxbi.friend.client.model;

import com.doxbi.friend.FriendMod;
import com.doxbi.friend.entity.FriendEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class FriendModel extends GeoModel<FriendEntity> {
    private static final ResourceLocation MODEL =
            new ResourceLocation(FriendMod.MOD_ID, "geo/friend.geo.json");
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(FriendMod.MOD_ID, "textures/entity/friend.png");
    private static final ResourceLocation WHITE_TEXTURE =
            new ResourceLocation(FriendMod.MOD_ID, "textures/entity/friend_white.png");
    private static final ResourceLocation ANIMATIONS =
            new ResourceLocation(FriendMod.MOD_ID, "animations/friend.animation.json");

    @Override
    public ResourceLocation getModelResource(FriendEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(FriendEntity animatable) {
        return "white".equals(animatable.visualVariant()) ? WHITE_TEXTURE : TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(FriendEntity animatable) {
        return ANIMATIONS;
    }
}
