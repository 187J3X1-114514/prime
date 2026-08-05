package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RendererFrameSettingsTest {
    @Test
    void cameraAndRenderKeepTheBeginFrameSnapshotUntilTheNextFrame() {
        RendererSettings first = settings(1L, PostProcessingMode.NRD_FSR);
        RendererSettings second = settings(2L, PostProcessingMode.DISABLED);
        AtomicReference<RendererSettings> config = new AtomicReference<>(first);
        RendererFrameSettings frame = new RendererFrameSettings();

        frame.beginFrame(config.get());
        config.set(second);

        assertSame(first, frame.forCamera());
        assertSame(first, frame.forRender());

        frame.beginFrame(config.get());
        assertSame(second, frame.forCamera());
        assertSame(second, frame.forRender());

        frame.clear();
        assertNull(frame.forCamera());
        assertNull(frame.forRender());
    }

    private static RendererSettings settings(long revision, PostProcessingMode mode) {
        return new RendererSettings(
                true,
                false,
                VoxelSurfaceSettings.DEFAULT_STEPS,
                mode,
                ReconstructionQualityMode.DEFAULT,
                AstronomySettings.defaults(),
                new LightingSettings.Snapshot(
                        LightingSettings.DEFAULT_SUN_QUARTER_STEPS,
                        LightingSettings.DEFAULT_STAR_QUARTER_STEPS,
                        LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS,
                        revision),
                new MaterialSettings.Snapshot(
                        MaterialSettings.DEFAULT_ROUGHNESS_STEPS,
                        revision),
                new DisplaySettings.Snapshot(
                        DisplaySettings.DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS,
                        DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS,
                        DisplaySettings.DEFAULT_CURVE_EXPONENT_STEPS,
                        DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS),
                revision);
    }
}
