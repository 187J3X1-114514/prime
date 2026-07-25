package dev.prime.render;

import dev.prime.render.post.Denoiser;
import dev.prime.render.vulkan.NoisyTargets;
import dev.prime.render.vulkan.ScreenshotDisplay;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;

/** Owns one frozen native-resolution wavefront accumulation session. */
final class ReferenceAccumulator implements Denoiser {
    final VulkanImage output;
    final VulkanImage accumulation;
    final VulkanImage wavefrontAccumulation;
    final NoisyTargets wavefrontTargets;
    final ScreenshotDisplay display;
    private boolean destroyed;

    private ReferenceAccumulator(
            VulkanImage output,
            VulkanImage accumulation,
            VulkanImage wavefrontAccumulation,
            NoisyTargets wavefrontTargets,
            ScreenshotDisplay display) {
        this.output = output;
        this.accumulation = accumulation;
        this.wavefrontAccumulation = wavefrontAccumulation;
        this.wavefrontTargets = wavefrontTargets;
        this.display = display;
    }

    static ReferenceAccumulator create(VulkanContext context, int width, int height) {
        VulkanImage output = null;
        VulkanImage accumulation = null;
        VulkanImage wavefrontAccumulation = null;
        NoisyTargets wavefrontTargets = null;
        ScreenshotDisplay display = null;
        try {
            output = context.createOutputImage(width, height);
            accumulation = context.createAccumulationImage(width, height);
            wavefrontAccumulation = context.createAccumulationImage(width, height);
            wavefrontTargets = NoisyTargets.createScratch(context, width, height);
            display = ScreenshotDisplay.create(context, accumulation, output);
            return new ReferenceAccumulator(
                    output,
                    accumulation,
                    wavefrontAccumulation,
                    wavefrontTargets,
                    display);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(display, exception);
            ResourceCleanup.destroy(wavefrontTargets, exception);
            ResourceCleanup.destroy(wavefrontAccumulation, exception);
            ResourceCleanup.destroy(accumulation, exception);
            ResourceCleanup.destroy(output, exception);
            throw exception;
        }
    }

    boolean matches(int width, int height) {
        return this.output.width() == width
                && this.output.height() == height
                && this.accumulation.width() == width
                && this.accumulation.height() == height
                && this.wavefrontAccumulation.width() == width
                && this.wavefrontAccumulation.height() == height;
    }

    @Override public Kind kind() { return Kind.REFERENCE_ACCUMULATION; }
    @Override public int renderWidth() { return this.accumulation.width(); }
    @Override public int renderHeight() { return this.accumulation.height(); }
    @Override public int displayWidth() { return this.output.width(); }
    @Override public int displayHeight() { return this.output.height(); }
    @Override public VulkanImage linearHdrOutput() { return this.accumulation; }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.display, failure);
        failure = ResourceCleanup.destroy(this.wavefrontTargets, failure);
        failure = ResourceCleanup.destroy(this.wavefrontAccumulation, failure);
        failure = ResourceCleanup.destroy(this.accumulation, failure);
        failure = ResourceCleanup.destroy(this.output, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }
}
