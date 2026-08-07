package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.config.PrimeSettings;
import org.junit.jupiter.api.Test;

final class MaterialSettingsTest {
    @Test
    void unauthoredMaterialsUseTheCalibratedDefaultRoughness() {
        assertEquals(90, MaterialSettings.DEFAULT_ROUGHNESS_STEPS);
        assertEquals(0.90F,
                MaterialSettings.linearRoughness(MaterialSettings.DEFAULT_ROUGHNESS_STEPS),
                1.0e-7F);
    }

    @Test
    void roughnessUsesExactHundredthStepsAndRevisionChangesOnlyWithTheValue() {
        PrimeSettings first = PrimeSettings.defaults().withDefaultRoughnessSteps(37);
        assertEquals(37, first.material().roughnessSteps());
        assertEquals(0.37F, first.material().linearRoughness(), 1.0e-7F);
        assertEquals(first, first.withDefaultRoughnessSteps(37));

        PrimeSettings second = first.withDefaultRoughnessSteps(38);
        assertEquals(first.materialRevision() + 1L, second.materialRevision());
        assertThrows(IllegalArgumentException.class,
                () -> first.withDefaultRoughnessSteps(-1));
        assertThrows(IllegalArgumentException.class,
                () -> first.withDefaultRoughnessSteps(101));
    }

    @Test
    void seamlessGlassIsOnByDefaultAndOwnsMaterialRevision() {
        PrimeSettings defaults = PrimeSettings.defaults();
        assertTrue(MaterialSettings.DEFAULT_SEAMLESS_GLASS);
        assertTrue(defaults.material().seamlessGlass());

        PrimeSettings bordered = defaults.withSeamlessGlass(false);
        assertFalse(bordered.material().seamlessGlass());
        assertEquals(defaults.materialRevision() + 1L, bordered.materialRevision());
        assertEquals(bordered, bordered.withSeamlessGlass(false));
        assertFalse(bordered.withSunQuarterSteps(1).material().seamlessGlass());
    }

    @Test
    void snapshotDerivesLinearRoughnessFromItsCanonicalSteps() {
        MaterialSettings.Snapshot snapshot =
                new MaterialSettings.Snapshot(37, true, 2L);

        assertEquals(0.37F, snapshot.linearRoughness());
        assertTrue(snapshot.seamlessGlass());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MaterialSettings.Snapshot(101, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MaterialSettings.Snapshot(0, -1L));
    }
}
