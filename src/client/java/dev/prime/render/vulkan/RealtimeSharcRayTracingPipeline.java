package dev.prime.render.vulkan;

import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.shader.ShaderAbi;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Realtime SHARC renderer with sparse training followed by one cache-only bridge. */
public final class RealtimeSharcRayTracingPipeline
        extends RealtimeRayTracingPipelineSupport {
    static final int RAYGEN_GROUP_COUNT = RealtimeSharcQueryGroups.GROUP_COUNT;
    static final int RAYGEN_MODULE_COUNT = RealtimeSharcQueryGroups.MODULE_COUNT;
    static final int TRAINING_RAYGEN_GROUP_COUNT =
            RealtimeSharcTrainingGroups.GROUP_COUNT;
    static final int TRAINING_RAYGEN_MODULE_COUNT =
            RealtimeSharcTrainingGroups.MODULE_COUNT;
    static final int TRAINING_WALK_CHUNK = 8;

    static int queryDispatchCount() {
        return 12;
    }

    static int trainingDispatchCount(int scatterCount) {
        return 6 * chunkCount(scatterCount, TRAINING_WALK_CHUNK) + 2;
    }

    static int trainingDispatchCount(
            int scatterCount, int width, int height) {
        return 6 * chunkCount(
                scatterCount, trainingWalkDepth(width, height)) + 2;
    }

    static int trainingWalkDepth(int width, int height) {
        long pixelCount = Math.multiplyExact((long) width, height);
        long trainingPathCount = Math.multiplyExact(
                (long) RealtimeSharc.trainingWidth(width),
                RealtimeSharc.trainingHeight(height));
        return (int) Math.min(
                TRAINING_WALK_CHUNK, pixelCount / trainingPathCount);
    }

    private static int chunkCount(int scatterCount, int chunkDepth) {
        if (scatterCount < 0) {
            throw new IllegalArgumentException("Scatter count must not be negative");
        }
        return scatterCount == 0
                ? 0
                : Math.floorDiv(scatterCount - 1, chunkDepth) + 1;
    }

    static int dispatchCount(int scatterCount) {
        return trainingDispatchCount(scatterCount) + queryDispatchCount() + 2;
    }

    public RealtimeSharcRayTracingPipeline(VulkanContext context, TraceBackend backend) {
        super(
                context,
                backend,
                RealtimeSharcQueryGroups.schedule(
                        context.capabilities().wavefrontShaderSuffix()),
                RealtimeSharcTrainingGroups.schedule(
                        context.capabilities().wavefrontShaderSuffix()),
                dispatchCount(dev.prime.render.ScatterSettings.DEFAULT_COUNT),
                "Prime SHARC ray tracing pipeline",
                "Prime SHARC shader binding table");
    }

    @Override
    protected int recordSharcTraining(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            TraceProgram trainingProgram,
            IntegratorFrameInput input,
            long commandOffset,
            int trainingWidth,
            int trainingHeight) {
        this.traceDirect(
                commandBuffer,
                stack,
                trainingProgram,
                trainingWidth,
                trainingHeight,
                RealtimeSharcTrainingGroups.CAMERA_DELTA_WALK);
        this.queueBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                trainingProgram,
                RealtimeSharcTrainingGroups.LANDING_ADVANCE,
                commandOffset,
                ShaderAbi.WAVEFRONT_PRIMARY_QUEUE);
        this.queueBarrier(commandBuffer, stack);
        int chunkCount = chunkCount(
                input.scatterCount(),
                trainingWalkDepth(input.width(), input.height()));
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            int parity = chunk & 1;
            int sourceQueue = parity == 0
                    ? ShaderAbi.WAVEFRONT_TRACE_QUEUE_0
                    : ShaderAbi.WAVEFRONT_TRACE_QUEUE_1;
            this.traceQueued(
                    commandBuffer,
                    stack,
                    trainingProgram,
                    RealtimeSharcTrainingGroups.PATH_WALK + parity,
                    commandOffset,
                    sourceQueue);
            this.queueBarrier(commandBuffer, stack);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    trainingProgram,
                    RealtimeSharcTrainingGroups.LIGHT_CLASSIFY,
                    commandOffset,
                    ShaderAbi.WAVEFRONT_PRIMARY_QUEUE);
            this.queueBarrier(commandBuffer, stack);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    trainingProgram,
                    RealtimeSharcTrainingGroups.LIGHT_NONE,
                    commandOffset,
                    ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    trainingProgram,
                    RealtimeSharcTrainingGroups.LIGHT_SUN,
                    commandOffset,
                    ShaderAbi.WAVEFRONT_AREA_QUEUE);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    trainingProgram,
                    RealtimeSharcTrainingGroups.LIGHT_AREA,
                    commandOffset,
                    ShaderAbi.WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE);
            this.queueBarrier(commandBuffer, stack);
            this.traceDirect(
                    commandBuffer,
                    stack,
                    trainingProgram,
                    trainingWidth,
                    trainingHeight,
                    RealtimeSharcTrainingGroups.ANCHOR_REDUCE);
            if (chunk + 1 < chunkCount) {
                this.sharcTrainingBarrier(commandBuffer, stack);
            }
        }
        return trainingDispatchCount(
                input.scatterCount(), input.width(), input.height());
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
                RealtimePrimaryGroups.CAMERA_TRACE);
        this.recordPrimaryPrefix(commandBuffer, stack, activeProgram, commandOffset);
        this.nextStepBarrier(commandBuffer, stack, false);
        this.traceQueued(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeSharcQueryGroups.BRIDGE_TRACE,
                commandOffset,
                ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0);
        this.queueBarrier(commandBuffer, stack);
        this.traceQueued(
                commandBuffer,
                stack,
                activeProgram,
                RealtimeSharcQueryGroups.BRIDGE_QUERY,
                commandOffset,
                ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1);
        this.resolveInputBarrier(commandBuffer, stack);
        this.recordOutputTail(
                commandBuffer,
                stack,
                activeProgram,
                commandOffset,
                input.width(),
                input.height(),
                RealtimeSharcQueryGroups.BRANCH_RESOLVE,
                RealtimeSharcQueryGroups.NOISY_OUTPUT_RESOLVE);
        return queryDispatchCount();
    }
}
