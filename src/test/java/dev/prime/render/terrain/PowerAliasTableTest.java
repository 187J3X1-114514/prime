package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PowerAliasTableTest {
    @Test
    void representedMassMatchesDeterministicAliasSampling() {
        PowerAliasTable table = PowerAliasTable.build(new float[] {1.0F, 3.0F, 6.0F});
        int sampleCount = 300_000;
        int[] counts = new int[table.size()];
        for (int sample = 0; sample < sampleCount; sample++) {
            float value = (sample + 0.5F) / sampleCount;
            float scaled = value * table.size();
            int bucket = Math.min((int) scaled, table.size() - 1);
            int selected = scaled - bucket < table.aliasProbability(bucket)
                    ? bucket
                    : table.alias(bucket);
            counts[selected]++;
        }

        float massSum = 0.0F;
        for (int index = 0; index < table.size(); index++) {
            float represented = table.probabilityMass(index);
            massSum += represented;
            assertEquals(represented, (float) counts[index] / sampleCount, 2.0E-5F);
        }
        assertEquals(1.0F, massSum, 2.0E-6F);
        assertEquals(0.1F, table.probabilityMass(0), 1.0E-6F);
        assertEquals(0.3F, table.probabilityMass(1), 1.0E-6F);
        assertEquals(0.6F, table.probabilityMass(2), 1.0E-6F);
    }

    @Test
    void proposalRequiresCompletePositiveFiniteSupport() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PowerAliasTable.build(new float[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> PowerAliasTable.build(new float[] {1.0F, 0.0F}));
        assertThrows(
                IllegalArgumentException.class,
                () -> PowerAliasTable.build(new float[] {1.0F, Float.NaN}));
        assertTrue(PowerAliasTable.build(new float[] {Float.MIN_VALUE}).probabilityMass(0) > 0.0F);
    }
}
