package dev.prime.render.vulkan.nrd;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.FrameCamera;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.SunDirection;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.VulkanBuffer;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.WavefrontSignals;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
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
public final class NrdDenoiser implements Destroyable, WavefrontSignals {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int IMAGE_USAGE = VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT;
    static final int MOTION_NRD_BINDING = 0;
    static final int MOTION_FSR_BINDING = 23;
    static final int MOTION_BINDING_COUNT = 24;
    private static final int MOTION_PUSH_SIZE = ShaderAbi.NRD_MOTION_PUSH_CONSTANT_SIZE;
    private static final int COMPOSITE_BINDING_COUNT = 31;
    private static final int COMPOSITE_PUSH_SIZE = 28;
    // Wavefront resolve writes 65504 for a sky view-Z. Keep the valid range below that sentinel while
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
    private final MotionPipeline motionPipeline;
    private final CompositePipeline composite;
    private final Matrix4f currentNrdProjection = new Matrix4f();
    private final Matrix4f previousNrdProjection = new Matrix4f();
    private final Matrix4f previousWorldToView = new Matrix4f();
    private final ArrayDeque<FrameBindings> freeBindings = new ArrayDeque<>();
    private final Set<FrameBindings> allBindings =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private FrameCamera previousCamera;
    private long previousSceneResetRevision = Long.MIN_VALUE;
    private long previousAtlasView;
    private long previousAtlasSampler;
    private SunDirection previousSunDirection;
    private float previousCameraJitterX;
    private float previousCameraJitterY;
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
            MotionPipeline motionPipeline,
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
        this.motionPipeline = motionPipeline;
        this.composite = composite;
    }

    static <T> void validateMotionBindings(
            T[] descriptorImages, T nrdMotion, T fsrMotion) {
        if (descriptorImages.length != MOTION_BINDING_COUNT
                || descriptorImages[MOTION_NRD_BINDING] != nrdMotion
                || descriptorImages[MOTION_FSR_BINDING] != fsrMotion
                || nrdMotion == fsrMotion) {
            throw new IllegalStateException(
                    "NRD and FSR motion outputs must use distinct ABI bindings");
        }
    }

    public static NrdDenoiser create(
            VulkanContext context,
            int width,
            int height,
            VulkanImage output,
            VulkanImage stableAccumulation,
            AtmospherePipeline atmosphere) {
        String debugPrefix = "Prime NRD";
        NrdNative.Instance nativeInstance = NrdNative.create(width, height);
        Images images = null;
        long nearestSampler = 0L;
        long linearSampler = 0L;
        ComputePipeline[] pipelines = null;
        MotionPipeline motionPipeline = null;
        CompositePipeline composite = null;
        try {
            NrdNative.Description description = nativeInstance.description();
            validateNativeContract(description);
            images = Images.create(
                    context,
                    width,
                    height,
                    description,
                    debugPrefix);
            nearestSampler = createSampler(context, false, debugPrefix + " nearest-clamp sampler");
            linearSampler = createSampler(context, true, debugPrefix + " linear-clamp sampler");
            pipelines = createPipelines(context, description, nearestSampler, linearSampler);
            motionPipeline = MotionPipeline.create(
                    context, images, "/prime/shaders/nrd_motion.comp.spv", debugPrefix);
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
                    motionPipeline,
                    composite);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(composite, exception);
            ResourceCleanup.destroy(motionPipeline, exception);
            destroyPipelines(pipelines, exception);
            if (linearSampler != 0L) {
                VK12.vkDestroySampler(context.vkDevice(), linearSampler, null);
            }
            if (nearestSampler != 0L) {
                VK12.vkDestroySampler(context.vkDevice(), nearestSampler, null);
            }
            ResourceCleanup.destroy(images, exception);
            ResourceCleanup.close(nativeInstance, exception);
            throw exception;
        }
    }

    public VulkanImage noisyDiffuse() {
        return this.images.noisyDiffuse;
    }

    public VulkanImage noisySpecular() {
        return this.images.noisySpecular;
    }

    @Override
    public VulkanImage diffuseDirection() {
        return this.images.noisyDiffuseSh1;
    }

    @Override
    public VulkanImage specularDirection() {
        return this.images.noisySpecularSh1;
    }

    @Override
    public boolean usesShInputs() {
        return true;
    }

    public VulkanImage denoisedDiffuse() {
        return this.images.denoisedDiffuse;
    }

    public VulkanImage denoisedSpecular() {
        return this.images.denoisedSpecular;
    }

    public VulkanImage specularMaterial() {
        return this.images.specularMaterial;
    }

    public VulkanImage normalRoughness() {
        return this.images.normalRoughness;
    }

    public VulkanImage viewZ() {
        return this.images.viewZ;
    }

    public VulkanImage motion() {
        return this.images.fsrMotion;
    }

    public VulkanImage fsrDepth() {
        return this.images.fsrDepth;
    }

    public VulkanImage material() {
        return this.images.material;
    }

    public VulkanImage primaryPosition() {
        return this.images.primaryPosition;
    }

    @Override public VulkanImage reflectionNoisyDiffuse() { return this.images.reflectionNoisyDiffuse; }
    @Override public VulkanImage reflectionNoisySpecular() { return this.images.reflectionNoisySpecular; }
    @Override public VulkanImage reflectionNormalRoughness() { return this.images.reflectionNormalRoughness; }
    @Override public VulkanImage reflectionMaterial() { return this.images.reflectionMaterial; }
    @Override public VulkanImage reflectionSpecularMaterial() { return this.images.reflectionSpecularMaterial; }
    @Override public VulkanImage reflectionPosition() { return this.images.reflectionPosition; }
    @Override public VulkanImage reflectionDiffuseDirection() { return this.images.reflectionNoisyDiffuseSh1; }
    @Override public VulkanImage reflectionSpecularDirection() { return this.images.reflectionNoisySpecularSh1; }
    @Override public VulkanImage displayPosition() { return this.images.displayPosition; }

    public VulkanImage sunLighting() {
        return this.images.sunLighting;
    }

    public VulkanImage sunPenumbra() {
        return this.images.sunPenumbra;
    }

    public VulkanImage validation() {
        return this.images.validation;
    }

    @Override
    public VulkanImage rawNumericalDiagnostic() {
        return this.images.reprojectionError;
    }

    public VulkanImage fsrReactiveMask() {
        return this.images.fsrReactiveMask;
    }

    public VulkanImage fsrTransparencyCompositionMask() {
        return this.images.fsrTransparencyCompositionMask;
    }

    /**
     * Makes the raw signal images writable by raygen and all NRD-owned images available to the
     * preparation and denoising compute passes. The GENERAL layout is stable for their complete
     * lifetime; only explicit availability and visibility dependencies change between stages.
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
            float cameraJitterY,
            float sunRadianceMultiplier,
            float displayOverexposure,
            boolean forceRestart,
            NrdDiagnostics.Mode selectedDiagnostic) {
        return this.recordInternal(
                commandBuffer,
                camera,
                sceneResetRevision,
                atlasView,
                atlasSampler,
                sunDirection,
                cameraJitterX,
                cameraJitterY,
                sunRadianceMultiplier,
                displayOverexposure,
                forceRestart,
                selectedDiagnostic);
    }

    private FrameToken recordInternal(
            VkCommandBuffer commandBuffer,
            FrameCamera camera,
            long sceneResetRevision,
            long atlasView,
            long atlasSampler,
            SunDirection sunDirection,
            float cameraJitterX,
            float cameraJitterY,
            float sunRadianceMultiplier,
            float displayOverexposure,
            boolean forceRestart,
            NrdDiagnostics.Mode selectedDiagnostic) {
        this.requireOpen();
        Objects.requireNonNull(selectedDiagnostic, "selectedDiagnostic");
        boolean restart = forceRestart
                || this.previousCamera == null
                || sceneResetRevision != this.previousSceneResetRevision
                || atlasView != this.previousAtlasView
                || atlasSampler != this.previousAtlasSampler
                || sunDirectionDiscontinuous(sunDirection, this.previousSunDirection);
        FrameCamera historyCamera = restart ? camera : this.previousCamera;
        int diagnosticMode = selectedDiagnostic.outputSelector();
        float historyCameraJitterX = restart ? cameraJitterX : this.previousCameraJitterX;
        float historyCameraJitterY = restart ? cameraJitterY : this.previousCameraJitterY;
        int currentFrameIndex = restart ? 0 : this.frameIndex;
        long now = System.nanoTime();
        float deltaMilliseconds = this.previousSubmissionNanos == 0L
                ? 1000.0f / 60.0f
                : Math.min((now - this.previousSubmissionNanos) * 1.0e-6f, 1000.0f);
        this.nativeInstance.setFrameSettings(createFrameSettings(
                camera,
                historyCamera,
                cameraJitterX,
                cameraJitterY,
                historyCameraJitterX,
                historyCameraJitterY,
                this.width,
                this.height,
                currentFrameIndex,
                restart,
                deltaMilliseconds,
                selectedDiagnostic.nativeValidation(),
                sunDirection));
        NrdNative.DispatchList dispatches = this.nativeInstance.getDispatches();
        FrameBindings bindings = this.acquireBindings(dispatches.size());
        try {
            bindings.prepare(dispatches, this);
            rayTraceToComputeBarrier(commandBuffer);
            this.motionPipeline.record(
                    commandBuffer,
                    camera,
                    historyCamera,
                    this.width,
                    this.height,
                    diagnosticMode,
                    cameraJitterX,
                    cameraJitterY);
            computeToComputeBarrier(commandBuffer);
            for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
                if (dispatchIndex != 0) {
                    computeToComputeBarrier(commandBuffer);
                }
                ComputePipeline pipeline = this.pipelines[dispatches.pipelineIndex(dispatchIndex)];
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
                        dispatches.gridWidth(dispatchIndex),
                        dispatches.gridHeight(dispatchIndex),
                        1);
            }
            computeToComputeBarrier(commandBuffer);
            this.composite.record(
                    commandBuffer,
                    this.width,
                    this.height,
                    diagnosticMode,
                    sunRadianceMultiplier,
                    cameraJitterX,
                    cameraJitterY,
                    displayOverexposure);
            return new FrameToken(
                    this,
                    bindings,
                    camera,
                    sceneResetRevision,
                    atlasView,
                    atlasSampler,
                    sunDirection,
                    cameraJitterX,
                    cameraJitterY,
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
        this.previousCameraJitterX = token.cameraJitterX;
        this.previousCameraJitterY = token.cameraJitterY;
        this.frameIndex = token.nextFrameIndex;
        this.previousSubmissionNanos = token.submissionNanos;
        this.context.afterSubmission(() -> this.recycle(token.bindings));
    }

    private FrameBindings acquireBindings(int requiredDispatches) {
        synchronized (this.freeBindings) {
            for (Iterator<FrameBindings> iterator = this.freeBindings.iterator(); iterator.hasNext();) {
                FrameBindings bindings = iterator.next();
                if (bindings.dispatchCapacity >= requiredDispatches) {
                    iterator.remove();
                    return bindings;
                }
            }
        }
        FrameBindings created = FrameBindings.create(this, requiredDispatches);
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

    VulkanImage resolveResource(int resourceType, int indexInPool, int identifier) {
        boolean reflection = identifier == 2;
        return switch (resourceType) {
            case NrdNative.RESOURCE_IN_MV -> reflection ? this.images.reflectionMotion : this.images.motion;
            case NrdNative.RESOURCE_IN_NORMAL_ROUGHNESS -> reflection ? this.images.reflectionNormalRoughness : this.images.normalRoughness;
            case NrdNative.RESOURCE_IN_VIEWZ -> reflection ? this.images.reflectionViewZ : this.images.viewZ;
            case NrdNative.RESOURCE_IN_DIFF_RADIANCE_HITDIST -> reflection ? this.images.reflectionNoisyDiffuse : this.images.noisyDiffuse;
            case NrdNative.RESOURCE_IN_SPEC_RADIANCE_HITDIST -> reflection ? this.images.reflectionNoisySpecular : this.images.noisySpecular;
            case NrdNative.RESOURCE_IN_DIFF_SH0 -> reflection ? this.images.reflectionNoisyDiffuse : this.images.noisyDiffuse;
            case NrdNative.RESOURCE_IN_DIFF_SH1 -> reflection ? this.images.reflectionNoisyDiffuseSh1 : this.images.noisyDiffuseSh1;
            case NrdNative.RESOURCE_IN_SPEC_SH0 -> reflection ? this.images.reflectionNoisySpecular : this.images.noisySpecular;
            case NrdNative.RESOURCE_IN_SPEC_SH1 -> reflection ? this.images.reflectionNoisySpecularSh1 : this.images.noisySpecularSh1;
            case NrdNative.RESOURCE_IN_PENUMBRA -> this.images.sunPenumbra;
            case NrdNative.RESOURCE_OUT_DIFF_RADIANCE_HITDIST -> reflection ? this.images.reflectionDenoisedDiffuse : this.images.denoisedDiffuse;
            case NrdNative.RESOURCE_OUT_SPEC_RADIANCE_HITDIST -> reflection ? this.images.reflectionDenoisedSpecular : this.images.denoisedSpecular;
            case NrdNative.RESOURCE_OUT_DIFF_SH0 -> reflection ? this.images.reflectionDenoisedDiffuse : this.images.denoisedDiffuse;
            case NrdNative.RESOURCE_OUT_DIFF_SH1 -> reflection ? this.images.reflectionDenoisedDiffuseSh1 : this.images.denoisedDiffuseSh1;
            case NrdNative.RESOURCE_OUT_SPEC_SH0 -> reflection ? this.images.reflectionDenoisedSpecular : this.images.denoisedSpecular;
            case NrdNative.RESOURCE_OUT_SPEC_SH1 -> reflection ? this.images.reflectionDenoisedSpecularSh1 : this.images.denoisedSpecularSh1;
            case NrdNative.RESOURCE_OUT_SHADOW_TRANSLUCENCY -> this.images.sunShadow;
            case NrdNative.RESOURCE_OUT_VALIDATION -> this.images.validation;
            case NrdNative.RESOURCE_TRANSIENT_POOL -> checkedPoolImage(
                    this.images.transientPool, indexInPool, "transient");
            case NrdNative.RESOURCE_PERMANENT_POOL -> checkedPoolImage(
                    this.images.permanentPool, indexInPool, "permanent");
            default -> throw new IllegalStateException(
                    "NRD requested unsupported resource type "
                            + resourceType);
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
        // Submission-completion callbacks may race teardown. Publish terminal ownership first;
        // the binding lock then makes each late recycle destroy rather than requeue its binding.
        this.destroyed = true;
        RuntimeException failure = null;
        synchronized (this.freeBindings) {
            for (FrameBindings bindings : this.allBindings) {
                failure = ResourceCleanup.destroy(bindings, failure);
            }
            this.allBindings.clear();
            this.freeBindings.clear();
        }
        failure = ResourceCleanup.destroy(this.composite, failure);
        failure = ResourceCleanup.destroy(this.motionPipeline, failure);
        failure = destroyPipelines(this.pipelines, failure);
        VK12.vkDestroySampler(this.context.vkDevice(), this.linearSampler, null);
        VK12.vkDestroySampler(this.context.vkDevice(), this.nearestSampler, null);
        failure = ResourceCleanup.destroy(this.images, failure);
        failure = ResourceCleanup.close(this.nativeInstance, failure);
        ResourceCleanup.throwIfFailed(failure);
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

    private NrdNative.FrameSettings createFrameSettings(
            FrameCamera camera,
            FrameCamera previous,
            float cameraJitterX,
            float cameraJitterY,
            float previousCameraJitterX,
            float previousCameraJitterY,
            int width,
            int height,
            int frameIndex,
            boolean restart,
            float deltaMilliseconds,
            boolean enableValidation,
            SunDirection sunDirection) {
        NrdCameraTransform.projectionForNrd(camera.projection(), this.currentNrdProjection);
        NrdCameraTransform.projectionForNrd(previous.projection(), this.previousNrdProjection);
        NrdCameraTransform.previousWorldToView(camera, previous, this.previousWorldToView);
        return new NrdNative.FrameSettings(
                this.currentNrdProjection,
                this.previousNrdProjection,
                camera.viewRotation(),
                this.previousWorldToView,
                cameraJitterX,
                cameraJitterY,
                previousCameraJitterX,
                previousCameraJitterY,
                width,
                height,
                width,
                height,
                frameIndex,
                restart,
                deltaMilliseconds,
                DENOISING_RANGE,
                enableValidation,
                sunDirection.x(),
                sunDirection.y(),
                sunDirection.z());
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
            destroyPipelines(pipelines, exception);
            throw exception;
        }
    }

    private static RuntimeException destroyPipelines(
            ComputePipeline[] pipelines, RuntimeException failure) {
        if (pipelines == null) {
            return failure;
        }
        for (int index = pipelines.length - 1; index >= 0; index--) {
            failure = ResourceCleanup.destroy(pipelines[index], failure);
        }
        return failure;
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
        private final float cameraJitterX;
        private final float cameraJitterY;
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
                float cameraJitterX,
                float cameraJitterY,
                int nextFrameIndex,
                long submissionNanos) {
            this.owner = owner;
            this.bindings = bindings;
            this.camera = camera;
            this.sceneResetRevision = sceneResetRevision;
            this.atlasView = atlasView;
            this.atlasSampler = atlasSampler;
            this.sunDirection = sunDirection;
            this.cameraJitterX = cameraJitterX;
            this.cameraJitterY = cameraJitterY;
            this.nextFrameIndex = nextFrameIndex;
            this.submissionNanos = submissionNanos;
        }
    }

    private static final class Images implements Destroyable {
        private final VulkanImage noisyDiffuse;
        private final VulkanImage noisySpecular;
        private final VulkanImage noisyDiffuseSh1;
        private final VulkanImage noisySpecularSh1;
        private final VulkanImage normalRoughness;
        private final VulkanImage viewZ;
        private final VulkanImage motion;
        private final VulkanImage fsrMotion;
        private final VulkanImage fsrDepth;
        private final VulkanImage material;
        private final VulkanImage specularMaterial;
        private final VulkanImage primaryPosition;
        private final VulkanImage sunLighting;
        private final VulkanImage sunPenumbra;
        private final VulkanImage sunShadow;
        private final VulkanImage reprojectionError;
        private final VulkanImage validation;
        private final VulkanImage denoisedDiffuse;
        private final VulkanImage denoisedSpecular;
        private final VulkanImage denoisedDiffuseSh1;
        private final VulkanImage denoisedSpecularSh1;
        private final VulkanImage reflectionNoisyDiffuse;
        private final VulkanImage reflectionNoisySpecular;
        private final VulkanImage reflectionNoisyDiffuseSh1;
        private final VulkanImage reflectionNoisySpecularSh1;
        private final VulkanImage reflectionNormalRoughness;
        private final VulkanImage reflectionViewZ;
        private final VulkanImage reflectionMotion;
        private final VulkanImage reflectionMaterial;
        private final VulkanImage reflectionSpecularMaterial;
        private final VulkanImage reflectionPosition;
        private final VulkanImage reflectionDenoisedDiffuse;
        private final VulkanImage reflectionDenoisedSpecular;
        private final VulkanImage reflectionDenoisedDiffuseSh1;
        private final VulkanImage reflectionDenoisedSpecularSh1;
        private final VulkanImage displayPosition;
        private final VulkanImage fsrReactiveMask;
        private final VulkanImage fsrTransparencyCompositionMask;
        private final VulkanImage[] permanentPool;
        private final VulkanImage[] transientPool;
        private final VulkanImage[] ownedImages;
        private boolean destroyed;

        private Images(
                VulkanImage noisyDiffuse,
                VulkanImage noisySpecular,
                VulkanImage noisyDiffuseSh1,
                VulkanImage noisySpecularSh1,
                VulkanImage normalRoughness,
                VulkanImage viewZ,
                VulkanImage motion,
                VulkanImage fsrMotion,
                VulkanImage fsrDepth,
                VulkanImage material,
                VulkanImage specularMaterial,
                VulkanImage primaryPosition,
                VulkanImage sunLighting,
                VulkanImage sunPenumbra,
                VulkanImage sunShadow,
                VulkanImage reprojectionError,
                VulkanImage validation,
                VulkanImage denoisedDiffuse,
                VulkanImage denoisedSpecular,
                VulkanImage denoisedDiffuseSh1,
                VulkanImage denoisedSpecularSh1,
                VulkanImage reflectionNoisyDiffuse,
                VulkanImage reflectionNoisySpecular,
                VulkanImage reflectionNoisyDiffuseSh1,
                VulkanImage reflectionNoisySpecularSh1,
                VulkanImage reflectionNormalRoughness,
                VulkanImage reflectionViewZ,
                VulkanImage reflectionMotion,
                VulkanImage reflectionMaterial,
                VulkanImage reflectionSpecularMaterial,
                VulkanImage reflectionPosition,
                VulkanImage reflectionDenoisedDiffuse,
                VulkanImage reflectionDenoisedSpecular,
                VulkanImage reflectionDenoisedDiffuseSh1,
                VulkanImage reflectionDenoisedSpecularSh1,
                VulkanImage displayPosition,
                VulkanImage fsrReactiveMask,
                VulkanImage fsrTransparencyCompositionMask,
                VulkanImage[] permanentPool,
                VulkanImage[] transientPool,
                VulkanImage[] ownedImages) {
            this.noisyDiffuse = noisyDiffuse;
            this.noisySpecular = noisySpecular;
            this.noisyDiffuseSh1 = noisyDiffuseSh1;
            this.noisySpecularSh1 = noisySpecularSh1;
            this.normalRoughness = normalRoughness;
            this.viewZ = viewZ;
            this.motion = motion;
            this.fsrMotion = fsrMotion;
            this.fsrDepth = fsrDepth;
            this.material = material;
            this.specularMaterial = specularMaterial;
            this.primaryPosition = primaryPosition;
            this.sunLighting = sunLighting;
            this.sunPenumbra = sunPenumbra;
            this.sunShadow = sunShadow;
            this.reprojectionError = reprojectionError;
            this.validation = validation;
            this.denoisedDiffuse = denoisedDiffuse;
            this.denoisedSpecular = denoisedSpecular;
            this.denoisedDiffuseSh1 = denoisedDiffuseSh1;
            this.denoisedSpecularSh1 = denoisedSpecularSh1;
            this.reflectionNoisyDiffuse = reflectionNoisyDiffuse;
            this.reflectionNoisySpecular = reflectionNoisySpecular;
            this.reflectionNoisyDiffuseSh1 = reflectionNoisyDiffuseSh1;
            this.reflectionNoisySpecularSh1 = reflectionNoisySpecularSh1;
            this.reflectionNormalRoughness = reflectionNormalRoughness;
            this.reflectionViewZ = reflectionViewZ;
            this.reflectionMotion = reflectionMotion;
            this.reflectionMaterial = reflectionMaterial;
            this.reflectionSpecularMaterial = reflectionSpecularMaterial;
            this.reflectionPosition = reflectionPosition;
            this.reflectionDenoisedDiffuse = reflectionDenoisedDiffuse;
            this.reflectionDenoisedSpecular = reflectionDenoisedSpecular;
            this.reflectionDenoisedDiffuseSh1 = reflectionDenoisedDiffuseSh1;
            this.reflectionDenoisedSpecularSh1 = reflectionDenoisedSpecularSh1;
            this.displayPosition = displayPosition;
            this.fsrReactiveMask = fsrReactiveMask;
            this.fsrTransparencyCompositionMask = fsrTransparencyCompositionMask;
            this.permanentPool = permanentPool;
            this.transientPool = transientPool;
            this.ownedImages = ownedImages;
        }

        private static Images create(
                VulkanContext context,
                int width,
                int height,
                NrdNative.Description description,
                String debugPrefix) {
            ArrayList<VulkanImage> created = new ArrayList<>();
            try {
                VulkanImage noisy = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " noisy diffuse");
                VulkanImage noisySpecular = createImage(
                        context,
                        created,
                        width,
                        height,
                        VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " noisy specular");
                VulkanImage noisyDiffuseSh1 = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " noisy diffuse SH1");
                VulkanImage noisySpecularSh1 = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " noisy specular SH1");
                VulkanImage normal = createImage(
                        context, created, width, height, VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32, debugPrefix + " normal roughness");
                VulkanImage viewZ = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R32_SFLOAT, debugPrefix + " view Z");
                VulkanImage motion = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " 2.5D screen motion");
                VulkanImage fsrMotion = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " visible-surface FSR motion");
                VulkanImage fsrDepth = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R32_SFLOAT, debugPrefix + " FSR depth");
                VulkanImage material = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " material metadata");
                VulkanImage specularMaterial = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " specular material or virtual guide");
                VulkanImage primaryPosition = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R32G32B32A32_SFLOAT, debugPrefix + " primary or virtual position");
                VulkanImage sunLighting = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " unshadowed sun lighting");
                VulkanImage sunPenumbra = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16_SFLOAT, debugPrefix + " noisy sun penumbra");
                VulkanImage sunShadow = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16_SFLOAT, debugPrefix + " SIGMA sun shadow");
                VulkanImage reprojectionError = createImage(
                        context,
                        created,
                        width,
                        height,
                        VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " reprojection error");
                VulkanImage validation = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R8G8B8A8_UNORM, debugPrefix + " validation output");
                VulkanImage denoised = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, debugPrefix + " denoised diffuse");
                VulkanImage denoisedSpecular = createImage(
                        context,
                        created,
                        width,
                        height,
                        VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " denoised specular");
                VulkanImage denoisedDiffuseSh1 = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " denoised diffuse SH1");
                VulkanImage denoisedSpecularSh1 = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " denoised specular SH1");
                VulkanImage reflectionNoisyDiffuse = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " reflection noisy diffuse");
                VulkanImage reflectionNoisySpecular = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " reflection noisy specular");
                VulkanImage reflectionNoisyDiffuseSh1 = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " reflection noisy diffuse SH1");
                VulkanImage reflectionNoisySpecularSh1 = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " reflection noisy specular SH1");
                VulkanImage reflectionNormalRoughness = createImage(
                        context, created, width, height, VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32,
                        debugPrefix + " reflection normal roughness");
                VulkanImage reflectionViewZ = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R32_SFLOAT,
                        debugPrefix + " reflection view Z");
                VulkanImage reflectionMotion = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " reflection 2.5D motion");
                VulkanImage reflectionMaterial = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " reflection material");
                VulkanImage reflectionSpecularMaterial = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " reflection specular material");
                VulkanImage reflectionPosition = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                        debugPrefix + " reflection virtual position");
                VulkanImage reflectionDenoisedDiffuse = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " reflection denoised diffuse");
                VulkanImage reflectionDenoisedSpecular = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " reflection denoised specular");
                VulkanImage reflectionDenoisedDiffuseSh1 = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " reflection denoised diffuse SH1");
                VulkanImage reflectionDenoisedSpecularSh1 = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        debugPrefix + " reflection denoised specular SH1");
                VulkanImage displayPosition = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                        debugPrefix + " visible primary position");
                VulkanImage fsrReactiveMask = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R8_UNORM, debugPrefix + " FSR reactive mask");
                VulkanImage fsrTransparencyCompositionMask = createImage(
                        context, created, width, height, VK12.VK_FORMAT_R8_UNORM, debugPrefix + " FSR transparency mask");
                VulkanImage[] permanent = createPool(
                        context, created, width, height, description.permanentPool(), debugPrefix + " permanent");
                VulkanImage[] transientImages = createPool(
                        context, created, width, height, description.transientPool(), debugPrefix + " transient");
                return new Images(
                        noisy,
                        noisySpecular,
                        noisyDiffuseSh1,
                        noisySpecularSh1,
                        normal,
                        viewZ,
                        motion,
                        fsrMotion,
                        fsrDepth,
                        material,
                        specularMaterial,
                        primaryPosition,
                        sunLighting,
                        sunPenumbra,
                        sunShadow,
                        reprojectionError,
                        validation,
                        denoised,
                        denoisedSpecular,
                        denoisedDiffuseSh1,
                        denoisedSpecularSh1,
                        reflectionNoisyDiffuse,
                        reflectionNoisySpecular,
                        reflectionNoisyDiffuseSh1,
                        reflectionNoisySpecularSh1,
                        reflectionNormalRoughness,
                        reflectionViewZ,
                        reflectionMotion,
                        reflectionMaterial,
                        reflectionSpecularMaterial,
                        reflectionPosition,
                        reflectionDenoisedDiffuse,
                        reflectionDenoisedSpecular,
                        reflectionDenoisedDiffuseSh1,
                        reflectionDenoisedSpecularSh1,
                        displayPosition,
                        fsrReactiveMask,
                        fsrTransparencyCompositionMask,
                        permanent,
                        transientImages,
                        created.toArray(VulkanImage[]::new));
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
            return this.ownedImages;
        }

        @Override
        public void destroy() {
            if (this.destroyed) {
                return;
            }
            this.destroyed = true;
            VulkanImage[] owned = this.allImages();
            for (int index = owned.length - 1; index >= 0; index--) {
                owned[index].destroy();
            }
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
        private final int dispatchCapacity;
        private long[] resourceDescriptorSets = new long[0];
        private long[] constantsDescriptorSets = new long[0];
        private int[] allocatedPipelineIndices = new int[0];
        private int allocatedSetCount;
        private boolean destroyed;

        private FrameBindings(
                NrdDenoiser owner,
                long descriptorPool,
                VulkanBuffer constantBuffer,
                int dispatchCapacity) {
            this.owner = owner;
            this.descriptorPool = descriptorPool;
            this.constantBuffer = constantBuffer;
            this.dispatchCapacity = dispatchCapacity;
        }

        private static FrameBindings create(NrdDenoiser owner, int requiredDispatches) {
            NrdNative.Description description = owner.description;
            // NRD's pool description can count identical denoiser dispatch names only once even
            // though GetComputeDispatches returns one sequence per denoiser identifier.
            int dispatchCapacity = Math.max(Math.max(description.setsMaxNum(), requiredDispatches), 1);
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
                        .descriptorCount(dispatchCapacity * description.samplers().size());
                sizes.get(1)
                        .type(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(dispatchCapacity);
                sizes.get(2)
                        .type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .descriptorCount(dispatchCapacity * maxTextures);
                sizes.get(3)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(dispatchCapacity * maxStorages);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(Math.multiplyExact(dispatchCapacity, 2))
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
                        Math.multiplyExact(stride, dispatchCapacity),
                        VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                        true,
                        "Prime NRD frame constants");
                return new FrameBindings(
                        owner,
                        descriptorPool,
                        constantBuffer,
                        dispatchCapacity);
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

        private void prepare(NrdNative.DispatchList dispatches, NrdDenoiser denoiser) {
            if (this.destroyed) {
                throw new IllegalStateException("NRD frame bindings are destroyed");
            }
            if (dispatches.size() > this.dispatchCapacity) {
                throw new IllegalStateException("NRD dispatch count exceeds its descriptor pool contract");
            }
            if (dispatches.isEmpty()) {
                return;
            }
            if (this.resourceDescriptorSets.length < dispatches.size()) {
                this.resourceDescriptorSets = new long[dispatches.size()];
                this.constantsDescriptorSets = new long[dispatches.size()];
                this.allocatedPipelineIndices = new int[dispatches.size()];
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (!this.matchesAllocatedLayouts(dispatches)) {
                    this.allocatedSetCount = 0;
                    VulkanContext.check(
                            VK12.vkResetDescriptorPool(
                                    denoiser.context.vkDevice(), this.descriptorPool, 0),
                            "reset Prime NRD descriptor pool");
                    LongBuffer layouts = stack.mallocLong(Math.multiplyExact(dispatches.size(), 2));
                    for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
                        int pipelineIndex = dispatches.pipelineIndex(dispatchIndex);
                        ComputePipeline pipeline = denoiser.pipelines[pipelineIndex];
                        layouts.put(pipeline.resourceDescriptorSetLayout);
                        layouts.put(pipeline.constantsDescriptorSetLayout);
                        this.allocatedPipelineIndices[dispatchIndex] = pipelineIndex;
                    }
                    layouts.flip();
                    VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType$Default()
                            .descriptorPool(this.descriptorPool)
                            .pSetLayouts(layouts);
                    LongBuffer sets = stack.mallocLong(Math.multiplyExact(dispatches.size(), 2));
                    VulkanContext.check(
                            VK12.vkAllocateDescriptorSets(
                                    denoiser.context.vkDevice(), allocateInfo, sets),
                            "allocate Prime NRD frame descriptor sets");
                    for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
                        this.resourceDescriptorSets[dispatchIndex] = sets.get();
                        this.constantsDescriptorSets[dispatchIndex] = sets.get();
                    }
                    this.allocatedSetCount = dispatches.size();
                }

                long constantStride = VulkanContext.alignUp(
                        Math.max(denoiser.description.constantBufferMaxDataSize(), 1),
                        Math.max(denoiser.context.uniformBufferOffsetAlignment(), 1L));
                for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
                    this.writeDispatch(
                            stack,
                            dispatchIndex,
                            dispatches,
                            constantStride,
                            denoiser);
                }
            }
        }

        private boolean matchesAllocatedLayouts(NrdNative.DispatchList dispatches) {
            if (this.allocatedSetCount != dispatches.size()) {
                return false;
            }
            for (int dispatchIndex = 0; dispatchIndex < dispatches.size(); dispatchIndex++) {
                if (this.allocatedPipelineIndices[dispatchIndex]
                        != dispatches.pipelineIndex(dispatchIndex)) {
                    return false;
                }
            }
            return true;
        }

        private void writeDispatch(
                MemoryStack stack,
                int dispatchIndex,
                NrdNative.DispatchList dispatches,
                long constantStride,
                NrdDenoiser denoiser) {
            boolean hasConstants = denoiser.description.pipelines()
                    .get(dispatches.pipelineIndex(dispatchIndex))
                    .hasConstantData();
            int constantDataSize = dispatches.constantDataSize(dispatchIndex);
            if (hasConstants && constantDataSize == 0) {
                throw new IllegalStateException("NRD pipeline requires missing constant data");
            }
            if (constantDataSize > denoiser.description.constantBufferMaxDataSize()) {
                throw new IllegalStateException("NRD constant data exceeds its declared maximum");
            }
            int resourceCount = dispatches.resourceCount(dispatchIndex);
            int writeCount = resourceCount + (hasConstants ? 1 : 0);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
            VkDescriptorImageInfo.Buffer imageInfos =
                    VkDescriptorImageInfo.calloc(resourceCount, stack);
            int writeIndex = 0;
            if (hasConstants) {
                long constantOffset = constantStride * dispatchIndex;
                this.constantBuffer.put(
                        constantOffset,
                        dispatches.constantDataAddress(dispatchIndex),
                        constantDataSize);
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(this.constantBuffer.handle())
                        .offset(constantOffset)
                        .range(constantDataSize);
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
            for (int resourceIndex = 0; resourceIndex < resourceCount; resourceIndex++) {
                int resourceType = dispatches.resourceType(dispatchIndex, resourceIndex);
                VulkanImage image = denoiser.resolveResource(
                        resourceType,
                        dispatches.resourceIndexInPool(dispatchIndex, resourceIndex),
                        dispatches.identifier(dispatchIndex));
                int binding;
                int descriptorType;
                int nativeDescriptorType = dispatches.resourceDescriptorType(
                        dispatchIndex, resourceIndex);
                if (nativeDescriptorType == NrdNative.DESCRIPTOR_TEXTURE) {
                    binding = denoiser.description.textureOffset()
                            + denoiser.description.resourcesBaseRegisterIndex()
                            + textureIndex++;
                    descriptorType = VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
                } else if (nativeDescriptorType == NrdNative.DESCRIPTOR_STORAGE_TEXTURE) {
                    binding = denoiser.description.storageTextureOffset()
                            + denoiser.description.resourcesBaseRegisterIndex()
                            + storageIndex++;
                    descriptorType = VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                } else {
                    throw new IllegalStateException(
                            "Unknown NRD resource descriptor type " + nativeDescriptorType);
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

    private static final class MotionPipeline implements Destroyable {
        private final VulkanContext context;
        private final long descriptorSetLayout;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long pipelineLayout;
        private final long pipeline;
        private final Matrix4f currentClipToWorld = new Matrix4f();
        private final Matrix4f previousWorldToClip = new Matrix4f();
        private final Matrix4f previousRenderedWorldToClip = new Matrix4f();
        private final Matrix4f worldToViewScratch = new Matrix4f();
        private boolean destroyed;

        private MotionPipeline(
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

        private static MotionPipeline create(
                VulkanContext context,
                Images images,
                String shaderResource,
                String debugPrefix) {
            long descriptorSetLayout = 0L;
            long descriptorPool = 0L;
            long descriptorSet = 0L;
            long pipelineLayout = 0L;
            long pipeline = 0L;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorSetLayoutBinding.Buffer bindings =
                        VkDescriptorSetLayoutBinding.calloc(MOTION_BINDING_COUNT, stack);
                for (int index = 0; index < MOTION_BINDING_COUNT; index++) {
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
                        VK12.vkCreateDescriptorSetLayout(
                                context.vkDevice(), setLayoutInfo, null, pointer),
                        "create " + debugPrefix + " motion descriptor layout");
                descriptorSetLayout = pointer.get(0);

                VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(COMPUTE_STAGE)
                        .offset(0)
                        .size(MOTION_PUSH_SIZE);
                VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pSetLayouts(stack.longs(descriptorSetLayout))
                        .pPushConstantRanges(pushRange);
                pointer.clear();
                VulkanContext.check(
                        VK12.vkCreatePipelineLayout(
                                context.vkDevice(), pipelineLayoutInfo, null, pointer),
                        "create " + debugPrefix + " motion pipeline layout");
                pipelineLayout = pointer.get(0);

                long shaderModule = createResourceShaderModule(
                        context, stack, shaderResource);
                try {
                    VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType$Default()
                            .stage(COMPUTE_STAGE)
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
                            "create " + debugPrefix + " motion pipeline");
                    pipeline = pointer.get(0);
                } finally {
                    VK12.vkDestroyShaderModule(context.vkDevice(), shaderModule, null);
                }

                VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(MOTION_BINDING_COUNT);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(1)
                        .pPoolSizes(poolSize);
                pointer.clear();
                VulkanContext.check(
                        VK12.vkCreateDescriptorPool(context.vkDevice(), poolInfo, null, pointer),
                        "create " + debugPrefix + " motion descriptor pool");
                descriptorPool = pointer.get(0);

                VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default()
                        .descriptorPool(descriptorPool)
                        .pSetLayouts(stack.longs(descriptorSetLayout));
                pointer.clear();
                VulkanContext.check(
                        VK12.vkAllocateDescriptorSets(context.vkDevice(), allocateInfo, pointer),
                        "allocate " + debugPrefix + " motion descriptor set");
                descriptorSet = pointer.get(0);

                VulkanImage[] descriptorImages = new VulkanImage[] {
                    images.motion,
                    images.viewZ,
                    images.primaryPosition,
                    images.reprojectionError,
                    images.fsrDepth,
                    images.noisyDiffuse,
                    images.noisySpecular,
                    images.normalRoughness,
                    images.material,
                    images.specularMaterial,
                    images.noisyDiffuseSh1,
                    images.noisySpecularSh1,
                    images.reflectionMotion,
                    images.reflectionViewZ,
                    images.reflectionPosition,
                    images.reflectionNoisyDiffuse,
                    images.reflectionNoisySpecular,
                    images.reflectionNormalRoughness,
                    images.reflectionMaterial,
                    images.reflectionSpecularMaterial,
                    images.reflectionNoisyDiffuseSh1,
                    images.reflectionNoisySpecularSh1,
                    images.displayPosition,
                    images.fsrMotion
                };
                validateMotionBindings(
                        descriptorImages, images.motion, images.fsrMotion);
                VkDescriptorImageInfo.Buffer imageInfos =
                        VkDescriptorImageInfo.calloc(MOTION_BINDING_COUNT, stack);
                VkWriteDescriptorSet.Buffer writes =
                        VkWriteDescriptorSet.calloc(MOTION_BINDING_COUNT, stack);
                for (int index = 0; index < MOTION_BINDING_COUNT; index++) {
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
                return new MotionPipeline(
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
                    VK12.vkDestroyDescriptorSetLayout(
                            context.vkDevice(), descriptorSetLayout, null);
                }
                throw exception;
            }
        }

        private void record(
                VkCommandBuffer commandBuffer,
                FrameCamera camera,
                FrameCamera previous,
                int width,
                int height,
                int diagnosticMode,
                float cameraJitterX,
                float cameraJitterY) {
            NrdCameraTransform.currentClipToWorld(camera, this.currentClipToWorld);
            NrdCameraTransform.previousWorldToClip(
                    camera, previous, this.previousWorldToClip, this.worldToViewScratch);
            NrdCameraTransform.previousRenderedWorldToClip(
                    camera, previous, this.previousRenderedWorldToClip);
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
                ByteBuffer push = stack.malloc(MOTION_PUSH_SIZE).order(ByteOrder.nativeOrder());
                this.currentClipToWorld.get(0, push);
                this.previousWorldToClip.get(64, push);
                this.previousRenderedWorldToClip.get(128, push);
                push.putInt(ShaderAbi.NRD_MOTION_PUSH_DIAGNOSTIC_MODE_OFFSET, diagnosticMode);
                int jitterOffset = ShaderAbi.NRD_MOTION_PUSH_CURRENT_JITTER_PIXELS_OFFSET;
                push.putFloat(jitterOffset, cameraJitterX);
                push.putFloat(jitterOffset + Float.BYTES, cameraJitterY);
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
                VK12.vkDestroyDescriptorSetLayout(
                        this.context.vkDevice(), this.descriptorSetLayout, null);
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
                    images.denoisedSpecular,
                    images.material,
                    images.specularMaterial,
                    stableAccumulation,
                    atmosphere.aerialRadiance(),
                    atmosphere.aerialTransmittance(),
                    images.validation,
                    images.reprojectionError,
                    images.motion,
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

        private void record(
                VkCommandBuffer commandBuffer,
                int width,
                int height,
                int diagnosticMode,
                float sunRadianceMultiplier,
                float cameraJitterX,
                float cameraJitterY,
                float displayOverexposure) {
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
                push.putInt(8, diagnosticMode);
                push.putFloat(12, sunRadianceMultiplier);
                push.putFloat(16, cameraJitterX);
                push.putFloat(20, cameraJitterY);
                push.putFloat(24, displayOverexposure);
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
