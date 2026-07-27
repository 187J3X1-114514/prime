package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.ScreenshotFramePlan;
import dev.prime.render.terrain.TerrainScene;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

/** Device side effects for one already-planned native screenshot sample. */
public final class ScreenshotFrameExecutor {
    private final VulkanContext context;

    public ScreenshotFrameExecutor(VulkanContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public void execute(
            RayTracingPipeline pipeline,
            AtmospherePipeline atmosphere,
            LabPbrTextureAtlas labPbrAtlas,
            TerrainScene.ResidentSceneView scene,
            ScreenshotFramePlan plan,
            VulkanImage displayOutput,
            VulkanImage stableRadiance,
            VulkanImage runningMean,
            BasicRawWavefrontFrame rawFrame,
            ScreenshotDisplay display,
            VulkanGpuTextureView atlasView,
            long textureRevision,
            VulkanGpuTexture mainColor) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(atmosphere, "atmosphere");
        Objects.requireNonNull(labPbrAtlas, "labPbrAtlas");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(displayOutput, "displayOutput");
        Objects.requireNonNull(stableRadiance, "stableRadiance");
        Objects.requireNonNull(runningMean, "runningMean");
        Objects.requireNonNull(rawFrame, "rawFrame");
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(atlasView, "atlasView");
        Objects.requireNonNull(mainColor, "mainColor");
        plan.requireSceneRevision(scene.revision());
        plan.requireTextureRevision(textureRevision);
        validateExtents(
                plan,
                displayOutput,
                stableRadiance,
                runningMean,
                mainColor);

        var encoder = this.context.commandEncoder();
        VkCommandBuffer commandBuffer =
                encoder.allocateAndBeginTransientCommandBuffer();
        long atmosphereFrame = 0L;
        LabPbrTextureAtlas.FrameToken labPbrFrame = null;
        boolean submissionAccepted = false;
        try {
            this.context.device().instance().debug().beginDebugGroup(
                    commandBuffer,
                    () -> "Prime unbiased screenshot accumulation");
            atmosphereFrame = atmosphere.prepare(
                    commandBuffer,
                    plan.integrator().camera(),
                    plan.integrator().sunDirection());
            VulkanImageTransitions.prepareOutputForComposite(
                    commandBuffer, displayOutput);
            VulkanImageTransitions.prepareAccumulationForTrace(
                    commandBuffer, stableRadiance);
            VulkanImageTransitions.prepareAccumulationForTrace(
                    commandBuffer, runningMean);
            rawFrame.prepareForRayTrace(commandBuffer);
            VulkanImageTransitions.prepareAtlasForTrace(
                    commandBuffer, atlasView.texture());
            labPbrFrame = labPbrAtlas.prepare(commandBuffer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                pipeline.traceScreenshot(
                        commandBuffer, plan.integrator(), scene);
                VulkanImageTransitions.prepareScreenshotDisplay(
                        commandBuffer, runningMean);
                display.record(
                        commandBuffer,
                        plan.input().width(),
                        plan.input().height(),
                        plan.input().displayOverexposure());
                VulkanImageTransitions.finishAtlasRead(
                        commandBuffer, atlasView.texture());
                VulkanImageTransitions.prepareImagesForCopy(
                        commandBuffer, displayOutput, mainColor);
                VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
                copy.get(0).srcSubresource()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1);
                copy.get(0).dstSubresource()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1);
                copy.get(0).extent().set(
                        plan.input().width(),
                        plan.input().height(),
                        1);
                VK12.vkCmdCopyImage(
                        commandBuffer,
                        displayOutput.image(),
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        mainColor.vkImage(),
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        copy);
                VulkanImageTransitions.finishImageCopy(
                        commandBuffer, displayOutput, mainColor);
            }
            this.context.device().instance().debug().endDebugGroup(
                    commandBuffer);
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer),
                    "end Prime screenshot accumulation command buffer");
            encoder.execute(commandBuffer);
            // execute() transfers this command buffer to Minecraft's open Submission only after
            // its validation succeeds; failed calls still own and must abandon recorded state.
            submissionAccepted = true;
            long submittedAtmosphereFrame = atmosphereFrame;
            RuntimeException commitFailure = ResourceCleanup.run(
                    () -> atmosphere.submitted(submittedAtmosphereFrame), null);
            LabPbrTextureAtlas.FrameToken submittedLabPbrFrame =
                    labPbrFrame;
            commitFailure = ResourceCleanup.run(
                    () -> labPbrAtlas.submitted(submittedLabPbrFrame),
                    commitFailure);
            ResourceCleanup.throwIfFailed(commitFailure);
        } catch (RuntimeException exception) {
            if (!submissionAccepted) {
                RuntimeException failure = exception;
                long abandonedAtmosphereFrame = atmosphereFrame;
                failure = ResourceCleanup.run(
                        () -> atmosphere.abandon(abandonedAtmosphereFrame),
                        failure);
                LabPbrTextureAtlas.FrameToken abandonedLabPbrFrame =
                        labPbrFrame;
                throw ResourceCleanup.run(
                        () -> labPbrAtlas.abandon(abandonedLabPbrFrame),
                        failure);
            }
            throw exception;
        }
    }

    private static void validateExtents(
            ScreenshotFramePlan plan,
            VulkanImage displayOutput,
            VulkanImage stableRadiance,
            VulkanImage runningMean,
            VulkanGpuTexture mainColor) {
        int width = plan.input().width();
        int height = plan.input().height();
        if (displayOutput.width() != width
                || displayOutput.height() != height
                || stableRadiance.width() != width
                || stableRadiance.height() != height
                || runningMean.width() != width
                || runningMean.height() != height
                || mainColor.getWidth(0) != width
                || mainColor.getHeight(0) != height) {
            throw new IllegalArgumentException(
                    "Screenshot device resources do not match the semantic frame extent");
        }
    }
}
