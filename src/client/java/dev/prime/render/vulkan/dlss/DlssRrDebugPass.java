package dev.prime.render.vulkan.dlss;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.DisplaySettings;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.DispatchMath;
import dev.prime.render.vulkan.VulkanShaderModules;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Prime-owned release-safe visualizer over RR inputs and reflection-MV construction guides. */
final class DlssRrDebugPass implements Destroyable {
    private static final int IMAGE_COUNT = 10;
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int PUSH_SIZE = 20;
    private static final int LOCAL_SIZE = 8;
    private static final String SHADER = "/prime/shaders/rr_debug.comp.spv";

    private final VulkanContext context;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final int dispatchX;
    private final int dispatchY;
    private boolean destroyed;

    private DlssRrDebugPass(
            VulkanContext context,
            long descriptorSetLayout,
            long descriptorPool,
            long descriptorSet,
            long pipelineLayout,
            long pipeline,
            int width,
            int height) {
        this.context = context;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.dispatchX = DispatchMath.divideRoundUp(width, LOCAL_SIZE);
        this.dispatchY = DispatchMath.divideRoundUp(height, LOCAL_SIZE);
    }

    static DlssRrDebugPass create(
            VulkanContext context,
            DlssRrTargets targets,
            VulkanImage displayOutput,
            VulkanBuffer exposureState) {
        List<VulkanImage> images = List.of(
                targets.inputColor(),
                targets.motion(),
                targets.specularMotion(),
                targets.viewZ(),
                targets.rrNormalRoughness(),
                targets.material(),
                targets.specularMaterial(),
                targets.specularHitDistance(),
                targets.rrOutput(),
                displayOutput);
        long setLayout = 0L;
        long descriptorPool = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(IMAGE_COUNT + 1, stack);
            for (int binding = 0; binding < IMAGE_COUNT; binding++) {
                bindings.get(binding).binding(binding)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(COMPUTE_STAGE);
            }
            bindings.get(IMAGE_COUNT).binding(IMAGE_COUNT)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(COMPUTE_STAGE);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateDescriptorSetLayout(
                            context.vkDevice(),
                            VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                    .sType$Default().pBindings(bindings),
                            null,
                            pointer),
                    "create RR debug descriptor layout");
            setLayout = pointer.get(0);
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(COMPUTE_STAGE).offset(0).size(PUSH_SIZE);
            pointer.clear();
            VulkanContext.check(
                    VK12.vkCreatePipelineLayout(
                            context.vkDevice(),
                            VkPipelineLayoutCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pSetLayouts(stack.longs(setLayout))
                                    .pPushConstantRanges(pushRange),
                            null,
                            pointer),
                    "create RR debug pipeline layout");
            pipelineLayout = pointer.get(0);
            long shader = VulkanShaderModules.create(context, stack, SHADER);
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default().stage(COMPUTE_STAGE).module(shader).pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
                info.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                pointer.clear();
                context.createComputePipeline(info, pointer, "RR debug");
                pipeline = pointer.get(0);
            } finally {
                VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
            }
            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(2, stack);
            poolSize.get(0).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(IMAGE_COUNT);
            poolSize.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1);
            pointer.clear();
            VulkanContext.check(
                    VK12.vkCreateDescriptorPool(
                            context.vkDevice(),
                            VkDescriptorPoolCreateInfo.calloc(stack)
                                    .sType$Default().maxSets(1).pPoolSizes(poolSize),
                            null,
                            pointer),
                    "create RR debug descriptor pool");
            descriptorPool = pointer.get(0);
            pointer.clear();
            VulkanContext.check(
                    VK12.vkAllocateDescriptorSets(
                            context.vkDevice(),
                            VkDescriptorSetAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .descriptorPool(descriptorPool)
                                    .pSetLayouts(stack.longs(setLayout)),
                            pointer),
                    "allocate RR debug descriptor set");
            long descriptorSet = pointer.get(0);
            VkDescriptorImageInfo.Buffer imageInfos = VkDescriptorImageInfo.calloc(IMAGE_COUNT, stack);
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(IMAGE_COUNT + 1, stack);
            for (int binding = 0; binding < IMAGE_COUNT; binding++) {
                imageInfos.get(binding).imageView(images.get(binding).view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(binding).sType$Default().dstSet(descriptorSet).dstBinding(binding)
                        .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(binding).address(), 1));
            }
            VkDescriptorBufferInfo.Buffer exposureInfo =
                    VkDescriptorBufferInfo.calloc(1, stack);
            exposureInfo.get(0).buffer(exposureState.handle())
                    .offset(0L).range(exposureState.size());
            writes.get(IMAGE_COUNT).sType$Default()
                    .dstSet(descriptorSet).dstBinding(IMAGE_COUNT)
                    .descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(exposureInfo);
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new DlssRrDebugPass(
                    context,
                    setLayout,
                    descriptorPool,
                    descriptorSet,
                    pipelineLayout,
                    pipeline,
                    displayOutput.width(),
                    displayOutput.height());
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            if (pipeline != 0L) VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
            if (pipelineLayout != 0L) VK12.vkDestroyPipelineLayout(context.vkDevice(), pipelineLayout, null);
            if (setLayout != 0L) VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), setLayout, null);
            throw exception;
        }
    }

    void record(
            VkCommandBuffer commandBuffer,
            DlssRrDebugView view,
            boolean fullscreen,
            int frameIndex,
            int jitterPhaseCount,
            DisplaySettings.Snapshot display) {
        if (view == DlssRrDebugView.OFF) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putInt(0, view.shaderId());
            push.putInt(4, fullscreen ? 1 : 0);
            push.putInt(8, Math.floorMod(frameIndex, jitterPhaseCount) + 1);
            push.putInt(12, jitterPhaseCount);
            push.putFloat(16, display.finalExposureMultiplier());
            VK12.vkCmdBindPipeline(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.descriptorSet),
                    null);
            VK12.vkCmdPushConstants(
                    commandBuffer, this.pipelineLayout, COMPUTE_STAGE, 0, push);
            VK12.vkCmdDispatch(commandBuffer, this.dispatchX, this.dispatchY, 1);
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
        VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
        VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
    }
}
