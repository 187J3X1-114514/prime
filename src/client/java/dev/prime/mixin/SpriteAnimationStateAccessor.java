package dev.prime.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Mapped access to the base atlas animation cursor used by auxiliary material maps. */
@Mixin(SpriteContents.AnimationState.class)
public interface SpriteAnimationStateAccessor {
    @Accessor("frame")
    int prime$frame();

    @Accessor("subFrame")
    int prime$subFrame();
}
