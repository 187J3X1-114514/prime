package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.shader.ShaderAbi;
import org.junit.jupiter.api.Test;

final class CompiledClusterLightsTest {
    private static final int HEADER_WORDS = 12;
    private static final int NODE_DIRECTION_WORD =
            ShaderAbi.LIGHT_NODE_DIRECTION_CHILD_CENTROID_RESERVED_OFFSET / Integer.BYTES;
    private static final int NODE_CHILD_WORD = NODE_DIRECTION_WORD + 1;

    @Test
    void relocationChangesOnlyTheFiveHeaderPointers() {
        int[] relative = validOneEmitterPayload();
        long[] offsets = {48L, 96L, 104L, 112L, 208L};
        relative[44] = 0x1234_5678;
        CompiledClusterLights lights = CompiledClusterLights.fromEncoded(
                relative,
                new CompiledClusterLights.Summary(
                        1, -1.0F, -2.0F, -3.0F, 1.0F, 2.0F, 3.0F, 4.0F));

        int[] encoded = lights.encodedWords();
        int[] relocated = lights.relocate(0x1000L);

        assertNotSame(encoded, relocated);
        assertArrayEquals(relative, encoded);
        for (int pointer = 0; pointer < 5; pointer++) {
            assertEquals(
                    0x1000L + offsets[pointer],
                    getLong(relocated, pointer * 2));
        }
        assertEquals(relative[10], relocated[10]);
        assertEquals(relative[11], relocated[11]);
        assertEquals(relative[44], relocated[44]);
    }

    @Test
    void encodedPayloadValidationRejectsBrokenIdentity() {
        int[] header = new int[12];
        header[11] = 2;
        CompiledClusterLights.Summary oneEmitter =
                new CompiledClusterLights.Summary(
                        1, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(header, oneEmitter));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(
                        new int[] {1}, CompiledClusterLights.EMPTY.summary()));
    }

    @Test
    void encodedPayloadValidationRejectsBrokenAbiLayout() {
        int[] relative = validOneEmitterPayload();
        CompiledClusterLights.Summary oneEmitter =
                new CompiledClusterLights.Summary(
                        1, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);

        putLong(relative, 2, 76L);

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(relative, oneEmitter));
    }

    @Test
    void encodedPayloadValidationRejectsBrokenEmitterReference() {
        int[] relative = validOneEmitterPayload();
        CompiledClusterLights.Summary oneEmitter =
                new CompiledClusterLights.Summary(
                        1, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);

        relative[48] = 256;

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(relative, oneEmitter));
    }

    @Test
    void encodedPayloadValidationRejectsRootDirectionSummaryMismatch() {
        int[] relative = validOneEmitterPayload();
        relative[HEADER_WORDS + NODE_DIRECTION_WORD] = 0;

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(
                        relative,
                        new CompiledClusterLights.Summary(
                                1,
                                0.0F,
                                0.0F,
                                0.0F,
                                1.0F,
                                1.0F,
                                1.0F,
                                1.0F)));
    }

    @Test
    void legacyForwardStreamUpgradesWithFullDirectionalSupport() {
        int[] legacy = new int[816];
        long[] offsets = {48L, 80L, 84L, 96L, 192L};
        for (int pointer = 0; pointer < offsets.length; pointer++) {
            putLong(legacy, pointer * 2, offsets[pointer]);
        }
        legacy[11] = 1;
        legacy[20] = CpuLightTree.LEAF_FLAG;
        legacy[21] = CpuLightTree.NO_INDEX;
        populateLegacyEmitter(legacy, 24);

        int[] directional = CompiledClusterLights.addFullDirectionStream(legacy);
        int[] upgraded = CompiledClusterLights.upgradeTreeLayout(
                directional, 1, LightDirection.FULL);
        CompiledClusterLights lights = CompiledClusterLights.fromEncoded(
                upgraded,
                new CompiledClusterLights.Summary(
                        1, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F));

        assertEquals(LightDirection.FULL, directional[21]);
        assertEquals(104L, getLong(upgraded, 4));
        assertEquals(112L, getLong(upgraded, 6));
        assertEquals(LightDirection.FULL, upgraded[HEADER_WORDS + NODE_DIRECTION_WORD]);
        assertArrayEquals(upgraded, lights.encodedWords());
    }

    @Test
    void legacyEmitterUvsUpgradeFromHalfToFixedAtlasCoordinates() {
        int[] legacy = validLegacyV10Payload();
        legacy[40] = PrimitivePacking.packHalf2(0.75F, 0.5F);
        legacy[41] = PrimitivePacking.packHalf2(0.875F, 0.5F);
        legacy[42] = PrimitivePacking.packHalf2(0.75F, 0.625F);

        int[] uvUpgraded = CompiledClusterLights.upgradeUvPacking(legacy, 1);
        int[] upgraded = CompiledClusterLights.upgradeTreeLayout(
                uvUpgraded, 1, LightDirection.FULL);
        CompiledClusterLights lights = CompiledClusterLights.fromEncoded(
                upgraded,
                new CompiledClusterLights.Summary(
                        1, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F));

        assertEquals(PrimitivePacking.packUv(0.75F, 0.5F), upgraded[44]);
        assertEquals(PrimitivePacking.packUv(0.875F, 0.5F), upgraded[45]);
        assertEquals(PrimitivePacking.packUv(0.75F, 0.625F), upgraded[46]);
        assertArrayEquals(upgraded, lights.encodedWords());
    }

    @Test
    void encodedPayloadValidationRejectsNonfiniteNodeBounds() {
        int[] relative = validOneEmitterPayload();
        relative[HEADER_WORDS] = Float.floatToRawIntBits(Float.NaN);

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(
                        relative,
                        new CompiledClusterLights.Summary(
                                1,
                                0.0F,
                                0.0F,
                                0.0F,
                                1.0F,
                                1.0F,
                                1.0F,
                                1.0F)));
    }

    @Test
    void versionTwelveF16NodesRebuildIntoExactF32Bounds() {
        int[] versionTwelve = validVersionTwelvePayload();

        int[] upgraded = CompiledClusterLights.upgradeTreeLayout(
                versionTwelve, 1, LightDirection.FULL);
        CompiledClusterLights lights = CompiledClusterLights.fromEncoded(
                upgraded,
                new CompiledClusterLights.Summary(
                        1, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F));

        assertEquals(96L, getLong(upgraded, 2));
        assertEquals(1.0F, Float.intBitsToFloat(upgraded[16]), 0.0F);
        assertEquals(1.0F, Float.intBitsToFloat(upgraded[17]), 0.0F);
        assertEquals(0.0F, Float.intBitsToFloat(upgraded[18]), 0.0F);
        assertArrayEquals(upgraded, lights.encodedWords());
    }

    @Test
    void legacyEmitterUvUpgradeRejectsAChangedSamplingDistribution() {
        int[] legacy = validLegacyV10Payload();
        legacy[40] = 1;

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.upgradeUvPacking(legacy, 1));
    }

    @Test
    void legacyTreeUpgradeRejectsDirectionOutsideItsStream() {
        int[] legacy = validLegacyV10Payload();
        putLong(legacy, 2, 92L);

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.upgradeTreeLayout(
                        legacy, 1, LightDirection.FULL));
    }

    private static int[] validOneEmitterPayload() {
        int[] relative = new int[820];
        long[] offsets = {48L, 96L, 104L, 112L, 208L};
        for (int pointer = 0; pointer < offsets.length; pointer++) {
            putLong(relative, pointer * 2, offsets[pointer]);
        }
        relative[11] = 1;
        relative[15] = Float.floatToRawIntBits(1.0F);
        relative[16] = Float.floatToRawIntBits(1.0F);
        relative[17] = Float.floatToRawIntBits(1.0F);
        relative[18] = Float.floatToRawIntBits(1.0F);
        relative[HEADER_WORDS + NODE_DIRECTION_WORD] = LightDirection.FULL;
        relative[HEADER_WORDS + NODE_CHILD_WORD] = CpuLightTree.LEAF_FLAG;
        relative[24] = 0;
        relative[25] = 1;
        relative[26] = 0;
        relative[27] = Float.floatToRawIntBits(1.0F);
        populateLegacyEmitter(relative, 28);
        relative[48] = 0;
        relative[49] = 0;
        return relative;
    }

    private static int[] validLegacyV10Payload() {
        int[] legacy = new int[816];
        long[] offsets = {48L, 80L, 88L, 96L, 192L};
        for (int pointer = 0; pointer < offsets.length; pointer++) {
            putLong(legacy, pointer * 2, offsets[pointer]);
        }
        legacy[11] = 1;
        legacy[20] = CpuLightTree.LEAF_FLAG;
        legacy[21] = LightDirection.FULL;
        legacy[22] = CpuLightTree.NO_INDEX;
        populateLegacyEmitter(legacy, 24);
        return legacy;
    }

    private static int[] validVersionTwelvePayload() {
        int[] versionTwelve = new int[816];
        long[] offsets = {48L, 80L, 88L, 96L, 192L};
        for (int pointer = 0; pointer < offsets.length; pointer++) {
            putLong(versionTwelve, pointer * 2, offsets[pointer]);
        }
        versionTwelve[11] = 1;
        versionTwelve[13] = PrimitivePacking.packHalf2(0.0F, 1.0F);
        versionTwelve[14] = PrimitivePacking.packHalf2(1.0F, 0.0F);
        versionTwelve[15] = LightDirection.FULL;
        versionTwelve[16] = Float.floatToRawIntBits(1.0F);
        versionTwelve[18] = CpuLightTree.LEAF_FLAG;
        versionTwelve[20] = 0;
        versionTwelve[21] = 1;
        versionTwelve[22] = 0;
        versionTwelve[23] = Float.floatToRawIntBits(1.0F);
        populateLegacyEmitter(versionTwelve, 24);
        return versionTwelve;
    }

    private static void populateLegacyEmitter(int[] words, int base) {
        words[base + 3] = Float.floatToRawIntBits(0.5F);
        words[base + 4] = Float.floatToRawIntBits(1.0F);
        words[base + 9] = Float.floatToRawIntBits(1.0F);
        words[base + 11] = Float.floatToRawIntBits(1.0F);
        words[base + 14] = Float.floatToRawIntBits(1.0F);
        words[base + 20] = 0;
        words[base + 21] = 0;
        words[base + 22] = 0;
    }

    private static long getLong(int[] words, int offset) {
        return Integer.toUnsignedLong(words[offset])
                | (long) words[offset + 1] << 32;
    }

    private static void putLong(int[] words, int offset, long value) {
        words[offset] = (int) value;
        words[offset + 1] = (int) (value >>> 32);
    }
}
