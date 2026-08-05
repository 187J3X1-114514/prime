package dev.prime.render.vulkan.replay;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.replay.CapturedRenderStage;
import dev.prime.render.replay.RenderStageSchema;
import dev.prime.render.replay.RenderPixelFormat;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.nrd.PreparedNrdFrame;
import dev.prime.render.vulkan.nrd.NrdCompositeFrame;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
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
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Optional GPU-to-host observation point for production stage resources.
 *
 * <p>No instance, descriptor, pipeline, buffer or dispatch exists unless capture is explicitly
 * requested.
 */
public final class ReplayStageCapturePass implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int PUSH_BYTES = 2 * Integer.BYTES;
    private static final int CHANNELS = 4;
    private static final int LOCAL_SIZE = 8;
    private static final String RAW_SHADER =
            "/prime/shaders/replay_capture_raw.comp.spv";
    private static final String PREPARED_NRD_SHADER =
            "/prime/shaders/replay_capture_prepared_nrd.comp.spv";
    private static final String POST_NRD_SHADER =
            "/prime/shaders/replay_capture_post_nrd.comp.spv";

    private final VulkanContext context;
    private final RenderStageSchema schema;
    private final VulkanBuffer readback;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final int width;
    private final int height;
    private final int byteSize;
    private boolean recorded;
    private boolean submitted;
    private boolean destroyed;

    private ReplayStageCapturePass(
            VulkanContext context,
            RenderStageSchema schema,
            VulkanBuffer readback,
            long descriptorSetLayout,
            long descriptorPool,
            long descriptorSet,
            long pipelineLayout,
            long pipeline,
            int width,
            int height,
            int byteSize) {
        this.context = context;
        this.schema = schema;
        this.readback = readback;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.width = width;
        this.height = height;
        this.byteSize = byteSize;
    }

    public static ReplayStageCapturePass createRaw(
            VulkanContext context, RawWavefrontFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (!frame.usesShInputs()) {
            throw new IllegalArgumentException(
                    "NRD raw capture requires explicit SH direction images");
        }
        return create(
                context,
                RenderStageSchema.RAW_WAVEFRONT,
                RAW_SHADER,
                List.of(
                        frame.viewZ(),
                        frame.primaryPosition(),
                        frame.noisyDiffuse(),
                        frame.noisySpecular(),
                        frame.normalRoughness(),
                        frame.material(),
                        frame.specularMaterial(),
                        frame.diffuseDirection(),
                        frame.specularDirection(),
                        frame.reflectionPosition(),
                        frame.reflectionNoisyDiffuse(),
                        frame.reflectionNoisySpecular(),
                        frame.reflectionNormalRoughness(),
                        frame.reflectionMaterial(),
                        frame.reflectionSpecularMaterial(),
                        frame.reflectionDiffuseDirection(),
                        frame.reflectionSpecularDirection(),
                        frame.displayPosition()));
    }

    public static ReplayStageCapturePass createPreparedNrd(
            VulkanContext context, PreparedNrdFrame frame) {
        Objects.requireNonNull(frame, "frame");
        PreparedNrdFrame.Branch primary = frame.primary();
        PreparedNrdFrame.Branch reflection = frame.reflection();
        return create(
                context,
                RenderStageSchema.PREPARED_NRD,
                PREPARED_NRD_SHADER,
                List.of(
                        primary.motion(),
                        primary.normalRoughness(),
                        primary.viewZ(),
                        primary.noisyDiffuse(),
                        primary.noisySpecular(),
                        primary.noisyDiffuseSh1(),
                        primary.noisySpecularSh1(),
                        reflection.motion(),
                        reflection.normalRoughness(),
                        reflection.viewZ(),
                        reflection.noisyDiffuse(),
                        reflection.noisySpecular(),
                        reflection.noisyDiffuseSh1(),
                        reflection.noisySpecularSh1(),
                        frame.sunPenumbra(),
                        frame.fsrDepth(),
                        frame.fsrMotion()));
    }

    public static ReplayStageCapturePass createPostNrd(
            VulkanContext context, NrdCompositeFrame frame) {
        Objects.requireNonNull(frame, "frame");
        return create(
                context,
                RenderStageSchema.POST_NRD,
                POST_NRD_SHADER,
                List.of(
                        frame.color(),
                        frame.fsrReactive(),
                        frame.fsrTransparencyComposition()));
    }

    private static ReplayStageCapturePass create(
            VulkanContext context,
            RenderStageSchema schema,
            String shaderResource,
            List<VulkanImage> images) {
        Objects.requireNonNull(context, "context");
        if (images.size() != schema.signalCount()) {
            throw new IllegalArgumentException(
                    "Replay capture schema and image count differ");
        }
        int width = images.getFirst().width();
        int height = images.getFirst().height();
        for (int index = 0; index < images.size(); index++) {
            VulkanImage image = images.get(index);
            if (image.width() != width || image.height() != height) {
                throw new IllegalArgumentException(
                        "Replay capture images have different extents");
            }
            if (image.format() != vulkanFormat(schema.format(index))) {
                throw new IllegalArgumentException(
                        "Replay capture image "
                                + schema.signals().get(index)
                                + " has an unexpected format");
            }
        }
        long byteSizeLong = Math.multiplyExact(
                Math.multiplyExact(
                        Math.multiplyExact(
                                Math.multiplyExact((long) width, height),
                                schema.signalCount()),
                        CHANNELS),
                Integer.BYTES);
        if (byteSizeLong > Integer.MAX_VALUE
                || byteSizeLong > context.maxStorageBufferRange()) {
            throw new IllegalArgumentException(
                    "Replay capture exceeds the device storage-buffer range");
        }
        int byteSize = (int) byteSizeLong;
        VulkanBuffer readback = null;
        long setLayout = 0L;
        long descriptorPool = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            readback = context.createReadbackBuffer(
                    byteSize,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    "Prime " + schema + " replay readback");
            int bindingCount = images.size() + 1;
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
            for (int binding = 0; binding < images.size(); binding++) {
                bindings.get(binding)
                        .binding(binding)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1)
                        .stageFlags(COMPUTE_STAGE);
            }
            bindings.get(images.size())
                    .binding(images.size())
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(COMPUTE_STAGE);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateDescriptorSetLayout(
                            context.vkDevice(),
                            VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pBindings(bindings),
                            null,
                            pointer),
                    "create " + schema + " replay-capture descriptor layout");
            setLayout = pointer.get(0);

            VkPushConstantRange.Buffer pushRange =
                    VkPushConstantRange.calloc(1, stack)
                            .stageFlags(COMPUTE_STAGE)
                            .offset(0)
                            .size(PUSH_BYTES);
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
                    "create " + schema + " replay-capture pipeline layout");
            pipelineLayout = pointer.get(0);

            long shader = createShaderModule(
                    context, stack, shaderResource);
            try {
                VkPipelineShaderStageCreateInfo stage =
                        VkPipelineShaderStageCreateInfo.calloc(stack)
                                .sType$Default()
                                .stage(COMPUTE_STAGE)
                                .module(shader)
                                .pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer createInfo =
                        VkComputePipelineCreateInfo.calloc(1, stack);
                createInfo.get(0)
                        .sType$Default()
                        .stage(stage)
                        .layout(pipelineLayout);
                pointer.clear();
                VulkanContext.check(
                        VK12.vkCreateComputePipelines(
                                context.vkDevice(),
                                0L,
                                createInfo,
                                null,
                                pointer),
                        "create " + schema + " replay-capture pipeline");
                pipeline = pointer.get(0);
            } finally {
                VK12.vkDestroyShaderModule(
                        context.vkDevice(), shader, null);
            }

            VkDescriptorPoolSize.Buffer poolSizes =
                    VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(images.size());
            poolSizes.get(1)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1);
            pointer.clear();
            VulkanContext.check(
                    VK12.vkCreateDescriptorPool(
                            context.vkDevice(),
                            VkDescriptorPoolCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .maxSets(1)
                                    .pPoolSizes(poolSizes),
                            null,
                            pointer),
                    "create " + schema + " replay-capture descriptor pool");
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
                    "allocate " + schema + " replay-capture descriptor set");
            long descriptorSet = pointer.get(0);
            VkDescriptorImageInfo.Buffer imageInfos =
                    VkDescriptorImageInfo.calloc(images.size(), stack);
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(bindingCount, stack);
            for (int binding = 0; binding < images.size(); binding++) {
                imageInfos.get(binding)
                        .imageView(images.get(binding).view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(binding)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(binding)
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(
                                imageInfos.get(binding).address(), 1));
            }
            VkDescriptorBufferInfo.Buffer bufferInfo =
                    VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(readback.handle())
                            .offset(0L)
                            .range(byteSize);
            writes.get(images.size())
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(images.size())
                    .descriptorCount(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(bufferInfo);
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new ReplayStageCapturePass(
                    context,
                    schema,
                    readback,
                    setLayout,
                    descriptorPool,
                    descriptorSet,
                    pipelineLayout,
                    pipeline,
                    width,
                    height,
                    byteSize);
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) {
                VK12.vkDestroyDescriptorPool(
                        context.vkDevice(), descriptorPool, null);
            }
            if (pipeline != 0L) {
                VK12.vkDestroyPipeline(
                        context.vkDevice(), pipeline, null);
            }
            if (pipelineLayout != 0L) {
                VK12.vkDestroyPipelineLayout(
                        context.vkDevice(), pipelineLayout, null);
            }
            if (setLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(
                        context.vkDevice(), setLayout, null);
            }
            if (readback != null) {
                readback.destroy();
            }
            throw exception;
        }
    }

    private static int vulkanFormat(RenderPixelFormat format) {
        return switch (format) {
            case R8_UNORM -> VK12.VK_FORMAT_R8_UNORM;
            case R16_FLOAT -> VK12.VK_FORMAT_R16_SFLOAT;
            case R32_FLOAT -> VK12.VK_FORMAT_R32_SFLOAT;
            case RGB10_A2_UNORM -> VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case RGBA16_FLOAT -> VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
            case RGBA32_FLOAT -> VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
        };
    }

    public void recordAfterRayTrace(VkCommandBuffer commandBuffer) {
        record(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    public void recordAfterCompute(VkCommandBuffer commandBuffer) {
        record(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private void record(
            VkCommandBuffer commandBuffer,
            long sourceStage,
            long sourceAccess) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        if (this.recorded || this.submitted || this.destroyed) {
            throw new IllegalStateException(
                    "Replay stage capture cannot be recorded twice");
        }
        this.recorded = true;
        memoryBarrier(commandBuffer, sourceStage, sourceAccess);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.descriptorSet),
                    null);
            ByteBuffer push =
                    stack.malloc(PUSH_BYTES).order(ByteOrder.nativeOrder());
            push.putInt(0, this.width);
            push.putInt(Integer.BYTES, this.height);
            VK12.vkCmdPushConstants(
                    commandBuffer,
                    this.pipelineLayout,
                    COMPUTE_STAGE,
                    0,
                    push);
            VK12.vkCmdDispatch(
                    commandBuffer,
                    (this.width + LOCAL_SIZE - 1) / LOCAL_SIZE,
                    (this.height + LOCAL_SIZE - 1) / LOCAL_SIZE,
                    1);
        }
        // Raw and prepared logical versions may alias the same images. The observation must finish
        // before the next production compute stage is allowed to overwrite those images.
        memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT
                        | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    /**
     * Transfers ownership to a queue-completion callback.
     *
     * <p>Call immediately after submitting the command buffer containing this capture.
     */
    public CompletableFuture<CapturedRenderStage> submitted() {
        if (!this.recorded || this.submitted || this.destroyed) {
            throw new IllegalStateException(
                    "Replay stage capture was not recorded exactly once");
        }
        this.submitted = true;
        CompletableFuture<CapturedRenderStage> result =
                new CompletableFuture<>();
        this.context.afterSubmission(() -> {
            CapturedRenderStage captured = null;
            Throwable failure = null;
            try {
                captured = readCompleted();
            } catch (Throwable throwable) {
                failure = throwable;
            }
            try {
                destroy();
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                } else {
                    failure.addSuppressed(throwable);
                }
            }
            if (failure == null) {
                result.complete(captured);
            } else {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private CapturedRenderStage readCompleted() {
        byte[] bytes = this.readback.read(0L, this.byteSize);
        ByteBuffer input =
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int[] words = new int[this.byteSize / Integer.BYTES];
        for (int index = 0; index < words.length; index++) {
            words[index] = input.getInt();
        }
        return new CapturedRenderStage(
                this.schema, this.width, this.height, words);
    }

    private static void memoryBarrier(
            VkCommandBuffer commandBuffer,
            long sourceStage,
            long sourceAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier =
                    VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(sourceStage)
                    .srcAccessMask(sourceAccess)
                    .dstStageMask(
                            VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(
                            VK12.VK_ACCESS_SHADER_READ_BIT
                                    | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pMemoryBarriers(barrier));
        }
    }

    private static long createShaderModule(
            VulkanContext context,
            MemoryStack stack,
            String resourceName) {
        byte[] bytes;
        try (InputStream input =
                ReplayStageCapturePass.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing shader resource " + resourceName);
            }
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read shader resource " + resourceName,
                    exception);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateShaderModule(
                            context.vkDevice(),
                            VkShaderModuleCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pCode(code),
                            null,
                            pointer),
                    "create " + resourceName);
            return pointer.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.readback, failure);
        failure = ResourceCleanup.run(
                () -> VK12.vkDestroyDescriptorPool(
                        this.context.vkDevice(), this.descriptorPool, null),
                failure);
        failure = ResourceCleanup.run(
                () -> VK12.vkDestroyPipeline(
                        this.context.vkDevice(), this.pipeline, null),
                failure);
        failure = ResourceCleanup.run(
                () -> VK12.vkDestroyPipelineLayout(
                        this.context.vkDevice(), this.pipelineLayout, null),
                failure);
        failure = ResourceCleanup.run(
                () -> VK12.vkDestroyDescriptorSetLayout(
                        this.context.vkDevice(), this.descriptorSetLayout, null),
                failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }
}
