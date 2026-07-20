package dev.prime.render.post;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.vulkan.PathTraceTargets;
import dev.prime.render.vulkan.VulkanImage;
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

    PathTraceTargets targets();

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
