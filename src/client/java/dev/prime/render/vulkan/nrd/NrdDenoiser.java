package dev.prime.render.vulkan.nrd;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
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
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Vulkan realization of NRD Core's API-independent dispatch descriptions.
 *
 * <p>NRD never owns or sees a Vulkan handle. Prime creates every image, pipeline, descriptor and
 * constant buffer, records all dispatches on the existing Minecraft command buffer and retires
 * frame bindings at the real queue completion point. This boundary is intentionally generic so a
 * later wavefront path scheduler can replace raygen without changing denoiser ownership.
 */
public final class NrdDenoiser implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int IMAGE_USAGE = VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT;
    private static final int COMPOSITE_BINDING_COUNT = 6;
    private static final int COMPOSITE_PUSH_SIZE = 8;
    // world.rgen writes 65504 for a sky view-Z. Keep the valid range below that sentinel while
    // remaining far beyond Minecraft's usable terrain and Prime's 16,000-block aerial volume.
    private static final float DENOISING_RANGE = 60_000.0f;
    private static final float SUN_HISTORY_DISCONTINUITY_COSINE =
            (float) Math.cos(Math.toRadians(1.0));

    private final VulkanContext context;
    private final int width;
    private final int height;
    private final NrdNative.Instance nativeInstance;
    private final NrdNative.Description description;
    private final Images images;
    private final long nearestSampler;
    private final long linearSampler;
    private final ComputePipeline[] pipelines;
    private final CompositePipeline composite;
    private final ArrayDeque<FrameBindings> freeBindings = new ArrayDeque<>();
    private final Set<FrameBindings> allBindings =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private FrameCamera previousCamera;
    private long previousSceneResetRevision = Long.MIN_VALUE;
    private long previousAtlasView;
    private long previousAtlasSampler;
    private SunDirection previousSunDirection;
    private float[] previousCameraJitter;
    private int frameIndex;
    private long previousSubmissionNanos;
    private boolean destroyed;

    private NrdDenoiser(
            VulkanContext context,
            int width,
            int height,
            NrdNative.Instance nativeInstance,
            Images images,
            long nearestSampler,
            long linearSampler,
            ComputePipeline[] pipelines,
            CompositePipeline composite) {
        this.context = context;
        this.width = width;
        this.height = height;
        this.nativeInstance = nativeInstance;
        this.description = nativeInstance.description();
        this.images = images;
        this.nearestSampler = nearestSampler;
        this.linearSampler = linearSampler;
        this.pipelines = pipelines;
        this.composite = composite;
    }

    public static NrdDenoiser create(
            VulkanContext context,
            int width,
            int height,
            VulkanImage output,
            VulkanImage stableAccumulation,
            AtmospherePipeline atmosphere) {
        NrdNative.Instance nativeInstance = NrdNative.create(width, height);
        Images images = null;
        long nearestSampler = 0L;
        long linearSampler = 0L;
        ComputePipeline[] pipelines = null;
        CompositePipeline composite = null;
        try {
            NrdNative.Description description = nativeInstance.description();
            validateNativeContract(description);
            images = Images.create(context, width, height, description);
            nearestSampler = createSampler(context, false, "Prime NRD nearest-clamp sampler");
            linearSampler = createSampler(context, true, "Prime NRD linear-clamp sampler");
            pipelines = createPipelines(context, description, nearestSampler, linearSampler);
            composite = CompositePipeline.create(
                    context, output, stableAccumulation, images, atmosphere);
            return new NrdDenoiser(
                    context,
                    width,
                    height,
                    nativeInstance,
                    images,
                    nearestSampler,
                    linearSampler,
                    pipelines,
                    composite);
        } catch (RuntimeException exception) {
            if (composite != null) {
                composite.destroy();
            }
            destroyPipelines(pipelines);
            if (linearSampler != 0L) {
                VK12.vkDestroySampler(context.vkDevice(), linearSampler, null);
            }
            if (nearestSampler != 0L) {
                VK12.vkDestroySampler(context.vkDevice(), nearestSampler, null);
            }
            if (images != null) {
                images.destroy();
            }
            nativeInstance.close();
            throw exception;
        }
    }

    public VulkanImage noisyDiffuse() {
        return this.images.noisyDiffuse;
    }

    public VulkanImage normalRoughness() {
        return this.images.normalRoughness;
    }

    public VulkanImage viewZ() {
        return this.images.viewZ;
    }

    public VulkanImage motion() {
        return this.images.motion;
    }

    public VulkanImage material() {
        return this.images.material;
    }

    /**
     * Makes the signal images writable by raygen and all NRD-owned images available to compute.
     * The GENERAL layout is stable for their complete lifetime; only explicit availability and
     * visibility dependencies change between ray tracing, denoising and composition.
     */
    public void prepareForRayTrace(VkCommandBuffer commandBuffer) {
        this.requireOpen();
        VulkanImage[] allImages = this.images.allImages();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(allImages.length, stack);
            for (int index = 0; index < allImages.length; index++) {
                VulkanImage image = allImages[index];
                boolean initialized = image.initialized();
                barriers.get(index)
                        .sType$Default()
                        .srcStageMask(initialized ? VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                        .srcAccessMask(initialized ? VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT : 0L)
                        .dstStageMask(
                                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                                        | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                        .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                        .oldLayout(initialized ? VK12.VK_IMAGE_LAYOUT_GENERAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED)
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
            issueImageBarrier(commandBuffer, stack, barriers);
        }
    }

    public FrameToken record(
            VkCommandBuffer commandBuffer,
            FrameCamera camera,
            long sceneResetRevision,
            long atlasView,
            long atlasSampler,
            SunDirection sunDirection,
            float cameraJitterX,
            float cameraJitterY) {
        this.requireOpen();
        boolean restart = this.previousCamera == null
                || sceneResetRevision != this.previousSceneResetRevision
                || atlasView != this.previousAtlasView
                || atlasSampler != this.previousAtlasSampler
                || sunDirectionDiscontinuous(sunDirection, this.previousSunDirection);
        FrameCamera historyCamera = restart ? camera : this.previousCamera;
        float[] cameraJitter = new float[] {cameraJitterX, cameraJitterY};
        float[] historyCameraJitter = restart ? cameraJitter : this.previousCameraJitter;
        int currentFrameIndex = restart ? 0 : this.frameIndex;
        long now = System.nanoTime();
        float deltaMilliseconds = this.previousSubmissionNanos == 0L
                ? 1000.0f / 60.0f
                : Math.min((now - this.previousSubmissionNanos) * 1.0e-6f, 1000.0f);
        this.nativeInstance.setFrameSettings(createFrameSettings(
                camera,
                historyCamera,
                cameraJitter,
                historyCameraJitter,
                this.width,
                this.height,
                currentFrameIndex,
                restart,
                deltaMilliseconds));
        List<NrdNative.Dispatch> dispatches = this.nativeInstance.getDispatches();
        FrameBindings bindings = this.acquireBindings();
        try {
            bindings.prepare(dispatches, this);
            rayTraceToComputeBarrier(commandBuffer);
            for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
                if (dispatchIndex != 0) {
                    computeToComputeBarrier(commandBuffer);
                }
                NrdNative.Dispatch dispatch = dispatches.get(dispatchIndex);
                ComputePipeline pipeline = this.pipelines[dispatch.pipelineIndex()];
                VK12.vkCmdBindPipeline(
                        commandBuffer,
                        VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                        pipeline.pipeline);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VK12.vkCmdBindDescriptorSets(
                            commandBuffer,
                            VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                            pipeline.pipelineLayout,
                            0,
                            stack.longs(
                                    bindings.resourceDescriptorSets[dispatchIndex],
                                    bindings.constantsDescriptorSets[dispatchIndex]),
                            null);
                }
                VK12.vkCmdDispatch(
                        commandBuffer,
                        dispatch.gridWidth(),
                        dispatch.gridHeight(),
                        1);
            }
            computeToComputeBarrier(commandBuffer);
            this.composite.record(commandBuffer, this.width, this.height);
            return new FrameToken(
                    this,
                    bindings,
                    camera,
                    sceneResetRevision,
                    atlasView,
                    atlasSampler,
                    sunDirection,
                    cameraJitter,
                    restart ? 1 : currentFrameIndex + 1,
                    now);
        } catch (RuntimeException exception) {
            this.recycle(bindings);
            throw exception;
        }
    }

    /** Must be called immediately after the command buffer containing {@code token} is submitted. */
    public void submitted(FrameToken token) {
        this.requireOpen();
        if (token.owner != this || token.submitted) {
            throw new IllegalArgumentException("NRD frame token does not belong to this submission");
        }
        token.submitted = true;
        this.previousCamera = token.camera;
        this.previousSceneResetRevision = token.sceneResetRevision;
        this.previousAtlasView = token.atlasView;
        this.previousAtlasSampler = token.atlasSampler;
        this.previousSunDirection = token.sunDirection;
        this.previousCameraJitter = token.cameraJitter;
        this.frameIndex = token.nextFrameIndex;
        this.previousSubmissionNanos = token.submissionNanos;
        this.context.afterSubmission(() -> this.recycle(token.bindings));
    }

    private FrameBindings acquireBindings() {
        synchronized (this.freeBindings) {
            FrameBindings bindings = this.freeBindings.pollFirst();
            if (bindings != null) {
                return bindings;
            }
        }
        FrameBindings created = FrameBindings.create(this);
        synchronized (this.freeBindings) {
            this.allBindings.add(created);
        }
        return created;
    }

    private void recycle(FrameBindings bindings) {
        synchronized (this.freeBindings) {
            if (this.destroyed) {
                bindings.destroy();
                this.allBindings.remove(bindings);
            } else {
                this.freeBindings.addLast(bindings);
            }
        }
    }

    VulkanImage resolveResource(NrdNative.Resource resource) {
        return switch (resource.resourceType()) {
            case NrdNative.RESOURCE_IN_MV -> this.images.motion;
            case NrdNative.RESOURCE_IN_NORMAL_ROUGHNESS -> this.images.normalRoughness;
            case NrdNative.RESOURCE_IN_VIEWZ -> this.images.viewZ;
            case NrdNative.RESOURCE_IN_DIFF_RADIANCE_HITDIST -> this.images.noisyDiffuse;
            case NrdNative.RESOURCE_OUT_DIFF_RADIANCE_HITDIST -> this.images.denoisedDiffuse;
            case NrdNative.RESOURCE_TRANSIENT_POOL -> checkedPoolImage(
                    this.images.transientPool, resource.indexInPool(), "transient");
            case NrdNative.RESOURCE_PERMANENT_POOL -> checkedPoolImage(
                    this.images.permanentPool, resource.indexInPool(), "permanent");
            default -> throw new IllegalStateException(
                    "REBLUR_DIFFUSE requested unsupported resource type " + resource.resourceType());
        };
    }

    private static VulkanImage checkedPoolImage(VulkanImage[] pool, int index, String name) {
        if (index < 0 || index >= pool.length) {
            throw new IllegalStateException("NRD " + name + " pool index is out of range: " + index);
        }
        return pool[index];
    }

    private static boolean sunDirectionDiscontinuous(
            SunDirection current,
            SunDirection previous) {
        if (previous == null) {
            return true;
        }
        float cosine = current.x() * previous.x()
                + current.y() * previous.y()
                + current.z() * previous.z();
        // Minecraft advances the sun a fraction of a degree per tick. REBLUR's anti-lag should
        // track that lighting change instead of discarding history twenty times per second; a
        // command-driven time jump remains a true discontinuity and restarts temporal history.
        return cosine < SUN_HISTORY_DISCONTINUITY_COSINE;
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        synchronized (this.freeBindings) {
            for (FrameBindings bindings : this.allBindings) {
                bindings.destroy();
            }
            this.allBindings.clear();
            this.freeBindings.clear();
        }
        this.composite.destroy();
        destroyPipelines(this.pipelines);
        VK12.vkDestroySampler(this.context.vkDevice(), this.linearSampler, null);
        VK12.vkDestroySampler(this.context.vkDevice(), this.nearestSampler, null);
        this.images.destroy();
        this.nativeInstance.close();
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("NRD denoiser is destroyed");
        }
    }

    private static void validateNativeContract(NrdNative.Description description) {
        if (description.nrdVersion() != NrdNative.EXPECTED_NRD_VERSION
                || description.samplerOffset() != 0
                || description.textureOffset() != 20
                || description.constantBufferOffset() != 2
                || description.storageTextureOffset() != 3
                || description.constantBufferAndSamplersSpaceIndex() != 1
                || description.resourcesSpaceIndex() != 0
                || description.samplersBaseRegisterIndex() != 0
                || description.resourcesBaseRegisterIndex() != 0
                || !description.samplers().equals(List.of(0, 1))
                || !"main".equals(description.shaderEntryPoint())) {
            throw new IllegalStateException("Bundled NRD library does not match Prime's Vulkan ABI contract");
        }
    }

    private static NrdNative.FrameSettings createFrameSettings(
            FrameCamera camera,
            FrameCamera previous,
            float[] cameraJitter,
            float[] previousCameraJitter,
            int width,
            int height,
            int frameIndex,
            boolean restart,
            float deltaMilliseconds) {
        Matrix4f previousWorldToView = new Matrix4f(previous.viewRotation())
                .translate(
                        (float) (camera.x() - previous.x()),
                        (float) (camera.y() - previous.y()),
                        (float) (camera.z() - previous.z()));
        return new NrdNative.FrameSettings(
                camera.projection().get(new float[16]),
                previous.projection().get(new float[16]),
                camera.viewRotation().get(new float[16]),
                previousWorldToView.get(new float[16]),
                cameraJitter,
                previousCameraJitter,
                width,
                height,
                width,
                height,
                frameIndex,
                restart,
                deltaMilliseconds,
                DENOISING_RANGE);
    }

    private static long createSampler(VulkanContext context, boolean linear, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int filter = linear ? VK12.VK_FILTER_LINEAR : VK12.VK_FILTER_NEAREST;
            VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(filter)
                    .minFilter(filter)
                    .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f)
                    .maxLod(0.0f);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateSampler(context.vkDevice(), createInfo, null, pointer),
                    "create " + label);
            long sampler = pointer.get(0);
            context.device().instance().debug().setObjectName(
                    context.vkDevice(), VK12.VK_OBJECT_TYPE_SAMPLER, sampler, label);
            return sampler;
        }
    }

    private static ComputePipeline[] createPipelines(
            VulkanContext context,
            NrdNative.Description description,
            long nearestSampler,
            long linearSampler) {
        ComputePipeline[] pipelines = new ComputePipeline[description.pipelines().size()];
        try {
            for (int index = 0; index < pipelines.length; index++) {
                pipelines[index] = ComputePipeline.create(
                        context,
                        description,
                        description.pipelines().get(index),
                        nearestSampler,
                        linearSampler,
                        index);
            }
            return pipelines;
        } catch (RuntimeException exception) {
            destroyPipelines(pipelines);
            throw exception;
        }
    }

    private static void destroyPipelines(ComputePipeline[] pipelines) {
        if (pipelines == null) {
            return;
        }
        for (int index = pipelines.length - 1; index >= 0; index--) {
            if (pipelines[index] != null) {
                pipelines[index].destroy();
            }
        }
    }

    private static void rayTraceToComputeBarrier(VkCommandBuffer commandBuffer) {
        memoryBarrier(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void computeToComputeBarrier(VkCommandBuffer commandBuffer) {
        memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void memoryBarrier(
            VkCommandBuffer commandBuffer,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(sourceStage)
                    .srcAccessMask(sourceAccess)
                    .dstStageMask(destinationStage)
                    .dstAccessMask(destinationAccess);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pMemoryBarriers(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private static void issueImageBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VkImageMemoryBarrier2.Buffer barriers) {
        VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pImageMemoryBarriers(barriers);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
    }

    private static int vkFormat(int nrdFormat) {
        return switch (nrdFormat) {
            case 0 -> VK12.VK_FORMAT_R8_UNORM;
            case 1 -> VK12.VK_FORMAT_R8_SNORM;
            case 2 -> VK12.VK_FORMAT_R8_UINT;
            case 3 -> VK12.VK_FORMAT_R8_SINT;
            case 4 -> VK12.VK_FORMAT_R8G8_UNORM;
            case 5 -> VK12.VK_FORMAT_R8G8_SNORM;
            case 6 -> VK12.VK_FORMAT_R8G8_UINT;
            case 7 -> VK12.VK_FORMAT_R8G8_SINT;
            case 8 -> VK12.VK_FORMAT_R8G8B8A8_UNORM;
            case 9 -> VK12.VK_FORMAT_R8G8B8A8_SNORM;
            case 10 -> VK12.VK_FORMAT_R8G8B8A8_UINT;
            case 11 -> VK12.VK_FORMAT_R8G8B8A8_SINT;
            case 12 -> VK12.VK_FORMAT_R8G8B8A8_SRGB;
            case 13 -> VK12.VK_FORMAT_R16_UNORM;
            case 14 -> VK12.VK_FORMAT_R16_SNORM;
            case 15 -> VK12.VK_FORMAT_R16_UINT;
            case 16 -> VK12.VK_FORMAT_R16_SINT;
            case 17 -> VK12.VK_FORMAT_R16_SFLOAT;
            case 18 -> VK12.VK_FORMAT_R16G16_UNORM;
            case 19 -> VK12.VK_FORMAT_R16G16_SNORM;
            case 20 -> VK12.VK_FORMAT_R16G16_UINT;
            case 21 -> VK12.VK_FORMAT_R16G16_SINT;
            case 22 -> VK12.VK_FORMAT_R16G16_SFLOAT;
            case 23 -> VK12.VK_FORMAT_R16G16B16A16_UNORM;
            case 24 -> VK12.VK_FORMAT_R16G16B16A16_SNORM;
            case 25 -> VK12.VK_FORMAT_R16G16B16A16_UINT;
            case 26 -> VK12.VK_FORMAT_R16G16B16A16_SINT;
            case 27 -> VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
            case 28 -> VK12.VK_FORMAT_R32_UINT;
            case 29 -> VK12.VK_FORMAT_R32_SINT;
            case 30 -> VK12.VK_FORMAT_R32_SFLOAT;
            case 31 -> VK12.VK_FORMAT_R32G32_UINT;
            case 32 -> VK12.VK_FORMAT_R32G32_SINT;
            case 33 -> VK12.VK_FORMAT_R32G32_SFLOAT;
            case 34 -> VK12.VK_FORMAT_R32G32B32_UINT;
            case 35 -> VK12.VK_FORMAT_R32G32B32_SINT;
            case 36 -> VK12.VK_FORMAT_R32G32B32_SFLOAT;
            case 37 -> VK12.VK_FORMAT_R32G32B32A32_UINT;
            case 38 -> VK12.VK_FORMAT_R32G32B32A32_SINT;
            case 39 -> VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
            case 40 -> VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case 41 -> VK12.VK_FORMAT_A2B10G10R10_UINT_PACK32;
            case 42 -> VK12.VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            case 43 -> VK12.VK_FORMAT_E5B9G9R9_UFLOAT_PACK32;
            default -> throw new IllegalStateException("Unsupported NRD texture format " + nrdFormat);
        };
    }

    public static final class FrameToken {
        private final NrdDenoiser owner;
        private final FrameBindings bindings;
        private final FrameCamera camera;
        private final long sceneResetRevision;
        private final long atlasView;
        private final long atlasSampler;
        private final SunDirection sunDirection;
        private final float[] cameraJitter;
        private final int nextFrameIndex;
        private final long submissionNanos;
        private boolean submitted;

        private FrameToken(
                NrdDenoiser owner,
                FrameBindings bindings,
                FrameCamera camera,
                long sceneResetRevision,
                long atlasView,
                long atlasSampler,
                SunDirection sunDirection,
                float[] cameraJitter,
                int nextFrameIndex,
                long submissionNanos) {
            this.owner = owner;
            this.bindings = bindings;
            this.camera = camera;
            this.sceneResetRevision = sceneResetRevision;
            this.atlasView = atlasView;
            this.atlasSampler = atlasSampler;
            this.sunDirection = sunDirection;
            this.cameraJitter = cameraJitter;
            this.nextFrameIndex = nextFrameIndex;
            this.submissionNanos = submissionNanos;
        }
    }

    private static final class Images implements Destroyable {
        private final VulkanImage noisyDiffuse;
        private final VulkanImage normalRoughness;
        private final VulkanImage viewZ;
        private final VulkanImage motion;
        private final VulkanImage material;
        private final VulkanImage denoisedDiffuse;
        private final VulkanImage[] permanentPool;
        private final VulkanImage[] transientPool;
        private boolean destroyed;

        private Images(
                VulkanImage noisyDiffuse,
                VulkanImage normalRoughness,
                VulkanImage viewZ,
                VulkanImage motion,
                VulkanImage material,
                VulkanImage denoisedDiffuse,
                VulkanImage[] permanentPool,
                VulkanImage[] transientPool) {
            this.noisyDiffuse = noisyDiffuse;
            this.normalRoughness = normalRoughness;
            this.viewZ = viewZ;
            this.motion = motion;
            this.material = material;
            this.denoisedDiffuse = denoisedDiffuse;
            this.permanentPool = permanentPool;
            this.transientPool = transientPool;
        }

        private static Images create(
                VulkanContext context,
                int width,
                int height,
                NrdNative.Description description) {
            ArrayList<VulkanImage> created = new ArrayList<>();
            try {
                VulkanImage noisy = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "Prime NRD noisy diffuse");
                VulkanImage normal = createImage(
                        context, created, width, height, VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32, "Prime NRD normal roughness");
                VulkanImage viewZ = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R32_SFLOAT, "Prime NRD view Z");
                VulkanImage motion = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "Prime NRD world motion");
                VulkanImage material = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "Prime NRD material factor");
                VulkanImage denoised = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "Prime NRD denoised diffuse");
                VulkanImage[] permanent = createPool(
                        context, created, width, height, description.permanentPool(), "permanent");
                VulkanImage[] transientImages = createPool(
                        context, created, width, height, description.transientPool(), "transient");
                return new Images(noisy, normal, viewZ, motion, material, denoised, permanent, transientImages);
            } catch (RuntimeException exception) {
                for (int index = created.size() - 1; index >= 0; index--) {
                    created.get(index).destroy();
                }
                throw exception;
            }
        }

        private static VulkanImage[] createPool(
                VulkanContext context,
                List<VulkanImage> created,
                int width,
                int height,
                List<NrdNative.TextureInfo> descriptions,
                String poolName) {
            VulkanImage[] pool = new VulkanImage[descriptions.size()];
            for (int index = 0; index < pool.length; index++) {
                NrdNative.TextureInfo texture = descriptions.get(index);
                int factor = texture.downsampleFactor();
                if (factor <= 0) {
                    throw new IllegalStateException("NRD returned a non-positive downsample factor");
                }
                int textureWidth = (width + factor - 1) / factor;
                int textureHeight = (height + factor - 1) / factor;
                pool[index] = createImage(
                        context,
                        created,
                        textureWidth,
                        textureHeight,
                        vkFormat(texture.format()),
                        "Prime NRD " + poolName + " " + index);
            }
            return pool;
        }

        private static VulkanImage createImage(
                VulkanContext context,
                List<VulkanImage> created,
                int width,
                int height,
                int format,
                String label) {
            VulkanImage image = context.createImage2D(width, height, format, IMAGE_USAGE, label);
            created.add(image);
            return image;
        }

        private VulkanImage[] allImages() {
            VulkanImage[] result = new VulkanImage[6 + this.permanentPool.length + this.transientPool.length];
            result[0] = this.noisyDiffuse;
            result[1] = this.normalRoughness;
            result[2] = this.viewZ;
            result[3] = this.motion;
            result[4] = this.material;
            result[5] = this.denoisedDiffuse;
            System.arraycopy(this.permanentPool, 0, result, 6, this.permanentPool.length);
            System.arraycopy(
                    this.transientPool,
                    0,
                    result,
                    6 + this.permanentPool.length,
                    this.transientPool.length);
            return result;
        }

        @Override
        public void destroy() {
            if (this.destroyed) {
                return;
            }
            this.destroyed = true;
            for (int index = this.transientPool.length - 1; index >= 0; index--) {
                this.transientPool[index].destroy();
            }
            for (int index = this.permanentPool.length - 1; index >= 0; index--) {
                this.permanentPool[index].destroy();
            }
            this.denoisedDiffuse.destroy();
            this.material.destroy();
            this.motion.destroy();
            this.viewZ.destroy();
            this.normalRoughness.destroy();
            this.noisyDiffuse.destroy();
        }
    }

    private static final class ComputePipeline implements Destroyable {
        private final VulkanContext context;
        private final long resourceDescriptorSetLayout;
        private final long constantsDescriptorSetLayout;
        private final long pipelineLayout;
        private final long pipeline;
        private boolean destroyed;

        private ComputePipeline(
                VulkanContext context,
                long resourceDescriptorSetLayout,
                long constantsDescriptorSetLayout,
                long pipelineLayout,
                long pipeline) {
            this.context = context;
            this.resourceDescriptorSetLayout = resourceDescriptorSetLayout;
            this.constantsDescriptorSetLayout = constantsDescriptorSetLayout;
            this.pipelineLayout = pipelineLayout;
            this.pipeline = pipeline;
        }

        private static ComputePipeline create(
                VulkanContext context,
                NrdNative.Description description,
                NrdNative.Pipeline pipelineDescription,
                long nearestSampler,
                long linearSampler,
                int pipelineIndex) {
            long resourceDescriptorSetLayout = 0L;
            long constantsDescriptorSetLayout = 0L;
            long pipelineLayout = 0L;
            long pipeline = 0L;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                resourceDescriptorSetLayout = createNrdResourceDescriptorSetLayout(
                        context, stack, description, pipelineDescription);
                constantsDescriptorSetLayout = createNrdConstantsDescriptorSetLayout(
                        context,
                        stack,
                        description,
                        pipelineDescription,
                        nearestSampler,
                        linearSampler);
                VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pSetLayouts(stack.longs(
                                resourceDescriptorSetLayout,
                                constantsDescriptorSetLayout));
                LongBuffer layoutPointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreatePipelineLayout(context.vkDevice(), layoutInfo, null, layoutPointer),
                        "create Prime NRD pipeline layout " + pipelineIndex);
                pipelineLayout = layoutPointer.get(0);
                long shaderModule = createShaderModule(
                        context, stack, pipelineDescription.spirv(), "Prime NRD shader " + pipelineIndex);
                try {
                    VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType$Default()
                            .stage(COMPUTE_STAGE)
                            .module(shaderModule)
                            .pName(stack.UTF8(description.shaderEntryPoint()));
                    VkComputePipelineCreateInfo.Buffer createInfo = VkComputePipelineCreateInfo.calloc(1, stack);
                    createInfo.get(0)
                            .sType$Default()
                            .stage(stage)
                            .layout(pipelineLayout);
                    LongBuffer pipelinePointer = stack.mallocLong(1);
                    VulkanContext.check(
                            VK12.vkCreateComputePipelines(
                                    context.vkDevice(), 0L, createInfo, null, pipelinePointer),
                            "create Prime NRD compute pipeline " + pipelineDescription.identifier());
                    pipeline = pipelinePointer.get(0);
                } finally {
                    VK12.vkDestroyShaderModule(context.vkDevice(), shaderModule, null);
                }
                return new ComputePipeline(
                        context,
                        resourceDescriptorSetLayout,
                        constantsDescriptorSetLayout,
                        pipelineLayout,
                        pipeline);
            } catch (RuntimeException exception) {
                if (pipeline != 0L) {
                    VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
                }
                if (pipelineLayout != 0L) {
                    VK12.vkDestroyPipelineLayout(context.vkDevice(), pipelineLayout, null);
                }
                if (constantsDescriptorSetLayout != 0L) {
                    VK12.vkDestroyDescriptorSetLayout(
                            context.vkDevice(), constantsDescriptorSetLayout, null);
                }
                if (resourceDescriptorSetLayout != 0L) {
                    VK12.vkDestroyDescriptorSetLayout(
                            context.vkDevice(), resourceDescriptorSetLayout, null);
                }
                throw exception;
            }
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
                VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
                VK12.vkDestroyDescriptorSetLayout(
                        this.context.vkDevice(), this.constantsDescriptorSetLayout, null);
                VK12.vkDestroyDescriptorSetLayout(
                        this.context.vkDevice(), this.resourceDescriptorSetLayout, null);
            }
        }
    }

    private static long createNrdResourceDescriptorSetLayout(
            VulkanContext context,
            MemoryStack stack,
            NrdNative.Description description,
            NrdNative.Pipeline pipeline) {
        int resourceCount = pipeline.ranges().stream()
                .mapToInt(NrdNative.PipelineRange::descriptorsNum)
                .sum();
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(resourceCount, stack);
        int bindingIndex = 0;
        int textureIndex = 0;
        int storageIndex = 0;
        for (NrdNative.PipelineRange range : pipeline.ranges()) {
            for (int descriptorIndex = 0; descriptorIndex < range.descriptorsNum(); descriptorIndex++) {
                int binding;
                int descriptorType;
                if (range.descriptorType() == NrdNative.DESCRIPTOR_TEXTURE) {
                    binding = description.textureOffset()
                            + description.resourcesBaseRegisterIndex()
                            + textureIndex++;
                    descriptorType = VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
                } else if (range.descriptorType() == NrdNative.DESCRIPTOR_STORAGE_TEXTURE) {
                    binding = description.storageTextureOffset()
                            + description.resourcesBaseRegisterIndex()
                            + storageIndex++;
                    descriptorType = VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                } else {
                    throw new IllegalStateException("Unknown NRD descriptor range type " + range.descriptorType());
                }
                bindings.get(bindingIndex++)
                        .binding(binding)
                        .descriptorType(descriptorType)
                        .descriptorCount(1)
                        .stageFlags(COMPUTE_STAGE);
            }
        }
        VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(context.vkDevice(), createInfo, null, pointer),
                "create Prime NRD resource descriptor set layout");
        return pointer.get(0);
    }

    private static long createNrdConstantsDescriptorSetLayout(
            VulkanContext context,
            MemoryStack stack,
            NrdNative.Description description,
            NrdNative.Pipeline pipeline,
            long nearestSampler,
            long linearSampler) {
        int bindingCount = description.samplers().size() + (pipeline.hasConstantData() ? 1 : 0);
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
        int bindingIndex = 0;
        long[] immutableSamplers = new long[] {nearestSampler, linearSampler};
        if (description.samplers().size() != immutableSamplers.length) {
            throw new IllegalStateException("Prime expects NRD's nearest and linear samplers");
        }
        for (int samplerIndex = 0; samplerIndex < description.samplers().size(); samplerIndex++) {
            bindings.get(bindingIndex++)
                    .binding(description.samplerOffset()
                            + description.samplersBaseRegisterIndex()
                            + samplerIndex)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(COMPUTE_STAGE)
                    .pImmutableSamplers(stack.longs(immutableSamplers[samplerIndex]));
        }
        if (pipeline.hasConstantData()) {
            bindings.get(bindingIndex)
                    .binding(description.constantBufferOffset()
                            + description.constantBufferRegisterIndex())
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(COMPUTE_STAGE);
        }
        VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
        LongBuffer pointer = stack.mallocLong(1);
        VulkanContext.check(
                VK12.vkCreateDescriptorSetLayout(context.vkDevice(), createInfo, null, pointer),
                "create Prime NRD constants descriptor set layout");
        return pointer.get(0);
    }

    private static long createShaderModule(
            VulkanContext context,
            MemoryStack stack,
            byte[] bytes,
            String label) {
        // NRD embeds several large compute shaders. MemoryStack is deliberately reserved for the
        // small Vulkan structs below: putting SPIR-V there can exhaust LWJGL's fixed per-thread
        // stack while all NRD pipelines are created during the first rendered frame.
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code);
            LongBuffer pointer = stack.mallocLong(1);
            VulkanContext.check(
                    VK12.vkCreateShaderModule(context.vkDevice(), createInfo, null, pointer),
                    "create " + label);
            return pointer.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static final class FrameBindings implements Destroyable {
        private final NrdDenoiser owner;
        private final long descriptorPool;
        private final VulkanBuffer constantBuffer;
        private long[] resourceDescriptorSets = new long[0];
        private long[] constantsDescriptorSets = new long[0];
        private boolean destroyed;

        private FrameBindings(
                NrdDenoiser owner,
                long descriptorPool,
                VulkanBuffer constantBuffer) {
            this.owner = owner;
            this.descriptorPool = descriptorPool;
            this.constantBuffer = constantBuffer;
        }

        private static FrameBindings create(NrdDenoiser owner) {
            NrdNative.Description description = owner.description;
            int sets = Math.max(description.setsMaxNum(), 1);
            int maxTextures = 1;
            int maxStorages = 1;
            for (NrdNative.Pipeline pipeline : description.pipelines()) {
                int textures = 0;
                int storages = 0;
                for (NrdNative.PipelineRange range : pipeline.ranges()) {
                    if (range.descriptorType() == NrdNative.DESCRIPTOR_TEXTURE) {
                        textures += range.descriptorsNum();
                    } else {
                        storages += range.descriptorsNum();
                    }
                }
                maxTextures = Math.max(maxTextures, textures);
                maxStorages = Math.max(maxStorages, storages);
            }
            long descriptorPool = 0L;
            VulkanBuffer constantBuffer = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
                sizes.get(0)
                        .type(VK12.VK_DESCRIPTOR_TYPE_SAMPLER)
                        .descriptorCount(sets * description.samplers().size());
                sizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(sets);
                sizes.get(2)
                        .type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .descriptorCount(sets * maxTextures);
                sizes.get(3)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(sets * maxStorages);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(Math.multiplyExact(sets, 2))
                        .pPoolSizes(sizes);
                LongBuffer poolPointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(owner.context.vkDevice(), poolInfo, null, poolPointer),
                        "create Prime NRD frame descriptor pool");
                descriptorPool = poolPointer.get(0);
                long stride = VulkanContext.alignUp(
                        Math.max(description.constantBufferMaxDataSize(), 1),
                        Math.max(owner.context.uniformBufferOffsetAlignment(), 1L));
                constantBuffer = owner.context.createBuffer(
                        Math.multiplyExact(stride, sets),
                        VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                        true,
                        "Prime NRD frame constants");
                return new FrameBindings(owner, descriptorPool, constantBuffer);
            } catch (RuntimeException exception) {
                if (constantBuffer != null) {
                    constantBuffer.destroy();
                }
                if (descriptorPool != 0L) {
                    VK12.vkDestroyDescriptorPool(owner.context.vkDevice(), descriptorPool, null);
                }
                throw exception;
            }
        }

        private void prepare(List<NrdNative.Dispatch> dispatches, NrdDenoiser denoiser) {
            if (this.destroyed) {
                throw new IllegalStateException("NRD frame bindings are destroyed");
            }
            if (dispatches.size() > denoiser.description.setsMaxNum()) {
                throw new IllegalStateException("NRD dispatch count exceeds its descriptor pool contract");
            }
            VulkanContext.check(
                    VK12.vkResetDescriptorPool(denoiser.context.vkDevice(), this.descriptorPool, 0),
                    "reset Prime NRD descriptor pool");
            this.resourceDescriptorSets = new long[dispatches.size()];
            this.constantsDescriptorSets = new long[dispatches.size()];
            if (dispatches.isEmpty()) {
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer layouts = stack.mallocLong(Math.multiplyExact(dispatches.size(), 2));
                for (NrdNative.Dispatch dispatch : dispatches) {
                    ComputePipeline pipeline = denoiser.pipelines[dispatch.pipelineIndex()];
                    layouts.put(pipeline.resourceDescriptorSetLayout);
                    layouts.put(pipeline.constantsDescriptorSetLayout);
                }
                layouts.flip();
                VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default()
                        .descriptorPool(this.descriptorPool)
                        .pSetLayouts(layouts);
                LongBuffer sets = stack.mallocLong(Math.multiplyExact(dispatches.size(), 2));
                VulkanContext.check(
                        VK12.vkAllocateDescriptorSets(denoiser.context.vkDevice(), allocateInfo, sets),
                        "allocate Prime NRD frame descriptor sets");
                for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
                    this.resourceDescriptorSets[dispatchIndex] = sets.get();
                    this.constantsDescriptorSets[dispatchIndex] = sets.get();
                }

                long constantStride = VulkanContext.alignUp(
                        Math.max(denoiser.description.constantBufferMaxDataSize(), 1),
                        Math.max(denoiser.context.uniformBufferOffsetAlignment(), 1L));
                for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
                    this.writeDispatch(
                            stack,
                            dispatchIndex,
                            dispatches.get(dispatchIndex),
                            constantStride,
                            denoiser);
                }
            }
        }

        private void writeDispatch(
                MemoryStack stack,
                int dispatchIndex,
                NrdNative.Dispatch dispatch,
                long constantStride,
                NrdDenoiser denoiser) {
            boolean hasConstants = denoiser.description.pipelines()
                    .get(dispatch.pipelineIndex())
                    .hasConstantData();
            if (hasConstants && dispatch.constantData().length == 0) {
                throw new IllegalStateException("NRD pipeline requires missing constant data");
            }
            if (dispatch.constantData().length > denoiser.description.constantBufferMaxDataSize()) {
                throw new IllegalStateException("NRD constant data exceeds its declared maximum");
            }
            int writeCount = dispatch.resources().size() + (hasConstants ? 1 : 0);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
            VkDescriptorImageInfo.Buffer imageInfos =
                    VkDescriptorImageInfo.calloc(dispatch.resources().size(), stack);
            int writeIndex = 0;
            if (hasConstants) {
                long constantOffset = constantStride * dispatchIndex;
                ByteBuffer source = stack.malloc(dispatch.constantData().length)
                        .put(dispatch.constantData())
                        .flip();
                this.constantBuffer.put(constantOffset, source);
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(this.constantBuffer.handle())
                        .offset(constantOffset)
                        .range(dispatch.constantData().length);
                writes.get(writeIndex++)
                        .sType$Default()
                        .dstSet(this.constantsDescriptorSets[dispatchIndex])
                        .dstBinding(denoiser.description.constantBufferOffset()
                                + denoiser.description.constantBufferRegisterIndex())
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .pBufferInfo(bufferInfo);
            }
            int textureIndex = 0;
            int storageIndex = 0;
            for (int resourceIndex = 0; resourceIndex < dispatch.resources().size(); resourceIndex++) {
                NrdNative.Resource resource = dispatch.resources().get(resourceIndex);
                VulkanImage image = denoiser.resolveResource(resource);
                int binding;
                int descriptorType;
                if (resource.descriptorType() == NrdNative.DESCRIPTOR_TEXTURE) {
                    binding = denoiser.description.textureOffset()
                            + denoiser.description.resourcesBaseRegisterIndex()
                            + textureIndex++;
                    descriptorType = VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
                } else if (resource.descriptorType() == NrdNative.DESCRIPTOR_STORAGE_TEXTURE) {
                    binding = denoiser.description.storageTextureOffset()
                            + denoiser.description.resourcesBaseRegisterIndex()
                            + storageIndex++;
                    descriptorType = VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                } else {
                    throw new IllegalStateException("Unknown NRD resource descriptor type " + resource.descriptorType());
                }
                imageInfos.get(resourceIndex)
                        .imageView(image.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(writeIndex++)
                        .sType$Default()
                        .dstSet(this.resourceDescriptorSets[dispatchIndex])
                        .dstBinding(binding)
                        .descriptorCount(1)
                        .descriptorType(descriptorType)
                        .pImageInfo(VkDescriptorImageInfo.create(
                                imageInfos.get(resourceIndex).address(), 1));
            }
            VK12.vkUpdateDescriptorSets(denoiser.context.vkDevice(), writes, null);
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                this.constantBuffer.destroy();
                VK12.vkDestroyDescriptorPool(
                        this.owner.context.vkDevice(), this.descriptorPool, null);
            }
        }
    }

    private static final class CompositePipeline implements Destroyable {
        private final VulkanContext context;
        private final long descriptorSetLayout;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long pipelineLayout;
        private final long pipeline;
        private boolean destroyed;

        private CompositePipeline(
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

        private static CompositePipeline create(
                VulkanContext context,
                VulkanImage output,
                VulkanImage stableAccumulation,
                Images images,
                AtmospherePipeline atmosphere) {
            long descriptorSetLayout = 0L;
            long descriptorPool = 0L;
            long descriptorSet = 0L;
            long pipelineLayout = 0L;
            long pipeline = 0L;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorSetLayoutBinding.Buffer bindings =
                        VkDescriptorSetLayoutBinding.calloc(COMPOSITE_BINDING_COUNT, stack);
                for (int index = 0; index < COMPOSITE_BINDING_COUNT; index++) {
                    bindings.get(index)
                            .binding(index)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .descriptorCount(1)
                            .stageFlags(COMPUTE_STAGE);
                }
                VkDescriptorSetLayoutCreateInfo setLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pBindings(bindings);
                LongBuffer pointer = stack.mallocLong(1);
                VulkanContext.check(
                        VK12.vkCreateDescriptorSetLayout(context.vkDevice(), setLayoutInfo, null, pointer),
                        "create Prime NRD composite descriptor layout");
                descriptorSetLayout = pointer.get(0);

                VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(COMPUTE_STAGE)
                        .offset(0)
                        .size(COMPOSITE_PUSH_SIZE);
                VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pSetLayouts(stack.longs(descriptorSetLayout))
                        .pPushConstantRanges(pushRange);
                pointer.clear();
                VulkanContext.check(
                        VK12.vkCreatePipelineLayout(context.vkDevice(), pipelineLayoutInfo, null, pointer),
                        "create Prime NRD composite pipeline layout");
                pipelineLayout = pointer.get(0);

                long shaderModule = createResourceShaderModule(
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
                    VulkanContext.check(
                            VK12.vkCreateComputePipelines(
                                    context.vkDevice(), 0L, pipelineInfo, null, pointer),
                            "create Prime NRD composite pipeline");
                    pipeline = pointer.get(0);
                } finally {
                    VK12.vkDestroyShaderModule(context.vkDevice(), shaderModule, null);
                }

                VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(COMPOSITE_BINDING_COUNT);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(1)
                        .pPoolSizes(poolSize);
                pointer.clear();
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(context.vkDevice(), poolInfo, null, pointer),
                        "create Prime NRD composite descriptor pool");
                descriptorPool = pointer.get(0);
                VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default()
                        .descriptorPool(descriptorPool)
                        .pSetLayouts(stack.longs(descriptorSetLayout));
                pointer.clear();
                VulkanContext.check(
                        VK12.vkAllocateDescriptorSets(context.vkDevice(), allocateInfo, pointer),
                        "allocate Prime NRD composite descriptor set");
                descriptorSet = pointer.get(0);

                VulkanImage[] descriptorImages = new VulkanImage[] {
                    output,
                    images.denoisedDiffuse,
                    images.material,
                    stableAccumulation,
                    atmosphere.aerialRadiance(),
                    atmosphere.aerialTransmittance()
                };
                VkDescriptorImageInfo.Buffer imageInfos =
                        VkDescriptorImageInfo.calloc(COMPOSITE_BINDING_COUNT, stack);
                VkWriteDescriptorSet.Buffer writes =
                        VkWriteDescriptorSet.calloc(COMPOSITE_BINDING_COUNT, stack);
                for (int index = 0; index < COMPOSITE_BINDING_COUNT; index++) {
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
                return new CompositePipeline(
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

        private void record(VkCommandBuffer commandBuffer, int width, int height) {
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
                ByteBuffer push = stack.malloc(COMPOSITE_PUSH_SIZE).order(ByteOrder.nativeOrder());
                push.putInt(0, width);
                push.putInt(4, height);
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

    private static long createResourceShaderModule(
            VulkanContext context,
            MemoryStack stack,
            String resourceName) {
        byte[] bytes;
        try (InputStream input = NrdDenoiser.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing shader resource " + resourceName);
            }
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read shader resource " + resourceName, exception);
        }
        return createShaderModule(context, stack, bytes, resourceName);
    }
}
