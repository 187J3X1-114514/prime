package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.RealtimeFramePlan;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionProcessor;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
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
    private final VulkanImageInitializationBatch imageInitialization =
            new VulkanImageInitializationBatch();

    public RealtimeFrameExecutor(VulkanContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public void execute(
            String debugLabel,
            RealtimeIntegratorPipeline pipeline,
            SunShadowPipeline sunShadow,
            AtmospherePipeline atmosphere,
            LabPbrTextureAtlas labPbrAtlas,
            TerrainScene.ResidentSceneView scene,
            RealtimeFramePlan plan,
            VulkanReconstructionProcessor processor,
            VulkanReconstructionProcessor.Frame processorFrame,
            VulkanImage output,
            VulkanImage stableRadiance,
            VulkanGpuTextureView atlasView,
            List<TraceBackend.SceneTexture> sceneTextures,
            long textureRevision,
            VulkanGpuTexture mainColor) {
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(processorFrame, "processorFrame");
        long atmosphereFrame = 0L;
        long pipelineFrame = 0L;
        LabPbrTextureAtlas.FrameToken labPbrFrame = null;
        MinecraftHostSubmission hostSubmission = new MinecraftHostSubmission();
        boolean initializationActive = false;
        try {
            Objects.requireNonNull(debugLabel, "debugLabel");
            Objects.requireNonNull(pipeline, "pipeline");
            Objects.requireNonNull(sunShadow, "sunShadow");
            Objects.requireNonNull(atmosphere, "atmosphere");
            Objects.requireNonNull(labPbrAtlas, "labPbrAtlas");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(stableRadiance, "stableRadiance");
            Objects.requireNonNull(atlasView, "atlasView");
            Objects.requireNonNull(sceneTextures, "sceneTextures");
            Objects.requireNonNull(mainColor, "mainColor");
            plan.requireSceneRevision(scene.revision());
            plan.requireTextureRevision(textureRevision);
            validateExtents(plan, processor, output, stableRadiance, mainColor);
            this.imageInitialization.begin();
            initializationActive = true;

            var encoder = this.context.commandEncoder();
            VkCommandBuffer commandBuffer =
                    encoder.allocateAndBeginTransientCommandBuffer();
            pipelineFrame = pipeline.prepareFrame(
                    commandBuffer,
                    this.imageInitialization,
                    plan,
                    scene,
                    textureRevision);
            this.context.device().instance().debug().beginDebugGroup(
                    commandBuffer, () -> debugLabel);
            VulkanImageTransitions.prepareOutputForComposite(
                    commandBuffer, this.imageInitialization, output);
            VulkanImageTransitions.prepareAccumulationForTrace(
                    commandBuffer, this.imageInitialization, stableRadiance);
            processor.prepareForRayTrace(
                    commandBuffer, this.imageInitialization);
            VulkanImageTransitions.prepareAtlasForTrace(
                    commandBuffer, atlasView.texture());
            VulkanImageTransitions.prepareSceneTexturesForTrace(
                    commandBuffer, sceneTextures);
            labPbrFrame = labPbrAtlas.prepare(commandBuffer);
            // Atmosphere preparation traces the sun cache through the shared RT descriptor set.
            // Every image named by that set must have its declared layout before this call.
            atmosphereFrame = atmosphere.prepare(
                    commandBuffer,
                    sunShadow,
                    plan.integrator(),
                    scene,
                    false);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                pipeline.trace(commandBuffer, plan.integrator(), scene);
                processor.record(
                        commandBuffer,
                        processorFrame,
                        plan.reconstruction(),
                        this.imageInitialization);
                VulkanImageTransitions.finishAtlasRead(
                        commandBuffer, atlasView.texture());
                VulkanImageTransitions.finishSceneTextureReads(
                        commandBuffer, sceneTextures);
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
            hostSubmission.acceptedByMinecraftHostSubmission();
            // A normal return transfers command/resource ownership and advances Prime histories.
            this.imageInitialization.submitted();
            initializationActive = false;
            long submittedPipelineFrame = pipelineFrame;
            RuntimeException commitFailure = ResourceCleanup.run(
                    () -> pipeline.submitted(submittedPipelineFrame), null);
            long submittedAtmosphereFrame = atmosphereFrame;
            commitFailure = ResourceCleanup.run(
                    () -> atmosphere.submitted(submittedAtmosphereFrame),
                    commitFailure);
            commitFailure = ResourceCleanup.run(
                    () -> processor.submitted(processorFrame), commitFailure);
            LabPbrTextureAtlas.FrameToken submittedLabPbrFrame =
                    labPbrFrame;
            commitFailure = ResourceCleanup.run(
                    () -> labPbrAtlas.submitted(submittedLabPbrFrame),
                    commitFailure);
            ResourceCleanup.throwIfFailed(commitFailure);
        } catch (RuntimeException exception) {
            if (!hostSubmission.wasAcceptedByMinecraftHostSubmission()) {
                RuntimeException failure = exception;
                if (initializationActive) {
                    failure = ResourceCleanup.run(
                            this.imageInitialization::abandon, failure);
                }
                if (pipelineFrame != 0L) {
                    long abandonedPipelineFrame = pipelineFrame;
                    failure = ResourceCleanup.run(
                            () -> pipeline.abandon(abandonedPipelineFrame),
                            failure);
                }
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
            VulkanReconstructionProcessor processor,
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
