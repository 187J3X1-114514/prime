package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.PerformanceIntegratorSettings;
import dev.prime.render.RealtimeIntegratorMode;
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

/** Realtime-only pipeline, queue ABI and reconstruction outputs. */
public final class RealtimeRayTracingPipeline implements RealtimeIntegratorPipeline {
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
    static final int DISPATCH_COUNT = 2 * ShaderAbi.WAVEFRONT_ROUNDS + 3;
    static final int DESCRIPTOR_BINDING_COUNT = 25;
    private static final int[] RAYGEN_MODULES = {
        0, 1, 1, 2, 2, 3, 3, 4
    };
    private static final int[] RAYGEN_CONTROLS = {
        0, 1, 257, 4, 260, 2, 258, 3
    };
    private static final int STORAGE_IMAGE_DESCRIPTOR_COUNT = 23;
    private static final long QUEUE_OFFSET_ALIGNMENT = 256L;
    private static final int ALL_RT_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

    private final VulkanContext context;
    private final TraceBackend backend;
    private final boolean performance;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final TraceProgram program;
    private VulkanBuffer wavefront;
    private OutputBindings bindings;
    private int lastRecordedPassCount;
    private boolean destroyed;

    public RealtimeRayTracingPipeline(VulkanContext context, TraceBackend backend) {
        this(context, backend, false);
    }

    RealtimeRayTracingPipeline(
            VulkanContext context,
            TraceBackend backend,
            boolean performance) {
        this.context = context;
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        this.performance = performance;
        long setLayout = 0L;
        long layout = 0L;
        TraceProgram traceProgram = null;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                setLayout = createDescriptorSetLayout(context, stack);
                layout = createPipelineLayout(
                        context, stack, backend.bindings().descriptorSetLayout(), setLayout);
            }
            boolean ser = context.capabilities().invocationReorderSupported()
                    && context.capabilities().wavefrontSubgroupSupported();
            String suffix = ser ? "_ser.rgen.spv" : ".rgen.spv";
            String prefix = performance
                    ? "/prime/shaders/lightweight_wavefront_"
                    : "/prime/shaders/realtime_wavefront_";
            String[] raygenShaders = performance
                    ? new String[] {
                        prefix + "head" + suffix,
                        prefix + "step" + suffix,
                        prefix + "resolve" + suffix
                    }
                    : new String[] {
                        prefix + "head" + suffix,
                        prefix + "step" + suffix,
                        prefix + "area" + suffix,
                        prefix + "tail" + suffix,
                        prefix + "resolve" + suffix
                    };
            int[] raygenModules = performance
                    ? PerformanceRayTracingPipeline.RAYGEN_MODULES
                    : RAYGEN_MODULES;
            int[] raygenControls = performance
                    ? PerformanceRayTracingPipeline.RAYGEN_CONTROLS
                    : RAYGEN_CONTROLS;
            traceProgram = TraceProgram.create(
                    context,
                    layout,
                    raygenShaders,
                    raygenModules,
                    raygenControls,
                    performance
                            ? "Prime performance ray tracing pipeline"
                            : "Prime realtime ray tracing pipeline",
                    performance
                            ? "Prime performance shader binding table"
                            : "Prime realtime shader binding table");
            this.descriptorSetLayout = setLayout;
            this.pipelineLayout = layout;
            this.program = traceProgram;
            this.lastRecordedPassCount = performance
                    ? PerformanceRayTracingPipeline.MAXIMUM_DISPATCH_COUNT
                    : DISPATCH_COUNT;
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

    @Override
    public RealtimeIntegratorMode mode() {
        return this.performance
                ? RealtimeIntegratorMode.PERFORMANCE
                : RealtimeIntegratorMode.QUALITY;
    }

    @Override
    public int passCount() {
        return this.lastRecordedPassCount;
    }

    @Override
    public long sizedResourceBytes() {
        return this.wavefront == null ? 0L : this.wavefront.size();
    }

    public void ensureDescriptors(
            long tlas,
            VulkanImage stableRadiance,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures,
            VulkanImage labPbrNormalAtlas,
            VulkanImage labPbrSpecularAtlas,
            AtmospherePipeline atmosphere,
            RawWavefrontFrame signals) {
        this.backend.ensureSceneDescriptors(
                tlas,
                atlasView,
                atlasSampler,
                sceneTextures,
                labPbrNormalAtlas,
                labPbrSpecularAtlas,
                atmosphere);
        int width = signals.noisyDiffuse().width();
        int height = signals.noisyDiffuse().height();
        long requiredBytes = this.wavefrontBytesForMode(width, height);
        this.validateRangesForMode(width, height, this.context.maxStorageBufferRange());
        this.validateDispatchForMode(
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
                    this.performance
                            ? "Prime performance wavefront slots"
                            : "Prime realtime wavefront slots");
        }
        if (this.bindings != null
                && this.bindings.matches(stableRadiance, signals, candidate.handle())) {
            return;
        }
        OutputBindings replacement;
        try {
            replacement = OutputBindings.create(
                    this.context,
                    this.descriptorSetLayout,
                    stableRadiance,
                    signals,
                    candidate,
                    this.queueOffsetForMode(width, height));
        } catch (RuntimeException exception) {
            if (replaces) {
                candidate.destroy();
            }
            throw exception;
        }
        OutputBindings previousBindings = this.bindings;
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
        if (this.wavefront == null
                || this.wavefront.size() != this.wavefrontBytesForMode(width, height)) {
            throw new IllegalStateException("Realtime wavefront extent mismatch");
        }
        if (!this.backend.bindings().ready()) {
            throw new IllegalStateException("Trace-backend resources are not prepared");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.bind(commandBuffer, stack, RayTracingPushConstants.encode(stack, input, scene));
            long commandOffset = this.queueCommandOffsetForMode(width, height);
            this.initializeQueues(commandBuffer, stack, commandOffset);
            this.trace(commandBuffer, stack, width, height, HEAD_GROUP);
            this.wavefrontBarrier(commandBuffer, stack);
            int sourceQueue = 0;
            int rounds = this.performance
                    ? performanceRounds(input.maximumBounces())
                    : ShaderAbi.WAVEFRONT_ROUNDS;
            this.lastRecordedPassCount = this.performance
                    ? rounds + 2
                    : DISPATCH_COUNT;
            for (int round = 0; round < rounds; round++) {
                this.traceIndirect(
                        commandBuffer,
                        stack,
                        stepGroup(sourceQueue),
                        commandOffset,
                        sourceQueue);
                this.wavefrontBarrier(commandBuffer, stack);
                if (!this.performance) {
                    this.traceIndirect(
                            commandBuffer,
                            stack,
                            areaGroup(sourceQueue),
                            commandOffset,
                            sourceQueue);
                }
                this.advanceQueue(commandBuffer, stack, commandOffset, sourceQueue);
                sourceQueue ^= 1;
            }
            if (this.performance) {
                this.trace(commandBuffer, stack, width, height, 3);
            } else {
                this.traceIndirect(
                        commandBuffer,
                        stack,
                        tailGroup(sourceQueue),
                        commandOffset,
                        sourceQueue);
                this.wavefrontBarrier(commandBuffer, stack);
                this.trace(commandBuffer, stack, width, height, RESOLVE_GROUP);
            }
        }
    }

    static int performanceRounds(int maximumScatters) {
        return PerformanceIntegratorSettings.validateScatters(maximumScatters) - 1;
    }

    private void bind(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            ByteBuffer pushConstants) {
        if (this.bindings == null) {
            throw new IllegalStateException("Realtime descriptors have not been initialized");
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
        VkStridedDeviceAddressRegionKHR raygen =
                VkStridedDeviceAddressRegionKHR.calloc(stack)
                        .deviceAddress(this.program.raygenAddress(group))
                        .stride(this.program.raygenRecordStride)
                        .size(this.program.raygenRecordStride);
        VkStridedDeviceAddressRegionKHR miss =
                VkStridedDeviceAddressRegionKHR.calloc(stack)
                        .deviceAddress(this.program.missAddress)
                        .stride(this.program.recordStride)
                        .size(this.program.recordStride * TraceProgram.MISS_GROUP_COUNT);
        VkStridedDeviceAddressRegionKHR hit =
                VkStridedDeviceAddressRegionKHR.calloc(stack)
                        .deviceAddress(this.program.hitAddress)
                        .stride(this.program.recordStride)
                        .size(this.program.recordStride * TraceProgram.HIT_GROUP_COUNT);
        KHRRayTracingPipeline.vkCmdTraceRaysKHR(
                commandBuffer,
                raygen,
                miss,
                hit,
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
        VkStridedDeviceAddressRegionKHR raygen =
                VkStridedDeviceAddressRegionKHR.calloc(stack)
                        .deviceAddress(this.program.raygenAddress(group))
                        .stride(this.program.raygenRecordStride)
                        .size(this.program.raygenRecordStride);
        VkStridedDeviceAddressRegionKHR miss =
                VkStridedDeviceAddressRegionKHR.calloc(stack)
                        .deviceAddress(this.program.missAddress)
                        .stride(this.program.recordStride)
                        .size(this.program.recordStride * TraceProgram.MISS_GROUP_COUNT);
        VkStridedDeviceAddressRegionKHR hit =
                VkStridedDeviceAddressRegionKHR.calloc(stack)
                        .deviceAddress(this.program.hitAddress)
                        .stride(this.program.recordStride)
                        .size(this.program.recordStride * TraceProgram.HIT_GROUP_COUNT);
        long indirectAddress = this.wavefront.deviceAddress()
                + commandOffset
                + (long) sourceQueue * ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE;
        KHRRayTracingPipeline.vkCmdTraceRaysIndirectKHR(
                commandBuffer,
                raygen,
                miss,
                hit,
                VkStridedDeviceAddressRegionKHR.calloc(stack),
                indirectAddress);
    }

    private void initializeQueues(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long commandOffset) {
        this.wavefrontToTransferBarrier(commandBuffer, stack);
        ByteBuffer commands = stack.calloc(
                ShaderAbi.WAVEFRONT_QUEUE_COUNT
                        * ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE);
        for (int queue = 0; queue < ShaderAbi.WAVEFRONT_QUEUE_COUNT; queue++) {
            int offset = queue * ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE;
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
                        + (long) sourceQueue * ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE,
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

    static long performanceWavefrontBytes(int width, int height) {
        return Math.addExact(
                performanceQueueOffset(width, height),
                performanceQueueBytes(width, height));
    }

    private long wavefrontBytesForMode(int width, int height) {
        return this.performance
                ? performanceWavefrontBytes(width, height)
                : wavefrontBytes(width, height);
    }

    static int raygenModule(int group) {
        return RAYGEN_MODULES[group];
    }

    static int raygenControl(int group) {
        return RAYGEN_CONTROLS[group];
    }

    private static int stepGroup(int queue) {
        return switch (queue) {
            case 0 -> STEP_QUEUE_0_GROUP;
            case 1 -> STEP_QUEUE_1_GROUP;
            default -> throw new IllegalArgumentException("Invalid realtime wavefront queue");
        };
    }

    private static int areaGroup(int queue) {
        return switch (queue) {
            case 0 -> AREA_QUEUE_0_GROUP;
            case 1 -> AREA_QUEUE_1_GROUP;
            default -> throw new IllegalArgumentException("Invalid realtime wavefront queue");
        };
    }

    private static int tailGroup(int queue) {
        return switch (queue) {
            case 0 -> TAIL_QUEUE_0_GROUP;
            case 1 -> TAIL_QUEUE_1_GROUP;
            default -> throw new IllegalArgumentException("Invalid realtime wavefront queue");
        };
    }

    static long queueOffset(int width, int height) {
        requireExtent(width, height);
        long pixels = Math.multiplyExact((long) width, (long) height);
        long bytes = Math.multiplyExact(
                Math.multiplyExact(pixels, ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL),
                ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
        return VulkanContext.alignUp(bytes, QUEUE_OFFSET_ALIGNMENT);
    }

    static long queueBytes(int width, int height) {
        requireExtent(width, height);
        long pixels = Math.multiplyExact((long) width, (long) height);
        long capacity = Math.multiplyExact(
                pixels, ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL);
        long areas = Math.multiplyExact(pixels, ShaderAbi.WAVEFRONT_AREA_RECORD_SIZE);
        long commands = Math.multiplyExact(
                ShaderAbi.WAVEFRONT_QUEUE_COUNT,
                ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE);
        long indices = Math.multiplyExact(
                Math.multiplyExact(ShaderAbi.WAVEFRONT_QUEUE_COUNT, capacity),
                ShaderAbi.WAVEFRONT_QUEUE_INDEX_SIZE);
        return Math.addExact(areas, Math.addExact(commands, indices));
    }

    static long queueCommandOffset(int width, int height) {
        long pixels = Math.multiplyExact((long) width, (long) height);
        return Math.addExact(
                queueOffset(width, height),
                Math.multiplyExact(pixels, ShaderAbi.WAVEFRONT_AREA_RECORD_SIZE));
    }

    static long performanceQueueOffset(int width, int height) {
        requireExtent(width, height);
        long pixels = Math.multiplyExact((long) width, (long) height);
        long bytes = Math.multiplyExact(
                Math.multiplyExact(
                        pixels,
                        ShaderAbi.LIGHTWEIGHT_WAVEFRONT_PATH_SLOTS_PER_PIXEL),
                ShaderAbi.LIGHTWEIGHT_WAVEFRONT_PATH_RECORD_SIZE);
        return VulkanContext.alignUp(bytes, QUEUE_OFFSET_ALIGNMENT);
    }

    static long performanceQueueBytes(int width, int height) {
        requireExtent(width, height);
        long pixels = Math.multiplyExact((long) width, (long) height);
        long capacity = Math.multiplyExact(
                pixels,
                ShaderAbi.LIGHTWEIGHT_WAVEFRONT_PATH_SLOTS_PER_PIXEL);
        long commands = Math.multiplyExact(
                ShaderAbi.LIGHTWEIGHT_WAVEFRONT_QUEUE_COUNT,
                ShaderAbi.LIGHTWEIGHT_WAVEFRONT_QUEUE_COMMAND_STRIDE);
        long indices = Math.multiplyExact(
                Math.multiplyExact(
                        ShaderAbi.LIGHTWEIGHT_WAVEFRONT_QUEUE_COUNT,
                        capacity),
                ShaderAbi.LIGHTWEIGHT_WAVEFRONT_QUEUE_INDEX_SIZE);
        return Math.addExact(commands, indices);
    }

    static long performanceQueueCommandOffset(int width, int height) {
        return performanceQueueOffset(width, height);
    }

    private long queueOffsetForMode(int width, int height) {
        return this.performance
                ? performanceQueueOffset(width, height)
                : queueOffset(width, height);
    }

    private long queueCommandOffsetForMode(int width, int height) {
        return this.performance
                ? performanceQueueCommandOffset(width, height)
                : queueCommandOffset(width, height);
    }

    static void validateRanges(int width, int height, long maximumRange) {
        long paths = queueOffset(width, height);
        long queue = queueBytes(width, height);
        if (paths > maximumRange || queue > maximumRange) {
            throw new IllegalStateException(
                    "Realtime wavefront descriptor exceeds maxStorageBufferRange");
        }
    }

    static void validateDispatch(int width, int height, int maximumInvocations) {
        long capacity = Math.multiplyExact(
                Math.multiplyExact((long) width, (long) height),
                ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL);
        if (capacity > Integer.toUnsignedLong(maximumInvocations)) {
            throw new IllegalStateException("Realtime wavefront queue exceeds dispatch capacity");
        }
    }

    static void validatePerformanceRanges(
            int width, int height, long maximumRange) {
        long paths = performanceQueueOffset(width, height);
        long queue = performanceQueueBytes(width, height);
        if (paths > maximumRange || queue > maximumRange) {
            throw new IllegalStateException(
                    "Performance wavefront descriptor exceeds maxStorageBufferRange");
        }
    }

    static void validatePerformanceDispatch(
            int width, int height, int maximumInvocations) {
        long capacity = Math.multiplyExact(
                Math.multiplyExact((long) width, (long) height),
                ShaderAbi.LIGHTWEIGHT_WAVEFRONT_PATH_SLOTS_PER_PIXEL);
        if (capacity > Integer.toUnsignedLong(maximumInvocations)) {
            throw new IllegalStateException(
                    "Performance wavefront queue exceeds dispatch capacity");
        }
    }

    private void validateRangesForMode(
            int width, int height, long maximumRange) {
        if (this.performance) {
            validatePerformanceRanges(width, height, maximumRange);
        } else {
            validateRanges(width, height, maximumRange);
        }
    }

    private void validateDispatchForMode(
            int width, int height, int maximumInvocations) {
        if (this.performance) {
            validatePerformanceDispatch(width, height, maximumInvocations);
        } else {
            validateDispatch(width, height, maximumInvocations);
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
        int cursor = 0;
        int[] imageBindings = imageBindings();
        for (int binding : imageBindings) {
            bindings.get(cursor++)
                    .binding(binding)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        bindings.get(cursor++)
                .binding(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(cursor)
                .binding(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE)
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
                "create realtime trace descriptor layout");
        return pointer.get(0);
    }

    private static long createPipelineLayout(
            VulkanContext context,
            MemoryStack stack,
            long sharedSetLayout,
            long realtimeSetLayout) {
        VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
        range.get(0).stageFlags(ALL_RT_STAGES).offset(0).size(ShaderAbi.PUSH_CONSTANT_SIZE);
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(sharedSetLayout, realtimeSetLayout))
                .pPushConstantRanges(range);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreatePipelineLayout(context.vkDevice(), info, null, pointer),
                "create realtime trace pipeline layout");
        return pointer.get(0);
    }

    private static int[] imageBindings() {
        return new int[] {
            ShaderAbi.DESCRIPTOR_STABLE_RADIANCE,
            ShaderAbi.DESCRIPTOR_NRD_NOISY_DIFFUSE,
            ShaderAbi.DESCRIPTOR_NRD_NOISY_SPECULAR,
            ShaderAbi.DESCRIPTOR_NRD_NORMAL_ROUGHNESS,
            ShaderAbi.DESCRIPTOR_NRD_VIEW_Z,
            ShaderAbi.DESCRIPTOR_WAVEFRONT_TRANSPORT_METADATA,
            ShaderAbi.DESCRIPTOR_NRD_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_SPECULAR_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_PRIMARY_POSITION,
            ShaderAbi.DESCRIPTOR_NRD_DIFFUSE_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_SPECULAR_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_NOISY_DIFFUSE,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_NOISY_SPECULAR,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_NORMAL_ROUGHNESS,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_SPECULAR_MATERIAL,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_POSITION,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_DIFFUSE_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_REFLECTION_SPECULAR_DIRECTION,
            ShaderAbi.DESCRIPTOR_NRD_DISPLAY_POSITION,
            ShaderAbi.DESCRIPTOR_NRD_SUN_LIGHTING,
            ShaderAbi.DESCRIPTOR_NRD_SUN_PENUMBRA,
            ShaderAbi.DESCRIPTOR_RAW_NUMERICAL_DIAGNOSTIC
        };
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

    private static final class OutputBindings implements Destroyable {
        private final VulkanContext context;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long stableRadiance;
        private final long[] images;
        private final long wavefront;
        private boolean destroyed;

        private OutputBindings(
                VulkanContext context,
                long descriptorPool,
                long descriptorSet,
                long stableRadiance,
                long[] images,
                long wavefront) {
            this.context = context;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.stableRadiance = stableRadiance;
            this.images = images.clone();
            this.wavefront = wavefront;
        }

        private static OutputBindings create(
                VulkanContext context,
                long layout,
                VulkanImage stableRadiance,
                RawWavefrontFrame signals,
                VulkanBuffer wavefront,
                long queueOffset) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(STORAGE_IMAGE_DESCRIPTOR_COUNT);
                sizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(2);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(1)
                        .pPoolSizes(sizes);
                LongBuffer poolPointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(
                                context.vkDevice(), poolInfo, null, poolPointer),
                        "create realtime trace descriptor pool");
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
                            "allocate realtime trace descriptor set");
                    long set = setPointer.get(0);
                    VulkanImage[] images = outputImages(stableRadiance, signals);
                    long[] views = new long[images.length];
                    VkDescriptorImageInfo.Buffer imageInfos =
                            VkDescriptorImageInfo.calloc(images.length, stack);
                    for (int index = 0; index < images.length; index++) {
                        views[index] = images[index].view();
                        imageInfos.get(index)
                                .imageView(views[index])
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    }
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
                    VkWriteDescriptorSet.Buffer writes =
                            VkWriteDescriptorSet.calloc(DESCRIPTOR_BINDING_COUNT, stack);
                    int[] imageBindings = imageBindings();
                    for (int index = 0; index < imageBindings.length; index++) {
                        writes.get(index)
                                .sType$Default()
                                .dstSet(set)
                                .dstBinding(imageBindings[index])
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(
                                        imageInfos.get(index).address(), 1));
                    }
                    writes.get(imageBindings.length)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(0).address(), 1));
                    writes.get(imageBindings.length + 1)
                            .sType$Default()
                            .dstSet(set)
                            .dstBinding(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(1).address(), 1));
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new OutputBindings(
                            context,
                            pool,
                            set,
                            stableRadiance.view(),
                            views,
                            wavefront.handle());
                } catch (RuntimeException exception) {
                    VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
                    throw exception;
                }
            }
        }

        private boolean matches(
                VulkanImage candidateStableRadiance,
                RawWavefrontFrame signals,
                long candidateWavefront) {
            if (this.stableRadiance != candidateStableRadiance.view()
                    || this.wavefront != candidateWavefront) {
                return false;
            }
            VulkanImage[] candidates = outputImages(candidateStableRadiance, signals);
            if (this.images.length != candidates.length) {
                return false;
            }
            for (int index = 0; index < candidates.length; index++) {
                if (this.images[index] != candidates[index].view()) {
                    return false;
                }
            }
            return true;
        }

        private static VulkanImage[] outputImages(
                VulkanImage stableRadiance, RawWavefrontFrame signals) {
            return new VulkanImage[] {
                stableRadiance,
                signals.noisyDiffuse(),
                signals.noisySpecular(),
                signals.normalRoughness(),
                signals.viewZ(),
                signals.transportMetadata(),
                signals.material(),
                signals.specularMaterial(),
                signals.primaryPosition(),
                signals.diffuseDirection(),
                signals.specularDirection(),
                signals.reflectionNoisyDiffuse(),
                signals.reflectionNoisySpecular(),
                signals.reflectionNormalRoughness(),
                signals.reflectionMaterial(),
                signals.reflectionSpecularMaterial(),
                signals.reflectionPosition(),
                signals.reflectionDiffuseDirection(),
                signals.reflectionSpecularDirection(),
                signals.displayPosition(),
                signals.sunLighting(),
                signals.sunPenumbra(),
                signals.rawNumericalDiagnostic()
            };
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
