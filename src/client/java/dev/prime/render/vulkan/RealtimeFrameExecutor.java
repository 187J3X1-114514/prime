package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.RealtimeFramePlan;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.post.RealtimePostProcessor;
import dev.prime.render.terrain.TerrainScene;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

/**
 * Sole device executor for one planned interactive frame.
 *
 * <p>All frame-scalar semantics are already fixed by {@link RealtimeFramePlan}. This class only
 * binds captured asset/scene residency, records Vulkan work, submits it and commits backend-owned
 * GPU histories.
 */
public final class RealtimeFrameExecutor {
    private final VulkanContext context;

    public RealtimeFrameExecutor(VulkanContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public void execute(
            String debugLabel,
            RayTracingPipeline pipeline,
            AtmospherePipeline atmosphere,
            LabPbrTextureAtlas labPbrAtlas,
            TerrainScene.ResidentSceneView scene,
            RealtimeFramePlan plan,
            RealtimePostProcessor processor,
            RealtimePostProcessor.Frame processorFrame,
            VulkanImage output,
            VulkanImage stableRadiance,
            VulkanGpuTextureView atlasView,
            long textureRevision,
            VulkanGpuTexture mainColor) {
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(processorFrame, "processorFrame");
        long atmosphereFrame = 0L;
        LabPbrTextureAtlas.FrameToken labPbrFrame = null;
        boolean submissionAccepted = false;
        try {
            Objects.requireNonNull(debugLabel, "debugLabel");
            Objects.requireNonNull(pipeline, "pipeline");
            Objects.requireNonNull(atmosphere, "atmosphere");
            Objects.requireNonNull(labPbrAtlas, "labPbrAtlas");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(stableRadiance, "stableRadiance");
            Objects.requireNonNull(atlasView, "atlasView");
            Objects.requireNonNull(mainColor, "mainColor");
            plan.requireSceneRevision(scene.revision());
            plan.requireTextureRevision(textureRevision);
            validateExtents(plan, processor, output, stableRadiance, mainColor);

            var encoder = this.context.commandEncoder();
            VkCommandBuffer commandBuffer =
                    encoder.allocateAndBeginTransientCommandBuffer();
            this.context.device().instance().debug().beginDebugGroup(
                    commandBuffer, () -> debugLabel);
            atmosphereFrame = atmosphere.prepare(
                    commandBuffer,
                    plan.integrator().camera(),
                    plan.integrator().sunDirection());
            VulkanImageTransitions.prepareOutputForComposite(
                    commandBuffer, output);
            VulkanImageTransitions.prepareAccumulationForTrace(
                    commandBuffer, stableRadiance);
            processor.prepareForRayTrace(commandBuffer);
            VulkanImageTransitions.prepareAtlasForTrace(
                    commandBuffer, atlasView.texture());
            labPbrFrame = labPbrAtlas.prepare(commandBuffer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                pipeline.trace(commandBuffer, plan.integrator(), scene);
                processor.record(
                        commandBuffer,
                        processorFrame,
                        plan.reconstruction());
                VulkanImageTransitions.finishAtlasRead(
                        commandBuffer, atlasView.texture());
                VulkanImageTransitions.prepareImagesForCopy(
                        commandBuffer, output, mainColor);
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
                        processor.displayWidth(),
                        processor.displayHeight(),
                        1);
                VK12.vkCmdCopyImage(
                        commandBuffer,
                        output.image(),
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        mainColor.vkImage(),
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        copy);
                VulkanImageTransitions.finishImageCopy(
                        commandBuffer, output, mainColor);
            }
            this.context.device().instance().debug().endDebugGroup(
                    commandBuffer);
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer),
                    "end Prime realtime command buffer");
            encoder.execute(commandBuffer);
            // Minecraft's execute() first validates the encoder, then appends this command buffer
            // to its open Submission. Only a normal return transfers recorded-resource ownership.
            submissionAccepted = true;
            long submittedAtmosphereFrame = atmosphereFrame;
            RuntimeException commitFailure = ResourceCleanup.run(
                    () -> atmosphere.submitted(submittedAtmosphereFrame), null);
            commitFailure = ResourceCleanup.run(
                    () -> processor.submitted(processorFrame), commitFailure);
            LabPbrTextureAtlas.FrameToken submittedLabPbrFrame =
                    labPbrFrame;
            commitFailure = ResourceCleanup.run(
                    () -> labPbrAtlas.submitted(submittedLabPbrFrame),
                    commitFailure);
            ResourceCleanup.throwIfFailed(commitFailure);
        } catch (RuntimeException exception) {
            if (!submissionAccepted) {
                RuntimeException failure = exception;
                if (atmosphereFrame != 0L) {
                    long abandonedAtmosphereFrame = atmosphereFrame;
                    failure = ResourceCleanup.run(
                            () -> atmosphere.abandon(abandonedAtmosphereFrame),
                            failure);
                }
                if (labPbrFrame != null) {
                    LabPbrTextureAtlas.FrameToken abandonedLabPbrFrame =
                            labPbrFrame;
                    failure = ResourceCleanup.run(
                            () -> labPbrAtlas.abandon(abandonedLabPbrFrame),
                            failure);
                }
                failure = ResourceCleanup.run(
                        () -> processor.abandon(processorFrame),
                        failure);
                throw failure;
            }
            throw exception;
        }
    }

    private static void validateExtents(
            RealtimeFramePlan plan,
            RealtimePostProcessor processor,
            VulkanImage output,
            VulkanImage stableRadiance,
            VulkanGpuTexture mainColor) {
        if (plan.integrator().width() != processor.renderWidth()
                || plan.integrator().height() != processor.renderHeight()
                || stableRadiance.width() != processor.renderWidth()
                || stableRadiance.height() != processor.renderHeight()
                || output.width() != processor.displayWidth()
                || output.height() != processor.displayHeight()
                || mainColor.getWidth(0) != processor.displayWidth()
                || mainColor.getHeight(0) != processor.displayHeight()) {
            throw new IllegalArgumentException(
                    "Realtime device resources do not match the semantic frame extents");
        }
    }
}
