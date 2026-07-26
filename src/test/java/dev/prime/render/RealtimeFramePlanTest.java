package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.RealtimePostProcessor;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.VulkanImage;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class RealtimeFramePlanTest {
    @Test
    void everyModeProducesOneConsistentDeviceFreeFramePlan() {
        for (PostProcessingMode mode : PostProcessingMode.values()) {
            for (ReconstructionQualityMode quality
                    : ReconstructionQualityMode.values()) {
                RealtimeFrameInput input = input(mode, quality);
                RealtimeSampleState.Plan sample =
                        RealtimeSampleState.initial().plan(
                                input.sampleStateInput());
                FsrSettings.Jitter jitter = input.expectedJitter(0);
                RealtimeFramePlan plan = RealtimeFramePlan.complete(
                        input,
                        sample,
                        new FrameStub(0, jitter, true));

                assertEquals(input.camera(), plan.integrator().camera());
                assertEquals(input.width(), plan.integrator().width());
                assertEquals(input.height(), plan.integrator().height());
                assertEquals(mode, plan.integrator().postProcessingMode());
                assertEquals(0, plan.integrator().sampleIndex());
                assertEquals(sample.epoch(), plan.integrator().sampleEpoch());
                assertEquals(jitter, plan.jitter());
                assertTrue(plan.reconstruction().forceRestart());
                assertTrue(plan.reconstructionReset());
                assertEquals(
                        mode == PostProcessingMode.NRD_FSR,
                        plan.integrator().rawNumericalDiagnostic());
                if (mode == PostProcessingMode.DISABLED) {
                    assertEquals(
                            ReconstructionQualityMode.NATIVE_AA.packedRayCone(
                                    input.camera().projection().m00(),
                                    input.camera().projection().m11(),
                                    input.width(),
                                    input.height()),
                            plan.integrator().packedRayCone());
                }
            }
        }
    }

    @Test
    void committedInputAdvancesWithoutRestartAndPreservesCapturedTime() {
        RealtimeFrameInput firstInput = input(
                PostProcessingMode.NRD_FSR,
                ReconstructionQualityMode.QUALITY);
        RealtimeSampleState.Plan first = RealtimeSampleState.initial().plan(
                firstInput.sampleStateInput());
        RealtimeFrameInput secondInput = new RealtimeFrameInput(
                firstInput.camera(),
                99L,
                firstInput.sceneRevision(),
                firstInput.textureRevision(),
                firstInput.width(),
                firstInput.height(),
                firstInput.displayWidth(),
                firstInput.displayHeight(),
                firstInput.sunDirection(),
                firstInput.cameraInWater(),
                firstInput.postProcessingMode(),
                firstInput.quality(),
                firstInput.lighting(),
                firstInput.material(),
                firstInput.shInput(),
                firstInput.triangleDebug(),
                firstInput.displayOverexposure(),
                firstInput.nrdDebugView(),
                firstInput.fsrDebugView(),
                firstInput.rrDebugView(),
                firstInput.rrDebugFullscreen(),
                false);
        RealtimeSampleState.Plan second = first.committedState().plan(
                secondInput.sampleStateInput());
        RealtimeFramePlan plan = RealtimeFramePlan.complete(
                secondInput,
                second,
                new FrameStub(
                        1, secondInput.expectedJitter(1), false));

        assertFalse(second.reset());
        assertEquals(1, plan.integrator().sampleIndex());
        assertEquals(99L, plan.reconstruction().frameTimeNanos());
        assertFalse(plan.reconstruction().forceRestart());
        assertFalse(plan.reconstructionReset());
    }

    @Test
    void backendCannotDivergeFromResetOrJitterSemantics() {
        RealtimeFrameInput input = input(
                PostProcessingMode.DLSS_RR,
                ReconstructionQualityMode.PERFORMANCE);
        RealtimeSampleState.Plan sample = RealtimeSampleState.initial().plan(
                input.sampleStateInput());

        assertThrows(
                IllegalStateException.class,
                () -> RealtimeFramePlan.complete(
                        input,
                        sample,
                        new FrameStub(
                                0, input.expectedJitter(0), false)));
        assertThrows(
                IllegalStateException.class,
                () -> RealtimeFramePlan.complete(
                        input,
                        sample,
                        new FrameStub(
                                0,
                                new FsrSettings.Jitter(0.0F, 0.0F),
                                true)));
    }

    @Test
    void capturedExtentAndModeMustMatchReconstructionBackend() {
        RealtimeFrameInput input = input(
                PostProcessingMode.NRD_FSR,
                ReconstructionQualityMode.QUALITY);

        input.requireCompatible(new BackendStub(
                input.postProcessingMode(),
                input.quality(),
                input.width(),
                input.height(),
                input.displayWidth(),
                input.displayHeight()));
        assertThrows(
                IllegalStateException.class,
                () -> input.requireCompatible(new BackendStub(
                        input.postProcessingMode(),
                        input.quality(),
                        input.width(),
                        input.height(),
                        input.displayWidth() + 1,
                        input.displayHeight())));
    }

    private static RealtimeFrameInput input(
            PostProcessingMode mode,
            ReconstructionQualityMode quality) {
        return new RealtimeFrameInput(
                new FrameCamera(new Matrix4f(), 1.0, 2.0, 3.0),
                42L,
                7L,
                11L,
                64,
                48,
                128,
                96,
                new SunDirection(0.0F, 1.0F, 0.0F),
                false,
                mode,
                quality,
                new LightingSettings.Snapshot(
                        0, 0, 0, 1.0F, 1.0F, 1.0F, 13L),
                new MaterialSettings.Snapshot(90, 0.9F, 17L),
                true,
                false,
                1.0F,
                NrdDiagnostics.Mode.RAW_NUMERICAL,
                FsrDebugView.OFF,
                DlssRrDebugView.OFF,
                false,
                false);
    }

    private record FrameStub(
            int frameIndex,
            FsrSettings.Jitter jitter,
            boolean reset)
            implements RealtimePostProcessor.Frame {
    }

    private record BackendStub(
            PostProcessingMode mode,
            ReconstructionQualityMode quality,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight)
            implements RealtimePostProcessor {
        @Override
        public RawWavefrontFrame rawFrame() {
            throw new UnsupportedOperationException();
        }

        @Override
        public VulkanImage linearHdrOutput() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void requestReset() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Frame beginFrame(FrameParameters parameters) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void prepareForRayTrace(VkCommandBuffer commandBuffer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void record(
                VkCommandBuffer commandBuffer,
                Frame frame,
                FrameParameters parameters) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void submitted(Frame frame) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void destroy() {
            throw new UnsupportedOperationException();
        }
    }
}
