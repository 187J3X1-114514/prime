package dev.prime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.AstronomySettings;
import dev.prime.render.HdrOutput;
import dev.prime.render.ScatterSettings;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainWorkerSettings;
import java.util.Properties;
import org.junit.jupiter.api.Test;

final class PrimeConfigTest {
    @Test
    void restoreDefaultsIncludesStandaloneSchedulingSettings() {
        PrimeConfig.setScatterCount(ScatterSettings.MAXIMUM_COUNT);
        PrimeConfig.setTerrainWorkerPercentage(TerrainWorkerSettings.MAXIMUM_PERCENTAGE);
        PrimeConfig.setHdrEnabled(true);
        PrimeConfig.setReferenceWhiteNits(400);

        PrimeConfig.restoreDefaults();

        assertEquals(ScatterSettings.DEFAULT_COUNT, PrimeConfig.scatterCount());
        assertEquals(
                ScatterSettings.DEFAULT_COUNT,
                PrimeConfig.rendererSettings().scatterCount());
        assertEquals(
                TerrainWorkerSettings.DEFAULT_PERCENTAGE,
                PrimeConfig.terrainWorkerPercentage());
        assertEquals(
                TerrainWorkerSettings.DEFAULT_PERCENTAGE,
                PrimeConfig.rendererSettings().terrainWorkerPercentage());
        assertFalse(PrimeConfig.hdrEnabled());
        assertFalse(HdrOutput.requested());
        assertEquals(0, PrimeConfig.referenceWhiteNits());
        assertEquals(0, HdrOutput.referenceWhiteNits());
    }

    @Test
    void terrainWorkerShareDoesNotInvalidateTemporalRendering() {
        int previousPercentage = PrimeConfig.terrainWorkerPercentage();
        long previousRevision = PrimeConfig.rendererSettings().revision();
        int replacement = previousPercentage == TerrainWorkerSettings.MAXIMUM_PERCENTAGE
                ? TerrainWorkerSettings.DEFAULT_PERCENTAGE
                : TerrainWorkerSettings.MAXIMUM_PERCENTAGE;
        try {
            PrimeConfig.setTerrainWorkerPercentage(replacement);

            assertEquals(replacement, PrimeConfig.rendererSettings().terrainWorkerPercentage());
            assertEquals(previousRevision, PrimeConfig.rendererSettings().revision());
        } finally {
            PrimeConfig.setTerrainWorkerPercentage(previousPercentage);
        }
    }

    @Test
    void hdrSwitchDoesNotInvalidateTemporalRendering() {
        boolean previous = PrimeConfig.hdrEnabled();
        long previousRevision = PrimeConfig.rendererSettings().revision();
        try {
            PrimeConfig.setHdrEnabled(!previous);

            assertEquals(previousRevision, PrimeConfig.rendererSettings().revision());
            assertEquals(!previous, HdrOutput.requested());
        } finally {
            PrimeConfig.setHdrEnabled(previous);
        }
    }

    @Test
    void referenceWhiteDoesNotInvalidateTemporalRendering() {
        int previous = PrimeConfig.referenceWhiteNits();
        long previousRevision = PrimeConfig.rendererSettings().revision();
        int replacement = previous == 400 ? 200 : 400;
        try {
            PrimeConfig.setReferenceWhiteNits(replacement);

            assertEquals(previousRevision, PrimeConfig.rendererSettings().revision());
            assertEquals(replacement, HdrOutput.referenceWhiteNits());
        } finally {
            PrimeConfig.setReferenceWhiteNits(previous);
        }
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
    void persistedReferenceWhiteAcceptsAutomaticOrIntegerNits() {
        assertEquals(0, PrimeConfig.parseReferenceWhiteNits("0"));
        assertEquals(400, PrimeConfig.parseReferenceWhiteNits("400"));
        assertEquals(10_000, PrimeConfig.parseReferenceWhiteNits("10000"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseReferenceWhiteNits("-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseReferenceWhiteNits("400.0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseReferenceWhiteNits("10001"));
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
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeSettings.defaults()
                        .withPostProcessingMode(PostProcessingMode.DISABLED));
        assertEquals(ReconstructionQualityMode.PERFORMANCE, ReconstructionQualityMode.DEFAULT);
        assertEquals(
                ReconstructionQualityMode.PERFORMANCE,
                ReconstructionQualityMode.fromId("future_quality"));
        assertEquals(DlssRrDebugView.OFF, DlssRrDebugView.fromId("future_view"));
    }

    @Test
    void onlyRemovedIntegratorPropertiesAreRecognizedForRewrite() {
        Properties properties = new Properties();
        assertFalse(PrimeConfig.hasLegacyIntegratorProperties(properties));
        properties.setProperty("renderer.integrator_mode", "lightweight_wavefront");
        assertTrue(PrimeConfig.hasLegacyIntegratorProperties(properties));
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
        assertTrue(serialized.contains("renderer.scatter_count=12\n"));
        assertTrue(serialized.contains("terrain.worker_percentage=50\n"));
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
        assertTrue(serialized.contains("display.hdr=false\n"));
        assertFalse(serialized.contains("display.transform="));
        assertFalse(serialized.contains("display.oklab_overexposure"));
        assertFalse(serialized.contains("display.oklab_curve_exponent"));
        assertTrue(serialized.contains("display.auto_exposure_compensation=0.6\n"));
        assertTrue(serialized.contains("display.reference_white_nits=0\n"));
        assertFalse(serialized.contains("display.middle_gray="));
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
    void terrainWorkerPercentageAcceptsOnlyIntegerPercentages() {
        assertEquals(1, PrimeConfig.parseTerrainWorkerPercentage("1"));
        assertEquals(50, PrimeConfig.parseTerrainWorkerPercentage("50"));
        assertEquals(100, PrimeConfig.parseTerrainWorkerPercentage("100"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseTerrainWorkerPercentage("0"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseTerrainWorkerPercentage("101"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimeConfig.parseTerrainWorkerPercentage("50.0"));
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
