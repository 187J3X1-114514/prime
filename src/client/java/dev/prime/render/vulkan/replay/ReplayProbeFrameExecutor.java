package dev.prime.render.vulkan.replay;

import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.replay.RayTraceReplayInput;
import dev.prime.render.replay.RenderBinaryFingerprint;
import dev.prime.render.replay.RenderPlatformFingerprint;
import dev.prime.render.replay.RenderReplayCapture;
import dev.prime.render.terrain.TerrainScene;
import dev.prime.render.vulkan.RayTracingPipeline;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImageTransitions;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Vulkan execution boundary for one preplanned low-resolution replay-probe frame. */
public final class ReplayProbeFrameExecutor {
    private final VulkanContext context;

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
            float sunRadianceMultiplier,
            float displayOverexposure,
            RenderPlatformFingerprint platform,
            RenderBinaryFingerprint binary) {
        Objects.requireNonNull(debugLabel, "debugLabel");
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(nrdFrame, "nrdFrame");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(integrator, "integrator");
        Objects.requireNonNull(replayInput, "replayInput");
        Objects.requireNonNull(atlasView, "atlasView");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(binary, "binary");

        var encoder = this.context.commandEncoder();
        VkCommandBuffer commandBuffer =
                encoder.allocateAndBeginTransientCommandBuffer();
        this.context.device().instance().debug().beginDebugGroup(
                commandBuffer, () -> debugLabel);
        probe.prepareForTrace(commandBuffer);
        VulkanImageTransitions.prepareAtlasForTrace(
                commandBuffer, atlasView.texture());
        pipeline.trace(commandBuffer, integrator, scene);
        NrdReplayProbe.RecordedFrame recorded = probe.recordAfterTrace(
                commandBuffer,
                nrdFrame,
                sunRadianceMultiplier,
                displayOverexposure);
        VulkanImageTransitions.finishAtlasRead(
                commandBuffer, atlasView.texture());
        this.context.device().instance().debug().endDebugGroup(
                commandBuffer);
        VulkanContext.check(
                VK12.vkEndCommandBuffer(commandBuffer),
                "end Prime replay-probe command buffer");
        encoder.execute(commandBuffer);
        return probe.submitted(
                recorded,
                platform,
                binary,
                replayInput);
    }
}
