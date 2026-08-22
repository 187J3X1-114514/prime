package dev.prime.render.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.vulkan.dlss.DlssRrProfile;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class ReconstructionModesTest {
    @Test
    void modeAndSessionDebugIdsRoundTrip() {
        for (PostProcessingMode mode : PostProcessingMode.values()) {
            assertEquals(mode, PostProcessingMode.fromId(mode.id()));
        }
        for (DlssRrDebugView view : DlssRrDebugView.values()) {
            assertEquals(view, DlssRrDebugView.fromId(view.id()));
            DlssRrDebugView cycled = view;
            for (int index = 0; index < DlssRrDebugView.values().length; index++) {
                cycled = cycled.next();
            }
            assertEquals(view, cycled);
        }
        Set<Integer> shaderIds = Arrays.stream(DlssRrDebugView.values())
                .map(DlssRrDebugView::shaderId)
                .collect(Collectors.toSet());
        assertEquals(DlssRrDebugView.values().length, shaderIds.size());
        assertEquals(0, DlssRrDebugView.OFF.shaderId());
        assertEquals(12, DlssRrDebugView.RR_OUTPUT.shaderId());
        assertEquals(13, DlssRrDebugView.WAVEFRONT_OVERVIEW.shaderId());
        assertEquals(14, DlssRrDebugView.HANDOFF_OVERVIEW.shaderId());
        assertEquals(15, DlssRrDebugView.GUIDE_RESOLVE_OVERVIEW.shaderId());
    }

    @Test
    void rrHandoffDiagnosticsPublishTheirPanelSemantics() {
        List<String> handoff = DlssRrDebugStatus.lines(
                ReconstructionQualityMode.PERFORMANCE,
                960,
                540,
                1920,
                1080,
                true,
                false,
                DlssRrDebugView.HANDOFF_OVERVIEW,
                true);
        assertTrue(handoff.contains(
                "Columns normal roughness diffuse-albedo specular-albedo specular-hit"));
        assertTrue(handoff.contains("Top RR-submitted / Bottom wavefront-scratch"));
        List<String> guideResolve = DlssRrDebugStatus.lines(
                ReconstructionQualityMode.PERFORMANCE,
                960,
                540,
                1920,
                1080,
                true,
                false,
                DlssRrDebugView.GUIDE_RESOLVE_OVERVIEW,
                true);
        assertTrue(guideResolve.contains(
                "Columns visible final-trans first-owned-trans selected flags"));
        assertTrue(guideResolve.contains(
                "Rows normal roughness diffuse-albedo specular-albedo"));
        assertTrue(guideResolve.contains(
                "Flags bottom: final reached / primary-bounce-zero / fallback (RGB)"));
        assertEquals(
                List.of(),
                DlssRrDebugStatus.lines(
                        ReconstructionQualityMode.PERFORMANCE,
                        960,
                        540,
                        1920,
                        1080,
                        true,
                        false,
                        DlssRrDebugView.OFF,
                        true));
    }

    @Test
    void fiveSharedQualityModesMapToFsrAndNgxWithoutChangingTheDefault() {
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
            assertEquals(ngx.get(quality), DlssRrProfile.ngxPerfQualityValue(quality));
            assertTrue(DlssRrProfile.jitterPhaseCount(quality) >= 64);
            assertEquals(
                    DlssRrProfile.jitter(quality, 0),
                    DlssRrProfile.jitter(
                            quality, DlssRrProfile.jitterPhaseCount(quality)));
        }
    }
}
