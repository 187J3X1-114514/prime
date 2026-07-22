package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Sums the raw estimator partitions into native-resolution linear HDR without filtering. */
final class NoisyCompositePass implements Destroyable {
    private static final int IMAGE_COUNT = 8;
    private static final int PUSH_SIZE = 16;
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final String SHADER = "/prime/shaders/noisy_composite.comp.spv";

    private final VulkanContext context;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final int width;
    private final int height;
    private boolean destroyed;

    private NoisyCompositePass(
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
        this.width = width;
        this.height = height;
    }

    static NoisyCompositePass create(
            VulkanContext context,
            NoisyTargets targets,
            VulkanImage stableRadiance,
            AtmospherePipeline atmosphere) {
        List<VulkanImage> images = List.of(
                targets.noisyDiffuse(),
                targets.noisySpecular(),
                targets.material(),
                stableRadiance,
                targets.sunLighting(),
                atmosphere.aerialRadiance(),
                atmosphere.aerialTransmittance(),
                targets.linearOutput());
        long setLayout = 0L;
        long descriptorPool = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(IMAGE_COUNT, stack);
            for (int binding = 0; binding < IMAGE_COUNT; binding++) {
                bindings.get(binding).binding(binding)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(COMPUTE_STAGE);
            }
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateDescriptorSetLayout(
                            context.vkDevice(),
                            VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                    .sType$Default().pBindings(bindings),
                            null,
                            pointer),
                    "create noisy-composite descriptor layout");
            setLayout = pointer.get(0);
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(COMPUTE_STAGE).offset(0).size(PUSH_SIZE);
            pointer.clear();
            VulkanContext.check(
                    VK12.vkCreatePipelineLayout(
                            context.vkDevice(),
                            VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                                    .pSetLayouts(stack.longs(setLayout))
                                    .pPushConstantRanges(pushRange),
                            null,
                            pointer),
                    "create noisy-composite pipeline layout");
            pipelineLayout = pointer.get(0);
            long shader = createShaderModule(context, stack);
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default().stage(COMPUTE_STAGE).module(shader).pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer createInfo =
                        VkComputePipelineCreateInfo.calloc(1, stack);
                createInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                pointer.clear();
                VulkanContext.check(
                        VK12.vkCreateComputePipelines(
                                context.vkDevice(), 0L, createInfo, null, pointer),
                        "create noisy-composite pipeline");
                pipeline = pointer.get(0);
            } finally {
                VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
            }
            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(IMAGE_COUNT);
            pointer.clear();
            VulkanContext.check(
                    VK12.vkCreateDescriptorPool(
                            context.vkDevice(),
                            VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                                    .maxSets(1).pPoolSizes(poolSize),
                            null,
                            pointer),
                    "create noisy-composite descriptor pool");
            descriptorPool = pointer.get(0);
            pointer.clear();
            VulkanContext.check(
                    VK12.vkAllocateDescriptorSets(
                            context.vkDevice(),
                            VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                                    .descriptorPool(descriptorPool)
                                    .pSetLayouts(stack.longs(setLayout)),
                            pointer),
                    "allocate noisy-composite descriptor set");
            long descriptorSet = pointer.get(0);
            VkDescriptorImageInfo.Buffer imageInfos =
                    VkDescriptorImageInfo.calloc(IMAGE_COUNT, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(IMAGE_COUNT, stack);
            for (int binding = 0; binding < IMAGE_COUNT; binding++) {
                imageInfos.get(binding).imageView(images.get(binding).view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(binding).sType$Default().dstSet(descriptorSet).dstBinding(binding)
                        .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(
                                imageInfos.get(binding).address(), 1));
            }
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new NoisyCompositePass(
                    context,
                    setLayout,
                    descriptorPool,
                    descriptorSet,
                    pipelineLayout,
                    pipeline,
                    targets.linearOutput().width(),
                    targets.linearOutput().height());
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            }
            if (pipeline != 0L) VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
            if (pipelineLayout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), pipelineLayout, null);
            }
            if (setLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), setLayout, null);
            }
            throw exception;
        }
    }

    void record(VkCommandBuffer commandBuffer, float sunRadianceMultiplier) {
        memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.calloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putInt(0, this.width);
            push.putInt(4, this.height);
            push.putFloat(8, sunRadianceMultiplier);
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
            VK12.vkCmdDispatch(
                    commandBuffer, (this.width + 7) / 8, (this.height + 7) / 8, 1);
        }
        memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    private static void memoryBarrier(
            VkCommandBuffer commandBuffer,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcStageMask(sourceStage).srcAccessMask(sourceAccess)
                    .dstStageMask(destinationStage).dstAccessMask(destinationAccess);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
    }

    private static long createShaderModule(VulkanContext context, MemoryStack stack) {
        byte[] bytes;
        try (InputStream input = NoisyCompositePass.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("Missing shader resource " + SHADER);
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read shader resource " + SHADER, exception);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateShaderModule(
                            context.vkDevice(),
                            VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),
                            null,
                            pointer),
                    "create " + SHADER);
            return pointer.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
        VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
        VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(
                this.context.vkDevice(), this.descriptorSetLayout, null);
    }
}
