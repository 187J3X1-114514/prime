package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.OfflineFramePlan;
import dev.prime.render.terrain.TerrainScene;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

/** Device side effects for one already-planned native offline sample. */
public final class OfflineFrameExecutor {
    private final VulkanContext context;
    private final VulkanImageInitializationBatch imageInitialization =
            new VulkanImageInitializationBatch();

    public OfflineFrameExecutor(VulkanContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public void execute(
            OfflineRayTracingPipeline pipeline,
            SunShadowPipeline sunShadow,
            AtmospherePipeline atmosphere,
            LabPbrTextureAtlas labPbrAtlas,
            TerrainScene.ResidentSceneView scene,
            OfflineFramePlan plan,
            VulkanImage displayOutput,
            VulkanImage runningMean,
            VulkanImage meteringAlbedo,
            VulkanImage meteringNormalRoughness,
            DisplayTransformPass display,
            VulkanGpuTextureView atlasView,
            List<TraceBackend.SceneTexture> sceneTextures,
            long textureRevision,
            VulkanGpuTexture mainColor) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(sunShadow, "sunShadow");
        Objects.requireNonNull(atmosphere, "atmosphere");
        Objects.requireNonNull(labPbrAtlas, "labPbrAtlas");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(displayOutput, "displayOutput");
        Objects.requireNonNull(runningMean, "runningMean");
        Objects.requireNonNull(meteringAlbedo, "meteringAlbedo");
        Objects.requireNonNull(meteringNormalRoughness, "meteringNormalRoughness");
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(atlasView, "atlasView");
        Objects.requireNonNull(sceneTextures, "sceneTextures");
        Objects.requireNonNull(mainColor, "mainColor");
        plan.requireSceneRevision(scene.revision());
        plan.requireTextureRevision(textureRevision);
        validateExtents(
                plan,
                displayOutput,
                runningMean,
                mainColor);

        var encoder = this.context.commandEncoder();
        VkCommandBuffer commandBuffer =
                encoder.allocateAndBeginTransientCommandBuffer();
        long atmosphereFrame = 0L;
        long pipelineFrame = 0L;
        LabPbrTextureAtlas.FrameToken labPbrFrame = null;
        boolean submissionAccepted = false;
        boolean initializationActive = false;
        try {
            this.imageInitialization.begin();
            initializationActive = true;
            pipelineFrame = pipeline.prepareFrame(
                    commandBuffer, this.imageInitialization);
            this.context.device().instance().debug().beginDebugGroup(
                    commandBuffer,
                    () -> "Prime offline path accumulation");
            VulkanImageTransitions.prepareOutputForComposite(
                    commandBuffer, this.imageInitialization, displayOutput);
            VulkanImageTransitions.prepareAccumulationForTrace(
                    commandBuffer, this.imageInitialization, runningMean);
            VulkanImageTransitions.prepareAccumulationForTrace(
                    commandBuffer, this.imageInitialization, meteringAlbedo);
            VulkanImageTransitions.prepareAccumulationForTrace(
                    commandBuffer, this.imageInitialization, meteringNormalRoughness);
            VulkanImageTransitions.prepareAtlasForTrace(
                    commandBuffer, atlasView.texture());
            VulkanImageTransitions.prepareSceneTexturesForTrace(
                    commandBuffer, sceneTextures);
            labPbrFrame = labPbrAtlas.prepare(commandBuffer);
            // The sun-cache raygen borrows the shared scene descriptor set prepared above.
            atmosphereFrame = atmosphere.prepare(
                    commandBuffer,
                    sunShadow,
                    plan.integrator(),
                    scene,
                    true);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                pipeline.trace(
                        commandBuffer, plan.integrator(), scene);
                VulkanImageTransitions.prepareOfflineDisplay(
                        commandBuffer, runningMean);
                display.record(
                        commandBuffer,
                        false,
                        0.0F,
                        plan.input().sampleCount() == 0L,
                        true,
                        plan.input().display());
                VulkanImageTransitions.finishAtlasRead(
                        commandBuffer, atlasView.texture());
                VulkanImageTransitions.finishSceneTextureReads(
                        commandBuffer, sceneTextures);
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
                    "end Prime offline accumulation command buffer");
            encoder.execute(commandBuffer);
            // execute() transfers this command buffer to Minecraft's open Submission only after
            // its validation succeeds; failed calls still own and must abandon recorded state.
            submissionAccepted = true;
            this.imageInitialization.submitted();
            initializationActive = false;
            long submittedPipelineFrame = pipelineFrame;
            RuntimeException commitFailure = ResourceCleanup.run(
                    () -> pipeline.submitted(submittedPipelineFrame), null);
            long submittedAtmosphereFrame = atmosphereFrame;
            commitFailure = ResourceCleanup.run(
                    () -> atmosphere.submitted(submittedAtmosphereFrame),
                    commitFailure);
            LabPbrTextureAtlas.FrameToken submittedLabPbrFrame =
                    labPbrFrame;
            commitFailure = ResourceCleanup.run(
                    () -> labPbrAtlas.submitted(submittedLabPbrFrame),
                    commitFailure);
            ResourceCleanup.throwIfFailed(commitFailure);
        } catch (RuntimeException exception) {
            if (!submissionAccepted) {
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
            OfflineFramePlan plan,
            VulkanImage displayOutput,
            VulkanImage runningMean,
            VulkanGpuTexture mainColor) {
        int width = plan.input().width();
        int height = plan.input().height();
        if (displayOutput.width() != width
                || displayOutput.height() != height
                || runningMean.width() != width
                || runningMean.height() != height
                || mainColor.getWidth(0) != width
                || mainColor.getHeight(0) != height) {
            throw new IllegalArgumentException(
                    "Offline device resources do not match the semantic frame extent");
        }
    }
}
