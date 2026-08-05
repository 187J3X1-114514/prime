package dev.prime.render.vulkan.reconstruction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.TransparentGuideMode;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReconstructionBackendRegistryTest {
    @Test
    void selectsRequestedBuiltInBackendAndItsExtent() {
        Reporter reporter = new Reporter();
        StubBackend dlss = backend(PostProcessingMode.DLSS_RR, 1280, 720);
        ReconstructionBackendRegistry registry = registry(dlss, reporter);

        ResolvedReconstruction resolved = registry.resolve(
                PostProcessingMode.DLSS_RR,
                ReconstructionQualityMode.QUALITY,
                1920,
                1080);

        assertEquals(PostProcessingMode.DLSS_RR, resolved.requestedMode());
        assertEquals(PostProcessingMode.DLSS_RR, resolved.effectiveMode());
        assertEquals(new ReconstructionExtent(1280, 720), resolved.extent());
        assertEquals(new ReconstructionExtent(1920, 1080), resolved.displayExtent());
        assertFalse(resolved.fellBack());
        assertEquals(0, reporter.total());
    }

    @Test
    void unavailableDlssFallsBackWithoutChangingTheRequestedProduct() {
        Reporter reporter = new Reporter();
        StubBackend dlss = backend(PostProcessingMode.DLSS_RR, 1280, 720);
        dlss.capability = ReconstructionBackend.Capability.unsupported("missing capability");
        ReconstructionBackendRegistry registry = registry(dlss, reporter);

        ResolvedReconstruction first = registry.resolve(
                PostProcessingMode.DLSS_RR,
                ReconstructionQualityMode.PERFORMANCE,
                1920,
                1080);
        ResolvedReconstruction second = registry.resolve(
                PostProcessingMode.DLSS_RR,
                ReconstructionQualityMode.PERFORMANCE,
                1920,
                1080);

        assertEquals(PostProcessingMode.DLSS_RR, first.requestedMode());
        assertEquals(PostProcessingMode.NRD_FSR, first.effectiveMode());
        assertTrue(first.fellBack());
        assertEquals(first.extent(), second.extent());
        assertEquals(1, reporter.unavailable);
        assertEquals(0, reporter.failed);
    }

    @Test
    void queryAndFeatureFailuresUseTheSameSingleDlssFallbackPath() {
        Reporter queryReporter = new Reporter();
        StubBackend queryDlss = backend(PostProcessingMode.DLSS_RR, 1280, 720);
        queryDlss.queryFailure = new IllegalStateException("query");
        ReconstructionBackendRegistry queryRegistry = registry(queryDlss, queryReporter);
        assertEquals(
                PostProcessingMode.NRD_FSR,
                queryRegistry.resolve(
                                PostProcessingMode.DLSS_RR,
                                ReconstructionQualityMode.BALANCED,
                                1920,
                                1080)
                        .effectiveMode());
        assertEquals(1, queryReporter.failed);

        Reporter createReporter = new Reporter();
        ReconstructionBackendRegistry createRegistry = registry(
                backend(PostProcessingMode.DLSS_RR, 1280, 720), createReporter);
        ResolvedReconstruction selected = createRegistry.resolve(
                PostProcessingMode.DLSS_RR,
                ReconstructionQualityMode.BALANCED,
                1920,
                1080);
        ResolvedReconstruction fallback = createRegistry.recoverCreationFailure(
                selected, new IllegalStateException("feature"));
        assertEquals(PostProcessingMode.NRD_FSR, fallback.effectiveMode());
        assertEquals(1, createReporter.failed);
        createRegistry.recoverCreationFailure(
                selected, new IllegalStateException("feature again"));
        assertEquals(1, createReporter.failed);
    }

    @Test
    void nrdAndNoisyFailuresRemainFailFast() {
        Reporter reporter = new Reporter();
        StubBackend nrd = backend(PostProcessingMode.NRD_FSR, 1280, 720);
        nrd.queryFailure = new IllegalStateException("nrd failed");
        Map<PostProcessingMode, ReconstructionBackend> backends = backends(
                backend(PostProcessingMode.DLSS_RR, 1280, 720), nrd);
        ReconstructionBackendRegistry registry =
                new ReconstructionBackendRegistry(backends, reporter);

        assertThrows(
                IllegalStateException.class,
                () -> registry.resolve(
                        PostProcessingMode.NRD_FSR,
                        ReconstructionQualityMode.QUALITY,
                        1920,
                        1080));
        assertEquals(0, reporter.total());
    }

    private static ReconstructionBackendRegistry registry(
            StubBackend dlss, Reporter reporter) {
        return new ReconstructionBackendRegistry(
                backends(dlss, backend(PostProcessingMode.NRD_FSR, 960, 540)),
                reporter);
    }

    private static Map<PostProcessingMode, ReconstructionBackend> backends(
            StubBackend dlss, StubBackend nrd) {
        EnumMap<PostProcessingMode, ReconstructionBackend> values =
                new EnumMap<>(PostProcessingMode.class);
        values.put(PostProcessingMode.DLSS_RR, dlss);
        values.put(PostProcessingMode.NRD_FSR, nrd);
        values.put(PostProcessingMode.DISABLED, backend(PostProcessingMode.DISABLED, 1920, 1080));
        return values;
    }

    private static StubBackend backend(PostProcessingMode mode, int width, int height) {
        return new StubBackend(mode, new ReconstructionExtent(width, height));
    }

    private static final class Reporter
            implements ReconstructionBackendRegistry.FailureReporter {
        private int unavailable;
        private int failed;

        @Override
        public void unavailable(String reason) {
            this.unavailable++;
        }

        @Override
        public void failed(String operation, RuntimeException exception) {
            this.failed++;
        }

        int total() {
            return this.unavailable + this.failed;
        }
    }

    private static final class StubBackend implements ReconstructionBackend {
        private final PostProcessingMode mode;
        private final ReconstructionExtent extent;
        private Capability capability = Capability.supported();
        private RuntimeException queryFailure;

        private StubBackend(PostProcessingMode mode, ReconstructionExtent extent) {
            this.mode = mode;
            this.extent = extent;
        }

        @Override public PostProcessingMode mode() { return this.mode; }
        @Override public Capability capability() { return this.capability; }

        @Override
        public ReconstructionExtent renderExtent(
                ReconstructionQualityMode quality, int displayWidth, int displayHeight) {
            if (this.queryFailure != null) {
                throw this.queryFailure;
            }
            return this.extent;
        }

        @Override
        public PostProcessingMode fallbackMode() {
            return this.mode == PostProcessingMode.DLSS_RR
                    ? PostProcessingMode.NRD_FSR
                    : null;
        }

        @Override
        public TransparentGuideMode transparentGuideMode() {
            return switch (this.mode) {
                case NRD_FSR -> TransparentGuideMode.REFLECTION_AND_TRANSMISSION;
                case DLSS_RR -> TransparentGuideMode.TRANSMISSION_ONLY;
                case DISABLED -> TransparentGuideMode.DISABLED;
            };
        }

        @Override
        public SubpixelJitter jitter(ReconstructionQualityMode quality, int frameIndex) {
            return new SubpixelJitter(0.0F, 0.0F);
        }

        @Override
        public int jitterPhase(ReconstructionQualityMode quality, int frameIndex) {
            return 1;
        }

        @Override public String executionLabel() { return this.mode.id(); }

        @Override
        public VulkanReconstructionProcessor create(CreateInput input) {
            throw new UnsupportedOperationException();
        }
    }
}
