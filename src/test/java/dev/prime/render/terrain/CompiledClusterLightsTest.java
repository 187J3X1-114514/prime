package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class CompiledClusterLightsTest {
    @Test
    void relocationChangesOnlyTheFiveHeaderPointers() {
        int[] relative = validOneEmitterPayload();
        long[] offsets = {48L, 80L, 88L, 96L, 192L};
        relative[40] = 0x1234_5678;
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
        assertEquals(relative[40], relocated[40]);
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

        relative[44] = 256;

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.fromEncoded(relative, oneEmitter));
    }

    @Test
    void encodedPayloadValidationRejectsRootDirectionSummaryMismatch() {
        int[] relative = validOneEmitterPayload();
        relative[21] = 0;

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
        legacy[44] = 0;
        legacy[45] = 0;

        int[] upgraded = CompiledClusterLights.addFullDirectionStream(legacy);
        CompiledClusterLights lights = CompiledClusterLights.fromEncoded(
                upgraded,
                new CompiledClusterLights.Summary(
                        1, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F));

        assertEquals(88L, getLong(upgraded, 4));
        assertEquals(96L, getLong(upgraded, 6));
        assertEquals(LightDirection.FULL, upgraded[21]);
        assertArrayEquals(upgraded, lights.encodedWords());
    }

    @Test
    void legacyEmitterUvsUpgradeFromHalfToFixedAtlasCoordinates() {
        int[] legacy = validOneEmitterPayload();
        legacy[40] = PrimitivePacking.packHalf2(0.75F, 0.5F);
        legacy[41] = PrimitivePacking.packHalf2(0.875F, 0.5F);
        legacy[42] = PrimitivePacking.packHalf2(0.75F, 0.625F);

        int[] upgraded = CompiledClusterLights.upgradeUvPacking(legacy, 1);
        CompiledClusterLights lights = CompiledClusterLights.fromEncoded(
                upgraded,
                new CompiledClusterLights.Summary(
                        1, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F));

        assertEquals(PrimitivePacking.packUv(0.75F, 0.5F), upgraded[40]);
        assertEquals(PrimitivePacking.packUv(0.875F, 0.5F), upgraded[41]);
        assertEquals(PrimitivePacking.packUv(0.75F, 0.625F), upgraded[42]);
        assertArrayEquals(upgraded, lights.encodedWords());
    }

    @Test
    void legacyEmitterUvUpgradeRejectsAChangedSamplingDistribution() {
        int[] legacy = validOneEmitterPayload();
        legacy[40] = 1;

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterLights.upgradeUvPacking(legacy, 1));
    }

    private static int[] validOneEmitterPayload() {
        int[] relative = new int[816];
        long[] offsets = {48L, 80L, 88L, 96L, 192L};
        for (int pointer = 0; pointer < offsets.length; pointer++) {
            putLong(relative, pointer * 2, offsets[pointer]);
        }
        relative[11] = 1;
        relative[20] = CpuLightTree.LEAF_FLAG;
        relative[21] = LightDirection.FULL;
        relative[22] = CpuLightTree.NO_INDEX;
        relative[44] = 0;
        relative[45] = 0;
        return relative;
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
