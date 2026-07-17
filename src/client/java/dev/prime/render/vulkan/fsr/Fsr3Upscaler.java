package dev.prime.render.vulkan.fsr;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.CameraDiscontinuity;
import dev.prime.render.FrameCamera;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.fsr.FsrDispatchValidator;
import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * FidelityFX FSR upscaling through AMD's signed Vulkan DLL.
 *
 * <p>The native library owns the complete FSR 3.1 pipeline and its temporal resources. Prime keeps
 * ownership of all external images and records the DLL dispatch into Minecraft's command buffer.
 * Only the final linear Rec.2020 to sRGB display transform remains a Prime shader, because that is
 * an application color-management boundary rather than part of FSR.
 */
public final class Fsr3Upscaler implements Destroyable {
    static final float NEAR_PLANE = 0.05F;

    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int DISPLAY_PUSH_SIZE = 12;
    private static final int DISPLAY_LOCAL_SIZE = 8;
    private static final String DISPLAY_SHADER = "/prime/shaders/fsr_display.comp.spv";

    private final int renderWidth;
    private final int renderHeight;
    private final int displayWidth;
    private final int displayHeight;
    private final FsrQualityMode qualityMode;
    private final VulkanImage sceneColor;
    private final VulkanImage inputMotion;
    private final VulkanImage inputDepth;
    private final VulkanImage reactiveMask;
    private final VulkanImage transparencyCompositionMask;
    private final VulkanImage linearOutput;
    private final FsrNative.Instance nativeInstance;
    private final DisplayPass displayPass;

    private FrameCamera previousCamera;
    private long previousSceneResetRevision = Long.MIN_VALUE;
    private long previousAtlasView;
    private long previousAtlasSampler;
    private int frameIndex;
    private long previousFrameNanos;
    private boolean resetRequested = true;
    private boolean initialized;
    private boolean destroyed;

    private Fsr3Upscaler(
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            FsrQualityMode qualityMode,
            VulkanImage sceneColor,
            VulkanImage inputMotion,
            VulkanImage inputDepth,
            VulkanImage reactiveMask,
            VulkanImage transparencyCompositionMask,
            VulkanImage linearOutput,
            FsrNative.Instance nativeInstance,
            DisplayPass displayPass) {
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.qualityMode = Objects.requireNonNull(qualityMode, "qualityMode");
        this.sceneColor = sceneColor;
        this.inputMotion = inputMotion;
        this.inputDepth = inputDepth;
        this.reactiveMask = reactiveMask;
        this.transparencyCompositionMask = transparencyCompositionMask;
        this.linearOutput = linearOutput;
        this.nativeInstance = nativeInstance;
        this.displayPass = displayPass;
    }

    public static Fsr3Upscaler create(
            VulkanContext context,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            FsrQualityMode qualityMode,
            VulkanImage sceneColor,
            VulkanImage inputMotion,
            VulkanImage inputDepth,
            VulkanImage reactiveMask,
            VulkanImage transparencyCompositionMask,
            VulkanImage displayOutput) {
        requireExtent(sceneColor, renderWidth, renderHeight, "scene color");
        requireExtent(inputMotion, renderWidth, renderHeight, "motion vectors");
        requireExtent(inputDepth, renderWidth, renderHeight, "depth");
        requireExtent(reactiveMask, renderWidth, renderHeight, "reactive mask");
        requireExtent(
                transparencyCompositionMask,
                renderWidth,
                renderHeight,
                "transparency/composition mask");
        requireExtent(displayOutput, displayWidth, displayHeight, "display output");

        VulkanImage linearOutput = null;
        FsrNative.Instance nativeInstance = null;
        DisplayPass displayPass = null;
        try {
            linearOutput = context.createImage2D(
                    displayWidth,
                    displayHeight,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                    "Prime FidelityFX linear HDR output");
            nativeInstance = FsrNative.create(
                    context, renderWidth, renderHeight, displayWidth, displayHeight);
            displayPass = DisplayPass.create(context, linearOutput, displayOutput);
            return new Fsr3Upscaler(
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    qualityMode,
                    sceneColor,
                    inputMotion,
                    inputDepth,
                    reactiveMask,
                    transparencyCompositionMask,
                    linearOutput,
                    nativeInstance,
                    displayPass);
        } catch (RuntimeException exception) {
            if (displayPass != null) {
                displayPass.destroy();
            }
            try {
                if (nativeInstance != null) {
                    nativeInstance.close();
                }
            } finally {
                if (linearOutput != null) {
                    linearOutput.destroy();
                }
            }
            throw exception;
        }
    }

    public int frameIndex() {
        return this.frameIndex;
    }

    public FsrSettings.Jitter jitter() {
        return this.qualityMode.jitter(this.frameIndex);
    }

    public void requestReset() {
        this.resetRequested = true;
    }

    public FrameToken beginFrame(
            FrameCamera camera,
            long sceneResetRevision,
            long atlasView,
            long atlasSampler) {
        this.requireOpen();
        Objects.requireNonNull(camera, "camera");
        boolean cameraCut = this.initialized
                && CameraDiscontinuity.isCut(this.previousCamera, camera);
        boolean reset = this.resetRequested
                || !this.initialized
                || cameraCut
                || sceneResetRevision != this.previousSceneResetRevision
                || atlasView != this.previousAtlasView
                || atlasSampler != this.previousAtlasSampler;
        int currentFrameIndex = reset ? 0 : this.frameIndex;
        FsrSettings.Jitter jitter = this.qualityMode.jitter(currentFrameIndex);
        long now = System.nanoTime();
        float deltaMilliseconds = this.previousFrameNanos == 0L
                ? 1000.0F / 60.0F
                : Math.min((now - this.previousFrameNanos) * 1.0e-6F, 1000.0F);
        return new FrameToken(
                this,
                camera,
                sceneResetRevision,
                atlasView,
                atlasSampler,
                currentFrameIndex,
                jitter,
                reset,
                cameraCut,
                deltaMilliseconds,
                now);
    }

    public void record(VkCommandBuffer commandBuffer, FrameToken token) {
        this.requireOpen();
        if (token.owner != this || token.recorded || token.submitted) {
            throw new IllegalArgumentException("FSR frame token does not belong to this recording");
        }
        token.recorded = true;
        FsrDispatchValidator.validate(
                this.renderWidth,
                this.renderHeight,
                this.displayWidth,
                this.displayHeight,
                token.jitter,
                FsrSettings.EXPOSURE,
                1.0F,
                1.0F);

        // NRD and the transparent composite write every imported input immediately before FSR.
        // The DLL owns its internal barriers, but this external producer/consumer dependency is
        // Prime's responsibility and must remain explicit.
        computeBarrier(commandBuffer);
        this.initializeLinearOutput(commandBuffer);
        FsrDebugView debugView = FsrSettings.debugView();
        this.nativeInstance.dispatch(
                commandBuffer,
                new FsrNative.Dispatch(
                        token.camera,
                        this.sceneColor,
                        this.inputDepth,
                        this.inputMotion,
                        this.reactiveMask,
                        this.transparencyCompositionMask,
                        this.linearOutput,
                        this.renderWidth,
                        this.renderHeight,
                        this.displayWidth,
                        this.displayHeight,
                        token.jitter,
                        token.deltaMilliseconds,
                        token.reset,
                        debugView));

        // FidelityFX restores imported resources to UNORDERED_ACCESS/GENERAL. Its output writes
        // still need an execution and memory dependency before Prime's display-transform shader
        // reads the same image.
        computeBarrier(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(DISPLAY_PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putInt(0, this.displayWidth);
            push.putInt(4, this.displayHeight);
            push.putInt(8, debugView == FsrDebugView.OFF ? 0 : 1);
            this.displayPass.record(commandBuffer, push);
        }
    }

    /** Must be called immediately after the command buffer containing {@code token} is submitted. */
    public void submitted(FrameToken token) {
        this.requireOpen();
        if (token.owner != this || !token.recorded || token.submitted) {
            throw new IllegalArgumentException("FSR frame token does not belong to this submission");
        }
        token.submitted = true;
        this.initialized = true;
        this.resetRequested = false;
        this.previousCamera = token.camera;
        this.previousSceneResetRevision = token.sceneResetRevision;
        this.previousAtlasView = token.atlasView;
        this.previousAtlasSampler = token.atlasSampler;
        this.previousFrameNanos = token.frameNanos;
        this.frameIndex = token.frameIndex + 1;
    }

    private void initializeLinearOutput(VkCommandBuffer commandBuffer) {
        if (this.linearOutput.initialized()) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                    .srcAccessMask(0L)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(this.linearOutput.image());
            barrier.get(0).subresourceRange()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pImageMemoryBarriers(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
        this.linearOutput.markInitialized();
    }

    private static void computeBarrier(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pMemoryBarriers(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private static void requireExtent(
            VulkanImage image, int expectedWidth, int expectedHeight, String name) {
        Objects.requireNonNull(image, name);
        if (image.width() != expectedWidth || image.height() != expectedHeight) {
            throw new IllegalArgumentException(
                    "FSR "
                            + name
                            + " extent is "
                            + image.width()
                            + "x"
                            + image.height()
                            + ", expected "
                            + expectedWidth
                            + "x"
                            + expectedHeight);
        }
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("FSR upscaler has been destroyed");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        this.displayPass.destroy();
        try {
            this.nativeInstance.close();
        } finally {
            this.linearOutput.destroy();
        }
    }

    /** Immutable temporal inputs chosen before ray generation plus submission bookkeeping. */
    public static final class FrameToken {
        private final Fsr3Upscaler owner;
        private final FrameCamera camera;
        private final long sceneResetRevision;
        private final long atlasView;
        private final long atlasSampler;
        private final int frameIndex;
        private final FsrSettings.Jitter jitter;
        private final boolean reset;
        private final boolean cameraCut;
        private final float deltaMilliseconds;
        private final long frameNanos;
        private boolean recorded;
        private boolean submitted;

        private FrameToken(
                Fsr3Upscaler owner,
                FrameCamera camera,
                long sceneResetRevision,
                long atlasView,
                long atlasSampler,
                int frameIndex,
                FsrSettings.Jitter jitter,
                boolean reset,
                boolean cameraCut,
                float deltaMilliseconds,
                long frameNanos) {
            this.owner = owner;
            this.camera = camera;
            this.sceneResetRevision = sceneResetRevision;
            this.atlasView = atlasView;
            this.atlasSampler = atlasSampler;
            this.frameIndex = frameIndex;
            this.jitter = jitter;
            this.reset = reset;
            this.cameraCut = cameraCut;
            this.deltaMilliseconds = deltaMilliseconds;
            this.frameNanos = frameNanos;
        }

        public int frameIndex() {
            return this.frameIndex;
        }

        public FsrSettings.Jitter jitter() {
            return this.jitter;
        }

        public boolean reset() {
            return this.reset;
        }

        public boolean cameraCut() {
            return this.cameraCut;
        }
    }

    private static final class DisplayPass implements Destroyable {
        private final VulkanContext context;
        private final long descriptorSetLayout;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long pipelineLayout;
        private final long pipeline;
        private final int dispatchX;
        private final int dispatchY;
        private boolean destroyed;

        private DisplayPass(
                VulkanContext context,
                long descriptorSetLayout,
                long descriptorPool,
                long descriptorSet,
                long pipelineLayout,
                long pipeline,
                int dispatchX,
                int dispatchY) {
            this.context = context;
            this.descriptorSetLayout = descriptorSetLayout;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.pipelineLayout = pipelineLayout;
            this.pipeline = pipeline;
            this.dispatchX = dispatchX;
            this.dispatchY = dispatchY;
        }

        private static DisplayPass create(
                VulkanContext context, VulkanImage linearInput, VulkanImage displayOutput) {
            long setLayout = 0L;
            long descriptorPool = 0L;
            long pipelineLayout = 0L;
            long pipeline = 0L;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorSetLayoutBinding.Buffer bindings =
                        VkDescriptorSetLayoutBinding.calloc(2, stack);
                bindings.get(0)
                        .binding(0)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .descriptorCount(1)
                        .stageFlags(COMPUTE_STAGE);
                bindings.get(1)
                        .binding(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1)
                        .stageFlags(COMPUTE_STAGE);
                VkDescriptorSetLayoutCreateInfo setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pBindings(bindings);
                LongBuffer pointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateDescriptorSetLayout(
                                context.vkDevice(), setInfo, null, pointer),
                        "create Prime display-transform descriptor layout");
                setLayout = pointer.get(0);

                VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(COMPUTE_STAGE)
                        .offset(0)
                        .size(DISPLAY_PUSH_SIZE);
                VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pSetLayouts(stack.longs(setLayout))
                        .pPushConstantRanges(pushRange);
                pointer.clear();
                VulkanContext.check(
                        VK12.vkCreatePipelineLayout(
                                context.vkDevice(), layoutInfo, null, pointer),
                        "create Prime display-transform pipeline layout");
                pipelineLayout = pointer.get(0);

                long shader = createResourceShaderModule(context, stack, DISPLAY_SHADER);
                try {
                    VkPipelineShaderStageCreateInfo stage =
                            VkPipelineShaderStageCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .stage(COMPUTE_STAGE)
                                    .module(shader)
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
                            "create Prime display-transform pipeline");
                    pipeline = pointer.get(0);
                } finally {
                    VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
                }

                VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
                poolSizes.get(0)
                        .type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .descriptorCount(1);
                poolSizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(1)
                        .pPoolSizes(poolSizes);
                pointer.clear();
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(context.vkDevice(), poolInfo, null, pointer),
                        "create Prime display-transform descriptor pool");
                descriptorPool = pointer.get(0);

                VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default()
                        .descriptorPool(descriptorPool)
                        .pSetLayouts(stack.longs(setLayout));
                pointer.clear();
                VulkanContext.check(
                        VK12.vkAllocateDescriptorSets(
                                context.vkDevice(), allocateInfo, pointer),
                        "allocate Prime display-transform descriptor set");
                long descriptorSet = pointer.get(0);

                VkDescriptorImageInfo.Buffer imageInfos = VkDescriptorImageInfo.calloc(2, stack);
                imageInfos.get(0)
                        .imageView(linearInput.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                imageInfos.get(1)
                        .imageView(displayOutput.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
                writes.get(0)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(0)
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(0).address(), 1));
                writes.get(1)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(1)
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(1).address(), 1));
                VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);

                return new DisplayPass(
                        context,
                        setLayout,
                        descriptorPool,
                        descriptorSet,
                        pipelineLayout,
                        pipeline,
                        divideRoundUp(displayOutput.width(), DISPLAY_LOCAL_SIZE),
                        divideRoundUp(displayOutput.height(), DISPLAY_LOCAL_SIZE));
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
                if (setLayout != 0L) {
                    VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), setLayout, null);
                }
                throw exception;
            }
        }

        private void record(VkCommandBuffer commandBuffer, ByteBuffer pushConstants) {
            VK12.vkCmdBindPipeline(
                    commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VK12.vkCmdBindDescriptorSets(
                        commandBuffer,
                        VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                        this.pipelineLayout,
                        0,
                        stack.longs(this.descriptorSet),
                        null);
            }
            VK12.vkCmdPushConstants(
                    commandBuffer,
                    this.pipelineLayout,
                    COMPUTE_STAGE,
                    0,
                    pushConstants);
            VK12.vkCmdDispatch(commandBuffer, this.dispatchX, this.dispatchY, 1);
        }

        @Override
        public void destroy() {
            if (this.destroyed) {
                return;
            }
            this.destroyed = true;
            VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
            VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(
                    this.context.vkDevice(), this.descriptorSetLayout, null);
        }
    }

    private static long createResourceShaderModule(
            VulkanContext context, MemoryStack stack, String resourceName) {
        byte[] bytes;
        try (InputStream input = Fsr3Upscaler.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing shader resource " + resourceName);
            }
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read shader resource " + resourceName, exception);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateShaderModule(context.vkDevice(), createInfo, null, pointer),
                    "create " + resourceName);
            return pointer.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static int divideRoundUp(int value, int divisor) {
        return Math.max(1, (value + divisor - 1) / divisor);
    }
}
