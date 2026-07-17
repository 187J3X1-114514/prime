package dev.prime.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.PrimeClient;
import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.terrain.TerrainScene;
import dev.prime.render.terrain.TerrainStreamer;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.RayTracingPipeline;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.VulkanCapabilities;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.nrd.NrdDenoiser;
import dev.prime.render.vulkan.nrd.NrdTransparentComposite;
import dev.prime.render.vulkan.fsr.Fsr3Upscaler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.tags.FluidTags;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

public final class VulkanRenderer implements AutoCloseable {
    private final VulkanContext context;
    private final StagingArena stagingArena;
    private final TerrainStreamer terrain;
    private final AccumulationState accumulationState = new AccumulationState();
    private final BlockPos.MutableBlockPos cameraBlockPosition = new BlockPos.MutableBlockPos();
    private RayTracingPipeline pipeline;
    private AtmospherePipeline atmosphere;
    private RenderImages renderImages;
    private FrameCamera camera;
    private FrameCamera previousSubmittedCamera;
    private SunDirection sunDirection;
    private boolean cameraMediumKnown;
    private boolean cameraInWater;
    private long submittedLightingRevision = Long.MIN_VALUE;
    private boolean shaderReloadRequested;
    private boolean closed;

    public VulkanRenderer(com.mojang.blaze3d.vulkan.VulkanDevice device, VulkanCapabilities capabilities) {
        VulkanContext newContext = new VulkanContext(device, capabilities);
        StagingArena newStagingArena = null;
        AtmospherePipeline newAtmosphere = null;
        RayTracingPipeline newPipeline = null;
        TerrainStreamer newTerrain = null;
        try {
            newStagingArena = new StagingArena(newContext);
            newAtmosphere = new AtmospherePipeline(newContext);
            newPipeline = new RayTracingPipeline(newContext);
            newTerrain = new TerrainStreamer(newContext, newStagingArena);
            this.context = newContext;
            this.stagingArena = newStagingArena;
            this.pipeline = newPipeline;
            this.atmosphere = newAtmosphere;
            this.terrain = newTerrain;
        } catch (RuntimeException exception) {
            if (newTerrain != null) {
                newTerrain.close();
            }
            if (newPipeline != null) {
                newPipeline.destroy();
            }
            if (newAtmosphere != null) {
                newAtmosphere.destroy();
            }
            if (newStagingArena != null) {
                newStagingArena.close();
            }
            newContext.close();
            throw exception;
        }
    }

    public void beginFrame(Minecraft minecraft) {
        this.reloadPipelineIfRequested();
        FrameCamera frameCamera = this.camera;
        if (frameCamera != null) {
            this.terrain.update(minecraft, frameCamera.x(), frameCamera.y(), frameCamera.z());
        } else if (minecraft.player != null) {
            this.terrain.update(
                    minecraft,
                    minecraft.player.getX(),
                    minecraft.player.getY(),
                    minecraft.player.getZ());
        } else {
            this.terrain.update(minecraft, 0.0, 0.0, 0.0);
        }
    }

    public void captureCamera(
            Matrix4fc renderedProjection,
            Matrix4fc baseProjection,
            Matrix4fc viewRotation,
            double x,
            double y,
            double z,
            float sunAngleRadians) {
        this.camera = FrameCamera.tryCreate(
                renderedProjection, baseProjection, viewRotation, x, y, z);
        this.sunDirection = SunDirection.fromVanillaAngle(sunAngleRadians);
    }

    public boolean isReady() {
        return this.terrain.isNearCameraReady() && this.terrain.sceneView() != null;
    }

    public void render(RenderTarget mainTarget) {
        TerrainScene.SceneView scene = this.terrain.sceneView();
        FrameCamera frameCamera = this.camera;
        SunDirection frameSunDirection = this.sunDirection;
        if (scene == null || frameCamera == null || frameSunDirection == null) {
            return;
        }
        if (!(mainTarget.getColorTexture() instanceof VulkanGpuTexture mainColor)) {
            throw new IllegalStateException("Prime expected a Vulkan main color texture");
        }
        if (mainColor.getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalStateException("Prime requires an RGBA8_UNORM main target");
        }
        int width = mainColor.getWidth(0);
        int height = mainColor.getHeight(0);
        if (width <= 0
                || height <= 0
                || mainTarget.width != width
                || mainTarget.height != height) {
            return;
        }
        FsrQualityMode requestedQualityMode = FsrSettings.qualityMode();
        int renderWidth = requestedQualityMode.renderWidth(width);
        int renderHeight = requestedQualityMode.renderHeight(height);
        long invocationCount = (long) renderWidth * renderHeight;
        if (invocationCount > Integer.toUnsignedLong(this.context.capabilities().maxRayDispatchInvocationCount())) {
            throw new IllegalStateException("Window dimensions exceed the Vulkan ray dispatch limit");
        }

        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        if (!(atlas.getTextureView() instanceof VulkanGpuTextureView atlasView)
                || !(atlas.getSampler() instanceof VulkanGpuSampler atlasSampler)) {
            throw new IllegalStateException("Prime expected Vulkan block atlas resources");
        }
        long atlasViewHandle = atlasView.vkImageView();
        long atlasSamplerHandle = atlasSampler.vkSampler();
        boolean resized = this.ensureRenderImages(
                width,
                height,
                renderWidth,
                renderHeight,
                requestedQualityMode,
                scene.tlas(),
                atlasView,
                atlasSampler);
        RenderImages images = this.renderImages;
        if (images == null) {
            return;
        }
        VulkanImage target = images.output;
        VulkanImage history = images.accumulation;
        VulkanImage sceneColor = images.sceneColor;
        NrdDenoiser denoiser = images.denoiser;
        NrdDenoiser reflectionDenoiser = images.reflectionDenoiser;
        NrdDenoiser transmissionDenoiser = images.transmissionDenoiser;
        Fsr3Upscaler upscaler = images.upscaler;
        LightingSettings.Snapshot lighting = LightingSettings.snapshot();
        boolean lightingChanged = this.submittedLightingRevision != Long.MIN_VALUE
                && lighting.revision() != this.submittedLightingRevision;
        if (lightingChanged) {
            // A radiance-scale change invalidates temporal estimators, but not geometry,
            // atmosphere transmittance, or light-tree probabilities.
            upscaler.requestReset();
        }
        boolean frameCameraInWater = this.isCameraInWater(minecraft, frameCamera);
        if (this.cameraMediumKnown && this.cameraInWater != frameCameraInWater) {
            // Crossing the water surface changes transport for essentially every visible path.
            // Treat it as a temporal discontinuity so NRD/FSR do not retain the previous medium.
            this.accumulationState.invalidate();
            upscaler.requestReset();
        }
        this.cameraMediumKnown = true;
        this.cameraInWater = frameCameraInWater;
        this.accumulationState.prepare(
                frameCamera,
                scene.resetRevision(),
                atlasViewHandle,
                atlasSamplerHandle,
                frameSunDirection,
                resized || lightingChanged);
        Fsr3Upscaler.FrameToken fsrFrame = upscaler.beginFrame(
                frameCamera,
                scene.resetRevision(),
                atlasViewHandle,
                atlasSamplerHandle);
        FsrSettings.Jitter cameraJitter = fsrFrame.jitter();
        FrameCamera temporalPreviousCamera = fsrFrame.reset()
                || this.previousSubmittedCamera == null
                ? frameCamera
                : this.previousSubmittedCamera;
        boolean temporalCameraValid = !fsrFrame.reset()
                && this.previousSubmittedCamera != null;

        var encoder = this.context.commandEncoder();
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
        this.context.device().instance().debug().beginDebugGroup(
                commandBuffer, () -> "Prime path tracing, NRD, and FidelityFX FSR 3.1.4");
        this.atmosphere.prepare(commandBuffer, frameCamera, frameSunDirection);
        this.prepareOutputForComposite(commandBuffer, target);
        this.prepareSceneColorForComposite(commandBuffer, sceneColor);
        this.prepareAccumulationForTrace(commandBuffer, history);
        denoiser.prepareForRayTrace(commandBuffer);
        reflectionDenoiser.prepareForRayTrace(commandBuffer);
        transmissionDenoiser.prepareForRayTrace(commandBuffer);
        this.prepareAtlasForTrace(commandBuffer, atlasView.texture());
        NrdDenoiser.FrameToken nrdFrame;
        NrdDenoiser.FrameToken reflectionNrdFrame;
        NrdDenoiser.FrameToken transmissionNrdFrame;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pushConstants = this.createPushConstants(
                    stack,
                    frameCamera,
                    scene,
                    renderWidth,
                    renderHeight,
                    frameSunDirection,
                    images.qualityMode,
                    fsrFrame.frameIndex(),
                    frameCameraInWater,
                    lighting);
            this.pipeline.trace(commandBuffer, pushConstants, renderWidth, renderHeight);
            nrdFrame = denoiser.record(
                    commandBuffer,
                    frameCamera,
                    scene.resetRevision(),
                    atlasViewHandle,
                    atlasSamplerHandle,
                    frameSunDirection,
                    cameraJitter.x(),
                    cameraJitter.y(),
                    lighting.sunMultiplier(),
                    fsrFrame.reset());
            this.prepareTransparentComposite(commandBuffer, sceneColor, denoiser);
            this.pipeline.traceTransparent(
                    commandBuffer,
                    pushConstants,
                    renderWidth,
                    renderHeight,
                    (float) (temporalPreviousCamera.renderX() - scene.originX()),
                    (float) (temporalPreviousCamera.renderY() - scene.originY()),
                    (float) (temporalPreviousCamera.renderZ() - scene.originZ()),
                    temporalCameraValid);
            reflectionNrdFrame = reflectionDenoiser.recordBranch(
                    commandBuffer,
                    frameCamera,
                    scene.resetRevision(),
                    atlasViewHandle,
                    atlasSamplerHandle,
                    frameSunDirection,
                    cameraJitter.x(),
                    cameraJitter.y(),
                    fsrFrame.reset());
            transmissionNrdFrame = transmissionDenoiser.recordBranch(
                    commandBuffer,
                    frameCamera,
                    scene.resetRevision(),
                    atlasViewHandle,
                    atlasSamplerHandle,
                    frameSunDirection,
                    cameraJitter.x(),
                    cameraJitter.y(),
                    fsrFrame.reset());
            images.transparentComposite.record(
                    commandBuffer,
                    renderWidth,
                    renderHeight,
                    lighting.sunMultiplier());
            this.finishTransparentComposite(commandBuffer, sceneColor, denoiser);
            this.finishAtlasRead(commandBuffer, atlasView.texture());
            upscaler.record(commandBuffer, fsrFrame);
            this.prepareImagesForCopy(commandBuffer, target, mainColor);
            VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
            copy.get(0).srcSubresource()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            copy.get(0).dstSubresource()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            copy.get(0).extent().set(width, height, 1);
            VK12.vkCmdCopyImage(
                    commandBuffer,
                    target.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    mainColor.vkImage(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    copy);
            this.finishImageCopy(commandBuffer, target, mainColor);
        }
        this.context.device().instance().debug().endDebugGroup(commandBuffer);
        VulkanContext.check(VK12.vkEndCommandBuffer(commandBuffer), "end Prime ray tracing command buffer");
        encoder.execute(commandBuffer);
        denoiser.submitted(nrdFrame);
        reflectionDenoiser.submitted(reflectionNrdFrame);
        transmissionDenoiser.submitted(transmissionNrdFrame);
        upscaler.submitted(fsrFrame);
        this.previousSubmittedCamera = frameCamera;
        this.submittedLightingRevision = lighting.revision();
        this.accumulationState.submitted(
                frameCamera,
                atlasViewHandle,
                atlasSamplerHandle,
                frameSunDirection);
        int accumulatedSampleCount = this.accumulationState.sampleIndex();
        if (accumulatedSampleCount >= 16
                && (accumulatedSampleCount & (accumulatedSampleCount - 1)) == 0) {
            PrimeClient.LOGGER.debug(
                    "Prime accumulation reached {} samples for scene revision {}",
                    accumulatedSampleCount,
                    scene.revision());
        }
    }

    public void invalidateBlocks(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ) {
        this.terrain.invalidateBlocks(
                minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
    }

    public void invalidateAll() {
        this.terrain.invalidateAll();
        if (this.renderImages != null) {
            this.renderImages.upscaler.requestReset();
        }
    }

    public void requestShaderReload() {
        this.shaderReloadRequested = true;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.context.awaitIdle();
        this.context.drainDeferredAfterIdle();
        this.terrain.close();
        this.pipeline.destroy();
        if (this.renderImages != null) {
            this.renderImages.destroy();
            this.renderImages = null;
        }
        this.atmosphere.destroy();
        this.stagingArena.close();
        this.context.close();
    }

    private void reloadPipelineIfRequested() {
        if (!this.shaderReloadRequested) {
            return;
        }
        this.shaderReloadRequested = false;
        AtmospherePipeline replacementAtmosphere = null;
        RayTracingPipeline replacementPipeline = null;
        NrdDenoiser replacementDenoiser = null;
        NrdDenoiser replacementReflectionDenoiser = null;
        NrdDenoiser replacementTransmissionDenoiser = null;
        NrdTransparentComposite replacementTransparentComposite = null;
        Fsr3Upscaler replacementUpscaler = null;
        try {
            replacementAtmosphere = new AtmospherePipeline(this.context);
            replacementPipeline = new RayTracingPipeline(this.context);
            RenderImages currentImages = this.renderImages;
            if (currentImages != null) {
                replacementDenoiser = NrdDenoiser.create(
                        this.context,
                        currentImages.sceneColor.width(),
                        currentImages.sceneColor.height(),
                        currentImages.sceneColor,
                        currentImages.accumulation,
                        replacementAtmosphere);
                replacementReflectionDenoiser = NrdDenoiser.createTransparentBranch(
                        this.context,
                        currentImages.sceneColor.width(),
                        currentImages.sceneColor.height(),
                        NrdDenoiser.TransparentBranch.REFLECTION);
                replacementTransmissionDenoiser = NrdDenoiser.createTransparentBranch(
                        this.context,
                        currentImages.sceneColor.width(),
                        currentImages.sceneColor.height(),
                        NrdDenoiser.TransparentBranch.TRANSMISSION);
                replacementTransparentComposite = NrdTransparentComposite.create(
                        this.context,
                        currentImages.sceneColor,
                        replacementDenoiser,
                        replacementReflectionDenoiser,
                        replacementTransmissionDenoiser,
                        replacementAtmosphere);
                replacementUpscaler = Fsr3Upscaler.create(
                        this.context,
                        currentImages.sceneColor.width(),
                        currentImages.sceneColor.height(),
                        currentImages.output.width(),
                        currentImages.output.height(),
                        currentImages.qualityMode,
                        currentImages.sceneColor,
                        replacementDenoiser.motion(),
                        replacementDenoiser.fsrDepth(),
                        replacementDenoiser.fsrReactiveMask(),
                        replacementDenoiser.fsrTransparencyCompositionMask(),
                        currentImages.output);
            }
        } catch (RuntimeException exception) {
            if (replacementUpscaler != null) {
                replacementUpscaler.destroy();
            }
            if (replacementTransparentComposite != null) {
                replacementTransparentComposite.destroy();
            }
            if (replacementTransmissionDenoiser != null) {
                replacementTransmissionDenoiser.destroy();
            }
            if (replacementReflectionDenoiser != null) {
                replacementReflectionDenoiser.destroy();
            }
            if (replacementDenoiser != null) {
                replacementDenoiser.destroy();
            }
            if (replacementPipeline != null) {
                replacementPipeline.destroy();
            }
            if (replacementAtmosphere != null) {
                replacementAtmosphere.destroy();
            }
            PrimeClient.LOGGER.error("Prime shader reload failed; keeping the previous pipeline", exception);
            return;
        }
        RayTracingPipeline previousPipeline = this.pipeline;
        AtmospherePipeline previousAtmosphere = this.atmosphere;
        RenderImages currentImages = this.renderImages;
        NrdDenoiser previousDenoiser = currentImages == null ? null : currentImages.denoiser;
        NrdDenoiser previousReflectionDenoiser =
                currentImages == null ? null : currentImages.reflectionDenoiser;
        NrdDenoiser previousTransmissionDenoiser =
                currentImages == null ? null : currentImages.transmissionDenoiser;
        NrdTransparentComposite previousTransparentComposite =
                currentImages == null ? null : currentImages.transparentComposite;
        Fsr3Upscaler previousUpscaler = currentImages == null ? null : currentImages.upscaler;
        this.pipeline = replacementPipeline;
        this.atmosphere = replacementAtmosphere;
        if (currentImages != null) {
            currentImages.denoiser = replacementDenoiser;
            currentImages.reflectionDenoiser = replacementReflectionDenoiser;
            currentImages.transmissionDenoiser = replacementTransmissionDenoiser;
            currentImages.transparentComposite = replacementTransparentComposite;
            currentImages.upscaler = replacementUpscaler;
        }
        this.context.defer(previousPipeline);
        if (previousDenoiser != null) {
            this.context.defer(previousUpscaler);
            this.context.defer(previousTransparentComposite);
            this.context.defer(previousTransmissionDenoiser);
            this.context.defer(previousReflectionDenoiser);
            this.context.defer(previousDenoiser);
        }
        this.context.defer(previousAtmosphere);
        this.accumulationState.invalidate();
        PrimeClient.LOGGER.info("Reloaded Prime ray tracing and atmosphere shaders");
    }

    private boolean ensureRenderImages(
            int displayWidth,
            int displayHeight,
            int renderWidth,
            int renderHeight,
            FsrQualityMode qualityMode,
            long tlas,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler) {
        RenderImages current = this.renderImages;
        if (current != null && current.matches(
                displayWidth, displayHeight, renderWidth, renderHeight, qualityMode)) {
            this.pipeline.ensureDescriptors(
                    tlas,
                    current.output,
                    current.accumulation,
                    current.sceneColor,
                    atlasView,
                    atlasSampler,
                    this.atmosphere,
                    current.denoiser,
                    current.reflectionDenoiser,
                    current.transmissionDenoiser);
            return false;
        }
        VulkanImage replacementOutput = null;
        VulkanImage replacementAccumulation = null;
        VulkanImage replacementSceneColor = null;
        NrdDenoiser replacementDenoiser = null;
        NrdDenoiser replacementReflectionDenoiser = null;
        NrdDenoiser replacementTransmissionDenoiser = null;
        NrdTransparentComposite replacementTransparentComposite = null;
        Fsr3Upscaler replacementUpscaler = null;
        try {
            replacementOutput = this.context.createOutputImage(displayWidth, displayHeight);
            replacementAccumulation = this.context.createAccumulationImage(renderWidth, renderHeight);
            replacementSceneColor = this.context.createImage2D(
                    renderWidth,
                    renderHeight,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime linear HDR scene color");
            replacementDenoiser = NrdDenoiser.create(
                    this.context,
                    renderWidth,
                    renderHeight,
                    replacementSceneColor,
                    replacementAccumulation,
                    this.atmosphere);
            replacementReflectionDenoiser = NrdDenoiser.createTransparentBranch(
                    this.context,
                    renderWidth,
                    renderHeight,
                    NrdDenoiser.TransparentBranch.REFLECTION);
            replacementTransmissionDenoiser = NrdDenoiser.createTransparentBranch(
                    this.context,
                    renderWidth,
                    renderHeight,
                    NrdDenoiser.TransparentBranch.TRANSMISSION);
            replacementTransparentComposite = NrdTransparentComposite.create(
                    this.context,
                    replacementSceneColor,
                    replacementDenoiser,
                    replacementReflectionDenoiser,
                    replacementTransmissionDenoiser,
                    this.atmosphere);
            replacementUpscaler = Fsr3Upscaler.create(
                    this.context,
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    qualityMode,
                    replacementSceneColor,
                    replacementDenoiser.motion(),
                    replacementDenoiser.fsrDepth(),
                    replacementDenoiser.fsrReactiveMask(),
                    replacementDenoiser.fsrTransparencyCompositionMask(),
                    replacementOutput);
        } catch (RuntimeException exception) {
            if (replacementUpscaler != null) {
                replacementUpscaler.destroy();
            }
            if (replacementTransparentComposite != null) {
                replacementTransparentComposite.destroy();
            }
            if (replacementTransmissionDenoiser != null) {
                replacementTransmissionDenoiser.destroy();
            }
            if (replacementReflectionDenoiser != null) {
                replacementReflectionDenoiser.destroy();
            }
            if (replacementDenoiser != null) {
                replacementDenoiser.destroy();
            }
            if (replacementSceneColor != null) {
                replacementSceneColor.destroy();
            }
            if (replacementAccumulation != null) {
                replacementAccumulation.destroy();
            }
            if (replacementOutput != null) {
                replacementOutput.destroy();
            }
            throw exception;
        }
        RenderImages replacement = new RenderImages(
                replacementOutput,
                replacementAccumulation,
                replacementSceneColor,
                replacementDenoiser,
                replacementReflectionDenoiser,
                replacementTransmissionDenoiser,
                replacementTransparentComposite,
                replacementUpscaler,
                qualityMode);
        try {
            this.pipeline.ensureDescriptors(
                    tlas,
                    replacement.output,
                    replacement.accumulation,
                    replacement.sceneColor,
                    atlasView,
                    atlasSampler,
                    this.atmosphere,
                    replacement.denoiser,
                    replacement.reflectionDenoiser,
                    replacement.transmissionDenoiser);
        } catch (RuntimeException exception) {
            replacement.destroy();
            throw exception;
        }
        this.renderImages = replacement;
        if (current != null) {
            // The pipeline retires its old descriptor set before these referenced image views.
            this.context.defer(current);
        }
        PrimeClient.LOGGER.debug(
                "Recreated Prime render images at display {}x{}, render {}x{}, FSR {} "
                        + "(output image={}, view={}; accumulation image={}, view={}; atlas image={}, view={}, sampler={})",
                displayWidth,
                displayHeight,
                renderWidth,
                renderHeight,
                qualityMode.id(),
                hex(replacement.output.image()),
                hex(replacement.output.view()),
                hex(replacement.accumulation.image()),
                hex(replacement.accumulation.view()),
                hex(atlasView.texture().vkImage()),
                hex(atlasView.vkImageView()),
                hex(atlasSampler.vkSampler()));
        return true;
    }

    private void prepareAtlasForTrace(VkCommandBuffer commandBuffer, VulkanGpuTexture atlas) {
        // Minecraft updates animated atlas regions in place and keeps the image in GENERAL.
        // Queue order alone is not a memory dependency: without this availability/visibility
        // barrier, ray tracing can observe an atlas render pass while it is still writing. The
        // resulting corruption is especially reproducible when a swapchain resize changes GPU
        // scheduling. Do not remove this half of the pair unless atlas ownership and submissions
        // are replaced by an equivalent explicit synchronization protocol.
        imageBarrier(
                commandBuffer,
                atlas.vkImage(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    private void finishAtlasRead(VkCommandBuffer commandBuffer, VulkanGpuTexture atlas) {
        // Complete Prime's sampled reads before a later Minecraft render/transfer pass writes the
        // atlas again. This is the reverse dependency of prepareAtlasForTrace; both barriers are
        // required even though the layout remains GENERAL throughout.
        imageBarrier(
                commandBuffer,
                atlas.vkImage(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT);
    }

    private void prepareOutputForComposite(VkCommandBuffer commandBuffer, VulkanImage image) {
        int oldLayout = image.initialized() ? VK12.VK_IMAGE_LAYOUT_GENERAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED;
        long sourceStage = image.initialized() ? VK12.VK_PIPELINE_STAGE_TRANSFER_BIT : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        long sourceAccess = image.initialized() ? VK12.VK_ACCESS_TRANSFER_READ_BIT : 0L;
        imageBarrier(
                commandBuffer,
                image.image(),
                oldLayout,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                sourceStage,
                sourceAccess,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
        image.markInitialized();
    }

    private void prepareAccumulationForTrace(VkCommandBuffer commandBuffer, VulkanImage image) {
        int oldLayout = image.initialized() ? VK12.VK_IMAGE_LAYOUT_GENERAL : VK12.VK_IMAGE_LAYOUT_UNDEFINED;
        long sourceStage = image.initialized()
                // The stable remainder is written by raygen, then read by NRD's composite pass.
                // Both accesses belong to the image's cross-frame lifetime: omitting the compute
                // read here would allow the next raygen dispatch to overwrite a pixel still being
                // consumed by the previous frame on an asynchronously executing Vulkan queue.
                ? KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        long sourceAccess = image.initialized()
                ? VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT
                : 0L;
        imageBarrier(
                commandBuffer,
                image.image(),
                oldLayout,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                sourceStage,
                sourceAccess,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
        image.markInitialized();
    }

    private void prepareSceneColorForComposite(VkCommandBuffer commandBuffer, VulkanImage image) {
        int oldLayout = image.initialized()
                ? VK12.VK_IMAGE_LAYOUT_GENERAL
                : VK12.VK_IMAGE_LAYOUT_UNDEFINED;
        imageBarrier(
                commandBuffer,
                image.image(),
                oldLayout,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                image.initialized()
                        ? VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                        : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                image.initialized()
                        ? VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT
                        : 0L,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT);
        image.markInitialized();
    }

    private void prepareTransparentComposite(
            VkCommandBuffer commandBuffer,
            VulkanImage sceneColor,
            NrdDenoiser denoiser) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(3, stack);
            VulkanImage reactiveMask = denoiser.fsrReactiveMask();
            VulkanImage transparencyMask = denoiser.fsrTransparencyCompositionMask();
            fillImageBarrier(
                    barriers.get(0),
                    sceneColor.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT);
            for (int index = 1; index < 3; index++) {
                VulkanImage image = index == 1 ? reactiveMask : transparencyMask;
                fillImageBarrier(
                        barriers.get(index),
                        image.image(),
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                        KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        VK12.VK_ACCESS_SHADER_WRITE_BIT);
            }
            // NRD first produces one coherent opaque image. The transparent ray pass then
            // overwrites only camera rays whose nearest real surface is glass/water and updates
            // FSR's semantic masks at the same pixels. This explicit dependency is essential:
            // queue order does not make the compute writes visible to later storage-image writes.
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pImageMemoryBarriers(barriers);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private void finishTransparentComposite(
            VkCommandBuffer commandBuffer,
            VulkanImage sceneColor,
            NrdDenoiser denoiser) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(3, stack);
            VulkanImage reactiveMask = denoiser.fsrReactiveMask();
            VulkanImage transparencyMask = denoiser.fsrTransparencyCompositionMask();
            fillImageBarrier(
                    barriers.get(0),
                    sceneColor.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                            | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT);
            for (int index = 1; index < 3; index++) {
                VulkanImage image = index == 1 ? reactiveMask : transparencyMask;
                fillImageBarrier(
                        barriers.get(index),
                        image.image(),
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                                | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        VK12.VK_ACCESS_SHADER_WRITE_BIT,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK12.VK_ACCESS_SHADER_READ_BIT);
            }
            // FSR samples all three images immediately after this point. Keep this barrier paired
            // with prepareTransparentComposite whenever the transparent stage is rescheduled.
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pImageMemoryBarriers(barriers);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private void prepareImagesForCopy(VkCommandBuffer commandBuffer, VulkanImage source, VulkanGpuTexture destination) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(2, stack);
            fillImageBarrier(
                    barriers.get(0),
                    source.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT);
            fillImageBarrier(
                    barriers.get(1),
                    destination.vkImage(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pImageMemoryBarriers(barriers);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private void finishImageCopy(VkCommandBuffer commandBuffer, VulkanImage source, VulkanGpuTexture destination) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(2, stack);
            fillImageBarrier(
                    barriers.get(0),
                    source.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT);
            fillImageBarrier(
                    barriers.get(1),
                    destination.vkImage(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT,
                    VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pImageMemoryBarriers(barriers);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private ByteBuffer createPushConstants(
            MemoryStack stack,
            FrameCamera camera,
            TerrainScene.SceneView scene,
            int width,
            int height,
            SunDirection sunDirection,
            FsrQualityMode qualityMode,
            int fsrFrameIndex,
            boolean cameraInWater,
            LightingSettings.Snapshot lighting) {
        ByteBuffer buffer = stack.calloc(ShaderAbi.PUSH_CONSTANT_SIZE).order(ByteOrder.nativeOrder());
        camera.inverseViewProjection().get(
                ShaderAbi.PUSH_INVERSE_VIEW_PROJECTION_OFFSET, buffer);
        int cameraOffset = ShaderAbi.PUSH_CAMERA_POSITION_OFFSET;
        buffer.putFloat(cameraOffset, (float) (camera.renderX() - scene.originX()));
        buffer.putFloat(cameraOffset + Float.BYTES, (float) (camera.renderY() - scene.originY()));
        buffer.putFloat(cameraOffset + 2 * Float.BYTES, (float) (camera.renderZ() - scene.originZ()));
        buffer.putFloat(
                ShaderAbi.PUSH_ATMOSPHERE_EYE_RADIUS_KM_OFFSET,
                AtmospherePipeline.eyeRadiusKm(camera.y()));
        buffer.putLong(ShaderAbi.PUSH_SECTION_TABLE_ADDRESS_OFFSET, scene.sectionTableAddress());
        buffer.putInt(ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET, width);
        buffer.putInt(ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET + Integer.BYTES, height);
        int sunOffset = ShaderAbi.PUSH_SUN_DIRECTION_OFFSET;
        buffer.putFloat(sunOffset, sunDirection.x());
        buffer.putFloat(sunOffset + Float.BYTES, sunDirection.y());
        buffer.putFloat(sunOffset + 2 * Float.BYTES, sunDirection.z());
        buffer.putInt(
                ShaderAbi.PUSH_RAY_CONE_OFFSET,
                qualityMode.packedRayCone(
                        camera.projection().m00(), camera.projection().m11(), width, height));
        int pathOffset = ShaderAbi.PUSH_PATH_OFFSET;
        buffer.putInt(pathOffset, this.accumulationState.sampleIndex());
        buffer.putInt(pathOffset + Integer.BYTES, this.accumulationState.epoch());
        buffer.putInt(
                pathOffset + 2 * Integer.BYTES,
                IntegratorSettings.packPathControl(
                        IntegratorSettings.MAXIMUM_BOUNCES,
                        qualityMode.jitterPhase(fsrFrameIndex),
                        cameraInWater));
        buffer.putInt(
                pathOffset + 3 * Integer.BYTES,
                IntegratorSettings.packLightingControl(
                        lighting.sunQuarterSteps(),
                        lighting.blockLightQuarterSteps()));
        return buffer.position(0).limit(ShaderAbi.PUSH_CONSTANT_SIZE);
    }

    private boolean isCameraInWater(Minecraft minecraft, FrameCamera camera) {
        if (minecraft.level == null) {
            return false;
        }
        BlockPos position = this.cameraBlockPosition.set(
                camera.x(), camera.y(), camera.z());
        var fluid = minecraft.level.getFluidState(position);
        // Match vanilla's height-aware camera test. A block-only check incorrectly puts the
        // camera in a medium while the eye is above shallow or flowing water in the same cell.
        return fluid.is(FluidTags.WATER)
                && camera.y() < position.getY() + fluid.getHeight(minecraft.level, position);
    }

    private static void imageBarrier(
            VkCommandBuffer commandBuffer,
            long image,
            int oldLayout,
            int newLayout,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
            fillImageBarrier(
                    barrier.get(0),
                    image,
                    oldLayout,
                    newLayout,
                    sourceStage,
                    sourceAccess,
                    destinationStage,
                    destinationAccess);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pImageMemoryBarriers(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private static void fillImageBarrier(
            VkImageMemoryBarrier2 barrier,
            long image,
            int oldLayout,
            int newLayout,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess) {
        barrier.sType$Default()
                .srcStageMask(sourceStage)
                .srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage)
                .dstAccessMask(destinationAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    private static String hex(long handle) {
        return "0x" + Long.toUnsignedString(handle, 16);
    }

    private static final class RenderImages implements Destroyable {
        private final VulkanImage output;
        private final VulkanImage accumulation;
        private final VulkanImage sceneColor;
        private final FsrQualityMode qualityMode;
        private NrdDenoiser denoiser;
        private NrdDenoiser reflectionDenoiser;
        private NrdDenoiser transmissionDenoiser;
        private NrdTransparentComposite transparentComposite;
        private Fsr3Upscaler upscaler;
        private boolean destroyed;

        private RenderImages(
                VulkanImage output,
                VulkanImage accumulation,
                VulkanImage sceneColor,
                NrdDenoiser denoiser,
                NrdDenoiser reflectionDenoiser,
                NrdDenoiser transmissionDenoiser,
                NrdTransparentComposite transparentComposite,
                Fsr3Upscaler upscaler,
                FsrQualityMode qualityMode) {
            this.output = output;
            this.accumulation = accumulation;
            this.sceneColor = sceneColor;
            this.denoiser = denoiser;
            this.reflectionDenoiser = reflectionDenoiser;
            this.transmissionDenoiser = transmissionDenoiser;
            this.transparentComposite = transparentComposite;
            this.upscaler = upscaler;
            this.qualityMode = qualityMode;
        }

        private boolean matches(
                int displayWidth,
                int displayHeight,
                int renderWidth,
                int renderHeight,
                FsrQualityMode qualityMode) {
            return this.output.width() == displayWidth
                    && this.output.height() == displayHeight
                    && this.accumulation.width() == renderWidth
                    && this.accumulation.height() == renderHeight
                    && this.sceneColor.width() == renderWidth
                    && this.sceneColor.height() == renderHeight
                    && this.qualityMode == qualityMode;
        }

        @Override
        public void destroy() {
            if (!this.destroyed) {
                this.destroyed = true;
                this.upscaler.destroy();
                this.transparentComposite.destroy();
                this.transmissionDenoiser.destroy();
                this.reflectionDenoiser.destroy();
                this.denoiser.destroy();
                this.sceneColor.destroy();
                this.accumulation.destroy();
                this.output.destroy();
            }
        }
    }
}
