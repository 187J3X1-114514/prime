package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.post.nrd.NrdDiagnostics;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Nearest-neighbor presentation and decoding for native diagnostic images. */
final class NativeDebugPresentPass implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int PUSH_SIZE = 20;
    private static final String SHADER = "/prime/shaders/native_debug_present.comp.spv";

    private final VulkanContext context;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final long pipelineLayout;
    private final long pipeline;
    private final int[] sourceWidths;
    private final int[] sourceHeights;
    private final int outputWidth;
    private final int outputHeight;
    private boolean destroyed;

    private NativeDebugPresentPass(
            VulkanContext context,
            long descriptorSetLayout,
            long descriptorPool,
            long[] descriptorSets,
            long pipelineLayout,
            long pipeline,
            VulkanImage[] sources,
            VulkanImage output) {
        this.context = context;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSets = descriptorSets;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.sourceWidths = new int[sources.length];
        this.sourceHeights = new int[sources.length];
        for (int index = 0; index < sources.length; index++) {
            this.sourceWidths[index] = sources[index].width();
            this.sourceHeights[index] = sources[index].height();
        }
        this.outputWidth = output.width();
        this.outputHeight = output.height();
    }

    static NativeDebugPresentPass create(
            VulkanContext context, VulkanImage output, VulkanImage... sources) {
        if (sources.length == 0) {
            throw new IllegalArgumentException("Native debug presentation needs a source");
        }
        long setLayout = 0L;
        long descriptorPool = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(1).stageFlags(COMPUTE_STAGE);
            bindings.get(1).binding(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(COMPUTE_STAGE);
            setLayout = VulkanDescriptors.createSetLayout(
                    context,
                    stack,
                    bindings,
                    "create native-debug presentation descriptor layout");
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(COMPUTE_STAGE).offset(0).size(PUSH_SIZE);
            pipelineLayout = VulkanDescriptors.createPipelineLayout(
                    context,
                    stack,
                    setLayout,
                    pushRange,
                    "create native-debug presentation pipeline layout");
            LongBuffer pointer = stack.mallocLong(1);
            long shader = VulkanShaderModules.create(context, stack, SHADER);
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default().stage(COMPUTE_STAGE).module(shader).pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer createInfo =
                        VkComputePipelineCreateInfo.calloc(1, stack);
                createInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                pointer.clear();
                context.createComputePipeline(
                        createInfo, pointer, "native-debug presentation");
                pipeline = pointer.get(0);
            } finally {
                VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
            }
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(sources.length);
            poolSizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(sources.length);
            descriptorPool = VulkanDescriptors.createPool(
                    context,
                    stack,
                    sources.length,
                    poolSizes,
                    "create native-debug presentation descriptor pool");
            LongBuffer setLayouts = stack.mallocLong(sources.length);
            for (int index = 0; index < sources.length; index++) {
                setLayouts.put(setLayout);
            }
            setLayouts.flip();
            LongBuffer setPointers = stack.mallocLong(sources.length);
            VulkanContext.check(
                    VK12.vkAllocateDescriptorSets(
                            context.vkDevice(),
                            VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                                    .descriptorPool(descriptorPool)
                                    .pSetLayouts(setLayouts),
                            setPointers),
                    "allocate native-debug presentation descriptor set");
            long[] descriptorSets = new long[sources.length];
            VkDescriptorImageInfo.Buffer imageInfos =
                    VkDescriptorImageInfo.calloc(sources.length * 2, stack);
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(sources.length * 2, stack);
            for (int index = 0; index < sources.length; index++) {
                long descriptorSet = setPointers.get(index);
                descriptorSets[index] = descriptorSet;
                imageInfos.get(index * 2).imageView(sources[index].view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(index * 2 + 1).imageView(output.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(index * 2).sType$Default().dstSet(descriptorSet).dstBinding(0)
                        .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(
                                imageInfos.get(index * 2).address(), 1));
                writes.get(index * 2 + 1).sType$Default().dstSet(descriptorSet).dstBinding(1)
                        .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(
                                imageInfos.get(index * 2 + 1).address(), 1));
            }
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new NativeDebugPresentPass(
                    context,
                    setLayout,
                    descriptorPool,
                    descriptorSets,
                    pipelineLayout,
                    pipeline,
                    sources,
                    output);
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

    void record(VkCommandBuffer commandBuffer, int source, int presentation) {
        if (source < 0 || source >= this.descriptorSets.length) {
            throw new IllegalArgumentException("Invalid native debug source " + source);
        }
        if (presentation < 0 || presentation > NrdDiagnostics.MAX_PRESENTATION) {
            throw new IllegalArgumentException(
                    "Invalid native debug presentation " + presentation);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanSync.memoryBarrier(
                    commandBuffer,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);

            ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putInt(0, this.sourceWidths[source]);
            push.putInt(4, this.sourceHeights[source]);
            push.putInt(8, this.outputWidth);
            push.putInt(12, this.outputHeight);
            push.putInt(16, presentation);
            VK12.vkCmdBindPipeline(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.descriptorSets[source]),
                    null);
            VK12.vkCmdPushConstants(
                    commandBuffer, this.pipelineLayout, COMPUTE_STAGE, 0, push);
            VK12.vkCmdDispatch(
                    commandBuffer,
                    (this.outputWidth + 7) / 8,
                    (this.outputHeight + 7) / 8,
                    1);
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
