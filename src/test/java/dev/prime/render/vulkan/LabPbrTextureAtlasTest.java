package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class LabPbrTextureAtlasTest {
    @Test
    void atlasAndAnimationBudgetsKeepOffsetsAboveTwoGibibytes() {
        long atlasBytes = LabPbrTextureAtlas.totalMipBytes(32_768, 32_768, 16);
        long animationBytes = LabPbrTextureAtlas.animationEndOffset(
                0L, 32_768, 32_768, true, true);

        assertTrue(atlasBytes > Integer.MAX_VALUE);
        assertEquals(8L * 32_768L * 32_768L, animationBytes);
    }

    @Test
    void argbSourcesAreWrittenAsVulkanRgbaBytes() {
        ByteBuffer bytes = ByteBuffer.allocate(4);

        LabPbrTextureAtlas.writeArgb(bytes, 0, 0xff123456);

        assertArrayEquals(
                new byte[] {0x12, 0x34, 0x56, (byte) 0xff},
                bytes.array());
    }

    @Test
    void animatedAuxiliaryMapsUseBaseFrameIndicesAndInterpolationProgress() {
        LabPbrTextureAtlas.MaterialSource source = LabPbrTextureAtlas.MaterialSource.create(
                new int[] {0xff000000, 0xffff0000},
                1,
                2,
                1,
                1,
                1,
                2);

        int blended = source.filtered(
                new LabPbrTextureAtlas.AnimationSample(0, 1, 500),
                0.0,
                0.0,
                1.0,
                1.0,
                1,
                1);

        assertEquals(0xff800000, blended);
    }

    @Test
    void singleFrameAuxiliaryMapsRemainStaticForAnimatedBaseSprites() {
        LabPbrTextureAtlas.MaterialSource source = LabPbrTextureAtlas.MaterialSource.create(
                new int[] {0xff204060},
                1,
                1,
                1,
                1,
                1,
                4);

        int sampled = source.filtered(
                new LabPbrTextureAtlas.AnimationSample(3, 0, 750),
                0.0,
                0.0,
                1.0,
                1.0,
                1,
                1);

        assertEquals(0xff204060, sampled);
    }

    @Test
    void semanticMipFilteringAveragesRawLinearChannels() {
        LabPbrTextureAtlas.MaterialSource source = LabPbrTextureAtlas.MaterialSource.create(
                new int[] {0xff000000, 0xffff0000, 0xff00ff00, 0xff0000ff},
                2,
                2,
                2,
                2,
                2,
                2);

        int sampled = source.filtered(
                new LabPbrTextureAtlas.AnimationSample(0, 0, 0),
                0.0,
                0.0,
                2.0,
                2.0,
                2,
                2);

        assertEquals(0xff404040, sampled);
    }

    @Test
    void specularFilteringTreatsThe255AlphaSentinelAsZeroEmission() {
        LabPbrTextureAtlas.MaterialSource source = LabPbrTextureAtlas.MaterialSource.create(
                new int[] {0xff000000, 0xfe000000},
                2,
                1,
                2,
                1,
                2,
                1);

        int sampled = source.filtered(
                new LabPbrTextureAtlas.AnimationSample(0, 0, 0),
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
        LabPbrTextureAtlas.MaterialSource source = LabPbrTextureAtlas.MaterialSource.create(
                new int[] {0xff000000, 0xff000000},
                2,
                1,
                2,
                1,
                2,
                1);

        int sampled = source.filtered(
                new LabPbrTextureAtlas.AnimationSample(0, 0, 0),
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
        LabPbrTextureAtlas.MaterialSource source = LabPbrTextureAtlas.MaterialSource.create(
                new int[] {0xff000000, 0xfe000000},
                1,
                2,
                1,
                1,
                1,
                2);

        int sampled = source.filtered(
                new LabPbrTextureAtlas.AnimationSample(0, 1, 500),
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
