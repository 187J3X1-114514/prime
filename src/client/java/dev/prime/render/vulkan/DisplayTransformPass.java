package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.DisplaySettings;
import dev.prime.render.ResourceCleanup;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
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

/** Prime's common linear Rec.2020 HDR to Oklab DRT / sRGB Rec.709 display boundary. */
public final class DisplayTransformPass implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int PUSH_SIZE = 28;
    private static final int LOCAL_SIZE = 8;
    private static final String SHADER = "/prime/shaders/fsr_display.comp.spv";

    private final VulkanContext context;
    private final AutoExposurePass autoExposure;
    private final VulkanBuffer exposureState;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final int width;
    private final int height;
    private boolean destroyed;

    private DisplayTransformPass(
            VulkanContext context,
            AutoExposurePass autoExposure,
            VulkanBuffer exposureState,
            long descriptorSetLayout,
            long descriptorPool,
            long descriptorSet,
            long pipelineLayout,
            long pipeline,
            int width,
            int height) {
        this.context = context;
        this.autoExposure = autoExposure;
        this.exposureState = exposureState;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.width = width;
        this.height = height;
    }

    public static DisplayTransformPass createRealtime(
            VulkanContext context,
            VulkanImage linearInput,
            RawWavefrontFrame meteringGuide,
            VulkanImage displayOutput) {
        return create(
                context,
                linearInput,
                meteringGuide.material(),
                meteringGuide.normalRoughness(),
                displayOutput,
                false,
                null);
    }

    public static DisplayTransformPass createOffline(
            VulkanContext context,
            VulkanImage linearInput,
            VulkanBuffer frozenExposure,
            VulkanImage displayOutput) {
        return create(
                context,
                linearInput,
                null,
                null,
                displayOutput,
                false,
                java.util.Objects.requireNonNull(frozenExposure, "frozenExposure"));
    }

    private static DisplayTransformPass create(
            VulkanContext context,
            VulkanImage linearInput,
            VulkanImage albedo,
            VulkanImage normalRoughness,
            VulkanImage displayOutput,
            boolean accumulatedMetering,
            VulkanBuffer frozenExposure) {
        if (linearInput.width() != displayOutput.width()
                || linearInput.height() != displayOutput.height()) {
            throw new IllegalArgumentException("Display transform input and output extents differ");
        }
        if (frozenExposure != null
                && frozenExposure.size() < AutoExposurePass.EXPOSURE_STATE_SIZE) {
            throw new IllegalArgumentException("Frozen exposure state is incomplete");
        }
        long setLayout = 0L;
        long descriptorPool = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        AutoExposurePass autoExposure = frozenExposure == null
                ? AutoExposurePass.create(
                        context,
                        linearInput,
                        java.util.Objects.requireNonNull(albedo, "albedo"),
                        java.util.Objects.requireNonNull(normalRoughness, "normalRoughness"),
                        accumulatedMetering)
                : null;
        VulkanBuffer exposureState = frozenExposure == null
                ? autoExposure.exposureState()
                : frozenExposure;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            bindings.get(0).binding(0)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(1).stageFlags(COMPUTE_STAGE);
            bindings.get(1).binding(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(COMPUTE_STAGE);
            bindings.get(2).binding(2)
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
                    "create common display-transform descriptor layout");
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
                    "create common display-transform pipeline layout");
            pipelineLayout = pointer.get(0);

            long shader = VulkanShaderModules.create(context, stack, SHADER);
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default().stage(COMPUTE_STAGE).module(shader).pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
                pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                pointer.clear();
                VulkanContext.check(
                        VK12.vkCreateComputePipelines(
                                context.vkDevice(), 0L, pipelineInfo, null, pointer),
                        "create common display-transform pipeline");
                pipeline = pointer.get(0);
            } finally {
                VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
            }

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(1);
            poolSizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            poolSizes.get(2).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            pointer.clear();
            VulkanContext.check(
                    VK12.vkCreateDescriptorPool(
                            context.vkDevice(),
                            VkDescriptorPoolCreateInfo.calloc(stack)
                                    .sType$Default().maxSets(1).pPoolSizes(poolSizes),
                            null,
                            pointer),
                    "create common display-transform descriptor pool");
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
                    "allocate common display-transform descriptor set");
            long descriptorSet = pointer.get(0);
            VkDescriptorImageInfo.Buffer imageInfos = VkDescriptorImageInfo.calloc(2, stack);
            imageInfos.get(0).imageView(linearInput.view()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            imageInfos.get(1).imageView(displayOutput.view()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorBufferInfo.Buffer exposureInfo =
                    VkDescriptorBufferInfo.calloc(1, stack);
            exposureInfo.get(0)
                    .buffer(exposureState.handle())
                    .offset(0L)
                    .range(AutoExposurePass.EXPOSURE_STATE_SIZE);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(0).address(), 1));
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1)
                    .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(1).address(), 1));
            writes.get(2).sType$Default().dstSet(descriptorSet).dstBinding(2)
                    .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(exposureInfo);
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new DisplayTransformPass(
                    context,
                    autoExposure,
                    exposureState,
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
            ResourceCleanup.destroy(autoExposure, exception);
            throw exception;
        }
    }

    public VulkanBuffer exposureState() {
        return this.exposureState;
    }

    public void record(
            VkCommandBuffer commandBuffer,
            boolean diagnostic,
            float deltaSeconds,
            boolean reset,
            boolean instant,
            DisplaySettings.Snapshot display) {
        java.util.Objects.requireNonNull(display, "display");
        if (this.autoExposure == null) {
            throw new IllegalStateException("Frozen display transform cannot adapt exposure");
        }
        this.autoExposure.record(
                commandBuffer,
                this.width,
                this.height,
                deltaSeconds,
                reset,
                instant,
                diagnostic,
                display.autoExposureCompensation());
        this.recordDisplay(commandBuffer, diagnostic, display);
    }

    public void recordFrozen(
            VkCommandBuffer commandBuffer,
            DisplaySettings.Snapshot display) {
        java.util.Objects.requireNonNull(display, "display");
        if (this.autoExposure != null) {
            throw new IllegalStateException("Adaptive display transform requires exposure update");
        }
        this.recordDisplay(commandBuffer, false, display);
    }

    private void recordDisplay(
            VkCommandBuffer commandBuffer,
            boolean diagnostic,
            DisplaySettings.Snapshot display) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putInt(0, this.width);
            push.putInt(4, this.height);
            push.putInt(8, diagnostic ? 1 : 0);
            push.putFloat(12, display.oklabOverexposure());
            push.putFloat(16, display.finalExposureMultiplier());
            push.putFloat(20, display.curveExponent());
            push.putFloat(24, display.curveCoefficient());
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
                    commandBuffer,
                    DispatchMath.divideRoundUp(this.width, LOCAL_SIZE),
                    DispatchMath.divideRoundUp(this.height, LOCAL_SIZE),
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
        VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
        if (this.autoExposure != null) {
            this.autoExposure.destroy();
        }
    }
}
