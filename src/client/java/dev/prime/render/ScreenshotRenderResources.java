package dev.prime.render;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.vulkan.BasicRawWavefrontFrame;
import dev.prime.render.vulkan.ScreenshotDisplay;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;

/** Owns one frozen native-resolution screenshot session and its wavefront output adapter. */
final class ScreenshotRenderResources implements Destroyable {
    final VulkanImage displayOutput;
    final VulkanImage runningMean;
    final VulkanImage stableRadiance;
    final BasicRawWavefrontFrame rawFrame;
    final ScreenshotDisplay display;
    private boolean destroyed;

    private ScreenshotRenderResources(
            VulkanImage displayOutput,
            VulkanImage runningMean,
            VulkanImage stableRadiance,
            BasicRawWavefrontFrame rawFrame,
            ScreenshotDisplay display) {
        this.displayOutput = displayOutput;
        this.runningMean = runningMean;
        this.stableRadiance = stableRadiance;
        this.rawFrame = rawFrame;
        this.display = display;
    }

    static ScreenshotRenderResources create(VulkanContext context, int width, int height) {
        VulkanImage displayOutput = null;
        VulkanImage runningMean = null;
        VulkanImage stableRadiance = null;
        BasicRawWavefrontFrame rawFrame = null;
        ScreenshotDisplay display = null;
        try {
            displayOutput = context.createOutputImage(width, height);
            runningMean = context.createAccumulationImage(width, height);
            stableRadiance = context.createAccumulationImage(width, height);
            rawFrame = BasicRawWavefrontFrame.createScreenshotScratch(
                    context, width, height);
            display = ScreenshotDisplay.create(context, runningMean, displayOutput);
            return new ScreenshotRenderResources(
                    displayOutput,
                    runningMean,
                    stableRadiance,
                    rawFrame,
                    display);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(display, exception);
            ResourceCleanup.destroy(rawFrame, exception);
            ResourceCleanup.destroy(stableRadiance, exception);
            ResourceCleanup.destroy(runningMean, exception);
            ResourceCleanup.destroy(displayOutput, exception);
            throw exception;
        }
    }

    boolean matches(int width, int height) {
        return this.displayOutput.width() == width
                && this.displayOutput.height() == height
                && this.runningMean.width() == width
                && this.runningMean.height() == height
                && this.stableRadiance.width() == width
                && this.stableRadiance.height() == height;
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.display, failure);
        failure = ResourceCleanup.destroy(this.rawFrame, failure);
        failure = ResourceCleanup.destroy(this.stableRadiance, failure);
        failure = ResourceCleanup.destroy(this.runningMean, failure);
        failure = ResourceCleanup.destroy(this.displayOutput, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }
}
