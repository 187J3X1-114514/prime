package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.blaze3d.platform.NativeImage;
import dev.prime.mixin.accessor.SpriteContentsAccessor;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class VanillaSpriteResolverTest {
    @Test
    void cachesByRawIdentityAndCapturesOnlyPrimeOwnedFacts() {
        try (TestSprite first = new TestSprite(0xff12_3456);
                TestSprite second = new TestSprite(0xffab_cdef)) {
            VanillaSpriteResolver resolver = new VanillaSpriteResolver();

            CapturedSprite captured = resolver.resolve(first);

            assertSame(captured, resolver.resolve(first));
            CapturedSprite equivalent = resolver.resolve(second);
            assertNotSame(captured, equivalent);
            assertEquals(captured, equivalent);
            assertEquals(2, resolver.resolvedCount());
            assertEquals(new SpriteId("example", "block/custom"), captured.id());
            assertEquals(0.5F, captured.u0());
            assertEquals(1.0F, captured.u1());
            assertEquals(0.0F, captured.v0());
            assertEquals(1.0F, captured.v1());
            assertEquals(16, captured.frameWidth());
            assertEquals(16, captured.frameHeight());
            assertEquals(0xff12_3456, captured.pixelView().argb(0, 0));
        }
    }

    private static final class TestSprite extends TextureAtlasSprite {
        private TestSprite(int argb) {
            this(new TestContents(argb));
        }

        private TestSprite(TestContents contents) {
            super(
                    Identifier.fromNamespaceAndPath("example", "atlas"),
                    contents,
                    32,
                    16,
                    16,
                    0,
                    0);
        }
    }

    private static final class TestContents
            extends SpriteContents
            implements SpriteContentsAccessor {
        private final NativeImage image;

        private TestContents(int argb) {
            this(new NativeImage(16, 16, true), argb);
        }

        private TestContents(NativeImage image, int argb) {
            super(
                    Identifier.fromNamespaceAndPath("example", "block/custom"),
                    new FrameSize(16, 16),
                    image);
            this.image = image;
            this.image.setPixel(0, 0, argb);
        }

        @Override
        public NativeImage prime$originalImage() {
            return this.image;
        }

        @Override
        public NativeImage[] prime$byMipLevel() {
            return new NativeImage[] {this.image};
        }
    }
}
