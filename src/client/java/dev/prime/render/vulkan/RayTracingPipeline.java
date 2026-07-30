package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.PrimeClient;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.terrain.TerrainScene;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRDeferredHostOperations;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.EXTOpacityMicromap;
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
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

public final class RayTracingPipeline implements Destroyable {
    private static final int WAVEFRONT_SCREENSHOT_RESOLVE_GROUP = 0;
    private static final int WAVEFRONT_HEAD_GROUP = 1;
    private static final int WAVEFRONT_STEP_QUEUE_0_GROUP = 2;
    private static final int WAVEFRONT_STEP_QUEUE_1_GROUP = 3;
    private static final int WAVEFRONT_TRANSITION_QUEUE_0_GROUP = 4;
    private static final int WAVEFRONT_TRANSITION_QUEUE_1_GROUP = 5;
    private static final int WAVEFRONT_TAIL_QUEUE_0_GROUP = 6;
    private static final int WAVEFRONT_TAIL_QUEUE_1_GROUP = 7;
    private static final int WAVEFRONT_RESOLVE_GROUP = 8;
    static final int RAYGEN_GROUP_COUNT = 9;
    static final int RAYGEN_MODULE_COUNT = 4;
    static final int RAYGEN_SHADER_STAGE_COUNT = 4;
    static final int MISS_GROUP_COUNT = 2;
    static final int HIT_GROUP_COUNT = 6;
    static final int FIXED_SHADER_MODULE_COUNT = 6;
    static final int ANY_HIT_SHADER_STAGE_COUNT = 2;
    static final int DESCRIPTOR_BINDING_COUNT = 37;
    static final int STORAGE_IMAGE_DESCRIPTOR_COUNT = 29;
    static final int RAYGEN_RECORD_DATA_SIZE = Integer.BYTES;
    private static final long WAVEFRONT_QUEUE_OFFSET_ALIGNMENT = 256L;
    static final int WAVEFRONT_STEP_DISPATCH_COUNT = ShaderAbi.WAVEFRONT_ROUNDS - 1;
    static final int WAVEFRONT_DISPATCH_COUNT = ShaderAbi.WAVEFRONT_ROUNDS + 3;
    private static final int GROUP_COUNT = RAYGEN_GROUP_COUNT + MISS_GROUP_COUNT + HIT_GROUP_COUNT;
    private static final int ALL_RT_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
    static final int BLOCK_ATLAS_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
    static final int LABPBR_SPECULAR_STAGES =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
    private static final int STARMAP_UPLOAD = 1;
    private static final int BSDF_LOOKUP_UPLOAD = 1 << 1;

    private final VulkanContext context;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final TracePipeline tracePipeline;
    private final BsdfLookupTable bsdfLookup;
    private final StarmapTexture starmap;
    private VulkanBuffer wavefrontPaths;
    private DescriptorBindings descriptorBindings;
    private long nextFrameToken;
    private long pendingFrameToken;
    private int pendingUploads;
    private boolean staticResourcesPrepared;
    private boolean destroyed;

    public RayTracingPipeline(VulkanContext context, StarmapTexture starmap) {
        this.context = context;
        this.starmap = java.util.Objects.requireNonNull(starmap, "starmap");
        long newDescriptorSetLayout = 0L;
        long newPipelineLayout = 0L;
        TracePipeline newTracePipeline = null;
        BsdfLookupTable newBsdfLookup = null;
        try {
            newBsdfLookup = new BsdfLookupTable(context);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                newDescriptorSetLayout = createDescriptorSetLayout(context, stack);
                newPipelineLayout = createPipelineLayout(context, stack, newDescriptorSetLayout);
                boolean ser = context.capabilities().invocationReorderSupported()
                        && context.capabilities().wavefrontSubgroupSupported();
                String suffix = ser ? "_ser.rgen.spv" : ".rgen.spv";
                String[] raygens = new String[] {
                    "/prime/shaders/wavefront_head" + suffix,
                    "/prime/shaders/wavefront_step" + suffix,
                    "/prime/shaders/wavefront_tail" + suffix,
                    "/prime/shaders/wavefront_resolve" + suffix
                };
                // Every render mode remains resident in one pipeline. Build-time specialization
                // keeps the existing per-stage optimization and register-allocation boundaries
                // without asking the driver to repeatedly prune the complete scheduler module.
                newTracePipeline = TracePipeline.create(
                        context,
                        stack,
                        newPipelineLayout,
                        raygens,
                        "Prime hybrid ray tracing pipeline",
                        "Prime hybrid shader binding table");
            }

            this.descriptorSetLayout = newDescriptorSetLayout;
            this.pipelineLayout = newPipelineLayout;
            this.tracePipeline = newTracePipeline;
            this.bsdfLookup = newBsdfLookup;
        } catch (RuntimeException exception) {
            if (newTracePipeline != null) {
                newTracePipeline.destroy();
            }
            if (newBsdfLookup != null) {
                newBsdfLookup.destroy();
            }
            if (newPipelineLayout != 0L) {
                VK12.vkDestroyPipelineLayout(context.vkDevice(), newPipelineLayout, null);
            }
            if (newDescriptorSetLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), newDescriptorSetLayout, null);
            }
            throw exception;
        }
    }

    public void ensureDescriptors(
            long tlas,
            VulkanImage stableRadiance,
            VulkanImage screenshotRunningMean,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<SceneTexture> sceneTextures,
            VulkanImage labPbrNormalAtlas,
            VulkanImage labPbrSpecularAtlas,
            AtmospherePipeline atmosphere,
            RawWavefrontFrame signals) {
        long requiredWavefrontBytes = wavefrontPathBytes(
                signals.noisyDiffuse().width(), signals.noisyDiffuse().height());
        validateWavefrontRanges(
                signals.noisyDiffuse().width(),
                signals.noisyDiffuse().height(),
                this.context.maxStorageBufferRange());
        validateWavefrontDispatch(
                signals.noisyDiffuse().width(),
                signals.noisyDiffuse().height(),
                this.context.capabilities().maxRayDispatchInvocationCount());
        VulkanBuffer candidateWavefront = this.wavefrontPaths;
        boolean replacesWavefront =
                candidateWavefront == null || candidateWavefront.size() != requiredWavefrontBytes;
        if (replacesWavefront) {
            candidateWavefront = this.context.createBuffer(
                    requiredWavefrontBytes,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false,
                    "Prime wavefront path slots");
        }
        if (this.descriptorBindings != null
                && this.descriptorBindings.matches(
                        tlas,
                        stableRadiance.view(),
                        screenshotRunningMean.view(),
                        atlasView.vkImageView(),
                        atlasSampler.vkSampler(),
                        sceneTextures,
                        labPbrNormalAtlas.view(),
                        labPbrSpecularAtlas.view(),
                        atmosphere.skyView().view(),
                        atmosphere.transmittanceLow().view(),
                        atmosphere.transmittanceHigh().view(),
                        atmosphere.aerialRadiance().view(),
                        atmosphere.aerialTransmittance().view(),
                        signals.noisyDiffuse().view(),
                        signals.noisySpecular().view(),
                        signals.normalRoughness().view(),
                        signals.viewZ().view(),
                        signals.transportMetadata().view(),
                        signals.material().view(),
                        signals.specularMaterial().view(),
                        signals.primaryPosition().view(),
                        signals.diffuseDirection().view(),
                        signals.specularDirection().view(),
                        signals.sunLighting().view(),
                        signals.sunPenumbra().view(),
                        signals.rawNumericalDiagnostic().view(),
                        candidateWavefront.handle())) {
            return;
        }
        DescriptorBindings replacement;
        try {
            replacement = DescriptorBindings.create(
                    this.context,
                    this.descriptorSetLayout,
                    tlas,
                    stableRadiance,
                    screenshotRunningMean,
                    atlasView,
                    atlasSampler,
                    sceneTextures,
                    labPbrNormalAtlas,
                    labPbrSpecularAtlas,
                    atmosphere,
                    signals,
                    this.bsdfLookup,
                    this.starmap,
                    candidateWavefront);
        } catch (RuntimeException exception) {
            if (replacesWavefront) {
                candidateWavefront.destroy();
            }
            throw exception;
        }
        DescriptorBindings previous = this.descriptorBindings;
        VulkanBuffer previousWavefront = this.wavefrontPaths;
        this.descriptorBindings = replacement;
        this.wavefrontPaths = candidateWavefront;
        if (previous != null) {
            this.context.defer(previous);
        }
        if (replacesWavefront && previousWavefront != null) {
            this.context.defer(previousWavefront);
        }
    }

    /**
     * Records immutable starmap and BSDF-LUT uploads without publishing their CPU state.
     *
     * <p>The returned primitive token is zero after the initial accepted upload and therefore has
     * no steady-state allocation or resource cost.
     */
    public long prepareFrame(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        if (this.pendingFrameToken != 0L) {
            throw new IllegalStateException(
                    "Ray-tracing static-resource upload is already pending");
        }
        int uploads = 0;
        try {
            if (this.starmap.prepare(commandBuffer, initialization)) {
                uploads |= STARMAP_UPLOAD;
            }
            if (this.bsdfLookup.prepare(commandBuffer, initialization)) {
                uploads |= BSDF_LOOKUP_UPLOAD;
            }
            if (uploads == 0) {
                this.staticResourcesPrepared = true;
                return 0L;
            }
            long token = this.nextFrameToken();
            this.pendingFrameToken = token;
            this.pendingUploads = uploads;
            return token;
        } catch (RuntimeException exception) {
            RuntimeException failure = exception;
            if ((uploads & BSDF_LOOKUP_UPLOAD) != 0) {
                failure = ResourceCleanup.run(this.bsdfLookup::abandon, failure);
            }
            if ((uploads & STARMAP_UPLOAD) != 0) {
                failure = ResourceCleanup.run(this.starmap::abandon, failure);
            }
            throw failure;
        }
    }

    public void submitted(long token) {
        if (token == 0L) {
            return;
        }
        this.requirePendingToken(token);
        RuntimeException failure = null;
        if ((this.pendingUploads & STARMAP_UPLOAD) != 0) {
            failure = ResourceCleanup.run(this.starmap::submitted, failure);
        }
        if ((this.pendingUploads & BSDF_LOOKUP_UPLOAD) != 0) {
            failure = ResourceCleanup.run(this.bsdfLookup::submitted, failure);
        }
        this.staticResourcesPrepared = failure == null;
        this.pendingFrameToken = 0L;
        this.pendingUploads = 0;
        ResourceCleanup.throwIfFailed(failure);
    }

    public void abandon(long token) {
        if (token == 0L) {
            return;
        }
        this.requirePendingToken(token);
        RuntimeException failure = null;
        if ((this.pendingUploads & BSDF_LOOKUP_UPLOAD) != 0) {
            failure = ResourceCleanup.run(this.bsdfLookup::abandon, failure);
        }
        if ((this.pendingUploads & STARMAP_UPLOAD) != 0) {
            failure = ResourceCleanup.run(this.starmap::abandon, failure);
        }
        this.pendingFrameToken = 0L;
        this.pendingUploads = 0;
        ResourceCleanup.throwIfFailed(failure);
    }

    public void trace(
            VkCommandBuffer commandBuffer,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
        this.traceWavefront(
                commandBuffer,
                input,
                scene,
                WAVEFRONT_RESOLVE_GROUP);
    }

    /** Records one raw native-resolution model sample into the screenshot running mean. */
    public void traceScreenshot(
            VkCommandBuffer commandBuffer,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
        this.traceWavefront(
                commandBuffer,
                input,
                scene,
                WAVEFRONT_SCREENSHOT_RESOLVE_GROUP);
    }

    private long nextFrameToken() {
        long token = ++this.nextFrameToken;
        if (token == 0L) {
            token = ++this.nextFrameToken;
        }
        return token;
    }

    private void requirePendingToken(long token) {
        if (token != this.pendingFrameToken) {
            throw new IllegalArgumentException(
                    "Ray-tracing frame token does not belong to this submission");
        }
    }

    private void traceWavefront(
            VkCommandBuffer commandBuffer,
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene,
            int resolveGroup) {
        int width = input.width();
        int height = input.height();
        if (this.wavefrontPaths == null
                || this.wavefrontPaths.size() != wavefrontPathBytes(width, height)) {
            throw new IllegalStateException(
                    "Wavefront path slots do not match the trace extent");
        }
        if (!this.staticResourcesPrepared && this.pendingFrameToken == 0L) {
            throw new IllegalStateException(
                    "Ray-tracing static resources were not prepared for this frame");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.bind(
                    commandBuffer,
                    RayTracingPushConstants.encode(stack, input, scene));
            long queueOffset = wavefrontQueueOffset(width, height);
            this.initializeWavefrontQueues(commandBuffer, stack, queueOffset);
            this.trace(commandBuffer, stack, width, height, WAVEFRONT_HEAD_GROUP);
            this.wavefrontBarrier(commandBuffer, stack);
            int sourceQueue = 0;
            for (int round = 0; round < WAVEFRONT_STEP_DISPATCH_COUNT; round++) {
                this.traceIndirect(
                        commandBuffer,
                        stack,
                        wavefrontStepGroup(sourceQueue),
                        queueOffset,
                        sourceQueue);
                this.advanceWavefrontQueue(
                        commandBuffer, stack, queueOffset, sourceQueue);
                sourceQueue ^= 1;
            }
            this.traceIndirect(
                    commandBuffer,
                    stack,
                    wavefrontTransitionGroup(sourceQueue),
                    queueOffset,
                    sourceQueue);
            this.advanceWavefrontQueue(
                    commandBuffer, stack, queueOffset, sourceQueue);
            sourceQueue ^= 1;
            this.traceIndirect(
                    commandBuffer,
                    stack,
                    wavefrontTailGroup(sourceQueue),
                    queueOffset,
                    sourceQueue);
            this.wavefrontBarrier(commandBuffer, stack);
            this.trace(commandBuffer, stack, width, height, resolveGroup);
        }
    }

    private void bind(VkCommandBuffer commandBuffer, ByteBuffer pushConstants) {
        if (this.descriptorBindings == null) {
            throw new IllegalStateException("Ray tracing descriptors have not been initialized");
        }
        if (pushConstants.remaining() != ShaderAbi.PUSH_CONSTANT_SIZE) {
            throw new IllegalArgumentException("Unexpected Prime push constant size");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                    this.tracePipeline.pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.descriptorBindings.descriptorSet),
                    null);
            VK12.vkCmdPushConstants(commandBuffer, this.pipelineLayout, ALL_RT_STAGES, 0, pushConstants);
        }
    }

    private void trace(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int width,
            int height,
            int raygenGroup) {
        VkStridedDeviceAddressRegionKHR raygen = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(this.tracePipeline.raygenAddress(raygenGroup))
                .stride(this.tracePipeline.raygenRecordStride)
                .size(this.tracePipeline.raygenRecordStride);
        VkStridedDeviceAddressRegionKHR miss = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(this.tracePipeline.missAddress)
                .stride(this.tracePipeline.recordStride)
                .size(this.tracePipeline.recordStride * MISS_GROUP_COUNT);
        VkStridedDeviceAddressRegionKHR hit = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(this.tracePipeline.hitAddress)
                .stride(this.tracePipeline.recordStride)
                .size(this.tracePipeline.recordStride * HIT_GROUP_COUNT);
        VkStridedDeviceAddressRegionKHR callable = VkStridedDeviceAddressRegionKHR.calloc(stack);
        KHRRayTracingPipeline.vkCmdTraceRaysKHR(
                commandBuffer, raygen, miss, hit, callable, width, height, 1);
    }

    private void traceIndirect(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int raygenGroup,
            long queueOffset,
            int sourceQueue) {
        VkStridedDeviceAddressRegionKHR raygen = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(this.tracePipeline.raygenAddress(raygenGroup))
                .stride(this.tracePipeline.raygenRecordStride)
                .size(this.tracePipeline.raygenRecordStride);
        VkStridedDeviceAddressRegionKHR miss = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(this.tracePipeline.missAddress)
                .stride(this.tracePipeline.recordStride)
                .size(this.tracePipeline.recordStride * MISS_GROUP_COUNT);
        VkStridedDeviceAddressRegionKHR hit = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(this.tracePipeline.hitAddress)
                .stride(this.tracePipeline.recordStride)
                .size(this.tracePipeline.recordStride * HIT_GROUP_COUNT);
        VkStridedDeviceAddressRegionKHR callable = VkStridedDeviceAddressRegionKHR.calloc(stack);
        long indirectAddress = this.wavefrontPaths.deviceAddress()
                + queueOffset
                + Math.multiplyExact(
                        (long) sourceQueue,
                        (long) ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE);
        KHRRayTracingPipeline.vkCmdTraceRaysIndirectKHR(
                commandBuffer, raygen, miss, hit, callable, indirectAddress);
    }

    private void initializeWavefrontQueues(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long queueOffset) {
        this.wavefrontToTransferBarrier(commandBuffer, stack);
        ByteBuffer commands = stack.calloc(
                ShaderAbi.WAVEFRONT_QUEUE_COUNT
                        * ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE);
        for (int queue = 0; queue < ShaderAbi.WAVEFRONT_QUEUE_COUNT; queue++) {
            int offset = queue * ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE;
            commands.putInt(offset, 0);
            commands.putInt(offset + Integer.BYTES, 1);
            commands.putInt(offset + 2 * Integer.BYTES, 1);
            commands.putInt(offset + 3 * Integer.BYTES, 0);
        }
        VK12.vkCmdUpdateBuffer(
                commandBuffer,
                this.wavefrontPaths.handle(),
                queueOffset,
                commands);
        this.transferToWavefrontBarrier(commandBuffer, stack);
    }

    private void wavefrontToTransferBarrier(
            VkCommandBuffer commandBuffer, MemoryStack stack) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcStageMask(KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR)
                .srcAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .dstAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                commandBuffer,
                VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    private void wavefrontBarrier(VkCommandBuffer commandBuffer, MemoryStack stack) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcStageMask(KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR)
                .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(
                        KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                                | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT)
                .dstAccessMask(
                        VK12.VK_ACCESS_SHADER_READ_BIT
                                | VK12.VK_ACCESS_SHADER_WRITE_BIT
                                | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                commandBuffer,
                VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    private void advanceWavefrontQueue(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long queueOffset,
            int completedSourceQueue) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcStageMask(KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR)
                .srcAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(
                        KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                                | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT
                                | VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .dstAccessMask(
                        VK12.VK_ACCESS_SHADER_READ_BIT
                                | VK12.VK_ACCESS_SHADER_WRITE_BIT
                                | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT
                                | VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                commandBuffer,
                VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        VK12.vkCmdFillBuffer(
                commandBuffer,
                this.wavefrontPaths.handle(),
                queueOffset
                        + Math.multiplyExact(
                                (long) completedSourceQueue,
                                (long) ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE),
                Integer.BYTES,
                0);
        this.transferToWavefrontBarrier(commandBuffer, stack);
    }

    private void transferToWavefrontBarrier(
            VkCommandBuffer commandBuffer, MemoryStack stack) {
        VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcStageMask(VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstStageMask(
                        KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                                | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT)
                .dstAccessMask(
                        VK12.VK_ACCESS_SHADER_READ_BIT
                                | VK12.VK_ACCESS_SHADER_WRITE_BIT
                                | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                commandBuffer,
                VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }

    static long wavefrontPathBytes(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Wavefront extent must be positive");
        }
        return Math.addExact(
                wavefrontQueueOffset(width, height),
                wavefrontQueueBytes(width, height));
    }

    static long wavefrontQueueOffset(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Wavefront extent must be positive");
        }
        long pixels = Math.multiplyExact((long) width, (long) height);
        long pathBytes = Math.multiplyExact(
                Math.multiplyExact(
                        pixels,
                        (long) ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL),
                (long) ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
        // Vulkan guarantees minStorageBufferOffsetAlignment is no greater than 256 bytes.
        return VulkanContext.alignUp(pathBytes, WAVEFRONT_QUEUE_OFFSET_ALIGNMENT);
    }

    static long wavefrontQueueBytes(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Wavefront extent must be positive");
        }
        long pixels = Math.multiplyExact((long) width, (long) height);
        long capacity = Math.multiplyExact(
                pixels, (long) ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL);
        long commands = Math.multiplyExact(
                (long) ShaderAbi.WAVEFRONT_QUEUE_COUNT,
                (long) ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE);
        long indices = Math.multiplyExact(
                Math.multiplyExact(
                        (long) ShaderAbi.WAVEFRONT_QUEUE_COUNT,
                        capacity),
                (long) ShaderAbi.WAVEFRONT_QUEUE_INDEX_SIZE);
        return Math.addExact(commands, indices);
    }

    static void validateWavefrontRanges(int width, int height, long maxStorageBufferRange) {
        long queueOffset = wavefrontQueueOffset(width, height);
        long queueBytes = wavefrontQueueBytes(width, height);
        if (queueOffset > maxStorageBufferRange || queueBytes > maxStorageBufferRange) {
            throw new IllegalStateException(
                    "Wavefront queue descriptor exceeds maxStorageBufferRange: paths="
                            + queueOffset
                            + ", queue="
                            + queueBytes
                            + ", device="
                            + maxStorageBufferRange);
        }
    }

    static void validateWavefrontDispatch(
            int width, int height, int maxRayDispatchInvocationCount) {
        long pixels = Math.multiplyExact((long) width, (long) height);
        long capacity = Math.multiplyExact(
                pixels, (long) ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL);
        long deviceLimit = Integer.toUnsignedLong(maxRayDispatchInvocationCount);
        if (capacity > deviceLimit) {
            throw new IllegalStateException(
                    "Compacted wavefront queue capacity exceeds maxRayDispatchInvocationCount: "
                            + capacity
                            + " > "
                            + deviceLimit);
        }
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            if (this.descriptorBindings != null) {
                this.descriptorBindings.destroy();
                this.descriptorBindings = null;
            }
            if (this.wavefrontPaths != null) {
                this.wavefrontPaths.destroy();
                this.wavefrontPaths = null;
            }
            this.tracePipeline.destroy();
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
            this.bsdfLookup.destroy();
        }
    }

    private static void writeShaderBindingTable(
            VulkanContext context,
            long pipeline,
            VulkanBuffer shaderBindingTable,
            int handleSize,
            ShaderBindingTableLayout layout) {
        ByteBuffer handles = MemoryUtil.memAlloc(GROUP_COUNT * handleSize);
        try {
            VulkanContext.check(
                    KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(
                            context.vkDevice(),
                            pipeline,
                            0,
                            GROUP_COUNT,
                            handles),
                    "read Prime shader group handles");
            long source = MemoryUtil.memAddress(handles);
            long destination = shaderBindingTable.mappedAddress();
            for (int raygenIndex = 0; raygenIndex < RAYGEN_GROUP_COUNT; raygenIndex++) {
                long recordAddress = destination + layout.raygenOffset()
                        + raygenIndex * layout.raygenRecordStride();
                MemoryUtil.memCopy(
                        source + (long) raygenIndex * handleSize,
                        recordAddress,
                        handleSize);
                MemoryUtil.memPutInt(
                        recordAddress + handleSize,
                        raygenRecordStage(raygenIndex));
            }
            for (int missIndex = 0; missIndex < MISS_GROUP_COUNT; missIndex++) {
                MemoryUtil.memCopy(
                        source + (long) (RAYGEN_GROUP_COUNT + missIndex) * handleSize,
                        destination + layout.missOffset() + missIndex * layout.recordStride(),
                        handleSize);
            }
            for (int hitIndex = 0; hitIndex < HIT_GROUP_COUNT; hitIndex++) {
                MemoryUtil.memCopy(
                        source + (long) (RAYGEN_GROUP_COUNT + MISS_GROUP_COUNT + hitIndex)
                                * handleSize,
                        destination + layout.hitOffset() + hitIndex * layout.recordStride(),
                        handleSize);
            }
            shaderBindingTable.flush(0L, layout.totalSize());
        } finally {
            MemoryUtil.memFree(handles);
        }
    }

    private static long createDescriptorSetLayout(VulkanContext context, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(DESCRIPTOR_BINDING_COUNT, stack);
        bindings.get(0)
                .binding(ShaderAbi.DESCRIPTOR_TLAS)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(1)
                .binding(ShaderAbi.DESCRIPTOR_BLOCK_ATLAS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(ShaderAbi.SCENE_TEXTURE_COUNT)
                // Slot zero is the block atlas. Dynamic entity and particle textures occupy the
                // remaining fixed slots and are selected non-uniformly by hit records.
                .stageFlags(BLOCK_ATLAS_STAGES);
        bindings.get(2)
                .binding(ShaderAbi.DESCRIPTOR_STABLE_RADIANCE)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        int[] atmosphereBindings = new int[] {
            ShaderAbi.DESCRIPTOR_SKY_VIEW,
            ShaderAbi.DESCRIPTOR_TRANSMITTANCE_LOW,
            ShaderAbi.DESCRIPTOR_TRANSMITTANCE_HIGH,
            ShaderAbi.DESCRIPTOR_AERIAL_RADIANCE,
            ShaderAbi.DESCRIPTOR_AERIAL_TRANSMITTANCE
        };
        for (int index = 0; index < atmosphereBindings.length; index++) {
            bindings.get(index + 3)
                    .binding(atmosphereBindings[index])
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        int[] nrdBindings = new int[] {
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
            ShaderAbi.DESCRIPTOR_NRD_DISPLAY_POSITION
        };
        for (int index = 0; index < nrdBindings.length; index++) {
            bindings.get(index + 8)
                    .binding(nrdBindings[index])
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        bindings.get(27)
                .binding(ShaderAbi.DESCRIPTOR_TRANSMISSION_GGX_ENERGY)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(28)
                .binding(ShaderAbi.DESCRIPTOR_LABPBR_NORMAL_ATLAS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
        bindings.get(29)
                .binding(ShaderAbi.DESCRIPTOR_LABPBR_SPECULAR_ATLAS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                // Closest-hit transports material data; raygen evaluates the authored alpha
                // when an emissive area-light triangle is sampled or directly visible.
                .stageFlags(LABPBR_SPECULAR_STAGES);
        bindings.get(30)
                .binding(ShaderAbi.DESCRIPTOR_NRD_SUN_LIGHTING)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(31)
                .binding(ShaderAbi.DESCRIPTOR_NRD_SUN_PENUMBRA)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(32)
                .binding(ShaderAbi.DESCRIPTOR_RAW_NUMERICAL_DIAGNOSTIC)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(33)
                .binding(ShaderAbi.DESCRIPTOR_STARMAP)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(34)
                .binding(ShaderAbi.DESCRIPTOR_SCREENSHOT_RUNNING_MEAN)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(35)
                .binding(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(36)
                .binding(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(context.vkDevice(), createInfo, null, pointer),
                "create Prime descriptor set layout");
        return pointer.get(0);
    }

    private static long createPipelineLayout(VulkanContext context, MemoryStack stack, long descriptorSetLayout) {
        VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                .stageFlags(ALL_RT_STAGES)
                .offset(0)
                .size(ShaderAbi.PUSH_CONSTANT_SIZE);
        VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(descriptorSetLayout))
                .pPushConstantRanges(pushRange);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreatePipelineLayout(context.vkDevice(), createInfo, null, pointer),
                "create Prime pipeline layout");
        return pointer.get(0);
    }

    private static long createPipeline(
            VulkanContext context,
            MemoryStack stack,
            long pipelineLayout,
            String[] raygenResources,
            String debugName) {
        PrimeClient.LOGGER.info(
                "Compiling Prime unified wavefront ray tracing pipeline");
        long compilationStart = System.nanoTime();
        if (raygenResources.length != RAYGEN_MODULE_COUNT) {
            throw new IllegalArgumentException("Unexpected Prime raygen module count");
        }
        long[] modules = new long[RAYGEN_MODULE_COUNT + FIXED_SHADER_MODULE_COUNT];
        long deferredOperation = 0L;
        try {
            for (int index = 0; index < RAYGEN_MODULE_COUNT; index++) {
                modules[index] = createShaderModule(context, raygenResources[index]);
            }
            int missModule = RAYGEN_MODULE_COUNT;
            int shadowMissModule = missModule + 1;
            int closestHitModule = missModule + 2;
            int anyHitModule = missModule + 3;
            int shadowAnyHitModule = missModule + 4;
            int shadowClosestHitModule = missModule + 5;
            modules[missModule] =
                    createShaderModule(context, "/prime/shaders/world.rmiss.spv");
            modules[shadowMissModule] =
                    createShaderModule(context, "/prime/shaders/shadow.rmiss.spv");
            modules[closestHitModule] =
                    createShaderModule(context, "/prime/shaders/world.rchit.spv");
            modules[anyHitModule] =
                    createShaderModule(context, "/prime/shaders/world.rahit.spv");
            modules[shadowAnyHitModule] =
                    createShaderModule(context, "/prime/shaders/shadow.rahit.spv");
            modules[shadowClosestHitModule] =
                    createShaderModule(context, "/prime/shaders/shadow.rchit.spv");
            int miss = RAYGEN_SHADER_STAGE_COUNT;
            int shadowMiss = miss + 1;
            int closestHit = miss + 2;
            int anyHit = miss + 3;
            int shadowAnyHit = miss + 4;
            int shadowClosestHit = miss + 5;
            VkPipelineShaderStageCreateInfo.Buffer stages =
                    VkPipelineShaderStageCreateInfo.calloc(
                            RAYGEN_SHADER_STAGE_COUNT + FIXED_SHADER_MODULE_COUNT, stack);
            ByteBuffer mainName = stack.UTF8("main");
            for (int index = 0; index < stages.capacity(); index++) {
                int stageFlag;
                if (index < RAYGEN_SHADER_STAGE_COUNT) {
                    stageFlag = KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
                } else if (index <= shadowMiss) {
                    stageFlag = KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR;
                } else if (index == anyHit || index == shadowAnyHit) {
                    stageFlag = KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
                } else {
                    stageFlag = KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
                }
                stages.get(index)
                        .sType$Default()
                        .stage(stageFlag)
                        .module(index < RAYGEN_SHADER_STAGE_COUNT
                                ? modules[index]
                                : modules[index
                                        - RAYGEN_SHADER_STAGE_COUNT
                                        + RAYGEN_MODULE_COUNT])
                        .pName(mainName);
            }

            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups =
                    VkRayTracingShaderGroupCreateInfoKHR.calloc(GROUP_COUNT, stack);
            for (int index = 0; index < RAYGEN_GROUP_COUNT; index++) {
                generalGroup(groups.get(index), raygenShaderStage(index));
            }
            generalGroup(groups.get(RAYGEN_GROUP_COUNT), miss);
            generalGroup(groups.get(RAYGEN_GROUP_COUNT + 1), shadowMiss);
            int hitBase = RAYGEN_GROUP_COUNT + MISS_GROUP_COUNT;
            triangleGroup(
                    groups.get(hitBase),
                    closestHit,
                    KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
            triangleGroup(groups.get(hitBase + 1), closestHit, anyHit);
            triangleGroup(groups.get(hitBase + 2), closestHit, anyHit);
            triangleGroup(
                    groups.get(hitBase + 3),
                    shadowClosestHit,
                    KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
            triangleGroup(groups.get(hitBase + 4), shadowClosestHit, anyHit);
            triangleGroup(
                    groups.get(hitBase + 5),
                    shadowClosestHit,
                    shadowAnyHit);

            VkRayTracingPipelineCreateInfoKHR.Buffer createInfo =
                    VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            createInfo.get(0)
                    .sType$Default()
                    .flags(context.capabilities().opacityMicromapSupported()
                            ? EXTOpacityMicromap
                                    .VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT
                            : 0)
                    .pStages(stages)
                    .pGroups(groups)
                    .maxPipelineRayRecursionDepth(1)
                    .layout(pipelineLayout);
            LongBuffer pointer = stack.mallocLong(1);
            LongBuffer deferredPointer = stack.mallocLong(1);
            VulkanContext.check(
                    KHRDeferredHostOperations.vkCreateDeferredOperationKHR(
                            context.vkDevice(), null, deferredPointer),
                    "create Prime deferred pipeline operation");
            deferredOperation = deferredPointer.get(0);
            int result = KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR(
                    context.vkDevice(),
                    deferredOperation,
                    0L,
                    createInfo,
                    null,
                    pointer);
            int workerCount = 1;
            if (result == KHRDeferredHostOperations.VK_OPERATION_DEFERRED_KHR) {
                int reportedConcurrency =
                        KHRDeferredHostOperations.vkGetDeferredOperationMaxConcurrencyKHR(
                                context.vkDevice(), deferredOperation);
                int availableProcessors = Runtime.getRuntime().availableProcessors();
                workerCount = deferredWorkerCount(
                        reportedConcurrency, availableProcessors);
                PrimeClient.LOGGER.info(
                        "Prime RT driver compilation concurrency: {} reported, {} host logical "
                                + "processor(s), {} thread(s) selected",
                        Integer.toUnsignedLong(reportedConcurrency),
                        availableProcessors,
                        workerCount);
                workerCount = completeDeferredPipelineCreation(
                        context, deferredOperation, workerCount);
            } else if (result != KHRDeferredHostOperations.VK_OPERATION_NOT_DEFERRED_KHR) {
                VulkanContext.check(result, "create Prime ray tracing pipeline");
            }
            long pipeline = pointer.get(0);
            long compilationMillis =
                    (System.nanoTime() - compilationStart) / 1_000_000L;
            PrimeClient.LOGGER.info(
                    "Prime ray tracing pipelines compiled in {} ms using {} host thread(s)",
                    compilationMillis,
                    workerCount);
            context.device().instance().debug().setObjectName(
                    context.vkDevice(),
                    VK12.VK_OBJECT_TYPE_PIPELINE,
                    pipeline,
                    debugName);
            return pipeline;
        } finally {
            if (deferredOperation != 0L) {
                KHRDeferredHostOperations.vkDestroyDeferredOperationKHR(
                        context.vkDevice(), deferredOperation, null);
            }
            for (long module : modules) {
                if (module != 0L) {
                    VK12.vkDestroyShaderModule(context.vkDevice(), module, null);
                }
            }
        }
    }

    static int deferredWorkerCount(int reportedConcurrency, int availableProcessors) {
        long reported = Integer.toUnsignedLong(reportedConcurrency);
        return (int) Math.max(
                1L,
                Math.min(reported, Math.max(availableProcessors, 1)));
    }

    static int raygenShaderStage(int group) {
        return switch (group) {
            case WAVEFRONT_HEAD_GROUP -> 0;
            case WAVEFRONT_STEP_QUEUE_0_GROUP,
                    WAVEFRONT_STEP_QUEUE_1_GROUP,
                    WAVEFRONT_TRANSITION_QUEUE_0_GROUP,
                    WAVEFRONT_TRANSITION_QUEUE_1_GROUP -> 1;
            case WAVEFRONT_TAIL_QUEUE_0_GROUP,
                    WAVEFRONT_TAIL_QUEUE_1_GROUP -> 2;
            case WAVEFRONT_SCREENSHOT_RESOLVE_GROUP,
                    WAVEFRONT_RESOLVE_GROUP -> 3;
            default -> throw new IllegalArgumentException(
                    "Invalid Prime raygen group " + group);
        };
    }

    static int raygenRecordStage(int group) {
        return switch (group) {
            case WAVEFRONT_HEAD_GROUP -> 0;
            case WAVEFRONT_STEP_QUEUE_0_GROUP -> 1;
            case WAVEFRONT_STEP_QUEUE_1_GROUP -> 1 | (1 << 8);
            case WAVEFRONT_TRANSITION_QUEUE_0_GROUP -> 2;
            case WAVEFRONT_TRANSITION_QUEUE_1_GROUP -> 2 | (1 << 8);
            case WAVEFRONT_TAIL_QUEUE_0_GROUP -> 3;
            case WAVEFRONT_TAIL_QUEUE_1_GROUP -> 3 | (1 << 8);
            case WAVEFRONT_RESOLVE_GROUP -> 4;
            case WAVEFRONT_SCREENSHOT_RESOLVE_GROUP -> 4 | (1 << 9);
            default -> throw new IllegalArgumentException(
                    "Invalid Prime raygen group " + group);
        };
    }

    static int wavefrontStepGroup(int queue) {
        return switch (queue) {
            case 0 -> WAVEFRONT_STEP_QUEUE_0_GROUP;
            case 1 -> WAVEFRONT_STEP_QUEUE_1_GROUP;
            default -> throw new IllegalArgumentException("Invalid wavefront queue " + queue);
        };
    }

    static int wavefrontTransitionGroup(int queue) {
        return switch (queue) {
            case 0 -> WAVEFRONT_TRANSITION_QUEUE_0_GROUP;
            case 1 -> WAVEFRONT_TRANSITION_QUEUE_1_GROUP;
            default -> throw new IllegalArgumentException("Invalid wavefront queue " + queue);
        };
    }

    static int wavefrontTailGroup(int queue) {
        return switch (queue) {
            case 0 -> WAVEFRONT_TAIL_QUEUE_0_GROUP;
            case 1 -> WAVEFRONT_TAIL_QUEUE_1_GROUP;
            default -> throw new IllegalArgumentException("Invalid wavefront queue " + queue);
        };
    }

    private static int completeDeferredPipelineCreation(
            VulkanContext context,
            long deferredOperation,
            int workerCount) {
        Thread[] workers = new Thread[Math.max(workerCount - 1, 0)];
        int startedWorkers = 0;
        try {
            for (int index = 0; index < workers.length; index++) {
                Thread worker = new Thread(
                        () -> joinDeferredOperation(context, deferredOperation),
                        "Prime RT compiler " + (index + 1));
                workers[index] = worker;
                worker.start();
                startedWorkers++;
            }
        } catch (RuntimeException | OutOfMemoryError exception) {
            // The calling thread still guarantees completion, so a host thread creation
            // failure only reduces parallelism and never leaves the Vulkan operation pending.
            PrimeClient.LOGGER.warn(
                    "Started only {} of {} Prime RT compiler worker thread(s)",
                    startedWorkers,
                    workers.length,
                    exception);
        }
        joinDeferredOperation(context, deferredOperation);

        boolean interrupted = false;
        for (int index = 0; index < startedWorkers; index++) {
            Thread worker = workers[index];
            for (;;) {
                try {
                    worker.join();
                    break;
                } catch (InterruptedException exception) {
                    // The Vulkan operation cannot be abandoned while its input pointers are live.
                    // Complete the ownership boundary, then restore the caller's interrupt state.
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        int result = KHRDeferredHostOperations.vkGetDeferredOperationResultKHR(
                context.vkDevice(), deferredOperation);
        while (result == VK12.VK_NOT_READY) {
            VulkanContext.check(
                    joinDeferredOperation(context, deferredOperation),
                    "join Prime deferred ray tracing pipeline");
            result = KHRDeferredHostOperations.vkGetDeferredOperationResultKHR(
                    context.vkDevice(), deferredOperation);
        }
        VulkanContext.check(result, "complete Prime deferred ray tracing pipeline");
        return startedWorkers + 1;
    }

    private static int joinDeferredOperation(
            VulkanContext context,
            long deferredOperation) {
        for (;;) {
            int result = KHRDeferredHostOperations.vkDeferredOperationJoinKHR(
                    context.vkDevice(), deferredOperation);
            if (result == KHRDeferredHostOperations.VK_THREAD_IDLE_KHR) {
                Thread.yield();
                continue;
            }
            if (result == VK12.VK_SUCCESS
                    || result == KHRDeferredHostOperations.VK_THREAD_DONE_KHR) {
                return VK12.VK_SUCCESS;
            }
            return result;
        }
    }

    private static void generalGroup(VkRayTracingShaderGroupCreateInfoKHR group, int shaderIndex) {
        group.sType$Default()
                .type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                .generalShader(shaderIndex)
                .closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
    }

    private static void triangleGroup(VkRayTracingShaderGroupCreateInfoKHR group, int closestHit, int anyHit) {
        group.sType$Default()
                .type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                .generalShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .closestHitShader(closestHit)
                .anyHitShader(anyHit)
                .intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
    }

    private static long createShaderModule(VulkanContext context, String resourceName) {
        byte[] bytes;
        try (InputStream input = RayTracingPipeline.class.getResourceAsStream(resourceName)) {
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
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default()
                        .pCode(code);
                LongBuffer pointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateShaderModule(context.vkDevice(), createInfo, null, pointer),
                        "create shader module " + resourceName);
                return pointer.get(0);
            }
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static final class TracePipeline implements Destroyable {
        private final VulkanContext context;
        private final long pipeline;
        private final VulkanBuffer shaderBindingTable;
        private final long raygenAddress;
        private final long raygenRecordStride;
        private final long missAddress;
        private final long hitAddress;
        private final long recordStride;
        private boolean destroyed;

        private TracePipeline(
                VulkanContext context,
                long pipeline,
                VulkanBuffer shaderBindingTable,
                ShaderBindingTableLayout layout) {
            this.context = context;
            this.pipeline = pipeline;
            this.shaderBindingTable = shaderBindingTable;
            this.raygenAddress = shaderBindingTable.deviceAddress() + layout.raygenOffset();
            this.raygenRecordStride = layout.raygenRecordStride();
            this.missAddress = shaderBindingTable.deviceAddress() + layout.missOffset();
            this.hitAddress = shaderBindingTable.deviceAddress() + layout.hitOffset();
            this.recordStride = layout.recordStride();
        }

        private static TracePipeline create(
                VulkanContext context,
                MemoryStack stack,
                long pipelineLayout,
                String[] raygenResources,
                String pipelineName,
                String sbtName) {
            long pipeline = 0L;
            VulkanBuffer shaderBindingTable = null;
            try {
                pipeline = createPipeline(
                        context, stack, pipelineLayout, raygenResources, pipelineName);
                int handleSize = context.capabilities().shaderGroupHandleSize();
                int handleAlignment = context.capabilities().shaderGroupHandleAlignment();
                int baseAlignment = context.capabilities().shaderGroupBaseAlignment();
                long bufferSize = ShaderBindingTableLayout.minimumBufferSize(
                        handleSize,
                        handleAlignment,
                        baseAlignment,
                        RAYGEN_RECORD_DATA_SIZE,
                        RAYGEN_GROUP_COUNT,
                        MISS_GROUP_COUNT,
                        HIT_GROUP_COUNT);
                shaderBindingTable = context.createBuffer(
                        bufferSize,
                        KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR,
                        true,
                        sbtName);
                ShaderBindingTableLayout layout = ShaderBindingTableLayout.create(
                        handleSize,
                        handleAlignment,
                        baseAlignment,
                        RAYGEN_RECORD_DATA_SIZE,
                        RAYGEN_GROUP_COUNT,
                        MISS_GROUP_COUNT,
                        HIT_GROUP_COUNT,
                        shaderBindingTable.deviceAddress());
                if (layout.recordStride() > context.capabilities().maxShaderGroupStride()
                        || layout.raygenRecordStride()
                                > context.capabilities().maxShaderGroupStride()) {
                    throw new IllegalStateException("Prime SBT record stride exceeds the device limit");
                }
                writeShaderBindingTable(
                        context, pipeline, shaderBindingTable, handleSize, layout);
                return new TracePipeline(context, pipeline, shaderBindingTable, layout);
            } catch (RuntimeException exception) {
                if (shaderBindingTable != null) {
                    shaderBindingTable.destroy();
                }
                if (pipeline != 0L) {
                    VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
                }
                throw exception;
            }
        }

        private long raygenAddress(int group) {
            if (group < 0 || group >= RAYGEN_GROUP_COUNT) {
                throw new IllegalArgumentException("Invalid Prime raygen group " + group);
            }
            return this.raygenAddress + group * this.raygenRecordStride;
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                this.shaderBindingTable.destroy();
                VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
            }
        }
    }

    private static final class DescriptorBindings implements Destroyable {
        private final VulkanContext context;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long tlas;
        private final long stableRadianceView;
        private final long screenshotRunningMeanView;
        private final long atlasView;
        private final long atlasSampler;
        private final List<SceneTexture> sceneTextures;
        private final long labPbrNormalAtlas;
        private final long labPbrSpecularAtlas;
        private final long skyView;
        private final long transmittanceLow;
        private final long transmittanceHigh;
        private final long aerialRadiance;
        private final long aerialTransmittance;
        private final long nrdNoisyDiffuse;
        private final long nrdNoisySpecular;
        private final long nrdNormalRoughness;
        private final long nrdViewZ;
        private final long wavefrontTransportMetadata;
        private final long nrdMaterial;
        private final long nrdSpecularMaterial;
        private final long nrdPrimaryPosition;
        private final long nrdDiffuseDirection;
        private final long nrdSpecularDirection;
        private final long nrdSunLighting;
        private final long nrdSunPenumbra;
        private final long rawNumericalDiagnostic;
        private final long wavefrontPaths;
        private boolean destroyed;

        private DescriptorBindings(
                VulkanContext context,
                long descriptorPool,
                long descriptorSet,
                long tlas,
                long stableRadianceView,
                long screenshotRunningMeanView,
                long atlasView,
                long atlasSampler,
                List<SceneTexture> sceneTextures,
                long labPbrNormalAtlas,
                long labPbrSpecularAtlas,
                long skyView,
                long transmittanceLow,
                long transmittanceHigh,
                long aerialRadiance,
                long aerialTransmittance,
                long nrdNoisyDiffuse,
                long nrdNoisySpecular,
                long nrdNormalRoughness,
                long nrdViewZ,
                long wavefrontTransportMetadata,
                long nrdMaterial,
                long nrdSpecularMaterial,
                long nrdPrimaryPosition,
                long nrdDiffuseDirection,
                long nrdSpecularDirection,
                long nrdSunLighting,
                long nrdSunPenumbra,
                long rawNumericalDiagnostic,
                long wavefrontPaths) {
            this.context = context;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.tlas = tlas;
            this.stableRadianceView = stableRadianceView;
            this.screenshotRunningMeanView = screenshotRunningMeanView;
            this.atlasView = atlasView;
            this.atlasSampler = atlasSampler;
            this.sceneTextures = List.copyOf(sceneTextures);
            this.labPbrNormalAtlas = labPbrNormalAtlas;
            this.labPbrSpecularAtlas = labPbrSpecularAtlas;
            this.skyView = skyView;
            this.transmittanceLow = transmittanceLow;
            this.transmittanceHigh = transmittanceHigh;
            this.aerialRadiance = aerialRadiance;
            this.aerialTransmittance = aerialTransmittance;
            this.nrdNoisyDiffuse = nrdNoisyDiffuse;
            this.nrdNoisySpecular = nrdNoisySpecular;
            this.nrdNormalRoughness = nrdNormalRoughness;
            this.nrdViewZ = nrdViewZ;
            this.wavefrontTransportMetadata = wavefrontTransportMetadata;
            this.nrdMaterial = nrdMaterial;
            this.nrdSpecularMaterial = nrdSpecularMaterial;
            this.nrdPrimaryPosition = nrdPrimaryPosition;
            this.nrdDiffuseDirection = nrdDiffuseDirection;
            this.nrdSpecularDirection = nrdSpecularDirection;
            this.nrdSunLighting = nrdSunLighting;
            this.nrdSunPenumbra = nrdSunPenumbra;
            this.rawNumericalDiagnostic = rawNumericalDiagnostic;
            this.wavefrontPaths = wavefrontPaths;
        }

        private static DescriptorBindings create(
                VulkanContext context,
                long descriptorSetLayout,
                long tlas,
                VulkanImage stableRadiance,
                VulkanImage screenshotRunningMean,
                VulkanGpuTextureView atlasView,
                VulkanGpuSampler atlasSampler,
                List<SceneTexture> sceneTextures,
                VulkanImage labPbrNormalAtlas,
                VulkanImage labPbrSpecularAtlas,
                AtmospherePipeline atmosphere,
                RawWavefrontFrame signals,
                BsdfLookupTable bsdfLookup,
                StarmapTexture starmap,
                VulkanBuffer wavefrontPaths) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
                sizes.get(0).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                sizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(STORAGE_IMAGE_DESCRIPTOR_COUNT);
                sizes.get(2)
                        .type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(ShaderAbi.SCENE_TEXTURE_COUNT + 4);
                sizes.get(3).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
                VkDescriptorPoolCreateInfo poolCreateInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(1)
                        .pPoolSizes(sizes);
                LongBuffer poolPointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(context.vkDevice(), poolCreateInfo, null, poolPointer),
                        "create Prime descriptor pool");
                long pool = poolPointer.get(0);
                try {
                    VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType$Default()
                            .descriptorPool(pool)
                            .pSetLayouts(stack.longs(descriptorSetLayout));
                    LongBuffer setPointer = stack.mallocLong(1);
                    VulkanContext.check(
                            VK12.vkAllocateDescriptorSets(context.vkDevice(), allocateInfo, setPointer),
                            "allocate Prime descriptor set");
                    long descriptorSet = setPointer.get(0);

                    VkWriteDescriptorSetAccelerationStructureKHR acceleration =
                            VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                                    .sType$Default()
                                    .pAccelerationStructures(stack.longs(tlas));
                    int atmosphereStart = 1 + ShaderAbi.SCENE_TEXTURE_COUNT;
                    int nrdStart = atmosphereStart + 5;
                    int bsdfIndex = nrdStart + 19;
                    int normalAtlasIndex = bsdfIndex + 1;
                    int specularAtlasIndex = normalAtlasIndex + 1;
                    int sunLightingIndex = specularAtlasIndex + 1;
                    int sunPenumbraIndex = sunLightingIndex + 1;
                    int diagnosticIndex = sunPenumbraIndex + 1;
                    int starmapIndex = diagnosticIndex + 1;
                    int screenshotIndex = starmapIndex + 1;
                    VkDescriptorImageInfo.Buffer imageInfos =
                            VkDescriptorImageInfo.calloc(screenshotIndex + 1, stack);
                    imageInfos.get(0)
                            .imageView(stableRadiance.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    if (sceneTextures.size() + 1 > ShaderAbi.SCENE_TEXTURE_COUNT) {
                        throw new IllegalArgumentException(
                                "Dynamic scene texture count exceeds the descriptor ABI");
                    }
                    for (int index = 0; index < ShaderAbi.SCENE_TEXTURE_COUNT; index++) {
                        SceneTexture texture = index == 0 || index > sceneTextures.size()
                                ? new SceneTexture(
                                        atlasView.texture().vkImage(),
                                        atlasView.vkImageView(),
                                        atlasSampler.vkSampler())
                                : sceneTextures.get(index - 1);
                        imageInfos.get(index + 1)
                                .sampler(texture.sampler())
                                .imageView(texture.view())
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    }
                    VulkanImage[] atmosphereImages = new VulkanImage[] {
                        atmosphere.skyView(),
                        atmosphere.transmittanceLow(),
                        atmosphere.transmittanceHigh(),
                        atmosphere.aerialRadiance(),
                        atmosphere.aerialTransmittance()
                    };
                    for (int index = 0; index < atmosphereImages.length; index++) {
                        imageInfos.get(index + atmosphereStart)
                                .imageView(atmosphereImages[index].view())
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    }
                    VulkanImage[] nrdImages = new VulkanImage[] {
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
                        signals.displayPosition()
                    };
                    for (int index = 0; index < nrdImages.length; index++) {
                        imageInfos.get(index + nrdStart)
                                .imageView(nrdImages[index].view())
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    }
                    imageInfos.get(bsdfIndex)
                            .sampler(bsdfLookup.sampler())
                            .imageView(bsdfLookup.transmissionGgxEnergy().view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    imageInfos.get(normalAtlasIndex)
                            .sampler(atlasSampler.vkSampler())
                            .imageView(labPbrNormalAtlas.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    imageInfos.get(specularAtlasIndex)
                            .sampler(atlasSampler.vkSampler())
                            .imageView(labPbrSpecularAtlas.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    imageInfos.get(sunLightingIndex)
                            .imageView(signals.sunLighting().view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    imageInfos.get(sunPenumbraIndex)
                            .imageView(signals.sunPenumbra().view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    imageInfos.get(diagnosticIndex)
                            .imageView(signals.rawNumericalDiagnostic().view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    imageInfos.get(starmapIndex)
                            .sampler(starmap.sampler())
                            .imageView(starmap.image().view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    imageInfos.get(screenshotIndex)
                            .imageView(screenshotRunningMean.view())
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorBufferInfo.Buffer bufferInfos =
                            VkDescriptorBufferInfo.calloc(2, stack);
                    long wavefrontQueueOffset = wavefrontQueueOffset(
                            signals.noisyDiffuse().width(),
                            signals.noisyDiffuse().height());
                    bufferInfos.get(0)
                            .buffer(wavefrontPaths.handle())
                            .offset(0L)
                            .range(wavefrontQueueOffset);
                    bufferInfos.get(1)
                            .buffer(wavefrontPaths.handle())
                            .offset(wavefrontQueueOffset)
                            .range(wavefrontPaths.size() - wavefrontQueueOffset);
                    VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(37, stack);
                    writes.get(0)
                            .sType$Default()
                            .pNext(acceleration.address())
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_TLAS)
                            .descriptorCount(1)
                            .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                    writes.get(1)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_STABLE_RADIANCE)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(0).address(), 1));
                    writes.get(2)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_BLOCK_ATLAS)
                            .descriptorCount(ShaderAbi.SCENE_TEXTURE_COUNT)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfos.get(1).address(),
                                    ShaderAbi.SCENE_TEXTURE_COUNT));
                    int[] atmosphereBindings = new int[] {
                        ShaderAbi.DESCRIPTOR_SKY_VIEW,
                        ShaderAbi.DESCRIPTOR_TRANSMITTANCE_LOW,
                        ShaderAbi.DESCRIPTOR_TRANSMITTANCE_HIGH,
                        ShaderAbi.DESCRIPTOR_AERIAL_RADIANCE,
                        ShaderAbi.DESCRIPTOR_AERIAL_TRANSMITTANCE
                    };
                    for (int index = 0; index < atmosphereBindings.length; index++) {
                        writes.get(index + 3)
                                .sType$Default()
                                .dstSet(descriptorSet)
                                .dstBinding(atmosphereBindings[index])
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(
                                        imageInfos.get(index + atmosphereStart).address(), 1));
                    }
                    int[] nrdBindings = new int[] {
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
                        ShaderAbi.DESCRIPTOR_NRD_DISPLAY_POSITION
                    };
                    for (int index = 0; index < nrdBindings.length; index++) {
                        writes.get(index + 8)
                                .sType$Default()
                                .dstSet(descriptorSet)
                                .dstBinding(nrdBindings[index])
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(
                                        imageInfos.get(index + nrdStart).address(), 1));
                    }
                    writes.get(27)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_TRANSMISSION_GGX_ENERGY)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfos.get(bsdfIndex).address(), 1));
                    writes.get(28)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_LABPBR_NORMAL_ATLAS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfos.get(normalAtlasIndex).address(), 1));
                    writes.get(29)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_LABPBR_SPECULAR_ATLAS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfos.get(specularAtlasIndex).address(), 1));
                    writes.get(30)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_NRD_SUN_LIGHTING)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfos.get(sunLightingIndex).address(), 1));
                    writes.get(31)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_NRD_SUN_PENUMBRA)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfos.get(sunPenumbraIndex).address(), 1));
                    writes.get(32)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_RAW_NUMERICAL_DIAGNOSTIC)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfos.get(diagnosticIndex).address(), 1));
                    writes.get(33)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_STARMAP)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfos.get(starmapIndex).address(), 1));
                    writes.get(34)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_SCREENSHOT_RUNNING_MEAN)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(
                                    imageInfos.get(screenshotIndex).address(), 1));
                    writes.get(35)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(0).address(), 1));
                    writes.get(36)
                            .sType$Default()
                            .dstSet(descriptorSet)
                            .dstBinding(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE)
                            .descriptorCount(1)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .pBufferInfo(VkDescriptorBufferInfo.create(
                                    bufferInfos.get(1).address(), 1));
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                    return new DescriptorBindings(
                            context,
                            pool,
                            descriptorSet,
                            tlas,
                            stableRadiance.view(),
                            screenshotRunningMean.view(),
                            atlasView.vkImageView(),
                            atlasSampler.vkSampler(),
                            sceneTextures,
                            labPbrNormalAtlas.view(),
                            labPbrSpecularAtlas.view(),
                            atmosphere.skyView().view(),
                            atmosphere.transmittanceLow().view(),
                            atmosphere.transmittanceHigh().view(),
                            atmosphere.aerialRadiance().view(),
                            atmosphere.aerialTransmittance().view(),
                            signals.noisyDiffuse().view(),
                            signals.noisySpecular().view(),
                            signals.normalRoughness().view(),
                            signals.viewZ().view(),
                            signals.transportMetadata().view(),
                            signals.material().view(),
                            signals.specularMaterial().view(),
                            signals.primaryPosition().view(),
                            signals.diffuseDirection().view(),
                            signals.specularDirection().view(),
                            signals.sunLighting().view(),
                            signals.sunPenumbra().view(),
                            signals.rawNumericalDiagnostic().view(),
                            wavefrontPaths.handle());
                } catch (RuntimeException exception) {
                    VK12.vkDestroyDescriptorPool(context.vkDevice(), pool, null);
                    throw exception;
                }
            }
        }

        private boolean matches(
                long tlas,
                long stableRadianceView,
                long screenshotRunningMeanView,
                long atlasView,
                long atlasSampler,
                List<SceneTexture> sceneTextures,
                long labPbrNormalAtlas,
                long labPbrSpecularAtlas,
                long skyView,
                long transmittanceLow,
                long transmittanceHigh,
                long aerialRadiance,
                long aerialTransmittance,
                long nrdNoisyDiffuse,
                long nrdNoisySpecular,
                long nrdNormalRoughness,
                long nrdViewZ,
                long wavefrontTransportMetadata,
                long nrdMaterial,
                long nrdSpecularMaterial,
                long nrdPrimaryPosition,
                long nrdDiffuseDirection,
                long nrdSpecularDirection,
                long nrdSunLighting,
                long nrdSunPenumbra,
                long rawNumericalDiagnostic,
                long wavefrontPaths) {
            return this.tlas == tlas
                    && this.stableRadianceView == stableRadianceView
                    && this.screenshotRunningMeanView == screenshotRunningMeanView
                    && this.atlasView == atlasView
                    && this.atlasSampler == atlasSampler
                    && this.sceneTextures.equals(sceneTextures)
                    && this.labPbrNormalAtlas == labPbrNormalAtlas
                    && this.labPbrSpecularAtlas == labPbrSpecularAtlas
                    && this.skyView == skyView
                    && this.transmittanceLow == transmittanceLow
                    && this.transmittanceHigh == transmittanceHigh
                    && this.aerialRadiance == aerialRadiance
                    && this.aerialTransmittance == aerialTransmittance
                    && this.nrdNoisyDiffuse == nrdNoisyDiffuse
                    && this.nrdNoisySpecular == nrdNoisySpecular
                    && this.nrdNormalRoughness == nrdNormalRoughness
                    && this.nrdViewZ == nrdViewZ
                    && this.wavefrontTransportMetadata == wavefrontTransportMetadata
                    && this.nrdMaterial == nrdMaterial
                    && this.nrdSpecularMaterial == nrdSpecularMaterial
                    && this.nrdPrimaryPosition == nrdPrimaryPosition
                    && this.nrdDiffuseDirection == nrdDiffuseDirection
                    && this.nrdSpecularDirection == nrdSpecularDirection
                    && this.nrdSunLighting == nrdSunLighting
                    && this.nrdSunPenumbra == nrdSunPenumbra
                    && this.rawNumericalDiagnostic == rawNumericalDiagnostic
                    && this.wavefrontPaths == wavefrontPaths;
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
            }
        }
    }

    /** Borrowed Minecraft texture handles referenced by one descriptor generation. */
    public record SceneTexture(long image, long view, long sampler) {
        public SceneTexture {
            if (image == 0L || view == 0L || sampler == 0L) {
                throw new IllegalArgumentException(
                        "Dynamic scene texture handles must be nonzero");
            }
        }
    }
}
