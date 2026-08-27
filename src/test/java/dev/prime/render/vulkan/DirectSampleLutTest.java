package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class DirectSampleLutTest {
    @Test
    void temporalStreamsPreserveDyadicOneDimensionalStratification() {
        for (int stream = 0; stream < DirectSampleLut.STREAM_COUNT; stream++) {
            for (int component = 0; component < 2; component++) {
                assertStratified(stream, component, 256);
                assertStratified(stream, component, DirectSampleLut.SAMPLE_COUNT);
            }
        }
        assertNotEquals(
                DirectSampleLut.temporalValue(0, 17, 0),
                DirectSampleLut.temporalValue(1, 17, 0));
        assertNotEquals(
                DirectSampleLut.temporalValue(1, 17, 1),
                DirectSampleLut.temporalValue(2, 17, 1));
    }

    @Test
    void pinnedBlueNoiseLayersAreUniformScalarPermutations() {
        byte[] source = DirectSampleLut.readBlueNoise();
        assertEquals(196_608, source.length);
        ByteBuffer values = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        for (int layer = 0; layer < DirectSampleLut.STREAM_COUNT * 2; layer++) {
            boolean[] occupied = new boolean[DirectSampleLut.BLUE_NOISE_TEXEL_COUNT];
            for (int texel = 0; texel < DirectSampleLut.BLUE_NOISE_TEXEL_COUNT; texel++) {
                int value = Short.toUnsignedInt(values.getShort());
                assertEquals(0, value & 3);
                int rank = value >>> 2;
                assertFalse(occupied[rank]);
                occupied[rank] = true;
            }
        }
    }

    @Test
    void gpuLayoutContainsThreeTemporalAndThreeSpatialPairs() {
        assertEquals(245_760, DirectSampleLut.ENTRY_COUNT);
        assertEquals(1_966_080, DirectSampleLut.BYTE_SIZE);
    }

    private static void assertStratified(int stream, int component, int count) {
        boolean[] occupied = new boolean[count];
        int shift = Integer.SIZE - Integer.numberOfTrailingZeros(count);
        for (int sample = 0; sample < count; sample++) {
            int bin = DirectSampleLut.temporalValue(stream, sample, component) >>> shift;
            assertFalse(occupied[bin]);
            occupied[bin] = true;
        }
    }
}
