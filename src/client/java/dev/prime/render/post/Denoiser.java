package dev.prime.render.post;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.vulkan.VulkanImage;

/**
 * Common ownership boundary for every noisy-sample consumer.
 *
 * <p>Realtime reconstruction and native-resolution reference accumulation differ in scheduling,
 * but both consume the integrator's linear HDR estimate and own the image that represents their
 * resolved result. This interface keeps that distinction out of the physical integrator.
 */
public interface Denoiser extends Destroyable {
    enum Kind {
        NRD_FSR,
        DLSS_RR,
        NOISY,
        REFERENCE_ACCUMULATION
    }

    Kind kind();

    int renderWidth();

    int renderHeight();

    int displayWidth();

    int displayHeight();

    /** Linear Rec.2020 HDR output before the shared display transform. */
    VulkanImage linearHdrOutput();
}
