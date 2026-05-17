package com.doxbi.friend.client.renderer;

import com.doxbi.friend.FriendMod;
import com.doxbi.friend.client.model.FriendModel;
import com.doxbi.friend.entity.FriendEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

public class FriendRenderer extends GeoEntityRenderer<FriendEntity> {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(FriendMod.MOD_ID, "textures/entity/friend.png");
    private static final ResourceLocation WHITE_TEXTURE =
            new ResourceLocation(FriendMod.MOD_ID, "textures/entity/friend_white.png");

    public FriendRenderer(EntityRendererProvider.Context context) {
        super(context, new FriendModel());
        this.shadowRadius = 0.42F;
        this.addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    @Override
    public ResourceLocation getTextureLocation(FriendEntity entity) {
        return "white".equals(entity.visualVariant()) ? WHITE_TEXTURE : TEXTURE;
    }
}
