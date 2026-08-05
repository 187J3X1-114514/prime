package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.platform.NativeImage;
import dev.prime.mixin.SpriteContentsAccessor;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import dev.prime.render.scene.SpritePixelView;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.IdentityHashMap;
import java.util.Objects;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/** Worker-confined adapter that resolves each raw vanilla sprite once per captured cluster. */
public final class VanillaSpriteResolver {
    private final IdentityHashMap<TextureAtlasSprite, CapturedSprite> resolved =
            new IdentityHashMap<>();

    public CapturedSprite resolve(TextureAtlasSprite sprite) {
        Objects.requireNonNull(sprite, "sprite");
        return this.resolved.computeIfAbsent(sprite, VanillaSpriteResolver::capture);
    }

    int resolvedCount() {
        return this.resolved.size();
    }

    private static CapturedSprite capture(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        Identifier name = contents.name();
        boolean animated = contents.isAnimated();
        IntList sourceFrames = animated ? contents.getUniqueFrames() : null;
        int[] frames;
        if (sourceFrames == null || sourceFrames.isEmpty()) {
            frames = new int[] {0};
        } else {
            frames = sourceFrames.toIntArray();
        }
        NativeImage image = ((SpriteContentsAccessor) (Object) contents).prime$originalImage();
        SpritePixelView pixels = image == null || image.isClosed()
                ? null
                : new NativeImageView(image);
        return new CapturedSprite(
                new SpriteId(name.getNamespace(), name.getPath()),
                sprite.getU0(),
                sprite.getV0(),
                sprite.getU1(),
                sprite.getV1(),
                contents.width(),
                contents.height(),
                animated,
                frames,
                pixels);
    }

    private record NativeImageView(NativeImage image) implements SpritePixelView {
        private NativeImageView {
            Objects.requireNonNull(image, "image");
        }

        @Override
        public int imageWidth() {
            return this.image.getWidth();
        }

        @Override
        public int imageHeight() {
            return this.image.getHeight();
        }

        @Override
        public int argb(int x, int y) {
            return this.image.getPixel(x, y);
        }
    }
}
