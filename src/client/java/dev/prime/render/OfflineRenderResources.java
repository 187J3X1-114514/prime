package dev.prime.render;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.vulkan.DisplayTransformPass;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import org.lwjgl.vulkan.VK12;

/** Owns one frozen native-resolution offline session and its display adapter. */
final class OfflineRenderResources implements Destroyable {
    final VulkanImage displayOutput;
    final VulkanImage runningMean;
    final VulkanImage meteringAlbedo;
    final VulkanImage meteringNormalRoughness;
    final DisplayTransformPass display;
    private boolean destroyed;

    private OfflineRenderResources(
            VulkanImage displayOutput,
            VulkanImage runningMean,
            VulkanImage meteringAlbedo,
            VulkanImage meteringNormalRoughness,
            DisplayTransformPass display) {
        this.displayOutput = displayOutput;
        this.runningMean = runningMean;
        this.meteringAlbedo = meteringAlbedo;
        this.meteringNormalRoughness = meteringNormalRoughness;
        this.display = display;
    }

    static OfflineRenderResources create(VulkanContext context, int width, int height) {
        VulkanImage displayOutput = null;
        VulkanImage runningMean = null;
        VulkanImage meteringAlbedo = null;
        VulkanImage meteringNormalRoughness = null;
        DisplayTransformPass display = null;
        try {
            displayOutput = context.createOutputImage(width, height);
            runningMean = context.createAccumulationImage(width, height);
            meteringAlbedo = context.createImage2D(
                    1,
                    1,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                    "Prime offline metering placeholder albedo");
            meteringNormalRoughness = context.createImage2D(
                    1,
                    1,
                    VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                    "Prime offline metering placeholder normal");
            display = DisplayTransformPass.createOffline(
                    context,
                    runningMean,
                    meteringAlbedo,
                    meteringNormalRoughness,
                    displayOutput);
            return new OfflineRenderResources(
                    displayOutput,
                    runningMean,
                    meteringAlbedo,
                    meteringNormalRoughness,
                    display);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(display, exception);
            ResourceCleanup.destroy(meteringNormalRoughness, exception);
            ResourceCleanup.destroy(meteringAlbedo, exception);
            ResourceCleanup.destroy(runningMean, exception);
            ResourceCleanup.destroy(displayOutput, exception);
            throw exception;
        }
    }

    boolean matches(int width, int height) {
        return this.displayOutput.width() == width
                && this.displayOutput.height() == height
                && this.runningMean.width() == width
                && this.runningMean.height() == height;
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.display, failure);
        failure = ResourceCleanup.destroy(this.meteringNormalRoughness, failure);
        failure = ResourceCleanup.destroy(this.meteringAlbedo, failure);
        failure = ResourceCleanup.destroy(this.runningMean, failure);
        failure = ResourceCleanup.destroy(this.displayOutput, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }
}
