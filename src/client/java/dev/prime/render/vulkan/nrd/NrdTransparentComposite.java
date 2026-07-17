package dev.prime.render.vulkan.nrd;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.FrameCamera;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import org.joml.Matrix4f;
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
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Resolves transparent NRD branches or the selected final validation layer before FSR. */
public final class NrdTransparentComposite implements Destroyable {
    private static final int BINDING_COUNT = 26;
    private static final int PUSH_SIZE = 112;
    private static final int HISTORY_IMAGE_USAGE = VK12.VK_IMAGE_USAGE_STORAGE_BIT;

    private final VulkanContext context;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final long pipelineLayout;
    private final long pipeline;
    private final VulkanImage[] reflectionHistory;
    private final VulkanImage[] transmissionHistory;
    private final VulkanImage[] checkerGuideHistory;
    private final Matrix4f previousWorldToClip = new Matrix4f();
    private final Matrix4f worldToViewScratch = new Matrix4f();
    private int historyReadIndex;
    private FrameCamera previousCamera;
    private float previousCameraJitterX;
    private float previousCameraJitterY;
    private boolean historyValid;
    private boolean destroyed;

    private NrdTransparentComposite(
            VulkanContext context,
            long descriptorSetLayout,
            long descriptorPool,
            long[] descriptorSets,
            long pipelineLayout,
            long pipeline,
            VulkanImage[] reflectionHistory,
            VulkanImage[] transmissionHistory,
            VulkanImage[] checkerGuideHistory) {
        this.context = context;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSets = descriptorSets.clone();
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.reflectionHistory = reflectionHistory;
        this.transmissionHistory = transmissionHistory;
        this.checkerGuideHistory = checkerGuideHistory;
    }

    public static NrdTransparentComposite create(
            VulkanContext context,
            VulkanImage sceneColor,
            NrdDenoiser opaque,
            NrdDenoiser reflection,
            NrdDenoiser transmission,
            AtmospherePipeline atmosphere) {
        long descriptorSetLayout = 0L;
        long descriptorPool = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        VulkanImage[] reflectionHistory = new VulkanImage[2];
        VulkanImage[] transmissionHistory = new VulkanImage[2];
        VulkanImage[] checkerGuideHistory = new VulkanImage[2];
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

            int width = sceneColor.width();
            int height = sceneColor.height();
            for (int index = 0; index < 2; index++) {
                reflectionHistory[index] = context.createImage2D(
                        width,
                        height,
                        VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        HISTORY_IMAGE_USAGE,
                        "Prime transparent checker reflection history " + index);
                transmissionHistory[index] = context.createImage2D(
                        width,
                        height,
                        VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        HISTORY_IMAGE_USAGE,
                        "Prime transparent checker transmission history " + index);
                checkerGuideHistory[index] = context.createImage2D(
                        width,
                        height,
                        VK12.VK_FORMAT_R32G32_SFLOAT,
                        HISTORY_IMAGE_USAGE,
                        "Prime transparent checker reconstruction guide " + index);
            }

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(BINDING_COUNT * 2);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(2)
                    .pPoolSizes(poolSize);
            pointer.clear();
            VulkanContext.check(
                    VK12.vkCreateDescriptorPool(context.vkDevice(), poolInfo, null, pointer),
                    "create Prime transparent NRD composite descriptor pool");
            descriptorPool = pointer.get(0);

            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout, descriptorSetLayout));
            LongBuffer descriptorPointers = stack.mallocLong(2);
            VulkanContext.check(
                    VK12.vkAllocateDescriptorSets(
                            context.vkDevice(), allocateInfo, descriptorPointers),
                    "allocate Prime transparent NRD composite descriptor sets");
            long[] descriptorSets = new long[] {
                descriptorPointers.get(0), descriptorPointers.get(1)
            };

            VulkanImage[] commonImages = new VulkanImage[] {
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
                atmosphere.aerialTransmittance(),
                opaque.validation(),
                reflection.validation(),
                transmission.validation(),
                reflection.primaryPosition(),
                transmission.primaryPosition(),
                reflection.motion(),
                transmission.motion()
            };
            if (commonImages.length != 20) {
                throw new IllegalStateException("Transparent composite descriptor ABI drift");
            }
            VkDescriptorImageInfo.Buffer imageInfos =
                    VkDescriptorImageInfo.calloc(BINDING_COUNT * 2, stack);
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(BINDING_COUNT * 2, stack);
            for (int setIndex = 0; setIndex < 2; setIndex++) {
                int writeIndex = 1 - setIndex;
                VulkanImage[] setImages = new VulkanImage[BINDING_COUNT];
                System.arraycopy(commonImages, 0, setImages, 0, commonImages.length);
                setImages[20] = reflectionHistory[setIndex];
                setImages[21] = reflectionHistory[writeIndex];
                setImages[22] = transmissionHistory[setIndex];
                setImages[23] = transmissionHistory[writeIndex];
                setImages[24] = checkerGuideHistory[setIndex];
                setImages[25] = checkerGuideHistory[writeIndex];
                for (int binding = 0; binding < BINDING_COUNT; binding++) {
                    int descriptorIndex = setIndex * BINDING_COUNT + binding;
                    imageInfos.get(descriptorIndex)
                            .imageView(setImages[binding].view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    writes.get(descriptorIndex)
                            .sType$Default()
                            .dstSet(descriptorSets[setIndex])
                            .dstBinding(binding)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfos.get(descriptorIndex).address(), 1));
                }
            }
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new NrdTransparentComposite(
                    context,
                    descriptorSetLayout,
                    descriptorPool,
                    descriptorSets,
                    pipelineLayout,
                    pipeline,
                    reflectionHistory,
                    transmissionHistory,
                    checkerGuideHistory);
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) {
                VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            }
            destroyImages(checkerGuideHistory);
            destroyImages(transmissionHistory);
            destroyImages(reflectionHistory);
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

    public FrameToken record(
            VkCommandBuffer commandBuffer,
            FrameCamera camera,
            int width,
            int height,
            float sunRadianceMultiplier,
            float cameraJitterX,
            float cameraJitterY,
            boolean forceRestart) {
        if (this.destroyed) {
            throw new IllegalStateException("Transparent NRD composite is destroyed");
        }
        if (width != this.reflectionHistory[0].width()
                || height != this.reflectionHistory[0].height()) {
            throw new IllegalArgumentException("Transparent checker history extent mismatch");
        }
        boolean restart = forceRestart || this.previousCamera == null || !this.historyValid;
        FrameCamera historyCamera = restart ? camera : this.previousCamera;
        float historyJitterX = restart ? cameraJitterX : this.previousCameraJitterX;
        float historyJitterY = restart ? cameraJitterY : this.previousCameraJitterY;
        NrdCameraTransform.previousWorldToClip(
                camera, historyCamera, this.previousWorldToClip, this.worldToViewScratch);
        this.prepareHistoryImages(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(
                    commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.descriptorSets[this.historyReadIndex]),
                    null);
            ByteBuffer push = stack.calloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            this.previousWorldToClip.get(0, push);
            push.putInt(64, width);
            push.putInt(68, height);
            push.putFloat(72, sunRadianceMultiplier);
            push.putInt(76, NrdDiagnostics.mode().outputSelector());
            push.putFloat(80, cameraJitterX);
            push.putFloat(84, cameraJitterY);
            push.putFloat(88, historyJitterX);
            push.putFloat(92, historyJitterY);
            push.putFloat(96, (float) (camera.renderX() - historyCamera.renderX()));
            push.putFloat(100, (float) (camera.renderY() - historyCamera.renderY()));
            push.putFloat(104, (float) (camera.renderZ() - historyCamera.renderZ()));
            push.putInt(108, restart ? 0 : 1);
            VK12.vkCmdPushConstants(
                    commandBuffer,
                    this.pipelineLayout,
                    VK12.VK_SHADER_STAGE_COMPUTE_BIT,
                    0,
                    push);
            VK12.vkCmdDispatch(commandBuffer, (width + 7) / 8, (height + 7) / 8, 1);
        }
        return new FrameToken(
                this,
                camera,
                cameraJitterX,
                cameraJitterY,
                1 - this.historyReadIndex);
    }

    /** Commits the CPU-side ping-pong state only after the containing command buffer is submitted. */
    public void submitted(FrameToken token) {
        if (this.destroyed) {
            throw new IllegalStateException("Transparent NRD composite is destroyed");
        }
        if (token.owner != this || token.submitted) {
            throw new IllegalArgumentException(
                    "Transparent checker frame token does not belong to this submission");
        }
        token.submitted = true;
        this.previousCamera = token.camera;
        this.previousCameraJitterX = token.cameraJitterX;
        this.previousCameraJitterY = token.cameraJitterY;
        this.historyReadIndex = token.nextHistoryReadIndex;
        this.historyValid = true;
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
            destroyImages(this.checkerGuideHistory);
            destroyImages(this.transmissionHistory);
            destroyImages(this.reflectionHistory);
            VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(
                    this.context.vkDevice(), this.descriptorSetLayout, null);
        }
    }

    private void prepareHistoryImages(VkCommandBuffer commandBuffer) {
        VulkanImage[] images = new VulkanImage[] {
            this.reflectionHistory[0],
            this.reflectionHistory[1],
            this.transmissionHistory[0],
            this.transmissionHistory[1],
            this.checkerGuideHistory[0],
            this.checkerGuideHistory[1]
        };
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(images.length, stack);
            for (int index = 0; index < images.length; index++) {
                VulkanImage image = images[index];
                boolean initialized = image.initialized();
                barriers.get(index)
                        .sType$Default()
                        .srcStageMask(initialized
                                ? VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                                : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                        .srcAccessMask(initialized
                                ? VK12.VK_ACCESS_SHADER_READ_BIT
                                        | VK12.VK_ACCESS_SHADER_WRITE_BIT
                                : 0L)
                        .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                        .dstAccessMask(
                                VK12.VK_ACCESS_SHADER_READ_BIT
                                        | VK12.VK_ACCESS_SHADER_WRITE_BIT)
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
                image.markInitialized();
            }
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pImageMemoryBarriers(barriers);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private static void destroyImages(VulkanImage[] images) {
        for (int index = images.length - 1; index >= 0; index--) {
            if (images[index] != null) {
                images[index].destroy();
            }
        }
    }

    public static final class FrameToken {
        private final NrdTransparentComposite owner;
        private final FrameCamera camera;
        private final float cameraJitterX;
        private final float cameraJitterY;
        private final int nextHistoryReadIndex;
        private boolean submitted;

        private FrameToken(
                NrdTransparentComposite owner,
                FrameCamera camera,
                float cameraJitterX,
                float cameraJitterY,
                int nextHistoryReadIndex) {
            this.owner = owner;
            this.camera = camera;
            this.cameraJitterX = cameraJitterX;
            this.cameraJitterY = cameraJitterY;
            this.nextHistoryReadIndex = nextHistoryReadIndex;
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
        // Compute shaders can grow beyond LWJGL's deliberately small per-thread MemoryStack.
        // SPIR-V is transient creation data, so use an explicitly owned native allocation and
        // release it immediately after vkCreateShaderModule has consumed the bytes.
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateShaderModule(context.vkDevice(), createInfo, null, pointer),
                    "create shader module " + resourceName);
            return pointer.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
