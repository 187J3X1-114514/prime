package dev.prime.render.fsr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class FsrSettingsTest {
    @Test
    void defaultUsesQualityUpscalingWithoutFrameGeneration() {
        assertEquals("3.1.4", FsrSettings.UPSCALER_VERSION);
        assertTrue(FsrSettings.DEFAULT_ENABLED);
        assertFalse(FsrSettings.FRAME_GENERATION_ENABLED);
        assertEquals(FsrQualityMode.QUALITY, FsrSettings.DEFAULT_QUALITY_MODE);
        assertEquals(new FsrSettings.Extent(2560, 1440),
                FsrSettings.DEFAULT_QUALITY_MODE.renderExtent(3840, 2160));
        assertEquals(0.2F, FsrSettings.RCAS_SHARPNESS);
        assertEquals(1.0F, FsrSettings.EXPOSURE);
    }

    @Test
    void everyVideoPresetOwnsItsResolutionAndTemporalContract() {
        Map<FsrQualityMode, FsrSettings.Extent> expectedExtents = Map.of(
                FsrQualityMode.NATIVE_AA, new FsrSettings.Extent(3840, 2160),
                FsrQualityMode.QUALITY, new FsrSettings.Extent(2560, 1440),
                FsrQualityMode.BALANCED, new FsrSettings.Extent(2258, 1270),
                FsrQualityMode.PERFORMANCE, new FsrSettings.Extent(1920, 1080),
                FsrQualityMode.ULTRA_PERFORMANCE, new FsrSettings.Extent(1280, 720));
        Map<FsrQualityMode, Integer> expectedPhases = Map.of(
                FsrQualityMode.NATIVE_AA, 8,
                FsrQualityMode.QUALITY, 18,
                FsrQualityMode.BALANCED, 23,
                FsrQualityMode.PERFORMANCE, 32,
                FsrQualityMode.ULTRA_PERFORMANCE, 72);

        for (FsrQualityMode mode : FsrQualityMode.values()) {
            assertEquals(expectedExtents.get(mode), mode.renderExtent(3840, 2160));
            assertEquals(expectedPhases.get(mode), mode.jitterPhaseCount());
            assertEquals(
                    (float) (Math.log(1.0 / mode.upscaleRatio()) / Math.log(2.0) - 1.0),
                    mode.mipBias(),
                    1.0e-6F);
        }
    }

    @Test
    void jitterUsesTheCanonicalHaltonPhaseForEachMode() {
        FsrQualityMode mode = FsrQualityMode.QUALITY;
        assertEquals(0.0F, mode.jitter(0).x(), 1.0e-7F);
        assertEquals(-1.0F / 6.0F, mode.jitter(0).y(), 1.0e-7F);
        assertEquals(-0.25F, mode.jitter(1).x(), 1.0e-7F);
        assertEquals(1.0F / 6.0F, mode.jitter(1).y(), 1.0e-7F);
        assertEquals(0.25F, mode.jitter(1).forFsrDispatch().x(), 1.0e-7F);
        assertEquals(-1.0F / 6.0F, mode.jitter(1).forFsrDispatch().y(), 1.0e-7F);
        assertEquals(
                mode.jitter(0),
                mode.jitter(mode.jitterPhaseCount()));
    }

    @Test
    void rayConeCarriesProjectionFootprintAndModeMipBias() {
        FsrQualityMode mode = FsrQualityMode.QUALITY;
        int packed = mode.packedRayCone(1.0F, 1.0F, 1920, 1080);
        float spread = Float.float16ToFloat((short) packed);
        float bias = Float.float16ToFloat((short) (packed >>> 16));
        assertEquals(2.0F / 1080.0F, spread, 1.0e-6F);
        assertEquals(mode.mipBias(), bias, 5.0e-4F);
    }

    @Test
    void persistedIdsRoundTripAndUnknownValuesUseQuality() {
        for (FsrQualityMode mode : FsrQualityMode.values()) {
            assertEquals(mode, FsrQualityMode.fromId(mode.id()));
        }
        assertEquals(FsrQualityMode.QUALITY, FsrQualityMode.fromId("future_mode"));
        for (FsrDebugView mode : FsrDebugView.values()) {
            assertEquals(mode, FsrDebugView.fromId(mode.id()));
        }
        assertEquals(FsrDebugView.OFF, FsrDebugView.fromId("future_view"));
    }
}
