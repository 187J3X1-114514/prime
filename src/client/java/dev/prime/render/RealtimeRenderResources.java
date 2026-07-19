package dev.prime.render;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.fsr.Fsr3Upscaler;
import dev.prime.render.vulkan.nrd.NrdDenoiser;
import org.lwjgl.vulkan.VK12;

/**
 * Owns the complete size-dependent resource graph for Prime's interactive render path.
 *
 * <p>This boundary is deliberately independent from the future offline accumulator. NRD and FSR
 * histories are meaningful only to the interactive path and must never become implicit inputs to
 * an unbiased offline sample. VulkanRenderer remains the frame orchestrator while each render
 * path owns and retires its own resources as one idempotent unit.
 */
final class RealtimeRenderResources implements Destroyable {
    final VulkanImage output;
    final VulkanImage accumulation;
    final VulkanImage sceneColor;
    final FsrQualityMode qualityMode;
    NrdDenoiser denoiser;
    Fsr3Upscaler upscaler;
    private boolean destroyed;

    private RealtimeRenderResources(
            VulkanImage output,
            VulkanImage accumulation,
            VulkanImage sceneColor,
            NrdDenoiser denoiser,
            Fsr3Upscaler upscaler,
            FsrQualityMode qualityMode) {
        this.output = output;
        this.accumulation = accumulation;
        this.sceneColor = sceneColor;
        this.denoiser = denoiser;
        this.upscaler = upscaler;
        this.qualityMode = qualityMode;
    }

    static RealtimeRenderResources create(
            VulkanContext context,
            AtmospherePipeline atmosphere,
            int displayWidth,
            int displayHeight,
            int renderWidth,
            int renderHeight,
            FsrQualityMode qualityMode) {
        VulkanImage output = null;
        VulkanImage accumulation = null;
        VulkanImage sceneColor = null;
        NrdDenoiser denoiser = null;
        Fsr3Upscaler upscaler = null;
        try {
            output = context.createOutputImage(displayWidth, displayHeight);
            accumulation = context.createAccumulationImage(renderWidth, renderHeight);
            sceneColor = context.createImage2D(
                    renderWidth,
                    renderHeight,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime realtime linear HDR scene color");
            denoiser = NrdDenoiser.create(
                    context,
                    renderWidth,
                    renderHeight,
                    sceneColor,
                    accumulation,
                    atmosphere);
            upscaler = Fsr3Upscaler.create(
                    context,
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    qualityMode,
                    sceneColor,
                    denoiser.motion(),
                    denoiser.fsrDepth(),
                    denoiser.fsrReactiveMask(),
                    denoiser.fsrTransparencyCompositionMask(),
                    output);
            return new RealtimeRenderResources(
                    output,
                    accumulation,
                    sceneColor,
                    denoiser,
                    upscaler,
                    qualityMode);
        } catch (RuntimeException exception) {
            if (upscaler != null) {
                upscaler.destroy();
            }
            if (denoiser != null) {
                denoiser.destroy();
            }
            if (sceneColor != null) {
                sceneColor.destroy();
            }
            if (accumulation != null) {
                accumulation.destroy();
            }
            if (output != null) {
                output.destroy();
            }
            throw exception;
        }
    }

    boolean matches(
            int displayWidth,
            int displayHeight,
            int renderWidth,
            int renderHeight,
            FsrQualityMode requestedQualityMode) {
        return this.output.width() == displayWidth
                && this.output.height() == displayHeight
                && this.accumulation.width() == renderWidth
                && this.accumulation.height() == renderHeight
                && this.sceneColor.width() == renderWidth
                && this.sceneColor.height() == renderHeight
                && this.qualityMode == requestedQualityMode;
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        this.upscaler.destroy();
        this.denoiser.destroy();
        this.sceneColor.destroy();
        this.accumulation.destroy();
        this.output.destroy();
    }
}
