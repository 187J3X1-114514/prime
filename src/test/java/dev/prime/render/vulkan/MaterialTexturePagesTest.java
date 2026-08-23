package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.terrain.LabPbrAtlasFrame;
import dev.prime.render.terrain.LabPbrMaterialSet;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

final class MaterialTexturePagesTest {
    @Test
    void capturedMaterialPixelsAreImmutable() {
        int[] pixels = {0xff102030};
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                pixels, 1, 1, 1, 1, 1, 1);

        pixels[0] = 0;
        int[] exposed = source.pixels();
        exposed[0] = 0;

        assertArrayEquals(new int[] {0xff102030}, source.pixels());
    }

    @Test
    void frameRejectsMissingAnimationSamples() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff102030}, 1, 1, 1, 1, 1, 1);
        LabPbrAtlasFrame.Sprite sprite = new LabPbrAtlasFrame.Sprite(
                1, 0, 0, 1, 1, 0, source, null, 0);
        LabPbrAtlasFrame.Snapshot snapshot = new LabPbrAtlasFrame.Snapshot(
                1, 1, 1, LabPbrMaterialSet.EMPTY, List.of(sprite));

        assertThrows(
                IllegalArgumentException.class,
                () -> new LabPbrAtlasFrame(0, snapshot, List.of()));
    }

    @Test
    void atlasAndAnimationBudgetsKeepOffsetsAboveTwoGibibytes() {
        long pageBytes = MaterialTexturePages.totalMipBytes(32_768, 32_768, 16);
        long animationBytes = MaterialTexturePages.animationEndOffset(
                0L, 32_768, 32_768, true, true);

        assertTrue(pageBytes > Integer.MAX_VALUE);
        assertEquals(8L * 32_768L * 32_768L, animationBytes);
    }

    @Test
    void argbSourcesAreWrittenAsVulkanRgbaBytes() {
        ByteBuffer bytes = ByteBuffer.allocate(4);

        MaterialTexturePages.writeArgb(bytes, 0, 0xff123456);

        assertArrayEquals(
                new byte[] {0x12, 0x34, 0x56, (byte) 0xff},
                bytes.array());
    }

    @Test
    void animatedAuxiliaryMapsUseBaseFrameIndicesAndInterpolationProgress() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff000000, 0xffff0000},
                1,
                2,
                1,
                1,
                1,
                2);

        int blended = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(0, 1, 500),
                0.0,
                0.0,
                1.0,
                1.0,
                1,
                1,
                true);

        assertEquals(0xff800000, blended);
    }

    @Test
    void cachedAnimationFramesPreserveFilteredInterpolation() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff000000, 0xffff0000},
                1,
                2,
                1,
                1,
                1,
                2);
        LabPbrAtlasFrame.Sprite sprite = new LabPbrAtlasFrame.Sprite(
                1, 0, 0, 1, 1, 0, null, source, 0);
        TexturePageLayout.Placement placement =
                new TexturePageLayout.Placement(0, 0, 0, sprite);
        MaterialAnimationFrames frames =
                MaterialAnimationFrames.create(placement, source, 1, true);
        ByteBuffer target = MemoryUtil.memAlloc(4);
        try {
            frames.write(
                    MemoryUtil.memAddress(target),
                    new LabPbrAtlasFrame.AnimationSample(0, 1, 500),
                    0);

            assertArrayEquals(
                    new byte[] {(byte) 0x80, 0, 0, (byte) 0xff},
                    new byte[] {target.get(0), target.get(1), target.get(2), target.get(3)});
        } finally {
            MemoryUtil.memFree(target);
            frames.destroy();
        }
    }

    @Test
    void singleFrameAuxiliaryMapsRemainStaticForAnimatedBaseSprites() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff204060},
                1,
                1,
                1,
                1,
                1,
                4);

        int sampled = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(3, 0, 750),
                0.0,
                0.0,
                1.0,
                1.0,
                1,
                1,
                true);

        assertEquals(0xff204060, sampled);
    }

    @Test
    void normalMipFilteringPreservesDirectionAndEncodesDistributionRoughness() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xffcc8080, 0xff3380c0},
                2,
                1,
                2,
                1,
                2,
                1);

        int sampled = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(0, 0, 0),
                0.0,
                0.0,
                2.0,
                1.0,
                2,
                1);

        int roughness = sampled >>> 24;
        assertTrue(roughness > 0 && roughness < 255);
        assertTrue(Math.abs((sampled >>> 16 & 0xff) - 128) <= 1);
        assertTrue(Math.abs((sampled >>> 8 & 0xff) - 128) <= 1);
        assertEquals(160, sampled & 0xff);
    }

    @Test
    void flatNormalMipAddsNoDistributionRoughness() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff8080ff}, 1, 1, 1, 1, 1, 1);

        int sampled = source.filtered(
                LabPbrAtlasFrame.AnimationSample.ZERO,
                0.0,
                0.0,
                1.0,
                1.0,
                1,
                1);

        assertEquals(0, sampled >>> 24);
        assertEquals(128, sampled >>> 16 & 0xff);
        assertEquals(128, sampled >>> 8 & 0xff);
        assertEquals(255, sampled & 0xff);
    }

    @Test
    void specularFilteringTreatsThe255AlphaSentinelAsZeroEmission() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff000000, 0xfe000000},
                2,
                1,
                2,
                1,
                2,
                1);

        int sampled = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(0, 0, 0),
                0.0,
                0.0,
                2.0,
                1.0,
                2,
                1,
                true);

        assertEquals(0x7f000000, sampled);
    }

    @Test
    void specularFilteringPreservesAnAllSentinelRegion() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff000000, 0xff000000},
                2,
                1,
                2,
                1,
                2,
                1);

        int sampled = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(0, 0, 0),
                0.0,
                0.0,
                2.0,
                1.0,
                2,
                1,
                true);

        assertEquals(0xff000000, sampled);
    }

    @Test
    void specularAnimationInterpolatesDecodedEmissionRatherThanTheSentinelByte() {
        LabPbrAtlasFrame.MaterialSource source = LabPbrAtlasFrame.MaterialSource.create(
                new int[] {0xff000000, 0xfe000000},
                1,
                2,
                1,
                1,
                1,
                2);

        int sampled = source.filtered(
                new LabPbrAtlasFrame.AnimationSample(0, 1, 500),
                0.0,
                0.0,
                1.0,
                1.0,
                1,
                1,
                true);

        assertEquals(0x7f000000, sampled);
    }
}
