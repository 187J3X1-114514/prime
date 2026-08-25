package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.RealtimeFramePlan;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Standard realtime renderer without SHARC resources. */
public final class RealtimeRayTracingPipeline extends RealtimeRayTracingPipelineSupport {
    static final int RAYGEN_GROUP_COUNT = RealtimeStandardGroups.GROUP_COUNT;
    static final int RAYGEN_MODULE_COUNT = RealtimeStandardGroups.MODULE_COUNT;

    static int dispatchCount(int scatterCount) {
        return 3 * scatterCount + 10;
    }

    static int[] primaryDirectInputImageIndices() {
        return new int[] {1, 2};
    }

    static int[] primaryInputImageIndices() {
        return new int[] {0, 1, 2, 4, 6, 7, 8, 9, 10, 20, 21};
    }

    static int[] nextStepInputImageIndices() {
        // Guide images participate even when the next stage only writes them: the fallback and
        // first-owned guide stores require an explicit WAW dependency. Omitting those images lets
        // an earlier fallback write win after the ownership transition.
        return new int[] {
            0, 1, 2, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 21
        };
    }

    static boolean standardBarrierPublishesImagesBefore(int group) {
        // Classifiers only mutate wavefront storage. Publish scratch at its next image consumer;
        // the sibling light-event kernels own disjoint records and logical signal lanes.
        return switch (group) {
            case RealtimePrimaryGroups.DELTA_WALK,
                    RealtimePrimaryGroups.LANDING_DUAL_LIGHT_ADVANCE,
                    RealtimePrimaryGroups.LANDING_GUIDE_ADVANCE,
                    RealtimeStandardGroups.NO_LIGHT_ADVANCE -> true;
            default -> false;
        };
    }

    public RealtimeRayTracingPipeline(VulkanContext context, TraceBackend backend) {
        super(
                context,
                backend,
                RealtimeStandardGroups.standardSchedule(
                        context.capabilities().wavefrontShaderSuffix()),
                null,
                dispatchCount(dev.prime.render.ScatterSettings.DEFAULT_COUNT),
                "Prime realtime ray tracing pipeline",
                "Prime realtime shader binding table");
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
        this.standardBarrierBefore(
                commandBuffer, stack, RealtimeStandardGroups.TRACE_CLASSIFY);
        for (int bounce = 0; bounce < input.scatterCount(); bounce++) {
            this.traceQueued(
                    commandBuffer,
                    stack,
                    activeProgram,
                    RealtimeStandardGroups.TRACE_CLASSIFY,
                    commandOffset,
                    ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0);
            this.standardBarrierBefore(
                    commandBuffer, stack, RealtimeStandardGroups.NO_LIGHT_ADVANCE);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    activeProgram,
                    RealtimeStandardGroups.NO_LIGHT_ADVANCE,
                    commandOffset,
                    ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1);
            this.traceQueued(
                    commandBuffer,
                    stack,
                    activeProgram,
                    RealtimeStandardGroups.DUAL_LIGHT_ADVANCE,
                    commandOffset,
                    ShaderAbi.WAVEFRONT_TRACE_QUEUE_0);
            if (bounce + 1 == input.scatterCount()) {
                this.resolveInputBarrier(commandBuffer, stack);
            } else {
                this.standardBarrierBefore(
                        commandBuffer, stack, RealtimeStandardGroups.TRACE_CLASSIFY);
            }
        }
        this.recordOutputTail(
                commandBuffer,
                stack,
                activeProgram,
                commandOffset,
                input.width(),
                input.height(),
                RealtimeStandardGroups.BRANCH_RESOLVE,
                RealtimeStandardGroups.NOISY_OUTPUT_RESOLVE);
        return dispatchCount(input.scatterCount());
    }

    private void standardBarrierBefore(
            VkCommandBuffer commandBuffer, MemoryStack stack, int group) {
        if (standardBarrierPublishesImagesBefore(group)) {
            this.nextStepBarrier(commandBuffer, stack, false);
        } else {
            this.queueBarrier(commandBuffer, stack);
        }
    }

}
