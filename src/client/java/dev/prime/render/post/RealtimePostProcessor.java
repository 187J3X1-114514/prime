package dev.prime.render.post;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.FrameCamera;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.SunDirection;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanImageInitializationBatch;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Public real-time reconstruction boundary shared by NRD-FSR and DLSS RR.
 *
 * <p>It owns render extent, temporal jitter/history, raw path targets, command recording,
 * submission state, full-resolution linear HDR output, and backend lifetime.
 */
public interface RealtimePostProcessor extends Destroyable {
    PostProcessingMode mode();

    ReconstructionQualityMode quality();

    int renderWidth();

    int renderHeight();

    int displayWidth();

    int displayHeight();

    RawWavefrontFrame rawFrame();

    VulkanImage linearHdrOutput();

    /** Device-local 16-byte auto-exposure state used by the current display transform. */
    long displayExposureStateBuffer();

    void requestReset();

    Frame beginFrame(FrameParameters parameters);

    void prepareForRayTrace(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization);

    void record(
            VkCommandBuffer commandBuffer,
            Frame frame,
            FrameParameters parameters,
            VulkanImageInitializationBatch initialization);

    /** Releases a frame whose command buffer was not submitted. */
    void abandon(Frame frame);

    void submitted(Frame frame);

    interface Frame {
        int frameIndex();

        FsrSettings.Jitter jitter();

        boolean reset();
    }

    record FrameParameters(
            FrameCamera camera,
            long frameTimeNanos,
            long sceneRevision,
            long textureRevision,
            boolean forceRestart,
            SunDirection sunDirection,
            LightingSettings.Snapshot lighting,
            DisplaySettings.Snapshot display,
            NrdDiagnostics.Mode nrdDebugView,
            FsrDebugView fsrDebugView,
            DlssRrDebugView rrDebugView,
            boolean rrDebugFullscreen) {
        public FrameParameters {
            camera = Objects.requireNonNull(camera, "camera");
            sunDirection = Objects.requireNonNull(sunDirection, "sunDirection");
            lighting = Objects.requireNonNull(lighting, "lighting");
            display = Objects.requireNonNull(display, "display");
            nrdDebugView = Objects.requireNonNull(nrdDebugView, "nrdDebugView");
            fsrDebugView = Objects.requireNonNull(fsrDebugView, "fsrDebugView");
            rrDebugView = Objects.requireNonNull(rrDebugView, "rrDebugView");
        }

        public float sunRadianceMultiplier() {
            return this.lighting.sunMultiplier();
        }
    }
}
