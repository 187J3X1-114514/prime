package dev.prime.render;

import dev.prime.render.post.Denoiser;
import dev.prime.render.vulkan.ScreenshotDisplay;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;

/** Zero-filter native-resolution denoiser used by one frozen reference accumulation session. */
final class ReferenceAccumulator implements Denoiser {
    final VulkanImage output;
    final VulkanImage accumulation;
    final ScreenshotDisplay display;
    private boolean destroyed;

    private ReferenceAccumulator(
            VulkanImage output, VulkanImage accumulation, ScreenshotDisplay display) {
        this.output = output;
        this.accumulation = accumulation;
        this.display = display;
    }

    static ReferenceAccumulator create(VulkanContext context, int width, int height) {
        VulkanImage output = null;
        VulkanImage accumulation = null;
        ScreenshotDisplay display = null;
        try {
            output = context.createOutputImage(width, height);
            accumulation = context.createAccumulationImage(width, height);
            display = ScreenshotDisplay.create(context, accumulation, output);
            return new ReferenceAccumulator(output, accumulation, display);
        } catch (RuntimeException exception) {
            if (display != null) {
                display.destroy();
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

    boolean matches(int width, int height) {
        return this.output.width() == width
                && this.output.height() == height
                && this.accumulation.width() == width
                && this.accumulation.height() == height;
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
        this.destroyed = true;
        this.display.destroy();
        this.accumulation.destroy();
        this.output.destroy();
    }
}
