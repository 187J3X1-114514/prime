package dev.prime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import java.util.Properties;
import org.junit.jupiter.api.Test;

final class PrimeConfigTest {
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
    void persistedOklabOverexposureAcceptsOnlyExactThirtySeconds() {
        assertEquals(32, PrimeConfig.parseOverexposureSteps("1"));
        assertEquals(33, PrimeConfig.parseOverexposureSteps("1.03125"));
        assertEquals(64, PrimeConfig.parseOverexposureSteps("2"));
        assertEquals("1.03125", PrimeConfig.formatOverexposure(33));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseOverexposureSteps("1.03"));
        assertThrows(IllegalArgumentException.class,
                () -> PrimeConfig.parseOverexposureSteps("2.03125"));
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
        assertFalse(serialized.contains("debug_view"));
        assertFalse(serialized.contains("debug_fullscreen"));
        assertTrue(serialized.contains("lighting.star_ev=0\n"));
        assertTrue(serialized.contains("display.final_exposure_ev=0\n"));

        Properties properties = new Properties();
        assertFalse(PrimeConfig.hasLegacyDebugProperties(properties));
        properties.setProperty("dlss_rr.debug_view", "motion");
        assertTrue(PrimeConfig.hasLegacyDebugProperties(properties));
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
