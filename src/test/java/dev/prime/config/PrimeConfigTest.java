package dev.prime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.AstronomySettings;
import dev.prime.render.ScatterSettings;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import java.util.Properties;
import org.junit.jupiter.api.Test;

final class PrimeConfigTest {
    @Test
    void restoreDefaultsIncludesScatterCount() {
        PrimeConfig.setScatterCount(ScatterSettings.MAXIMUM_COUNT);

        PrimeConfig.restoreDefaults();

        assertEquals(ScatterSettings.DEFAULT_COUNT, PrimeConfig.scatterCount());
        assertEquals(
                ScatterSettings.DEFAULT_COUNT,
                PrimeConfig.rendererSettings().scatterCount());
    }

    @Test
    void persistedAstronomyAcceptsOnlyIntegerDegreesInRange() {
        assertEquals(-90, PrimeConfig.parseLatitudeDegrees("-90"));
        assertEquals(30, PrimeConfig.parseLatitudeDegrees("30"));
        assertEquals(90, PrimeConfig.parseLatitudeDegrees("90"));
        assertEquals(0, PrimeConfig.parseSolarLongitudeDegrees("0"));
        assertEquals(359, PrimeConfig.parseSolarLongitudeDegrees("359"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseLatitudeDegrees("-91"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseLatitudeDegrees("30.5"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseSolarLongitudeDegrees("-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseSolarLongitudeDegrees("360"));
    }

    @Test
    void missingOrInvalidAstronomyMigratesToPersistedDefaults() {
        PrimeConfig.AstronomyLoad missing =
                PrimeConfig.parseAstronomy(new Properties());
        assertEquals(AstronomySettings.defaults(), missing.settings());
        assertTrue(missing.rewriteNeeded());

        Properties valid = new Properties();
        valid.setProperty("astronomy.latitude_degrees", "-45");
        valid.setProperty("astronomy.solar_longitude_degrees", "271");
        PrimeConfig.AstronomyLoad accepted =
                PrimeConfig.parseAstronomy(valid);
        assertEquals(
                new AstronomySettings(-45, 271),
                accepted.settings());
        assertFalse(accepted.rewriteNeeded());

        valid.setProperty("astronomy.latitude_degrees", "91");
        valid.setProperty("astronomy.solar_longitude_degrees", "-1");
        PrimeConfig.AstronomyLoad invalid =
                PrimeConfig.parseAstronomy(valid);
        assertEquals(AstronomySettings.defaults(), invalid.settings());
        assertTrue(invalid.rewriteNeeded());
        assertTrue(
                PrimeConfig.serializedContents()
                        .contains("astronomy.latitude_degrees=30\n"));
        assertTrue(
                PrimeConfig.serializedContents()
                        .contains("astronomy.solar_longitude_degrees=0\n"));
    }

    @Test
    void persistedEvAcceptsOnlyExactQuarterStopsInRange() {
        assertEquals(5, PrimeConfig.parseEvQuarterSteps("1.25"));
        assertEquals(-32, PrimeConfig.parseEvQuarterSteps("-8"));
        assertEquals(32, PrimeConfig.parseEvQuarterSteps("8"));
        assertEquals("1.25", PrimeConfig.formatEv(5));
        assertEquals("0", PrimeConfig.formatEv(0));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseEvQuarterSteps("0.1"));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseEvQuarterSteps("8.25"));
        assertEquals(32, PrimeConfig.parseStarEvQuarterSteps("8"));
        assertEquals("8", PrimeConfig.formatStarEv(32));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseStarEvQuarterSteps("8.25"));
    }

    @Test
    void persistedAutoExposureCompensationAcceptsOnlyExactHundredthsInRange() {
        assertEquals(0, PrimeConfig.parseAutoExposureCompensationSteps("0"));
        assertEquals(50, PrimeConfig.parseAutoExposureCompensationSteps("0.5"));
        assertEquals(100, PrimeConfig.parseAutoExposureCompensationSteps("1"));
        assertEquals("0.5", PrimeConfig.formatAutoExposureCompensation(50));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseAutoExposureCompensationSteps("0.505"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseAutoExposureCompensationSteps("1.01"));
    }

    @Test
    void persistedFinalExposureAcceptsOnlyExactQuarterStopsInRange() {
        assertEquals(5, PrimeConfig.parseFinalExposureQuarterSteps("1.25"));
        assertEquals(-32, PrimeConfig.parseFinalExposureQuarterSteps("-8"));
        assertEquals(32, PrimeConfig.parseFinalExposureQuarterSteps("8"));
        assertEquals("1.25", PrimeConfig.formatFinalExposure(5));
        assertEquals("0", PrimeConfig.formatFinalExposure(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseFinalExposureQuarterSteps("0.1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseFinalExposureQuarterSteps("8.25"));
    }

    @Test
    void persistedDefaultRoughnessAcceptsOnlyExactHundredths() {
        assertEquals(0, PrimeConfig.parseRoughnessSteps("0"));
        assertEquals(80, PrimeConfig.parseRoughnessSteps("0.8"));
        assertEquals(100, PrimeConfig.parseRoughnessSteps("1"));
        assertEquals("0.8", PrimeConfig.formatRoughness(80));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseRoughnessSteps("0.805"));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseRoughnessSteps("1.01"));
    }

    @Test
    void persistedVoxelSurfaceStrengthAcceptsExactPercentSteps() {
        assertEquals(0, PrimeConfig.parseVoxelSurfaceStrengthSteps("0"));
        assertEquals(100, PrimeConfig.parseVoxelSurfaceStrengthSteps("1"));
        assertEquals(200, PrimeConfig.parseVoxelSurfaceStrengthSteps("2"));
        assertEquals("1", PrimeConfig.formatVoxelSurfaceStrength(100));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseVoxelSurfaceStrengthSteps("1.005"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseVoxelSurfaceStrengthSteps("2.01"));
    }

    @Test
    void missingAndUnknownPostProcessingValuesRequestRrByDefault() {
        assertEquals(PostProcessingMode.DLSS_RR, PostProcessingMode.DEFAULT);
        assertEquals(PostProcessingMode.DLSS_RR, PostProcessingMode.fromId(null));
        assertEquals(PostProcessingMode.DLSS_RR, PostProcessingMode.fromId("future_backend"));
        assertEquals(ReconstructionQualityMode.PERFORMANCE, ReconstructionQualityMode.DEFAULT);
        assertEquals(
                ReconstructionQualityMode.PERFORMANCE,
                ReconstructionQualityMode.fromId("future_quality"));
        assertEquals(DlssRrDebugView.OFF, DlssRrDebugView.fromId("future_view"));
    }

    @Test
    void removedIntegratorPropertiesAreRecognizedForRewrite() {
        Properties properties = new Properties();
        assertFalse(PrimeConfig.hasLegacyIntegratorProperties(properties));
        properties.setProperty("renderer.integrator_mode", "megakernel");
        assertTrue(PrimeConfig.hasLegacyIntegratorProperties(properties));
        properties.clear();
        properties.setProperty("renderer.integrator", "performance");
        assertTrue(PrimeConfig.hasLegacyIntegratorProperties(properties));
        properties.clear();
        properties.setProperty("renderer.performance_maximum_bounces", "6");
        assertTrue(PrimeConfig.hasLegacyIntegratorProperties(properties));
        properties.clear();
        properties.setProperty("renderer.lightweight_maximum_bounces", "6");
        assertTrue(PrimeConfig.hasLegacyIntegratorProperties(properties));
    }

    @Test
    void legacyFsrQualityMigratesUnlessTheSharedKeyExists() {
        Properties legacy = new Properties();
        legacy.setProperty("fsr.quality", "balanced");
        assertEquals("balanced", PrimeConfig.configuredQualityId(legacy));

        legacy.setProperty("post_processing.quality", "quality");
        assertEquals("quality", PrimeConfig.configuredQualityId(legacy));
    }

    @Test
    void debugSelectionsAreSessionOnlyAndLegacyKeysAreRemovedOnRewrite() {
        String serialized = PrimeConfig.serializedContents();
        assertTrue(serialized.contains("renderer.path_tracing=true\n"));
        assertTrue(serialized.contains("renderer.sharc=true\n"));
        assertTrue(serialized.contains("renderer.scatter_count=16\n"));
        assertTrue(PrimeSettings.defaults().sharcEnabled());
        assertFalse(serialized.contains("renderer.integrator="));
        assertFalse(serialized.contains("renderer.integrator_mode="));
        assertFalse(serialized.contains("renderer.performance_maximum_bounces"));
        assertFalse(serialized.contains("renderer.lightweight_maximum_bounces"));
        assertTrue(serialized.contains(
                "experimental.voxel_texture_surfaces=false\n"));
        assertTrue(serialized.contains(
                "experimental.voxel_texture_surface_strength=1\n"));
        assertFalse(PrimeSettings.defaults().voxelTextureSurfaces());
        assertEquals(
                100,
                PrimeSettings.defaults().voxelTextureSurfaceStrengthSteps());
        assertFalse(serialized.contains("debug_view"));
        assertFalse(serialized.contains("debug_fullscreen"));
        assertTrue(serialized.contains("astronomy.latitude_degrees=30\n"));
        assertTrue(serialized.contains("astronomy.solar_longitude_degrees=0\n"));
        assertTrue(serialized.contains("lighting.star_ev=0\n"));
        assertTrue(serialized.contains("display.final_exposure_ev=0\n"));
        assertFalse(serialized.contains("display.transform="));
        assertFalse(serialized.contains("display.oklab_overexposure"));
        assertFalse(serialized.contains("display.oklab_curve_exponent"));
        assertTrue(serialized.contains("display.auto_exposure_compensation=0.5\n"));
        assertTrue(serialized.contains("material.seamless_glass=true\n"));
        assertTrue(serialized.contains("material.air_gap=true\n"));
        assertTrue(serialized.contains("material.vanilla_pbr_presets=true\n"));
        assertTrue(PrimeSettings.defaults().seamlessGlass());
        assertTrue(PrimeSettings.defaults().airGap());
        assertTrue(PrimeSettings.defaults().vanillaPbrPresets());

        Properties properties = new Properties();
        assertFalse(PrimeConfig.hasLegacyDebugProperties(properties));
        assertFalse(PrimeConfig.hasLegacyDisplayTransformProperties(properties));
        properties.setProperty("dlss_rr.debug_view", "motion");
        assertTrue(PrimeConfig.hasLegacyDebugProperties(properties));
        properties.setProperty("display.transform", "oklab");
        assertTrue(PrimeConfig.hasLegacyDisplayTransformProperties(properties));
    }

    @Test
    void scatterCountAcceptsOnlyTheSharedRuntimeRange() {
        assertEquals(1, PrimeConfig.parseScatterCount("1"));
        assertEquals(64, PrimeConfig.parseScatterCount("64"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseScatterCount("0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseScatterCount("65"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseScatterCount("12.0"));
    }

    @Test
    void pathTracingSwitchAcceptsOnlyExplicitBooleans() {
        assertTrue(PrimeConfig.parseBoolean("true"));
        assertTrue(PrimeConfig.parseBoolean("TRUE"));
        assertFalse(PrimeConfig.parseBoolean("false"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseBoolean("enabled"));
    }
}
