package dev.prime.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.PrimeClient;
import dev.prime.mixin.TextureAtlasAccessor;
import dev.prime.render.terrain.TerrainScene;
import dev.prime.render.terrain.TerrainStreamer;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.PostProcessingSettings;
import dev.prime.render.post.RealtimePostProcessor;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.DlssRrDebugStatus;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.LabPbrTextureAtlas;
import dev.prime.render.vulkan.RayTracingPipeline;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.VulkanCapabilities;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import dev.prime.render.vulkan.dlss.DlssRrNative;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.tags.FluidTags;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
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
    private final DlssRrNative.Context ngxContext;
    private final StagingArena stagingArena;
    private final TerrainStreamer terrain;
    private final LabPbrTextureAtlas labPbrAtlas;
    private final RealtimeSampleState realtimeSampleState = new RealtimeSampleState();
    private final BlockPos.MutableBlockPos cameraBlockPosition = new BlockPos.MutableBlockPos();
    private RayTracingPipeline pipeline;
    private AtmospherePipeline atmosphere;
    private RealtimeRenderResources realtimeResources;
    private ReferenceAccumulator screenshotResources;
    private BlockAtlasFrame blockAtlasFrame;
    private FrameCamera camera;
    private SunDirection sunDirection;
    private boolean cameraMediumKnown;
    private boolean cameraInWater;
    private long submittedLightingRevision = Long.MIN_VALUE;
    private long submittedMaterialRevision = Long.MIN_VALUE;
    private volatile boolean shaderReloadRequested;
    private ClientLevel screenshotWorld;
    private TerrainScene.SceneView screenshotScene;
    private FrameCamera screenshotCamera;
    private SunDirection screenshotSunDirection;
    private LightingSettings.Snapshot screenshotLighting;
    private MaterialSettings.Snapshot screenshotMaterial;
    private boolean screenshotCameraInWater;
    private long screenshotAtlasView;
    private long screenshotAtlasSampler;
    private long screenshotSampleCount;
    private boolean closed;
    private boolean rrFallbackReported;

    public VulkanRenderer(com.mojang.blaze3d.vulkan.VulkanDevice device, VulkanCapabilities capabilities) {
        VulkanContext newContext = new VulkanContext(device, capabilities);
        StagingArena newStagingArena = null;
        AtmospherePipeline newAtmosphere = null;
        RayTracingPipeline newPipeline = null;
        TerrainStreamer newTerrain = null;
        LabPbrTextureAtlas newLabPbrAtlas = null;
        DlssRrNative.Context newNgxContext = null;
        try {
            newStagingArena = new StagingArena(newContext);
            newAtmosphere = new AtmospherePipeline(newContext);
            newPipeline = new RayTracingPipeline(newContext);
            newTerrain = new TerrainStreamer(newContext, newStagingArena);
            newLabPbrAtlas = new LabPbrTextureAtlas(newContext, newStagingArena);
            newNgxContext = DlssRrBootstrap.initialize(newContext).orElse(null);
            this.context = newContext;
            this.ngxContext = newNgxContext;
            this.stagingArena = newStagingArena;
            this.pipeline = newPipeline;
            this.atmosphere = newAtmosphere;
            this.terrain = newTerrain;
            this.labPbrAtlas = newLabPbrAtlas;
        } catch (RuntimeException exception) {
            if (newNgxContext != null) {
                newNgxContext.close();
            }
            if (newLabPbrAtlas != null) {
                newLabPbrAtlas.close();
            }
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
        this.synchronizeLabPbr(minecraft);
        this.updateScreenshotSession(minecraft);
        if (ScreenshotMode.active()) {
            return;
        }
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

    private void synchronizeLabPbr(Minecraft minecraft) {
        TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        // Atlas objects exist before their GPU texture is uploaded. getTextureView() deliberately
        // throws during that short interval, which is normal startup state rather than a renderer
        // failure. The stitch map becomes non-empty in the same upload that creates the view.
        if (((TextureAtlasAccessor) (Object) atlas)
                .prime$texturesByName()
                .isEmpty()) {
            this.blockAtlasFrame = null;
            return;
        }
        if (!(atlas.getTextureView() instanceof VulkanGpuTextureView atlasView)
                || !(atlas.getSampler() instanceof VulkanGpuSampler atlasSampler)) {
            throw new IllegalStateException("Prime expected Vulkan block atlas resources");
        }
        this.terrain.setLabPbrMaterials(
                this.labPbrAtlas.ensure(minecraft, atlas, atlasView.vkImageView()));
        this.blockAtlasFrame = new BlockAtlasFrame(atlasView, atlasSampler);
    }

    public void captureCamera(
            Matrix4fc renderedProjection,
            Matrix4fc baseProjection,
            Matrix4fc viewRotation,
            double x,
            double y,
            double z,
            float sunAngleRadians) {
        if (ScreenshotMode.active()) {
            this.updateScreenshotProjection(baseProjection);
            return;
        }
        this.camera = FrameCamera.tryCreate(
                renderedProjection, baseProjection, viewRotation, x, y, z);
        this.sunDirection = SunDirection.fromVanillaAngle(sunAngleRadians);
    }

    public boolean isReady() {
        return this.terrain.isNearCameraReady() && this.terrain.sceneView() != null;
    }

    public void render(RenderTarget mainTarget) {
        if (ScreenshotMode.active()) {
            this.renderScreenshot(mainTarget);
        } else {
            this.renderRealtime(mainTarget);
        }
    }

    /**
     * Records Prime's interactive frame graph: one path sample, the selected reconstruction
     * backend, and the common display transform. Keeping this orchestration behind a named boundary
     * prevents the future offline accumulator from inheriting temporal resources by accident.
     */
    private void renderRealtime(RenderTarget mainTarget) {
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
        ReconstructionQualityMode requestedQualityMode = PostProcessingSettings.quality();
        PostProcessingMode effectiveMode = PostProcessingSettings.mode();
        int renderWidth;
        int renderHeight;
        if (effectiveMode == PostProcessingMode.DLSS_RR
                && this.ngxContext != null
                && DlssRrBootstrap.deviceReady()) {
            try {
                DlssRrNative.OptimalSettings optimal =
                        this.ngxContext.optimalSettings(width, height, requestedQualityMode);
                renderWidth = optimal.renderWidth();
                renderHeight = optimal.renderHeight();
                this.rrFallbackReported = false;
            } catch (RuntimeException exception) {
                DlssRrBootstrap.failSession(
                        "DLSS RR optimal-size query failed; using NRD-FSR", exception);
                effectiveMode = PostProcessingMode.NRD_FSR;
                renderWidth = requestedQualityMode.renderWidth(width);
                renderHeight = requestedQualityMode.renderHeight(height);
            }
        } else {
            if (effectiveMode == PostProcessingMode.DLSS_RR) {
                effectiveMode = PostProcessingMode.NRD_FSR;
                if (!this.rrFallbackReported) {
                    this.rrFallbackReported = true;
                    PrimeClient.LOGGER.warn(
                            "DLSS RR selected but unavailable; using NRD-FSR for this session: {}",
                            DlssRrBootstrap.unavailableReason());
                }
            }
            renderWidth = requestedQualityMode.renderWidth(width);
            renderHeight = requestedQualityMode.renderHeight(height);
        }
        long invocationCount = (long) renderWidth * renderHeight;
        if (invocationCount > Integer.toUnsignedLong(this.context.capabilities().maxRayDispatchInvocationCount())) {
            throw new IllegalStateException("Window dimensions exceed the Vulkan ray dispatch limit");
        }

        BlockAtlasFrame blockAtlas = this.blockAtlasFrame;
        if (blockAtlas == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        VulkanGpuTextureView atlasView = blockAtlas.view;
        VulkanGpuSampler atlasSampler = blockAtlas.sampler;
        long atlasViewHandle = atlasView.vkImageView();
        long atlasSamplerHandle = atlasSampler.vkSampler();
        boolean resized;
        try {
            resized = this.ensureRealtimeResources(
                    width,
                    height,
                    renderWidth,
                    renderHeight,
                    effectiveMode,
                    requestedQualityMode,
                    scene.tlas(),
                    atlasView,
                    atlasSampler);
        } catch (RuntimeException exception) {
            if (effectiveMode != PostProcessingMode.DLSS_RR) {
                throw exception;
            }
            DlssRrBootstrap.failSession(
                    "DLSS RR feature creation failed; using NRD-FSR", exception);
            effectiveMode = PostProcessingMode.NRD_FSR;
            renderWidth = requestedQualityMode.renderWidth(width);
            renderHeight = requestedQualityMode.renderHeight(height);
            resized = this.ensureRealtimeResources(
                    width,
                    height,
                    renderWidth,
                    renderHeight,
                    effectiveMode,
                    requestedQualityMode,
                    scene.tlas(),
                    atlasView,
                    atlasSampler);
        }
        RealtimeRenderResources images = this.realtimeResources;
        if (images == null) {
            return;
        }
        VulkanImage target = images.output;
        VulkanImage history = images.accumulation;
        RealtimePostProcessor processor = images.processor;
        LightingSettings.Snapshot lighting = LightingSettings.snapshot();
        MaterialSettings.Snapshot material = MaterialSettings.snapshot();
        boolean lightingChanged = this.submittedLightingRevision != Long.MIN_VALUE
                && lighting.revision() != this.submittedLightingRevision;
        boolean materialChanged = this.submittedMaterialRevision != Long.MIN_VALUE
                && material.revision() != this.submittedMaterialRevision;
        if (lightingChanged || materialChanged) {
            // Radiance and BSDF parameter changes invalidate temporal estimators, but not
            // geometry, atmosphere transmittance, or light-tree probabilities.
            images.requestReset();
        }
        boolean frameCameraInWater = this.isCameraInWater(minecraft, frameCamera);
        if (this.cameraMediumKnown && this.cameraInWater != frameCameraInWater) {
            // Crossing the water surface changes transport for essentially every visible path.
            // Treat it as a temporal discontinuity so NRD/FSR do not retain the previous medium.
            this.realtimeSampleState.invalidate();
            images.requestReset();
        }
        this.cameraMediumKnown = true;
        this.cameraInWater = frameCameraInWater;
        this.realtimeSampleState.prepare(
                frameCamera,
                scene.temporalRevision(),
                atlasViewHandle,
                atlasSamplerHandle,
                frameSunDirection,
                resized || lightingChanged || materialChanged);
        RealtimePostProcessor.FrameParameters postParameters =
                new RealtimePostProcessor.FrameParameters(
                        frameCamera,
                        scene.temporalRevision(),
                        atlasViewHandle,
                        atlasSamplerHandle,
                        frameSunDirection,
                        lighting.sunMultiplier());
        RealtimePostProcessor.Frame postFrame = processor.beginFrame(postParameters);
        if (images.mode == PostProcessingMode.DLSS_RR) {
            DlssRrDebugStatus.update(
                    images.qualityMode,
                    renderWidth,
                    renderHeight,
                    width,
                    height,
                    true,
                    postFrame.reset());
        } else {
            DlssRrDebugStatus.clear();
        }
        var encoder = this.context.commandEncoder();
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
        this.context.device().instance().debug().beginDebugGroup(
                commandBuffer,
                () -> images.mode == PostProcessingMode.DLSS_RR
                        ? "Prime 1spp path tracing and DLSS Ray Reconstruction"
                        : "Prime 1spp path tracing, NRD, and FidelityFX FSR 3.1.4");
        this.atmosphere.prepare(commandBuffer, frameCamera, frameSunDirection);
        this.prepareOutputForComposite(commandBuffer, target);
        this.prepareAccumulationForTrace(commandBuffer, history);
        processor.prepareForRayTrace(commandBuffer);
        this.prepareAtlasForTrace(commandBuffer, atlasView.texture());
        LabPbrTextureAtlas.FrameToken labPbrFrame = this.labPbrAtlas.prepare(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pushConstants = this.createPushConstants(
                    stack,
                    frameCamera,
                    scene,
                    renderWidth,
                    renderHeight,
                    frameSunDirection,
                    images.qualityMode.packedRayCone(
                            frameCamera.projection().m00(),
                            frameCamera.projection().m11(),
                            renderWidth,
                            renderHeight),
                    this.realtimeSampleState.sampleIndex(),
                    this.realtimeSampleState.epoch(),
                    images.mode == PostProcessingMode.DLSS_RR
                            ? images.qualityMode.rrJitterPhase(postFrame.frameIndex())
                            : images.qualityMode.fsrJitterPhase(postFrame.frameIndex()),
                    frameCameraInWater,
                    lighting,
                    material,
                    images.mode == PostProcessingMode.DLSS_RR);
            this.pipeline.trace(commandBuffer, pushConstants, renderWidth, renderHeight);
            processor.record(commandBuffer, postFrame, postParameters);
            this.finishAtlasRead(commandBuffer, atlasView.texture());
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
        this.labPbrAtlas.submitted(labPbrFrame);
        processor.submitted(postFrame);
        this.submittedLightingRevision = lighting.revision();
        this.submittedMaterialRevision = material.revision();
        this.realtimeSampleState.submitted(
                frameCamera,
                atlasViewHandle,
                atlasSamplerHandle,
                frameSunDirection);
        int accumulatedSampleCount = this.realtimeSampleState.sampleIndex();
        if (accumulatedSampleCount >= 16
                && (accumulatedSampleCount & (accumulatedSampleCount - 1)) == 0) {
            PrimeClient.LOGGER.debug(
                    "Prime accumulation reached {} samples for scene revision {}",
                    accumulatedSampleCount,
                    scene.revision());
        }
    }

    /** Records one native-resolution unbiased sample and presents the running mean directly. */
    private void renderScreenshot(RenderTarget mainTarget) {
        DlssRrDebugStatus.clear();
        TerrainScene.SceneView scene = this.screenshotScene;
        FrameCamera frameCamera = this.screenshotCamera;
        SunDirection frameSunDirection = this.screenshotSunDirection;
        LightingSettings.Snapshot lighting = this.screenshotLighting;
        MaterialSettings.Snapshot material = this.screenshotMaterial;
        RealtimeRenderResources realtime = this.realtimeResources;
        if (scene == null
                || frameCamera == null
                || frameSunDirection == null
                || lighting == null
                || material == null
                || realtime == null) {
            ScreenshotMode.request(false);
            this.stopScreenshotSession();
            this.renderRealtime(mainTarget);
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
        long invocationCount = (long) width * height;
        if (invocationCount
                > Integer.toUnsignedLong(
                        this.context.capabilities().maxRayDispatchInvocationCount())) {
            throw new IllegalStateException("Window dimensions exceed the Vulkan ray dispatch limit");
        }

        BlockAtlasFrame blockAtlas = this.blockAtlasFrame;
        if (blockAtlas == null) {
            return;
        }
        VulkanGpuTextureView atlasView = blockAtlas.view;
        VulkanGpuSampler atlasSampler = blockAtlas.sampler;
        long atlasViewHandle = atlasView.vkImageView();
        long atlasSamplerHandle = atlasSampler.vkSampler();
        if (this.screenshotAtlasView == 0L) {
            this.screenshotAtlasView = atlasViewHandle;
            this.screenshotAtlasSampler = atlasSamplerHandle;
        } else if (this.screenshotAtlasView != atlasViewHandle
                || this.screenshotAtlasSampler != atlasSamplerHandle) {
            // A resource-pack reload replaces the frozen material snapshot. Continuing would mix
            // two different texture sets in one statistical mean, so return to realtime and let
            // the ordinary reload/resynchronization path establish a new coherent scene.
            ScreenshotMode.request(false);
            this.stopScreenshotSession();
            this.renderRealtime(mainTarget);
            return;
        }

        this.ensureScreenshotResources(width, height);
        ReferenceAccumulator images = this.screenshotResources;
        if (images == null) {
            return;
        }
        this.pipeline.ensureDescriptors(
                scene.tlas(),
                images.output,
                images.accumulation,
                atlasView,
                atlasSampler,
                this.labPbrAtlas.normalAtlas(),
                this.labPbrAtlas.specularAtlas(),
                this.atmosphere,
                realtime.processor.targets());

        var encoder = this.context.commandEncoder();
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
        this.context.device().instance().debug().beginDebugGroup(
                commandBuffer, () -> "Prime unbiased screenshot accumulation");
        this.atmosphere.prepare(commandBuffer, frameCamera, frameSunDirection);
        this.prepareOutputForComposite(commandBuffer, images.output);
        this.prepareAccumulationForTrace(commandBuffer, images.accumulation);
        this.prepareAtlasForTrace(commandBuffer, atlasView.texture());
        LabPbrTextureAtlas.FrameToken labPbrFrame = this.labPbrAtlas.prepare(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int sampleIndex = (int) (this.screenshotSampleCount & 0xffffL);
            int sampleEpoch = (int) (this.screenshotSampleCount >>> 16);
            ByteBuffer pushConstants = this.createPushConstants(
                    stack,
                    frameCamera,
                    scene,
                    width,
                    height,
                    frameSunDirection,
                    packScreenshotRayCone(
                            frameCamera.projection().m00(),
                            frameCamera.projection().m11(),
                            width,
                            height),
                    sampleIndex,
                    sampleEpoch,
                    1,
                    this.screenshotCameraInWater,
                    lighting,
                    material,
                    false);
            this.pipeline.traceScreenshot(commandBuffer, pushConstants, width, height);
            this.prepareScreenshotDisplay(commandBuffer, images.accumulation);
            images.display.record(commandBuffer, width, height);
            this.finishAtlasRead(commandBuffer, atlasView.texture());
            this.prepareImagesForCopy(commandBuffer, images.output, mainColor);
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
                    images.output.image(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    mainColor.vkImage(),
                    VK12.VK_IMAGE_LAYOUT_GENERAL,
                    copy);
            this.finishImageCopy(commandBuffer, images.output, mainColor);
        }
        this.context.device().instance().debug().endDebugGroup(commandBuffer);
        VulkanContext.check(
                VK12.vkEndCommandBuffer(commandBuffer),
                "end Prime screenshot accumulation command buffer");
        encoder.execute(commandBuffer);
        this.labPbrAtlas.submitted(labPbrFrame);
        this.screenshotSampleCount++;
        if (this.screenshotSampleCount > 0L
                && (this.screenshotSampleCount & (this.screenshotSampleCount - 1L)) == 0L) {
            PrimeClient.LOGGER.info(
                    "Prime screenshot accumulation reached {} samples",
                    this.screenshotSampleCount);
        }
    }

    private void updateScreenshotSession(Minecraft minecraft) {
        boolean worldChanged = ScreenshotMode.active()
                && (minecraft.level == null || minecraft.level != this.screenshotWorld);
        if (worldChanged) {
            ScreenshotMode.request(false);
        }
        if (ScreenshotMode.active() && (!ScreenshotMode.requested() || worldChanged)) {
            this.stopScreenshotSession();
        }
        if (!ScreenshotMode.active()
                && ScreenshotMode.requested()
                && minecraft.level != null
                && this.camera != null
                && this.sunDirection != null
                && this.terrain.sceneView() != null
                && this.realtimeResources != null) {
            this.screenshotWorld = minecraft.level;
            this.screenshotScene = this.terrain.sceneView();
            this.screenshotCamera = this.camera;
            this.screenshotSunDirection = this.sunDirection;
            this.screenshotLighting = LightingSettings.snapshot();
            this.screenshotMaterial = MaterialSettings.snapshot();
            this.screenshotCameraInWater = this.isCameraInWater(minecraft, this.camera);
            this.screenshotAtlasView = 0L;
            this.screenshotAtlasSampler = 0L;
            this.screenshotSampleCount = 0L;
            ScreenshotMode.activate();
            PrimeClient.LOGGER.info(
                    "Entered Prime screenshot mode at scene revision {}",
                    this.screenshotScene.revision());
        }
    }

    private void stopScreenshotSession() {
        if (!ScreenshotMode.active() && this.screenshotWorld == null) {
            return;
        }
        ScreenshotMode.deactivate();
        this.screenshotWorld = null;
        this.screenshotScene = null;
        this.screenshotCamera = null;
        this.screenshotSunDirection = null;
        this.screenshotLighting = null;
        this.screenshotMaterial = null;
        this.screenshotAtlasView = 0L;
        this.screenshotAtlasSampler = 0L;
        this.screenshotSampleCount = 0L;
        if (this.screenshotResources != null) {
            this.context.defer(this.screenshotResources);
            this.screenshotResources = null;
        }
        // Dirty notifications continue to accumulate while uploads are paused. A full resync on
        // exit also covers animation-driven or external changes that do not expose a precise
        // block range, without invalidating the frozen screenshot while it is converging.
        this.terrain.invalidateAll();
        this.realtimeSampleState.invalidate();
        if (this.realtimeResources != null) {
            this.realtimeResources.requestReset();
        }
        PrimeClient.LOGGER.info("Left Prime screenshot mode; scheduled a full terrain resync");
    }

    private void updateScreenshotProjection(Matrix4fc baseProjection) {
        FrameCamera fixed = this.screenshotCamera;
        if (fixed == null) {
            return;
        }
        float previousAspect = Math.abs(fixed.projection().m11() / fixed.projection().m00());
        float nextAspect = Math.abs(baseProjection.m11() / baseProjection.m00());
        if (!Float.isFinite(previousAspect)
                || !Float.isFinite(nextAspect)
                || Math.abs(previousAspect - nextAspect) <= 1.0e-5F) {
            return;
        }
        Matrix4f projection = new Matrix4f(baseProjection);
        Matrix4f inverse = new Matrix4f(projection).mul(fixed.viewRotation()).invert();
        if (!inverse.isFinite()) {
            return;
        }
        this.screenshotCamera = new FrameCamera(
                projection,
                new Matrix4f(fixed.viewRotation()),
                inverse,
                fixed.x(),
                fixed.y(),
                fixed.z(),
                fixed.renderX(),
                fixed.renderY(),
                fixed.renderZ());
        this.screenshotSampleCount = 0L;
        if (this.screenshotResources != null) {
            this.context.defer(this.screenshotResources);
            this.screenshotResources = null;
        }
        PrimeClient.LOGGER.info("Restarted Prime screenshot accumulation for a new aspect ratio");
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
        if (this.realtimeResources != null) {
            this.realtimeResources.requestReset();
        }
    }

    public void requestShaderReload() {
        ScreenshotMode.request(false);
        this.shaderReloadRequested = true;
    }

    public void requestResourceReload() {
        this.labPbrAtlas.requestReload();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        ScreenshotMode.reset();
        this.context.awaitIdle();
        this.context.drainDeferredAfterIdle();
        this.terrain.close();
        this.labPbrAtlas.close();
        this.pipeline.destroy();
        if (this.realtimeResources != null) {
            this.realtimeResources.destroy();
            this.realtimeResources = null;
        }
        if (this.screenshotResources != null) {
            this.screenshotResources.destroy();
            this.screenshotResources = null;
        }
        this.atmosphere.destroy();
        this.stagingArena.close();
        if (this.ngxContext != null) {
            this.ngxContext.close();
        }
        this.context.close();
    }

    private void reloadPipelineIfRequested() {
        if (!this.shaderReloadRequested) {
            return;
        }
        this.shaderReloadRequested = false;
        AtmospherePipeline replacementAtmosphere = null;
        RayTracingPipeline replacementPipeline = null;
        RealtimeRenderResources replacementResources = null;
        try {
            replacementAtmosphere = new AtmospherePipeline(this.context);
            replacementPipeline = new RayTracingPipeline(this.context);
            RealtimeRenderResources current = this.realtimeResources;
            if (current != null) {
                replacementResources = RealtimeRenderResources.create(
                        this.context,
                        replacementAtmosphere,
                        current.output.width(),
                        current.output.height(),
                        current.accumulation.width(),
                        current.accumulation.height(),
                        current.mode,
                        current.qualityMode,
                        this.ngxContext);
            }
        } catch (RuntimeException exception) {
            if (replacementResources != null) {
                replacementResources.destroy();
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
        RealtimeRenderResources previousResources = this.realtimeResources;
        this.pipeline = replacementPipeline;
        this.atmosphere = replacementAtmosphere;
        this.realtimeResources = replacementResources;
        this.context.defer(previousPipeline);
        if (previousResources != null) {
            this.context.defer(previousResources);
        }
        this.context.defer(previousAtmosphere);
        this.realtimeSampleState.invalidate();
        PrimeClient.LOGGER.info("Reloaded Prime ray tracing and atmosphere shaders");
    }

    private boolean ensureRealtimeResources(
            int displayWidth,
            int displayHeight,
            int renderWidth,
            int renderHeight,
            PostProcessingMode mode,
            ReconstructionQualityMode qualityMode,
            long tlas,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler) {
        RealtimeRenderResources current = this.realtimeResources;
        if (current != null && current.matches(
                displayWidth, displayHeight, renderWidth, renderHeight, mode, qualityMode)) {
            this.pipeline.ensureDescriptors(
                    tlas,
                    current.output,
                    current.accumulation,
                    atlasView,
                    atlasSampler,
                    this.labPbrAtlas.normalAtlas(),
                    this.labPbrAtlas.specularAtlas(),
                    this.atmosphere,
                    current.processor.targets());
            return false;
        }
        RealtimeRenderResources replacement = RealtimeRenderResources.create(
                this.context,
                this.atmosphere,
                displayWidth,
                displayHeight,
                renderWidth,
                renderHeight,
                mode,
                qualityMode,
                this.ngxContext);
        try {
            this.pipeline.ensureDescriptors(
                    tlas,
                    replacement.output,
                    replacement.accumulation,
                    atlasView,
                    atlasSampler,
                    this.labPbrAtlas.normalAtlas(),
                    this.labPbrAtlas.specularAtlas(),
                    this.atmosphere,
                    replacement.processor.targets());
        } catch (RuntimeException exception) {
            replacement.destroy();
            throw exception;
        }
        this.realtimeResources = replacement;
        if (current != null) {
            // The pipeline retires its old descriptor set before these referenced image views.
            this.context.defer(current);
        }
        PrimeClient.LOGGER.debug(
                "Recreated Prime render images at display {}x{}, render {}x{}, {} {} "
                        + "(output image={}, view={}; accumulation image={}, view={}; atlas image={}, view={}, sampler={})",
                displayWidth,
                displayHeight,
                renderWidth,
                renderHeight,
                mode.id(),
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

    private void ensureScreenshotResources(int width, int height) {
        ReferenceAccumulator current = this.screenshotResources;
        if (current != null && current.matches(width, height)) {
            return;
        }
        ReferenceAccumulator replacement =
                ReferenceAccumulator.create(this.context, width, height);
        this.screenshotResources = replacement;
        this.screenshotSampleCount = 0L;
        if (current != null) {
            this.context.defer(current);
        }
        PrimeClient.LOGGER.info(
                "Created Prime screenshot resources at native {}x{}", width, height);
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

    private void prepareScreenshotDisplay(
            VkCommandBuffer commandBuffer, VulkanImage accumulation) {
        // Screenshot raygen updates an in-place running mean. The display pass reads that exact
        // RGBA32F history; this dependency is the only hand-off and deliberately contains no NRD
        // or FSR temporal resource.
        imageBarrier(
                commandBuffer,
                accumulation.image(),
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_IMAGE_LAYOUT_GENERAL,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT);
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
            int packedRayCone,
            int sampleIndex,
            int sampleEpoch,
            int jitterPhase,
            boolean cameraInWater,
            LightingSettings.Snapshot lighting,
            MaterialSettings.Snapshot material,
            boolean enableDlssRrGuide) {
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
        buffer.putInt(ShaderAbi.PUSH_RAY_CONE_OFFSET, packedRayCone);
        int pathOffset = ShaderAbi.PUSH_PATH_OFFSET;
        buffer.putInt(pathOffset, sampleIndex);
        buffer.putInt(pathOffset + Integer.BYTES, sampleEpoch);
        buffer.putInt(
                pathOffset + 2 * Integer.BYTES,
                IntegratorSettings.packPathControl(
                        IntegratorSettings.MAXIMUM_BOUNCES,
                        jitterPhase,
                        cameraInWater));
        int materialLightingControl = IntegratorSettings.packMaterialLightingControl(
                        lighting.sunQuarterSteps(),
                        lighting.blockLightQuarterSteps(),
                        material.roughnessSteps());
        if (enableDlssRrGuide) {
            materialLightingControl |= ShaderAbi.PATH_DLSS_RR_MASK;
        }
        buffer.putInt(pathOffset + 3 * Integer.BYTES, materialLightingControl);
        return buffer.position(0).limit(ShaderAbi.PUSH_CONSTANT_SIZE);
    }

    private static int packScreenshotRayCone(
            float projectionM00, float projectionM11, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Ray-cone render dimensions must be positive");
        }
        float x = 2.0F / (width * Math.abs(projectionM00));
        float y = 2.0F / (height * Math.abs(projectionM11));
        float spread = Math.max(x, y);
        if (!Float.isFinite(spread) || spread <= 0.0F) {
            throw new IllegalArgumentException("Ray-cone projection must be finite and non-zero");
        }
        // Screenshot mode renders natively and therefore has no upscaler-specific negative LOD
        // bias. The low half remains the physical one-pixel cone spread used by hit shaders.
        return Float.floatToFloat16(spread) & 0xffff;
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

    /** One block-atlas snapshot is resolved and synchronized at the frame boundary. */
    private record BlockAtlasFrame(
            VulkanGpuTextureView view,
            VulkanGpuSampler sampler) {
    }

}
