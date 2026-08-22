package dev.prime.render.vulkan;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

/** Checked creation of the single-set descriptor layouts used by Prime compute passes. */
public final class VulkanDescriptors {
    private VulkanDescriptors() {
    }

    public static long createSetLayout(
            VulkanContext context,
            MemoryStack stack,
            VkDescriptorSetLayoutBinding.Buffer bindings,
            String operation) {
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(
                        context.vkDevice(),
                        VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                .sType$Default()
                                .pBindings(bindings),
                        null,
                        pointer),
                operation);
        return pointer.get(0);
    }

    public static long createPipelineLayout(
            VulkanContext context,
            MemoryStack stack,
            long setLayout,
            VkPushConstantRange.Buffer pushConstants,
            String operation) {
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreatePipelineLayout(
                        context.vkDevice(),
                        VkPipelineLayoutCreateInfo.calloc(stack)
                                .sType$Default()
                                .pSetLayouts(stack.longs(setLayout))
                                .pPushConstantRanges(pushConstants),
                        null,
                        pointer),
                operation);
        return pointer.get(0);
    }

    public static long createPool(
            VulkanContext context,
            MemoryStack stack,
            int maxSets,
            VkDescriptorPoolSize.Buffer poolSizes,
            String operation) {
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorPool(
                        context.vkDevice(),
                        VkDescriptorPoolCreateInfo.calloc(stack)
                                .sType$Default()
                                .maxSets(maxSets)
                                .pPoolSizes(poolSizes),
                        null,
                        pointer),
                operation);
        return pointer.get(0);
    }

    public static long allocateSet(
            VulkanContext context,
            MemoryStack stack,
            long descriptorPool,
            long setLayout,
            String operation) {
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkAllocateDescriptorSets(
                        context.vkDevice(),
                        VkDescriptorSetAllocateInfo.calloc(stack)
                                .sType$Default()
                                .descriptorPool(descriptorPool)
                                .pSetLayouts(stack.longs(setLayout)),
                        pointer),
                operation);
        return pointer.get(0);
    }
}
