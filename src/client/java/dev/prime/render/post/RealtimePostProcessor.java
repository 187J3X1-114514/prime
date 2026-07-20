package dev.prime.render.post;

import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.vulkan.DenoiserInputs;
import dev.prime.render.vulkan.VulkanImage;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Public real-time reconstruction boundary shared by NRD-FSR and DLSS RR.
 *
 * <p>It owns render extent, temporal jitter/history, raw path targets, command recording,
 * submission state, full-resolution linear HDR output, and backend lifetime.
 */
public interface RealtimePostProcessor extends Denoiser {
    PostProcessingMode mode();

    @Override
    default Kind kind() {
        return switch (mode()) {
            case NRD_FSR -> Kind.NRD_FSR;
            case DLSS_RR -> Kind.DLSS_RR;
            case DISABLED -> Kind.NOISY;
        };
    }

    ReconstructionQualityMode quality();

    int renderWidth();

    int renderHeight();

    int displayWidth();

    int displayHeight();

    DenoiserInputs targets();

    VulkanImage linearHdrOutput();

    void requestReset();

    Frame beginFrame(FrameParameters parameters);

    void prepareForRayTrace(VkCommandBuffer commandBuffer);

    void record(VkCommandBuffer commandBuffer, Frame frame, FrameParameters parameters);

    void submitted(Frame frame);

    interface Frame {
        int frameIndex();

        FsrSettings.Jitter jitter();

        boolean reset();
    }

    record FrameParameters(
            FrameCamera camera,
            long sceneRevision,
            long atlasView,
            long atlasSampler,
            SunDirection sunDirection,
            float sunRadianceMultiplier) {}
}
