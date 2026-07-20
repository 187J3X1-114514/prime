package dev.prime.render.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.fsr.FsrQualityMode;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PostProcessingSettingsTest {
    @Test
    void persistedModeAndDebugIdsRoundTrip() {
        for (PostProcessingMode mode : PostProcessingMode.values()) {
            assertEquals(mode, PostProcessingMode.fromId(mode.id()));
        }
        for (DlssRrDebugView view : DlssRrDebugView.values()) {
            assertEquals(view, DlssRrDebugView.fromId(view.id()));
            assertEquals(view, view.next().next().next().next().next().next().next()
                    .next().next().next().next().next().next().next());
        }
    }

    @Test
    void fiveSharedQualityModesMapToFsrAndNgxWithoutChangingTheDefault() {
        Map<ReconstructionQualityMode, FsrQualityMode> fsr = Map.of(
                ReconstructionQualityMode.NATIVE_AA, FsrQualityMode.NATIVE_AA,
                ReconstructionQualityMode.QUALITY, FsrQualityMode.QUALITY,
                ReconstructionQualityMode.BALANCED, FsrQualityMode.BALANCED,
                ReconstructionQualityMode.PERFORMANCE, FsrQualityMode.PERFORMANCE,
                ReconstructionQualityMode.ULTRA_PERFORMANCE, FsrQualityMode.ULTRA_PERFORMANCE);
        Map<ReconstructionQualityMode, Integer> ngx = Map.of(
                ReconstructionQualityMode.NATIVE_AA, 5,
                ReconstructionQualityMode.QUALITY, 2,
                ReconstructionQualityMode.BALANCED, 1,
                ReconstructionQualityMode.PERFORMANCE, 0,
                ReconstructionQualityMode.ULTRA_PERFORMANCE, 3);

        assertEquals(5, ReconstructionQualityMode.values().length);
        assertEquals(ReconstructionQualityMode.PERFORMANCE, ReconstructionQualityMode.DEFAULT);
        for (ReconstructionQualityMode quality : ReconstructionQualityMode.values()) {
            assertEquals(quality, ReconstructionQualityMode.fromId(quality.id()));
            assertEquals(fsr.get(quality), quality.fsrMode());
            assertEquals(ngx.get(quality), quality.ngxPerfQualityValue());
            assertTrue(quality.rrJitterPhaseCount() >= 32);
            assertEquals(quality.rrJitter(0), quality.rrJitter(quality.rrJitterPhaseCount()));
        }
    }
}
