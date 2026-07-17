package dev.prime.render;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.vulkan.ScreenshotDisplay;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;

/** Native-resolution resources owned exclusively by one screenshot accumulation session. */
final class ScreenshotRenderResources implements Destroyable {
    final VulkanImage output;
    final VulkanImage accumulation;
    final ScreenshotDisplay display;
    private boolean destroyed;

    private ScreenshotRenderResources(
            VulkanImage output, VulkanImage accumulation, ScreenshotDisplay display) {
        this.output = output;
        this.accumulation = accumulation;
        this.display = display;
    }

    static ScreenshotRenderResources create(VulkanContext context, int width, int height) {
        VulkanImage output = null;
        VulkanImage accumulation = null;
        ScreenshotDisplay display = null;
        try {
            output = context.createOutputImage(width, height);
            accumulation = context.createAccumulationImage(width, height);
            display = ScreenshotDisplay.create(context, accumulation, output);
            return new ScreenshotRenderResources(output, accumulation, display);
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
