package dev.prime.render.vulkan.nrd;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Sums two independently denoised transparent branches into the opaque scene before FSR. */
public final class NrdTransparentComposite implements Destroyable {
    private static final int BINDING_COUNT = 13;
    private static final int PUSH_SIZE = 8;

    private final VulkanContext context;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private boolean destroyed;

    private NrdTransparentComposite(
            VulkanContext context,
            long descriptorSetLayout,
            long descriptorPool,
            long descriptorSet,
            long pipelineLayout,
            long pipeline) {
        this.context = context;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    public static NrdTransparentComposite create(
            VulkanContext context,
            VulkanImage sceneColor,
            NrdDenoiser reflection,
            NrdDenoiser transmission,
            AtmospherePipeline atmosphere) {
        long descriptorSetLayout = 0L;
        long descriptorPool = 0L;
        long descriptorSet = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(BINDING_COUNT, stack);
            for (int index = 0; index < BINDING_COUNT; index++) {
                bindings.get(index)
                        .binding(index)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1)
                        .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo setLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateDescriptorSetLayout(
                            context.vkDevice(), setLayoutInfo, null, pointer),
                    "create Prime transparent NRD composite descriptor layout");
            descriptorSetLayout = pointer.get(0);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(PUSH_SIZE);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            pointer.clear();
            VulkanContext.check(
                    VK12.vkCreatePipelineLayout(
                            context.vkDevice(), pipelineLayoutInfo, null, pointer),
                    "create Prime transparent NRD composite pipeline layout");
            pipelineLayout = pointer.get(0);

            long shaderModule = createShaderModule(
                    context, stack, "/prime/shaders/nrd_transparent_composite.comp.spv");
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default()
                        .stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                        .module(shaderModule)
                        .pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer pipelineInfo =
                        VkComputePipelineCreateInfo.calloc(1, stack);
                pipelineInfo.get(0)
                        .sType$Default()
                        .stage(stage)
                        .layout(pipelineLayout);
                pointer.clear();
                VulkanContext.check(
                        VK12.vkCreateComputePipelines(
                                context.vkDevice(), 0L, pipelineInfo, null, pointer),
                        "create Prime transparent NRD composite pipeline");
                pipeline = pointer.get(0);
            } finally {
                VK12.vkDestroyShaderModule(context.vkDevice(), shaderModule, null);
            }

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(BINDING_COUNT);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(1)
                    .pPoolSizes(poolSize);
            pointer.clear();
            VulkanContext.check(
                    VK12.vkCreateDescriptorPool(context.vkDevice(), poolInfo, null, pointer),
                    "create Prime transparent NRD composite descriptor pool");
            descriptorPool = pointer.get(0);

            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            pointer.clear();
            VulkanContext.check(
                    VK12.vkAllocateDescriptorSets(context.vkDevice(), allocateInfo, pointer),
                    "allocate Prime transparent NRD composite descriptor set");
            descriptorSet = pointer.get(0);

            VulkanImage[] images = new VulkanImage[] {
                sceneColor,
                reflection.denoisedDiffuse(),
                reflection.material(),
                reflection.denoisedSpecular(),
                reflection.specularMaterial(),
                reflection.transparentThroughput(),
                transmission.denoisedDiffuse(),
                transmission.material(),
                transmission.denoisedSpecular(),
                transmission.specularMaterial(),
                transmission.transparentThroughput(),
                atmosphere.aerialRadiance(),
                atmosphere.aerialTransmittance()
            };
            VkDescriptorImageInfo.Buffer imageInfos =
                    VkDescriptorImageInfo.calloc(BINDING_COUNT, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(BINDING_COUNT, stack);
            for (int index = 0; index < BINDING_COUNT; index++) {
                imageInfos.get(index)
                        .imageView(images[index].view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(index)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(index)
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(
                                imageInfos.get(index).address(), 1));
            }
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new NrdTransparentComposite(
                    context,
                    descriptorSetLayout,
                    descriptorPool,
                    descriptorSet,
                    pipelineLayout,
                    pipeline);
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            }
            if (pipeline != 0L) {
                VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
            }
            if (pipelineLayout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), pipelineLayout, null);
            }
            if (descriptorSetLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(
                        context.vkDevice(), descriptorSetLayout, null);
            }
            throw exception;
        }
    }

    public void record(VkCommandBuffer commandBuffer, int width, int height) {
        if (this.destroyed) {
            throw new IllegalStateException("Transparent NRD composite is destroyed");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(
                    commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.descriptorSet),
                    null);
            ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putInt(0, width);
            push.putInt(4, height);
            VK12.vkCmdPushConstants(
                    commandBuffer,
                    this.pipelineLayout,
                    VK12.VK_SHADER_STAGE_COMPUTE_BIT,
                    0,
                    push);
            VK12.vkCmdDispatch(commandBuffer, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
            VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(
                    this.context.vkDevice(), this.descriptorSetLayout, null);
        }
    }

    private static long createShaderModule(
            VulkanContext context,
            MemoryStack stack,
            String resourceName) {
        byte[] bytes;
        try (InputStream input = NrdTransparentComposite.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing shader resource " + resourceName);
            }
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Read shader resource " + resourceName, exception);
        }
        ByteBuffer code = stack.malloc(bytes.length);
        code.put(bytes).flip();
        VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default()
                .pCode(code);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateShaderModule(context.vkDevice(), createInfo, null, pointer),
                "create shader module " + resourceName);
        return pointer.get(0);
    }
}
