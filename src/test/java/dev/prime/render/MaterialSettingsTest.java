package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class MaterialSettingsTest {
    @Test
    void roughnessUsesExactHundredthStepsAndRevisionChangesOnlyWithTheValue() {
        int original = MaterialSettings.roughnessSteps();
        try {
            MaterialSettings.setRoughnessSteps(37);
            MaterialSettings.Snapshot first = MaterialSettings.snapshot();
            assertEquals(37, first.roughnessSteps());
            assertEquals(0.37F, first.linearRoughness(), 1.0e-7F);

            MaterialSettings.setRoughnessSteps(37);
            assertEquals(first.revision(), MaterialSettings.snapshot().revision());
            MaterialSettings.setRoughnessSteps(38);
            assertEquals(first.revision() + 1L, MaterialSettings.snapshot().revision());
        } finally {
            MaterialSettings.setRoughnessSteps(original);
        }
        assertThrows(IllegalArgumentException.class,
                () -> MaterialSettings.setRoughnessSteps(-1));
        assertThrows(IllegalArgumentException.class,
                () -> MaterialSettings.setRoughnessSteps(101));
    }
}
