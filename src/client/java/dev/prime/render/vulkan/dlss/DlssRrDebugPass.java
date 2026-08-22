package dev.prime.render.vulkan.dlss;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.DisplaySettings;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanImageInitializationBatch;
import dev.prime.render.vulkan.DispatchMath;
import dev.prime.render.vulkan.VulkanShaderModules;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
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
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Prime-owned release-safe visualizer over RR inputs and reflection-MV construction guides. */
final class DlssRrDebugPass implements Destroyable {
    private static final int IMAGE_COUNT = 18;
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int SNAPSHOT_FORMAT = VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int SNAPSHOT_USAGE = VK12.VK_IMAGE_USAGE_STORAGE_BIT;
    private static final int PUSH_SIZE = 20;
    private static final int LOCAL_SIZE = 8;
    private static final String SHADER = "/prime/shaders/rr_debug.comp.spv";
    private static final String CAPTURE_SHADER =
            "/prime/shaders/rr_debug_capture.comp.spv";

    private final VulkanContext context;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final long capturePipeline;
    private final VulkanImage rawMaterial;
    private final VulkanImage rawSpecularMaterial;
    private final VulkanImage rawTransportMetadata;
    private final int dispatchX;
    private final int dispatchY;
    private final int captureDispatchX;
    private final int captureDispatchY;
    private boolean destroyed;

    private DlssRrDebugPass(
            VulkanContext context,
            long descriptorSetLayout,
            long descriptorPool,
            long descriptorSet,
            long pipelineLayout,
            long pipeline,
            long capturePipeline,
            VulkanImage rawMaterial,
            VulkanImage rawSpecularMaterial,
            VulkanImage rawTransportMetadata,
            int displayWidth,
            int displayHeight,
            int renderWidth,
            int renderHeight) {
        this.context = context;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.capturePipeline = capturePipeline;
        this.rawMaterial = rawMaterial;
        this.rawSpecularMaterial = rawSpecularMaterial;
        this.rawTransportMetadata = rawTransportMetadata;
        this.dispatchX = DispatchMath.divideRoundUp(displayWidth, LOCAL_SIZE);
        this.dispatchY = DispatchMath.divideRoundUp(displayHeight, LOCAL_SIZE);
        this.captureDispatchX = DispatchMath.divideRoundUp(renderWidth, LOCAL_SIZE);
        this.captureDispatchY = DispatchMath.divideRoundUp(renderHeight, LOCAL_SIZE);
    }

    static DlssRrDebugPass create(
            VulkanContext context,
            DlssRrTargets targets,
            VulkanImage displayOutput,
            VulkanBuffer exposureState) {
        VulkanImage rawMaterial = null;
        VulkanImage rawSpecularMaterial = null;
        VulkanImage rawTransportMetadata = null;
        long setLayout = 0L;
        long descriptorPool = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        long capturePipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            rawMaterial = context.createImage2D(
                    targets.inputColor().width(),
                    targets.inputColor().height(),
                    SNAPSHOT_FORMAT,
                    SNAPSHOT_USAGE,
                    "Prime RR debug raw material snapshot");
            rawSpecularMaterial = context.createImage2D(
                    targets.inputColor().width(),
                    targets.inputColor().height(),
                    SNAPSHOT_FORMAT,
                    SNAPSHOT_USAGE,
                    "Prime RR debug raw specular material snapshot");
            rawTransportMetadata = context.createImage2D(
                    targets.inputColor().width(),
                    targets.inputColor().height(),
                    SNAPSHOT_FORMAT,
                    SNAPSHOT_USAGE,
                    "Prime RR debug raw transport metadata snapshot");
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
                    displayOutput,
                    targets.noisyDiffuse(),
                    targets.noisySpecular(),
                    targets.normalRoughness(),
                    targets.reflectionPosition(),
                    rawMaterial,
                    rawSpecularMaterial,
                    rawTransportMetadata,
                    targets.guideDiagnostic());
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
            pipeline = createPipeline(
                    context, stack, pipelineLayout, SHADER, "RR debug");
            capturePipeline = createPipeline(
                    context, stack, pipelineLayout, CAPTURE_SHADER, "RR debug capture");
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
                    capturePipeline,
                    rawMaterial,
                    rawSpecularMaterial,
                    rawTransportMetadata,
                    displayOutput.width(),
                    displayOutput.height(),
                    targets.inputColor().width(),
                    targets.inputColor().height());
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            if (capturePipeline != 0L) VK12.vkDestroyPipeline(context.vkDevice(), capturePipeline, null);
            if (pipeline != 0L) VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
            if (pipelineLayout != 0L) VK12.vkDestroyPipelineLayout(context.vkDevice(), pipelineLayout, null);
            if (setLayout != 0L) VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), setLayout, null);
            if (rawTransportMetadata != null) rawTransportMetadata.destroy();
            if (rawSpecularMaterial != null) rawSpecularMaterial.destroy();
            if (rawMaterial != null) rawMaterial.destroy();
            throw exception;
        }
    }

    private static long createPipeline(
            VulkanContext context,
            MemoryStack stack,
            long pipelineLayout,
            String shaderResource,
            String label) {
        long shader = VulkanShaderModules.create(context, stack, shaderResource);
        try {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(COMPUTE_STAGE)
                    .module(shader)
                    .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            LongBuffer pointer = stack.mallocLong(1);
            context.createComputePipeline(info, pointer, label);
            return pointer.get(0);
        } finally {
            VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
        }
    }

    void capture(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        this.prepareSnapshots(commandBuffer, initialization);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.capturePipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.descriptorSet),
                    null);
            VK12.vkCmdDispatch(
                    commandBuffer,
                    this.captureDispatchX,
                    this.captureDispatchY,
                    1);
        }
    }

    private void prepareSnapshots(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        VulkanImage[] images = {
            this.rawMaterial,
            this.rawSpecularMaterial,
            this.rawTransportMetadata
        };
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(images.length, stack);
            for (int index = 0; index < images.length; index++) {
                VulkanImage image = images[index];
                boolean initialized = initialization.prepare(image);
                barriers.get(index).sType$Default()
                        .srcStageMask(initialized
                                ? VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                                : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                        .srcAccessMask(initialized
                                ? VK12.VK_ACCESS_MEMORY_READ_BIT
                                        | VK12.VK_ACCESS_MEMORY_WRITE_BIT
                                : 0L)
                        .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                        .dstAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                        .oldLayout(initialized
                                ? VK12.VK_IMAGE_LAYOUT_GENERAL
                                : VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .image(image.image());
                barriers.get(index).subresourceRange()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pImageMemoryBarriers(barriers));
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
        VK12.vkDestroyPipeline(this.context.vkDevice(), this.capturePipeline, null);
        VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
        VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
        this.rawTransportMetadata.destroy();
        this.rawSpecularMaterial.destroy();
        this.rawMaterial.destroy();
    }
}
