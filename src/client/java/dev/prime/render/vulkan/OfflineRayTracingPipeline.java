package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
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
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Offline-only full-path pipeline with a single 144-byte slot per pixel. */
public final class OfflineRayTracingPipeline implements Destroyable {
    private static final int HEAD_GROUP = 0;
    private static final int STEP_QUEUE_0_GROUP = 1;
    private static final int STEP_QUEUE_1_GROUP = 2;
    private static final int AREA_QUEUE_0_GROUP = 3;
    private static final int AREA_QUEUE_1_GROUP = 4;
    private static final int TAIL_QUEUE_0_GROUP = 5;
    private static final int TAIL_QUEUE_1_GROUP = 6;
    private static final int RESOLVE_GROUP = 7;
    static final int RAYGEN_GROUP_COUNT = 8;
    static final int RAYGEN_MODULE_COUNT = 5;
    static final int DISPATCH_COUNT = 2 * ShaderAbi.OFFLINE_WAVEFRONT_ROUNDS + 3;
    static final int DESCRIPTOR_BINDING_COUNT = 3;
    private static final int[] RAYGEN_MODULES = {0, 1, 1, 2, 2, 3, 3, 4};
    private static final int[] RAYGEN_CONTROLS = {0, 1, 257, 2, 258, 3, 259, 4};
    private static final long QUEUE_OFFSET_ALIGNMENT = 256L;
    private static final int ALL_RT_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

    private final VulkanContext context;
    private final TraceBackend backend;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final TraceProgram program;
    private VulkanBuffer wavefront;
    private Bindings bindings;
    private boolean destroyed;

    public OfflineRayTracingPipeline(VulkanContext context, TraceBackend backend) {
        this.context = context;
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        long setLayout = 0L;
        long layout = 0L;
        TraceProgram traceProgram = null;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                setLayout = createDescriptorSetLayout(context, stack);
                layout = createPipelineLayout(
                        context, stack, backend.bindings().descriptorSetLayout(), setLayout);
            }
            String suffix = context.capabilities().wavefrontShaderSuffix();
            String prefix = "/prime/shaders/offline_wavefront_";
            traceProgram = TraceProgram.create(
                    context,
                    layout,
                    new String[] {
                        prefix + "head" + suffix,
                        prefix + "step" + suffix,
                        prefix + "area" + suffix,
                        prefix + "tail" + suffix,
                        prefix + "resolve" + suffix
                    },
                    RAYGEN_MODULES,
                    RAYGEN_CONTROLS,
                    "Prime offline ray tracing pipeline",
                    "Prime offline shader binding table");
            this.descriptorSetLayout = setLayout;
            this.pipelineLayout = layout;
            this.program = traceProgram;
        } catch (RuntimeException exception) {
            if (traceProgram != null) {
                traceProgram.destroy();
            }
            if (layout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), layout, null);
            }
            if (setLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), setLayout, null);
            }
            throw exception;
        }
    }

    public void ensureDescriptors(
            long tlas,
            VulkanImage runningMean,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures,
            VulkanImage labPbrNormalAtlas,
            VulkanImage labPbrSpecularAtlas,
            AtmospherePipeline atmosphere) {
        this.backend.ensureSceneDescriptors(
                tlas,
                atlasView,
                atlasSampler,
                sceneTextures,
                labPbrNormalAtlas,
                labPbrSpecularAtlas,
                atmosphere);
        int width = runningMean.width();
        int height = runningMean.height();
        long requiredBytes = wavefrontBytes(width, height);
        validateRanges(width, height, this.context.maxStorageBufferRange());
        validateDispatch(
                width,
                height,
                this.context.capabilities().maxRayDispatchInvocationCount());
        VulkanBuffer candidate = this.wavefront;
        boolean replaces = candidate == null || candidate.size() != requiredBytes;
        if (replaces) {
            candidate = this.context.createBuffer(
                    requiredBytes,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false,
                    "Prime offline wavefront slots");
        }
        if (this.bindings != null
                && this.bindings.matches(runningMean.view(), candidate.handle())) {
            return;
        }
        Bindings replacement;
        try {
            replacement = Bindings.create(
                    this.context,
                    this.descriptorSetLayout,
                    runningMean,
                    candidate,
                    queueOffset(width, height));
        } catch (RuntimeException exception) {
            if (replaces) {
                candidate.destroy();
            }
            throw exception;
        }
        Bindings previousBindings = this.bindings;
        VulkanBuffer previousWavefront = this.wavefront;
        this.bindings = replacement;
        this.wavefront = candidate;
        if (previousBindings != null) {
            this.context.defer(previousBindings);
        }
        if (replaces && previousWavefront != null) {
            this.context.defer(previousWavefront);
        }
    }

    public long prepareFrame(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        return this.backend.prepareFrame(commandBuffer, initialization);
    }

    public void submitted(long token) {
        this.backend.submitted(token);
    }

    public void abandon(long token) {
        this.backend.abandon(token);
    }

    /** Releases descriptor bindings and wavefront backing after the device has become idle. */
    public void releaseSizedResourcesAfterIdle() {
        if (this.bindings != null) {
            this.bindings.destroy();
            this.bindings = null;
        }
        if (this.wavefront != null) {
            this.wavefront.destroy();
            this.wavefront = null;
        }
    }

    public void trace(
            VkCommandBuffer commandBuffer,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
        int width = input.width();
        int height = input.height();
        if (this.wavefront == null || this.wavefront.size() != wavefrontBytes(width, height)) {
            throw new IllegalStateException("Offline wavefront extent mismatch");
        }
        if (!this.backend.bindings().ready()) {
            throw new IllegalStateException("Trace-backend resources are not prepared");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.bind(commandBuffer, stack, RayTracingPushConstants.encode(stack, input, scene));
            long commandOffset = queueOffset(width, height);
            this.initializeQueues(commandBuffer, stack, commandOffset);
            this.trace(commandBuffer, stack, width, height, HEAD_GROUP);
            this.wavefrontBarrier(commandBuffer, stack);
            int sourceQueue = 0;
            for (int round = 0; round < ShaderAbi.OFFLINE_WAVEFRONT_ROUNDS; round++) {
                this.traceIndirect(
                        commandBuffer,
                        stack,
                        sourceQueue == 0 ? STEP_QUEUE_0_GROUP : STEP_QUEUE_1_GROUP,
                        commandOffset,
                        sourceQueue);
                this.wavefrontBarrier(commandBuffer, stack);
                this.traceIndirect(
                        commandBuffer,
                        stack,
                        sourceQueue == 0 ? AREA_QUEUE_0_GROUP : AREA_QUEUE_1_GROUP,
                        commandOffset,
                        sourceQueue);
                this.advanceQueue(commandBuffer, stack, commandOffset, sourceQueue);
                sourceQueue ^= 1;
            }
            this.traceIndirect(
                    commandBuffer,
                    stack,
                    sourceQueue == 0 ? TAIL_QUEUE_0_GROUP : TAIL_QUEUE_1_GROUP,
                    commandOffset,
                    sourceQueue);
            this.wavefrontBarrier(commandBuffer, stack);
            this.trace(commandBuffer, stack, width, height, RESOLVE_GROUP);
        }
    }

    private void bind(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            ByteBuffer pushConstants) {
        if (this.bindings == null) {
            throw new IllegalStateException("Offline descriptors have not been initialized");
        }
        VK12.vkCmdBindPipeline(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                this.program.pipeline);
        VK12.vkCmdBindDescriptorSets(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                this.pipelineLayout,
                0,
                stack.longs(this.backend.bindings().descriptorSet(), this.bindings.descriptorSet),
                null);
        VK12.vkCmdPushConstants(
                commandBuffer,
                this.pipelineLayout,
                ALL_RT_STAGES,
                0,
                pushConstants);
    }

    private void trace(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int width,
            int height,
            int group) {
        KHRRayTracingPipeline.vkCmdTraceRaysKHR(
                commandBuffer,
                this.raygen(stack, group),
                this.miss(stack),
                this.hit(stack),
                VkStridedDeviceAddressRegionKHR.calloc(stack),
                width,
                height,
                1);
    }

    private void traceIndirect(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int group,
            long commandOffset,
            int sourceQueue) {
        KHRRayTracingPipeline.vkCmdTraceRaysIndirectKHR(
                commandBuffer,
                this.raygen(stack, group),
                this.miss(stack),
                this.hit(stack),
                VkStridedDeviceAddressRegionKHR.calloc(stack),
                this.wavefront.deviceAddress()
                        + commandOffset
                        + (long) sourceQueue
                                * ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COMMAND_STRIDE);
    }

    private VkStridedDeviceAddressRegionKHR raygen(MemoryStack stack, int group) {
        return VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(this.program.raygenAddress(group))
                .stride(this.program.raygenRecordStride)
                .size(this.program.raygenRecordStride);
    }

    private VkStridedDeviceAddressRegionKHR miss(MemoryStack stack) {
        return VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(this.program.missAddress)
                .stride(this.program.recordStride)
                .size(this.program.recordStride * TraceProgram.MISS_GROUP_COUNT);
    }

    private VkStridedDeviceAddressRegionKHR hit(MemoryStack stack) {
        return VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(this.program.hitAddress)
                .stride(this.program.recordStride)
                .size(this.program.recordStride * TraceProgram.HIT_GROUP_COUNT);
    }

    private void initializeQueues(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long commandOffset) {
        this.wavefrontToTransferBarrier(commandBuffer, stack);
        ByteBuffer commands = stack.calloc(
                ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COUNT
                        * ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COMMAND_STRIDE);
        for (int queue = 0; queue < ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COUNT; queue++) {
            int offset = queue * ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COMMAND_STRIDE;
            commands.putInt(offset + Integer.BYTES, 1);
            commands.putInt(offset + 2 * Integer.BYTES, 1);
        }
        VK12.vkCmdUpdateBuffer(
                commandBuffer, this.wavefront.handle(), commandOffset, commands);
        this.transferToWavefrontBarrier(commandBuffer, stack);
    }

    private void wavefrontToTransferBarrier(
            VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.barrier(
                commandBuffer,
                stack,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
    }

    private void transferToWavefrontBarrier(
            VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.barrier(
                commandBuffer,
                stack,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT
                        | VK12.VK_ACCESS_SHADER_WRITE_BIT
                        | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
    }

    private void wavefrontBarrier(VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.barrier(
                commandBuffer,
                stack,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT
                        | VK12.VK_ACCESS_SHADER_WRITE_BIT
                        | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
    }

    private void advanceQueue(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long commandOffset,
            int sourceQueue) {
        this.barrier(
                commandBuffer,
                stack,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT
                        | VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT
                        | VK12.VK_ACCESS_SHADER_WRITE_BIT
                        | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT
                        | VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
        VK12.vkCmdFillBuffer(
                commandBuffer,
                this.wavefront.handle(),
                commandOffset
                        + (long) sourceQueue
                                * ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COMMAND_STRIDE,
                Integer.BYTES,
                0);
        this.transferToWavefrontBarrier(commandBuffer, stack);
    }

    private void barrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        VkMemoryBarrier2.Buffer barriers = VkMemoryBarrier2.calloc(1, stack);
        barriers.get(0)
                .sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage)
                .dstAccessMask(destinationAccess);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                commandBuffer,
                VkDependencyInfo.calloc(stack)
                        .sType$Default()
                        .pMemoryBarriers(barriers));
    }

    static long wavefrontBytes(int width, int height) {
        return Math.addExact(queueOffset(width, height), queueBytes(width, height));
    }

    static int raygenModule(int group) {
        return RAYGEN_MODULES[group];
    }

    static int raygenControl(int group) {
        return RAYGEN_CONTROLS[group];
    }

    static long queueOffset(int width, int height) {
        requireExtent(width, height);
        long pixels = Math.multiplyExact((long) width, (long) height);
        long bytes = Math.multiplyExact(
                Math.multiplyExact(
                        pixels, ShaderAbi.OFFLINE_WAVEFRONT_PATH_SLOTS_PER_PIXEL),
                ShaderAbi.OFFLINE_WAVEFRONT_PATH_RECORD_SIZE);
        return VulkanContext.alignUp(bytes, QUEUE_OFFSET_ALIGNMENT);
    }

    static long queueBytes(int width, int height) {
        requireExtent(width, height);
        long pixels = Math.multiplyExact((long) width, (long) height);
        long capacity = Math.multiplyExact(
                pixels, ShaderAbi.OFFLINE_WAVEFRONT_PATH_SLOTS_PER_PIXEL);
        long commands = Math.multiplyExact(
                ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COUNT,
                ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COMMAND_STRIDE);
        long indices = Math.multiplyExact(
                Math.multiplyExact(
                        ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COUNT, capacity),
                ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_INDEX_SIZE);
        return Math.addExact(commands, indices);
    }

    static void validateRanges(int width, int height, long maximumRange) {
        if (queueOffset(width, height) > maximumRange
                || queueBytes(width, height) > maximumRange) {
            throw new IllegalStateException(
                    "Offline wavefront descriptor exceeds maxStorageBufferRange");
        }
    }

    static void validateDispatch(int width, int height, int maximumInvocations) {
        long capacity = Math.multiplyExact(
                Math.multiplyExact((long) width, (long) height),
                ShaderAbi.OFFLINE_WAVEFRONT_PATH_SLOTS_PER_PIXEL);
        if (capacity > Integer.toUnsignedLong(maximumInvocations)) {
            throw new IllegalStateException("Offline wavefront queue exceeds dispatch capacity");
        }
    }

    private static void requireExtent(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Wavefront extent must be positive");
        }
    }

    private static long createDescriptorSetLayout(
            VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(DESCRIPTOR_BINDING_COUNT, stack);
        bindings.get(0)
                .binding(ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(1)
                .binding(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(2)
                .binding(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(
                        context.vkDevice(), info, null, pointer),
                "create offline trace descriptor layout");
        return pointer.get(0);
    }

    private static long createPipelineLayout(
            VulkanContext context,
            MemoryStack stack,
            long sharedSetLayout,
            long offlineSetLayout) {
        VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
        range.get(0).stageFlags(ALL_RT_STAGES).offset(0).size(ShaderAbi.PUSH_CONSTANT_SIZE);
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(sharedSetLayout, offlineSetLayout))
                .pPushConstantRanges(range);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreatePipelineLayout(context.vkDevice(), info, null, pointer),
                "create offline trace pipeline layout");
        return pointer.get(0);
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            if (this.bindings != null) {
                this.bindings.destroy();
                this.bindings = null;
            }
            if (this.wavefront != null) {
                this.wavefront.destroy();
                this.wavefront = null;
            }
            this.program.destroy();
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(
                    this.context.vkDevice(), this.descriptorSetLayout, null);
        }
    }

    private static final class Bindings implements Destroyable {
        private final VulkanContext context;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long runningMean;
        private final long wavefront;
        private boolean destroyed;

        private Bindings(
                VulkanContext context,
                long descriptorPool,
                long descriptorSet,
                long runningMean,
                long wavefront) {
            this.context = context;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.runningMean = runningMean;
            this.wavefront = wavefront;
        }

        private static Bindings create(
                VulkanContext context,
                long layout,
                VulkanImage runningMean,
                VulkanBuffer wavefront,
                long queueOffset) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
                sizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(1)
                        .pPoolSizes(sizes);
                LongBuffer poolPointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(
                                context.vkDevice(), poolInfo, null, poolPointer),
                        "create offline trace descriptor pool");
                long pool = poolPointer.get(0);
                try {
                    VkDescriptorSetAllocateInfo allocation =
                            VkDescriptorSetAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .descriptorPool(pool)
                                    .pSetLayouts(stack.longs(layout));
                    LongBuffer setPointer = stack.mallocLong(1);
                    VulkanContext.check(
                            VK12.vkAllocateDescriptorSets(
                                    context.vkDevice(), allocation, setPointer),
                            "allocate offline trace descriptor set");
                    long set = setPointer.get(0);
                    VkDescriptorImageInfo imageInfo = VkDescriptorImageInfo.calloc(stack)
                            .imageView(runningMean.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorBufferInfo.Buffer bufferInfos =
                            VkDescriptorBufferInfo.calloc(2, stack);
                    bufferInfos.get(0)
                            .buffer(wavefront.handle())
                            .offset(0L)
                            .range(queueOffset);
                    bufferInfos.get(1)
                            .buffer(wavefront.handle())
                            .offset(queueOffset)
                            .range(wavefront.size() - queueOffset);
                    VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
                    writes.get(0)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfo.address(), 1));
                    writes.get(1)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(0).address(), 1));
                    writes.get(2)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(1).address(), 1));
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new Bindings(
                            context,
                            pool,
                            set,
                            runningMean.view(),
                            wavefront.handle());
                } catch (RuntimeException exception) {
                    VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
                    throw exception;
                }
            }
        }

        private boolean matches(long candidateRunningMean, long candidateWavefront) {
            return this.runningMean == candidateRunningMean
                    && this.wavefront == candidateWavefront;
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                VK12.vkDestroyDescriptorPool(
                        this.context.vkDevice(), this.descriptorPool, null);
            }
        }
    }
}
