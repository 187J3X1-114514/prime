package dev.prime.render;

import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.RealtimePostProcessor;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import java.util.Objects;

/**
 * Device-address-free semantic input captured once for an interactive frame.
 *
 * <p>Sample and reconstruction histories derive their plans from this value. GPU residency,
 * backend frame tokens and command buffers are deliberately outside the record.
 */
public record RealtimeFrameInput(
        FrameCamera camera,
        long frameTimeNanos,
        long sceneRevision,
        long residentSceneRevision,
        long textureRevision,
        int width,
        int height,
        int displayWidth,
        int displayHeight,
        SunDirection sunDirection,
        boolean cameraInWater,
        PostProcessingMode postProcessingMode,
        ReconstructionQualityMode quality,
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        boolean shInput,
        boolean triangleDebug,
        float displayOverexposure,
        NrdDiagnostics.Mode nrdDebugView,
        FsrDebugView fsrDebugView,
        DlssRrDebugView rrDebugView,
        boolean rrDebugFullscreen,
        boolean forceReset) {
    public RealtimeFrameInput {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(sunDirection, "sunDirection");
        Objects.requireNonNull(postProcessingMode, "postProcessingMode");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(nrdDebugView, "nrdDebugView");
        Objects.requireNonNull(fsrDebugView, "fsrDebugView");
        Objects.requireNonNull(rrDebugView, "rrDebugView");
        if (width <= 0
                || height <= 0
                || displayWidth <= 0
                || displayHeight <= 0) {
            throw new IllegalArgumentException(
                    "Realtime render and display extents must be positive");
        }
        if (residentSceneRevision < 0L) {
            throw new IllegalArgumentException(
                    "Realtime resident scene revision must be non-negative");
        }
        if (!Float.isFinite(displayOverexposure)
                || displayOverexposure < 1.0F
                || displayOverexposure > 2.0F) {
            throw new IllegalArgumentException(
                    "Display overexposure must be between 1.0 and 2.0");
        }
    }

    void requireCompatible(RealtimePostProcessor processor) {
        Objects.requireNonNull(processor, "processor");
        if (this.postProcessingMode != processor.mode()
                || this.quality != processor.quality()
                || this.width != processor.renderWidth()
                || this.height != processor.renderHeight()
                || this.displayWidth != processor.displayWidth()
                || this.displayHeight != processor.displayHeight()) {
            throw new IllegalStateException(
                    "Realtime frame input does not match its reconstruction backend");
        }
    }

    RealtimeSampleState.Input sampleStateInput() {
        return new RealtimeSampleState.Input(
                this.camera,
                this.sceneRevision,
                this.textureRevision,
                this.lighting.revision(),
                this.material.revision(),
                this.sunDirection,
                this.cameraInWater,
                this.forceReset);
    }

    RealtimePostProcessor.FrameParameters reconstructionInput(
            boolean forceRestart) {
        return new RealtimePostProcessor.FrameParameters(
                this.camera,
                this.frameTimeNanos,
                this.sceneRevision,
                this.textureRevision,
                forceRestart,
                this.sunDirection,
                this.lighting.sunMultiplier(),
                this.displayOverexposure,
                this.nrdDebugView,
                this.fsrDebugView,
                this.rrDebugView,
                this.rrDebugFullscreen);
    }

    IntegratorFrameInput integratorInput(
            int sampleIndex,
            int sampleEpoch,
            int reconstructionFrameIndex) {
        ReconstructionQualityMode rayQuality =
                this.postProcessingMode == PostProcessingMode.DISABLED
                        ? ReconstructionQualityMode.NATIVE_AA
                        : this.quality;
        return new IntegratorFrameInput(
                this.camera,
                this.width,
                this.height,
                this.sunDirection,
                rayQuality.packedRayCone(
                        this.camera.projection().m00(),
                        this.camera.projection().m11(),
                        this.width,
                        this.height),
                sampleIndex,
                sampleEpoch,
                this.jitterPhase(reconstructionFrameIndex),
                this.cameraInWater,
                this.postProcessingMode,
                this.lighting,
                this.material,
                this.shInput,
                this.postProcessingMode == PostProcessingMode.NRD_FSR
                        && this.nrdDebugView.rawNumerical(),
                this.triangleDebug);
    }

    FsrSettings.Jitter expectedJitter(int reconstructionFrameIndex) {
        if (reconstructionFrameIndex < 0) {
            throw new IllegalArgumentException(
                    "Reconstruction frame index must be non-negative");
        }
        return this.postProcessingMode == PostProcessingMode.NRD_FSR
                ? this.quality.fsrJitter(reconstructionFrameIndex)
                : this.quality.rrJitter(reconstructionFrameIndex);
    }

    private int jitterPhase(int reconstructionFrameIndex) {
        return this.postProcessingMode == PostProcessingMode.NRD_FSR
                ? this.quality.fsrJitterPhase(reconstructionFrameIndex)
                : this.quality.rrJitterPhase(reconstructionFrameIndex);
    }
}
