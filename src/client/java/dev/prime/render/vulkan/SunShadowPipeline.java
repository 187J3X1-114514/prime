package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.shader.ShaderAbi;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;

/** Shared sun-cache raygen, independent of either integrator pipeline. */
public final class SunShadowPipeline implements Destroyable {
    private static final int ALL_RT_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

    private final VulkanContext context;
    private final TraceBindings bindings;
    private final long pipelineLayout;
    private final TraceProgram program;
    private boolean destroyed;

    public SunShadowPipeline(VulkanContext context, TraceBindings bindings) {
        this.context = context;
        this.bindings = java.util.Objects.requireNonNull(bindings, "bindings");
        long layout = 0L;
        TraceProgram traceProgram = null;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
                range.get(0)
                        .stageFlags(ALL_RT_STAGES)
                        .offset(0)
                        .size(ShaderAbi.PUSH_CONSTANT_SIZE);
                VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pSetLayouts(stack.longs(bindings.descriptorSetLayout()))
                        .pPushConstantRanges(range);
                LongBuffer pointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreatePipelineLayout(
                                context.vkDevice(), info, null, pointer),
                        "create sun-shadow pipeline layout");
                layout = pointer.get(0);
            }
            traceProgram = TraceProgram.create(
                    context,
                    layout,
                    new String[] {"/prime/shaders/sun_shadow.rgen.spv"},
                    new int[] {0},
                    new int[] {0},
                    "Prime sun-shadow ray tracing pipeline",
                    "Prime sun-shadow shader binding table");
            this.pipelineLayout = layout;
            this.program = traceProgram;
        } catch (RuntimeException exception) {
            if (traceProgram != null) {
                traceProgram.destroy();
            }
            if (layout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), layout, null);
            }
            throw exception;
        }
    }

    void trace(
            VkCommandBuffer commandBuffer,
            ByteBuffer pushConstants,
            int width,
            int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Sun-shadow trace extent must be positive");
        }
        if (!this.bindings.ready()) {
            throw new IllegalStateException("Trace-backend resources are not prepared");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                    this.program.pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.bindings.descriptorSet()),
                    null);
            VK12.vkCmdPushConstants(
                    commandBuffer,
                    this.pipelineLayout,
                    ALL_RT_STAGES,
                    0,
                    pushConstants);
            VkStridedDeviceAddressRegionKHR raygen =
                    VkStridedDeviceAddressRegionKHR.calloc(stack)
                            .deviceAddress(this.program.raygenAddress(0))
                            .stride(this.program.raygenRecordStride)
                            .size(this.program.raygenRecordStride);
            VkStridedDeviceAddressRegionKHR miss =
                    VkStridedDeviceAddressRegionKHR.calloc(stack)
                            .deviceAddress(this.program.missAddress)
                            .stride(this.program.recordStride)
                            .size(this.program.recordStride * TraceProgram.MISS_GROUP_COUNT);
            VkStridedDeviceAddressRegionKHR hit =
                    VkStridedDeviceAddressRegionKHR.calloc(stack)
                            .deviceAddress(this.program.hitAddress)
                            .stride(this.program.recordStride)
                            .size(this.program.recordStride * TraceProgram.HIT_GROUP_COUNT);
            KHRRayTracingPipeline.vkCmdTraceRaysKHR(
                    commandBuffer,
                    raygen,
                    miss,
                    hit,
                    VkStridedDeviceAddressRegionKHR.calloc(stack),
                    width,
                    height,
                    1);
        }
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            this.program.destroy();
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
        }
    }
}
