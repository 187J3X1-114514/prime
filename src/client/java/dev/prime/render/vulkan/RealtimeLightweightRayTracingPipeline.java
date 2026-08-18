package dev.prime.render.vulkan;

import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.ScatterSettings;
import dev.prime.render.shader.ShaderAbi;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Independent realtime renderer with single-candidate emissive-block NEE. */
public final class RealtimeLightweightRayTracingPipeline
        extends RealtimeRayTracingPipeline.IndependentSupport {
    static final int RAYGEN_GROUP_COUNT = RealtimeLightweightWavefrontGroups.GROUP_COUNT;
    static final int RAYGEN_MODULE_COUNT = RealtimeLightweightWavefrontGroups.MODULE_COUNT;

    static int dispatchCount(int scatterCount) {
        return 4 * Math.max(scatterCount - 1, 0) + 5;
    }

    public RealtimeLightweightRayTracingPipeline(
            VulkanContext context, TraceBackend backend) {
        super(
                context,
                backend,
                RealtimeLightweightWavefrontGroups.standardSchedule(
                        context.capabilities().wavefrontShaderSuffix()),
                RealtimeLightweightWavefrontGroups.sharcSchedule(
                        context.capabilities().wavefrontShaderSuffix()),
                dispatchCount(ScatterSettings.DEFAULT_COUNT),
                "Prime lightweight realtime ray tracing pipeline",
                "Prime lightweight realtime shader binding table");
    }

    @Override
    protected int recordTransport(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            TraceProgram activeProgram,
            IntegratorFrameInput input,
            long commandOffset) {
        this.traceDirect(
                commandBuffer,
                stack,
                activeProgram,
                input.width(),
                input.height(),
                RealtimeLightweightWavefrontGroups.HEAD);
        this.allImagesBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeLightweightWavefrontGroups.PRIMARY_SUN,
                commandOffset,
                ShaderAbi.WAVEFRONT_AREA_QUEUE);
        this.primaryDirectBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeLightweightWavefrontGroups.PRIMARY,
                commandOffset,
                ShaderAbi.WAVEFRONT_SHADE_QUEUE);
        this.allImagesBarrier(commandBuffer, stack);
        int traceQueue = ShaderAbi.WAVEFRONT_TRACE_QUEUE_0;
        for (int scatter = 1; scatter < input.scatterCount(); scatter++) {
            this.recordRound(
                    commandBuffer,
                    stack,
                    activeProgram,
                    commandOffset,
                    traceQueue);
            traceQueue = traceQueue == ShaderAbi.WAVEFRONT_TRACE_QUEUE_0
                    ? ShaderAbi.WAVEFRONT_TRACE_QUEUE_1
                    : ShaderAbi.WAVEFRONT_TRACE_QUEUE_0;
        }
        this.traceQueued(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeLightweightWavefrontGroups.TRANSPARENT_RESOLVE,
                commandOffset,
                ShaderAbi.WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE);
        this.traceDirect(
                commandBuffer,
                stack,
                activeProgram,
                input.width(),
                input.height(),
                RealtimeLightweightWavefrontGroups.RESOLVE);
        return dispatchCount(input.scatterCount());
    }

    private void recordRound(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            TraceProgram activeProgram,
            long commandOffset,
            int traceQueue) {
        this.traceQueued(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeLightweightWavefrontGroups.step(traceQueue),
                commandOffset,
                traceQueue);
        this.queueBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeLightweightWavefrontGroups.light(),
                commandOffset,
                ShaderAbi.WAVEFRONT_AREA_QUEUE);
        this.directRadianceBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeLightweightWavefrontGroups.shade(traceQueue),
                commandOffset,
                ShaderAbi.WAVEFRONT_SHADE_QUEUE);
        this.traceQueued(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeLightweightWavefrontGroups.transparentShade(traceQueue),
                commandOffset,
                ShaderAbi.WAVEFRONT_TRANSPARENT_SHADE_QUEUE);
        this.allImagesBarrier(commandBuffer, stack);
    }
}
