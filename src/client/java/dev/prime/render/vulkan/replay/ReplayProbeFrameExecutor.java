package dev.prime.render.vulkan.replay;

import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.replay.RayTraceReplayInput;
import dev.prime.render.replay.RenderBinaryFingerprint;
import dev.prime.render.replay.RenderPlatformFingerprint;
import dev.prime.render.replay.RenderReplayCapture;
import dev.prime.render.terrain.TerrainScene;
import dev.prime.render.vulkan.RayTracingPipeline;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImageInitializationBatch;
import dev.prime.render.vulkan.VulkanImageTransitions;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Vulkan execution boundary for one preplanned low-resolution replay-probe frame. */
public final class ReplayProbeFrameExecutor {
    private final VulkanContext context;
    private final VulkanImageInitializationBatch imageInitialization =
            new VulkanImageInitializationBatch();

    public ReplayProbeFrameExecutor(VulkanContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public CompletableFuture<RenderReplayCapture> execute(
            String debugLabel,
            RayTracingPipeline pipeline,
            NrdReplayProbe probe,
            NrdReplayProbe.PlannedFrame nrdFrame,
            TerrainScene.ResidentSceneView scene,
            IntegratorFrameInput integrator,
            RayTraceReplayInput replayInput,
            VulkanGpuTextureView atlasView,
            List<RayTracingPipeline.SceneTexture> sceneTextures,
            float sunRadianceMultiplier,
            float displayOverexposure,
            RenderPlatformFingerprint platform,
            RenderBinaryFingerprint binary) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(nrdFrame, "nrdFrame");
        NrdReplayProbe.RecordedFrame recorded = null;
        long pipelineFrame = 0L;
        boolean recordingAttempted = false;
        boolean submissionAccepted = false;
        boolean initializationActive = false;
        try {
            Objects.requireNonNull(debugLabel, "debugLabel");
            Objects.requireNonNull(pipeline, "pipeline");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(integrator, "integrator");
            Objects.requireNonNull(replayInput, "replayInput");
            Objects.requireNonNull(atlasView, "atlasView");
            Objects.requireNonNull(sceneTextures, "sceneTextures");
            Objects.requireNonNull(platform, "platform");
            Objects.requireNonNull(binary, "binary");
            replayInput.requireMatch(integrator, scene);
            this.imageInitialization.begin();
            initializationActive = true;

            var encoder = this.context.commandEncoder();
            VkCommandBuffer commandBuffer =
                    encoder.allocateAndBeginTransientCommandBuffer();
            pipelineFrame = pipeline.prepareFrame(
                    commandBuffer, this.imageInitialization);
            this.context.device().instance().debug().beginDebugGroup(
                    commandBuffer, () -> debugLabel);
            probe.prepareForTrace(
                    commandBuffer, this.imageInitialization);
            VulkanImageTransitions.prepareAtlasForTrace(
                    commandBuffer, atlasView.texture());
            VulkanImageTransitions.prepareSceneTexturesForTrace(
                    commandBuffer, sceneTextures);
            pipeline.trace(commandBuffer, integrator, scene);
            recordingAttempted = true;
            recorded = probe.recordAfterTrace(
                    commandBuffer,
                    nrdFrame,
                    sunRadianceMultiplier,
                    displayOverexposure);
            VulkanImageTransitions.finishAtlasRead(
                    commandBuffer, atlasView.texture());
            VulkanImageTransitions.finishSceneTextureReads(
                    commandBuffer, sceneTextures);
            this.context.device().instance().debug().endDebugGroup(
                    commandBuffer);
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer),
                    "end Prime replay-probe command buffer");
            encoder.execute(commandBuffer);
            submissionAccepted = true;
            this.imageInitialization.submitted();
            initializationActive = false;
            pipeline.submitted(pipelineFrame);
            return probe.submitted(
                    recorded,
                    platform,
                    binary,
                    replayInput);
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
                if (recorded != null) {
                    NrdReplayProbe.RecordedFrame abandoned = recorded;
                    failure = ResourceCleanup.run(
                            () -> probe.abandon(abandoned), failure);
                } else if (!recordingAttempted) {
                    failure = ResourceCleanup.run(
                            () -> probe.abandon(nrdFrame), failure);
                }
                throw failure;
            }
            throw exception;
        }
    }
}
