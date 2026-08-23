package dev.prime.render.vulkan.nrd;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanDescriptors;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanShaderModules;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

final class NrdCompositePass implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int BINDING_COUNT = 28;
    private static final int PUSH_SIZE = 28;
    private final VulkanContext context;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private boolean destroyed;

    private NrdCompositePass(
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

    static NrdCompositePass create(
            VulkanContext context,
            VulkanImage output,
            VulkanImage stableAccumulation,
            NrdImages images,
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
                        .stageFlags(COMPUTE_STAGE);
            }
            descriptorSetLayout = VulkanDescriptors.createSetLayout(
                    context,
                    stack,
                    bindings,
                    "create Prime NRD composite descriptor layout");

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(COMPUTE_STAGE)
                    .offset(0)
                    .size(PUSH_SIZE);
            pipelineLayout = VulkanDescriptors.createPipelineLayout(
                    context,
                    stack,
                    descriptorSetLayout,
                    pushRange,
                    "create Prime NRD composite pipeline layout");

            LongBuffer pointer = stack.mallocLong(1);
            long shaderModule = VulkanShaderModules.create(
                    context, stack, "/prime/shaders/nrd_composite.comp.spv");
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default()
                        .stage(COMPUTE_STAGE)
                        .module(shaderModule)
                        .pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
                pipelineInfo.get(0)
                        .sType$Default()
                        .stage(stage)
                        .layout(pipelineLayout);
                pointer.clear();
                context.createComputePipeline(
                        pipelineInfo, pointer, "Prime NRD composite");
                pipeline = pointer.get(0);
            } finally {
                VK12.vkDestroyShaderModule(context.vkDevice(), shaderModule, null);
            }

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(BINDING_COUNT);
            descriptorPool = VulkanDescriptors.createPool(
                    context,
                    stack,
                    1,
                    poolSize,
                    "create Prime NRD composite descriptor pool");
            descriptorSet = VulkanDescriptors.allocateSet(
                    context,
                    stack,
                    descriptorPool,
                    descriptorSetLayout,
                    "allocate Prime NRD composite descriptor set");

            VulkanImage[] descriptorImages = new VulkanImage[] {
                output,
                images.denoisedDiffuse,
                images.denoisedSpecular,
                images.material,
                images.specularMaterial,
                stableAccumulation,
                atmosphere.aerialRadiance(),
                atmosphere.aerialTransmittance(),
                images.fsrReactiveMask,
                images.fsrTransparencyCompositionMask,
                images.sunLighting,
                images.sunShadow,
                images.denoisedDiffuseSh1,
                images.denoisedSpecularSh1,
                images.normalRoughness,
                images.viewZ,
                images.primaryPosition,
                images.noisySpecular,
                images.reflectionDenoisedDiffuse,
                images.reflectionDenoisedSpecular,
                images.reflectionMaterial,
                images.reflectionSpecularMaterial,
                images.reflectionDenoisedDiffuseSh1,
                images.reflectionDenoisedSpecularSh1,
                images.reflectionNormalRoughness,
                images.reflectionViewZ,
                images.reflectionPosition,
                images.displayPosition
            };
            VkDescriptorImageInfo.Buffer imageInfos =
                    VkDescriptorImageInfo.calloc(BINDING_COUNT, stack);
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(BINDING_COUNT, stack);
            for (int index = 0; index < BINDING_COUNT; index++) {
                imageInfos.get(index)
                        .imageView(descriptorImages[index].view())
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
            return new NrdCompositePass(
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
                VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), descriptorSetLayout, null);
            }
            throw exception;
        }
    }

    void record(
            VkCommandBuffer commandBuffer,
            int width,
            int height,
            float sunRadianceMultiplier,
            float cameraJitterX,
            float cameraJitterY,
            float epipoleX,
            float epipoleY) {
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
            push.putFloat(8, sunRadianceMultiplier);
            push.putFloat(12, cameraJitterX);
            push.putFloat(16, cameraJitterY);
            push.putFloat(20, epipoleX);
            push.putFloat(24, epipoleY);
            VK12.vkCmdPushConstants(
                    commandBuffer,
                    this.pipelineLayout,
                    COMPUTE_STAGE,
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
            VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
        }
    }
}
