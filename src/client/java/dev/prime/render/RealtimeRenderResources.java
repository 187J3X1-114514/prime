package dev.prime.render;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.RealtimePostProcessor;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.NrdFsrPostProcessor;
import dev.prime.render.vulkan.NoisyPostProcessor;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.dlss.DlssRrNative;
import dev.prime.render.vulkan.dlss.DlssRrPostProcessor;

/**
 * Owns the complete size-dependent resource graph for Prime's interactive render path.
 *
 * <p>This boundary is deliberately independent from the screenshot accumulator. NRD and FSR
 * histories are meaningful only to the interactive path and must never become implicit inputs to
 * an unbiased accumulated sample. VulkanRenderer remains the frame orchestrator while each render
 * path owns and retires its own resources as one idempotent unit.
 */
final class RealtimeRenderResources implements Destroyable {
    final VulkanImage output;
    final VulkanImage accumulation;
    final PostProcessingMode mode;
    final ReconstructionQualityMode qualityMode;
    final RealtimePostProcessor processor;
    private boolean destroyed;

    private RealtimeRenderResources(
            VulkanImage output,
            VulkanImage accumulation,
            RealtimePostProcessor processor,
            PostProcessingMode mode,
            ReconstructionQualityMode qualityMode) {
        this.output = output;
        this.accumulation = accumulation;
        this.processor = processor;
        this.mode = mode;
        this.qualityMode = qualityMode;
    }

    static RealtimeRenderResources create(
            VulkanContext context,
            AtmospherePipeline atmosphere,
            int displayWidth,
            int displayHeight,
            int renderWidth,
            int renderHeight,
            PostProcessingMode mode,
            ReconstructionQualityMode qualityMode,
            DlssRrNative.Context ngxContext) {
        VulkanImage output = null;
        VulkanImage accumulation = null;
        RealtimePostProcessor processor = null;
        try {
            output = context.createOutputImage(displayWidth, displayHeight);
            accumulation = context.createAccumulationImage(renderWidth, renderHeight);
            processor = switch (mode) {
                case NRD_FSR -> NrdFsrPostProcessor.create(
                        context,
                        atmosphere,
                        accumulation,
                        output,
                        renderWidth,
                        renderHeight,
                        displayWidth,
                        displayHeight,
                        qualityMode);
                case DLSS_RR -> {
                    if (ngxContext == null) {
                        throw new IllegalStateException(
                                "DLSS RR was selected without an initialized NGX context");
                    }
                    yield DlssRrPostProcessor.create(
                            context,
                            ngxContext,
                            atmosphere,
                            accumulation,
                            output,
                            renderWidth,
                            renderHeight,
                            displayWidth,
                            displayHeight,
                            qualityMode);
                }
                case DISABLED -> NoisyPostProcessor.create(
                        context,
                        atmosphere,
                        accumulation,
                        output,
                        renderWidth,
                        renderHeight,
                        qualityMode);
            };
            return new RealtimeRenderResources(
                    output,
                    accumulation,
                    processor,
                    mode,
                    qualityMode);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(processor, exception);
            ResourceCleanup.destroy(accumulation, exception);
            ResourceCleanup.destroy(output, exception);
            throw exception;
        }
    }

    boolean matches(
            int displayWidth,
            int displayHeight,
            int renderWidth,
            int renderHeight,
            PostProcessingMode requestedMode,
            ReconstructionQualityMode requestedQualityMode) {
        return this.output.width() == displayWidth
                && this.output.height() == displayHeight
                && this.accumulation.width() == renderWidth
                && this.accumulation.height() == renderHeight
                && this.processor.renderWidth() == renderWidth
                && this.processor.renderHeight() == renderHeight
                && this.mode == requestedMode
                && this.qualityMode == requestedQualityMode;
    }

    void requestReset() {
        this.processor.requestReset();
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.processor, failure);
        failure = ResourceCleanup.destroy(this.accumulation, failure);
        failure = ResourceCleanup.destroy(this.output, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }
}
