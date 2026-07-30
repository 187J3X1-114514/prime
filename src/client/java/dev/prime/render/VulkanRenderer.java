package dev.prime.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.PrimeClient;
import dev.prime.config.PrimeConfig;
import dev.prime.config.PrimeSettings;
import dev.prime.mixin.TextureAtlasAccessor;
import dev.prime.render.terrain.TerrainScene;
import dev.prime.render.terrain.TerrainStreamer;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.RealtimePostProcessor;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.DlssRrDebugStatus;
import dev.prime.render.replay.RenderReplayVerification;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.LabPbrTextureAtlas;
import dev.prime.render.vulkan.RayTracingPipeline;
import dev.prime.render.vulkan.RealtimeFrameExecutor;
import dev.prime.render.vulkan.ScreenshotFrameExecutor;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.StarmapTexture;
import dev.prime.render.vulkan.VulkanCapabilities;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import dev.prime.render.vulkan.dlss.DlssRrNative;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import dev.prime.render.vulkan.replay.ReplayProbeController;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.tags.FluidTags;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;

public final class VulkanRenderer implements AutoCloseable {
    private final VulkanContext context;
    private final RealtimeFrameExecutor realtimeExecutor;
    private final ScreenshotFrameExecutor screenshotExecutor;
    private final ReplayProbeController replayProbeController;
    private DlssRrNative.Context ngxContext;
    private final StagingArena stagingArena;
    private final StarmapTexture starmap;
    private final TerrainStreamer terrain;
    private final LabPbrTextureAtlas labPbrAtlas;
    private RealtimeSampleState realtimeSampleState = RealtimeSampleState.initial();
    private final BlockPos.MutableBlockPos cameraBlockPosition = new BlockPos.MutableBlockPos();
    private RayTracingPipeline pipeline;
    private AtmospherePipeline atmosphere;
    private RealtimeRenderResources realtimeResources;
    private ScreenshotRenderResources screenshotResources;
    private BlockAtlasFrame blockAtlasFrame;
    private long blockAtlasTextureRevision;
    private FrameCamera camera;
    private SunDirection sunDirection;
    // Resource-reload apply can publish this request off the render thread; all GPU mutation is
    // still consumed and owned by beginFrame on the render thread.
    private volatile boolean shaderReloadRequested;
    private ClientLevel screenshotWorld;
    private TerrainScene.ResidentSceneView screenshotScene;
    private FrameCamera screenshotCamera;
    private SunDirection screenshotSunDirection;
    private LightingSettings.Snapshot screenshotLighting;
    private MaterialSettings.Snapshot screenshotMaterial;
    private boolean screenshotCameraInWater;
    private long screenshotAtlasView;
    private long screenshotAtlasSampler;
    private long screenshotTextureRevision = Long.MIN_VALUE;
    private long screenshotSampleCount;
    private SessionControls frameControls = SessionControls.defaults();
    private List<String> debugLines = List.of();
    private boolean screenshotRequestRejected;
    private boolean closed;
    private boolean rrFallbackReported;
    private DlssRrNative.OptimalSettings rrOptimalSettings;
    private ReconstructionQualityMode rrOptimalQualityMode;
    private int rrOptimalDisplayWidth;
    private int rrOptimalDisplayHeight;
    public VulkanRenderer(com.mojang.blaze3d.vulkan.VulkanDevice device, VulkanCapabilities capabilities) {
        VulkanContext newContext = new VulkanContext(device, capabilities);
        StagingArena newStagingArena = null;
        StarmapTexture newStarmap = null;
        AtmospherePipeline newAtmosphere = null;
        RayTracingPipeline newPipeline = null;
        TerrainStreamer newTerrain = null;
        LabPbrTextureAtlas newLabPbrAtlas = null;
        DlssRrNative.Context newNgxContext = null;
        try {
            newStagingArena = new StagingArena(newContext);
            newStarmap = new StarmapTexture(newContext);
            newAtmosphere = new AtmospherePipeline(newContext);
            newPipeline = new RayTracingPipeline(newContext, newStarmap);
            newTerrain = new TerrainStreamer(newContext, newStagingArena);
            newLabPbrAtlas = new LabPbrTextureAtlas(newContext, newStagingArena);
            newNgxContext = DlssRrBootstrap.initialize(newContext).orElse(null);
            this.context = newContext;
            this.realtimeExecutor =
                    new RealtimeFrameExecutor(newContext);
            this.screenshotExecutor =
                    new ScreenshotFrameExecutor(newContext);
            this.replayProbeController =
                    new ReplayProbeController(newContext);
            this.ngxContext = newNgxContext;
            this.stagingArena = newStagingArena;
            this.starmap = newStarmap;
            this.pipeline = newPipeline;
            this.atmosphere = newAtmosphere;
            this.terrain = newTerrain;
            this.labPbrAtlas = newLabPbrAtlas;
        } catch (RuntimeException exception) {
            if (newNgxContext != null) {
                DlssRrNative.Context failedNgxContext = newNgxContext;
                ResourceCleanup.run(
                        () -> DlssRrBootstrap.release(failedNgxContext), exception);
            }
            ResourceCleanup.close(newLabPbrAtlas, exception);
            ResourceCleanup.close(newTerrain, exception);
            ResourceCleanup.destroy(newPipeline, exception);
            ResourceCleanup.destroy(newAtmosphere, exception);
            ResourceCleanup.destroy(newStarmap, exception);
            ResourceCleanup.close(newStagingArena, exception);
            ResourceCleanup.close(newContext, exception);
            throw exception;
        }
    }

    public boolean beginFrame(Minecraft minecraft, SessionControls controls) {
        this.frameControls = java.util.Objects.requireNonNull(controls, "controls");
        boolean screenshotRequested = controls.screenshotRequested();
        if (this.screenshotRequestRejected) {
            screenshotRequested = false;
            this.screenshotRequestRejected = false;
        }
        this.reloadPipelineIfRequested();
        this.synchronizeLabPbr(minecraft);
        this.terrain.setVoxelTextureSurfaces(
                PrimeConfig.settings().voxelTextureSurfaces(),
                PrimeConfig.settings().voxelTextureSurfaceStrengthSteps());
        screenshotRequested = this.updateScreenshotSession(minecraft, screenshotRequested);
        if (this.screenshotActive()) {
            return screenshotRequested;
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
        return screenshotRequested;
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
        long sourceGeneration = this.labPbrAtlas.sourceGeneration();
        BlockAtlasFrame previous = this.blockAtlasFrame;
        boolean changed = previous == null
                || previous.view().vkImageView() != atlasView.vkImageView()
                || previous.sampler().vkSampler() != atlasSampler.vkSampler()
                || previous.sourceGeneration() != sourceGeneration;
        if (changed) {
            this.blockAtlasTextureRevision =
                    Math.incrementExact(this.blockAtlasTextureRevision);
        }
        this.blockAtlasFrame = new BlockAtlasFrame(
                atlasView,
                atlasSampler,
                sourceGeneration,
                this.blockAtlasTextureRevision);
    }

    public void captureCamera(
            Matrix4fc renderedProjection,
            Matrix4fc baseProjection,
            Matrix4fc viewRotation,
            double x,
            double y,
            double z,
            float sunAngleRadians) {
        if (this.screenshotActive()) {
            this.updateScreenshotProjection(baseProjection);
            return;
        }
        this.camera = FrameCamera.tryCreate(
                renderedProjection, baseProjection, viewRotation, x, y, z);
        this.sunDirection = SunDirection.fromVanillaAngle(sunAngleRadians);
    }

    public boolean isReady() {
        return this.terrain.isNearCameraReady() && this.terrain.residentScene() != null;
    }

    public boolean screenshotActive() {
        return this.screenshotWorld != null;
    }

    public List<String> debugLines() {
        return this.debugLines;
    }

    public void render(RenderTarget mainTarget) {
        if (this.screenshotActive()) {
            this.renderScreenshot(mainTarget);
        } else {
            this.renderRealtime(mainTarget);
        }
    }

    /**
     * Records Prime's interactive frame graph: one estimator sample, the selected reconstruction
     * backend, and the common display transform. Keeping this orchestration behind a named boundary
     * prevents screenshot accumulation from inheriting temporal resources by accident.
     */
    private void renderRealtime(RenderTarget mainTarget) {
        TerrainScene.ResidentSceneView scene = this.terrain.residentScene();
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
        PrimeSettings settings = PrimeConfig.settings();
        ReconstructionQualityMode requestedQualityMode = settings.reconstructionQuality();
        PostProcessingMode effectiveMode = settings.postProcessingMode();
        int renderWidth;
        int renderHeight;
        if (effectiveMode == PostProcessingMode.DLSS_RR
                && this.ngxContext != null
                && DlssRrBootstrap.deviceReady()) {
            try {
                DlssRrNative.OptimalSettings optimal =
                        this.optimalDlssRrSettings(width, height, requestedQualityMode);
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
        } else if (effectiveMode == PostProcessingMode.DISABLED) {
            // The diagnostic raw path is intentionally a native-resolution 1 spp presentation;
            // reconstruction quality must not quietly turn it into an upscaled image.
            renderWidth = width;
            renderHeight = height;
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
        this.requireRayDispatchCapacity(renderWidth, renderHeight);

        BlockAtlasFrame blockAtlas = this.blockAtlasFrame;
        if (blockAtlas == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        VulkanGpuTextureView atlasView = blockAtlas.view;
        VulkanGpuSampler atlasSampler = blockAtlas.sampler;
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
            this.requireRayDispatchCapacity(renderWidth, renderHeight);
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
        VulkanImage history = images.stableRadiance;
        RealtimePostProcessor processor = images.processor;
        LightingSettings.Snapshot lighting = settings.lighting();
        MaterialSettings.Snapshot material = settings.material();
        boolean frameCameraInWater = this.isCameraInWater(minecraft, frameCamera);
        RealtimeFrameInput frameInput = new RealtimeFrameInput(
                frameCamera,
                System.nanoTime(),
                scene.temporalRevision(),
                scene.revision(),
                blockAtlas.textureRevision(),
                renderWidth,
                renderHeight,
                width,
                height,
                frameSunDirection,
                frameCameraInWater,
                images.mode,
                images.qualityMode,
                lighting,
                material,
                processor.rawFrame().usesShInputs(),
                this.frameControls.triangleDebug(),
                settings.display(),
                this.frameControls.nrdDebugView(),
                this.frameControls.fsrDebugView(),
                this.frameControls.rrDebugView(),
                this.frameControls.rrDebugFullscreen(),
                resized);
        frameInput.requireCompatible(processor);
        RealtimeSampleState.Plan sampleFrame =
                this.realtimeSampleState.plan(frameInput.sampleStateInput());
        RealtimePostProcessor.FrameParameters postParameters =
                frameInput.reconstructionInput(sampleFrame.reset());
        RealtimePostProcessor.Frame postFrame = processor.beginFrame(postParameters);
        RealtimeFramePlan framePlan;
        try {
            framePlan =
                    RealtimeFramePlan.complete(
                            frameInput,
                            sampleFrame,
                            postParameters,
                            postFrame);
            if (images.mode == PostProcessingMode.DLSS_RR) {
                this.debugLines = DlssRrDebugStatus.lines(
                        images.qualityMode,
                        renderWidth,
                        renderHeight,
                        width,
                        height,
                        true,
                        postFrame.reset(),
                        this.frameControls.rrDebugView(),
                        this.frameControls.rrDebugFullscreen());
            } else {
                this.debugLines = List.of();
            }
        } catch (RuntimeException exception) {
            throw ResourceCleanup.run(
                    () -> processor.abandon(postFrame), exception);
        }
        this.realtimeExecutor.execute(
                switch (images.mode) {
                    case DLSS_RR ->
                            "Prime 1spp path tracing and DLSS Ray Reconstruction";
                    case NRD_FSR ->
                            "Prime 1spp path tracing, NRD, and FidelityFX FSR 3.1.4";
                    case DISABLED ->
                            "Prime native 1spp path tracing without post-processing";
                },
                this.pipeline,
                this.atmosphere,
                this.labPbrAtlas,
                scene,
                framePlan,
                processor,
                postFrame,
                target,
                history,
                atlasView,
                blockAtlas.textureRevision(),
                mainColor);
        this.realtimeSampleState = sampleFrame.committedState();
        this.replayProbeController.run(
                new ReplayProbeController.RunInput(
                        this.pipeline,
                        this.atmosphere,
                        scene,
                        frameCamera,
                        frameSunDirection,
                        frameCameraInWater,
                        lighting,
                        material,
                        settings.oklabOverexposure(),
                        atlasView,
                        atlasSampler,
                        blockAtlas.textureRevision(),
                        images.stableRadiance,
                        this.labPbrAtlas.normalAtlas(),
                        this.labPbrAtlas.specularAtlas(),
                        images.processor.rawFrame()));
        int accumulatedSampleCount = this.realtimeSampleState.sampleIndex();
        if (accumulatedSampleCount >= 16
                && (accumulatedSampleCount & (accumulatedSampleCount - 1)) == 0) {
            PrimeClient.LOGGER.debug(
                    "Prime accumulation reached {} samples for scene revision {}",
                    accumulatedSampleCount,
                    scene.revision());
        }
    }

    /** Records one raw native-resolution model sample and presents the running mean directly. */
    private void renderScreenshot(RenderTarget mainTarget) {
        this.debugLines = List.of();
        TerrainScene.ResidentSceneView scene = this.screenshotScene;
        FrameCamera frameCamera = this.screenshotCamera;
        SunDirection frameSunDirection = this.screenshotSunDirection;
        LightingSettings.Snapshot lighting = this.screenshotLighting;
        MaterialSettings.Snapshot material = this.screenshotMaterial;
        if (scene == null
                || frameCamera == null
                || frameSunDirection == null
                || lighting == null
                || material == null) {
            this.cancelScreenshotSession();
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
        this.requireRayDispatchCapacity(width, height);

        BlockAtlasFrame blockAtlas = this.blockAtlasFrame;
        if (blockAtlas == null) {
            return;
        }
        VulkanGpuTextureView atlasView = blockAtlas.view;
        VulkanGpuSampler atlasSampler = blockAtlas.sampler;
        long atlasViewHandle = atlasView.vkImageView();
        long atlasSamplerHandle = atlasSampler.vkSampler();
        if (this.screenshotAtlasView != atlasViewHandle
                || this.screenshotAtlasSampler != atlasSamplerHandle
                || this.screenshotTextureRevision
                        != blockAtlas.textureRevision()) {
            // A resource-pack reload replaces the frozen material snapshot. Continuing would mix
            // two different texture sets in one statistical mean, so return to realtime and let
            // the ordinary reload/resynchronization path establish a new coherent scene.
            this.cancelScreenshotSession();
            this.renderRealtime(mainTarget);
            return;
        }

        this.ensureScreenshotResources(width, height);
        ScreenshotRenderResources images = this.screenshotResources;
        if (images == null) {
            return;
        }
        this.pipeline.ensureDescriptors(
                scene.tlas(),
                images.stableRadiance,
                images.runningMean,
                atlasView,
                atlasSampler,
                this.labPbrAtlas.normalAtlas(),
                this.labPbrAtlas.specularAtlas(),
                this.atmosphere,
                images.rawFrame);
        ScreenshotFramePlan framePlan = new ScreenshotFrameInput(
                frameCamera,
                width,
                height,
                scene.revision(),
                this.screenshotTextureRevision,
                frameSunDirection,
                this.screenshotCameraInWater,
                lighting,
                material,
                this.screenshotSampleCount,
                PrimeConfig.settings().display()).plan();
        this.screenshotExecutor.execute(
                this.pipeline,
                this.atmosphere,
                this.labPbrAtlas,
                scene,
                framePlan,
                images.displayOutput,
                images.stableRadiance,
                images.runningMean,
                images.rawFrame,
                images.display,
                atlasView,
                this.screenshotTextureRevision,
                mainColor);
        this.screenshotSampleCount = framePlan.nextSampleCount();
        if (this.screenshotSampleCount > 0L
                && (this.screenshotSampleCount & (this.screenshotSampleCount - 1L)) == 0L) {
            PrimeClient.LOGGER.info(
                    "Prime screenshot accumulation reached {} samples",
                    this.screenshotSampleCount);
        }
    }

    private boolean updateScreenshotSession(Minecraft minecraft, boolean requested) {
        boolean worldChanged = this.screenshotActive()
                && (minecraft.level == null || minecraft.level != this.screenshotWorld);
        if (worldChanged) {
            requested = false;
        }
        if (this.screenshotActive() && (!requested || worldChanged)) {
            this.stopScreenshotSession();
        }
        if (!this.screenshotActive()
                && requested
                && minecraft.level != null
                && this.camera != null
                && this.sunDirection != null
                && this.terrain.residentScene() != null
                && this.realtimeResources != null
                && this.blockAtlasFrame != null) {
            this.screenshotWorld = minecraft.level;
            this.screenshotScene = this.terrain.residentScene();
            this.screenshotCamera = this.camera;
            this.screenshotSunDirection = this.sunDirection;
            PrimeSettings settings = PrimeConfig.settings();
            this.screenshotLighting = settings.lighting();
            this.screenshotMaterial = settings.material();
            this.screenshotCameraInWater = this.isCameraInWater(minecraft, this.camera);
            BlockAtlasFrame atlas = this.blockAtlasFrame;
            this.screenshotAtlasView = atlas.view().vkImageView();
            this.screenshotAtlasSampler = atlas.sampler().vkSampler();
            this.screenshotTextureRevision = atlas.textureRevision();
            this.screenshotSampleCount = 0L;
            PrimeClient.LOGGER.info(
                    "Entered Prime screenshot mode at scene revision {}",
                    this.screenshotScene.revision());
        }
        return requested;
    }

    private void stopScreenshotSession() {
        if (!this.screenshotActive()) {
            return;
        }
        this.screenshotWorld = null;
        this.screenshotScene = null;
        this.screenshotCamera = null;
        this.screenshotSunDirection = null;
        this.screenshotLighting = null;
        this.screenshotMaterial = null;
        this.screenshotAtlasView = 0L;
        this.screenshotAtlasSampler = 0L;
        this.screenshotTextureRevision = Long.MIN_VALUE;
        this.screenshotSampleCount = 0L;
        ScreenshotRenderResources retiredResources = this.screenshotResources;
        this.screenshotResources = null;
        // Dirty notifications continue to accumulate while uploads are paused. A full resync on
        // exit also covers animation-driven or external changes that do not expose a precise
        // block range, without invalidating the frozen screenshot while it is converging.
        this.terrain.invalidateAll();
        this.realtimeSampleState = this.realtimeSampleState.invalidated();
        PrimeClient.LOGGER.info("Left Prime screenshot mode; scheduled a full terrain resync");
        if (retiredResources != null) {
            this.context.defer(retiredResources);
        }
    }

    private void cancelScreenshotSession() {
        this.screenshotRequestRejected = true;
        this.stopScreenshotSession();
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
        ScreenshotRenderResources retiredResources = this.screenshotResources;
        this.screenshotResources = null;
        PrimeClient.LOGGER.info("Restarted Prime screenshot accumulation for a new aspect ratio");
        if (retiredResources != null) {
            this.context.defer(retiredResources);
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
        if (this.realtimeResources != null) {
            this.realtimeResources.requestReset();
        }
    }

    public void requestShaderReload() {
        this.shaderReloadRequested = true;
    }

    public void requestResourceReload() {
        this.labPbrAtlas.requestReload();
    }

    /**
     * Executes two deterministic low-resolution restart/motion sequences from one rendered-world
     * snapshot and compares their production outputs.
     */
    public CompletableFuture<RenderReplayVerification> verifyReplayProbe(
            int width, int height) {
        return this.replayProbeController.request(width, height);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        // A failed wait leaves every GPU child live and permits a later close attempt. After a
        // successful wait, every child is attempted once and failures are aggregated.
        this.context.awaitIdle();
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(
                this.replayProbeController, failure);
        failure = ResourceCleanup.run(this.context::drainDeferredAfterIdle, failure);
        failure = ResourceCleanup.destroy(this.pipeline, failure);
        if (this.realtimeResources != null) {
            failure = ResourceCleanup.destroy(this.realtimeResources, failure);
            this.realtimeResources = null;
        }
        if (this.screenshotResources != null) {
            failure = ResourceCleanup.destroy(this.screenshotResources, failure);
            this.screenshotResources = null;
        }
        failure = ResourceCleanup.close(this.terrain, failure);
        failure = ResourceCleanup.close(this.labPbrAtlas, failure);
        failure = ResourceCleanup.destroy(this.atmosphere, failure);
        failure = ResourceCleanup.destroy(this.starmap, failure);
        failure = ResourceCleanup.close(this.stagingArena, failure);
        if (this.ngxContext != null) {
            failure = ResourceCleanup.run(this::releaseNgxContext, failure);
        }
        failure = ResourceCleanup.close(this.context, failure);
        this.debugLines = List.of();
        this.closed = true;
        ResourceCleanup.throwIfFailed(failure);
    }

    private void releaseNgxContext() {
        DlssRrNative.Context context = this.ngxContext;
        if (context == null) {
            return;
        }
        try {
            DlssRrBootstrap.release(context);
        } finally {
            // Context.close consumes the native handle before reporting shutdown failure. Either
            // outcome makes this Java owner terminal and prevents a retry from double-releasing.
            this.ngxContext = null;
        }
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
            replacementPipeline = new RayTracingPipeline(this.context, this.starmap);
            RealtimeRenderResources current = this.realtimeResources;
            if (current != null) {
                replacementResources = RealtimeRenderResources.create(
                        this.context,
                        replacementAtmosphere,
                        current.output.width(),
                        current.output.height(),
                        current.stableRadiance.width(),
                        current.stableRadiance.height(),
                        current.mode,
                        current.qualityMode,
                        this.ngxContext);
            }
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(replacementResources, exception);
            ResourceCleanup.destroy(replacementPipeline, exception);
            ResourceCleanup.destroy(replacementAtmosphere, exception);
            PrimeClient.LOGGER.error("Prime shader reload failed; keeping the previous pipeline", exception);
            return;
        }
        RayTracingPipeline previousPipeline = this.pipeline;
        AtmospherePipeline previousAtmosphere = this.atmosphere;
        RealtimeRenderResources previousResources = this.realtimeResources;
        this.pipeline = replacementPipeline;
        this.atmosphere = replacementAtmosphere;
        this.realtimeResources = replacementResources;
        this.realtimeSampleState = this.realtimeSampleState.invalidated();
        RuntimeException retirementFailure = ResourceCleanup.run(
                () -> this.context.defer(previousPipeline), null);
        if (previousResources != null) {
            retirementFailure = ResourceCleanup.run(
                    () -> this.context.defer(previousResources), retirementFailure);
        }
        retirementFailure = ResourceCleanup.run(
                () -> this.context.defer(previousAtmosphere), retirementFailure);
        ResourceCleanup.throwIfFailed(retirementFailure);
        PrimeClient.LOGGER.info("Reloaded Prime ray tracing and atmosphere shaders");
    }

    private DlssRrNative.OptimalSettings optimalDlssRrSettings(
            int displayWidth,
            int displayHeight,
            ReconstructionQualityMode qualityMode) {
        DlssRrNative.OptimalSettings cached = this.rrOptimalSettings;
        if (cached != null
                && this.rrOptimalDisplayWidth == displayWidth
                && this.rrOptimalDisplayHeight == displayHeight
                && this.rrOptimalQualityMode == qualityMode) {
            return cached;
        }
        DlssRrNative.OptimalSettings optimal =
                this.ngxContext.optimalSettings(displayWidth, displayHeight, qualityMode);
        this.rrOptimalDisplayWidth = displayWidth;
        this.rrOptimalDisplayHeight = displayHeight;
        this.rrOptimalQualityMode = qualityMode;
        this.rrOptimalSettings = optimal;
        return optimal;
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
                    current.stableRadiance,
                    current.stableRadiance,
                    atlasView,
                    atlasSampler,
                    this.labPbrAtlas.normalAtlas(),
                    this.labPbrAtlas.specularAtlas(),
                    this.atmosphere,
                    current.processor.rawFrame());
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
                    replacement.stableRadiance,
                    replacement.stableRadiance,
                    atlasView,
                    atlasSampler,
                    this.labPbrAtlas.normalAtlas(),
                    this.labPbrAtlas.specularAtlas(),
                    this.atmosphere,
                    replacement.processor.rawFrame());
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(replacement, exception);
            throw exception;
        }
        this.realtimeResources = replacement;
        // Publication is complete before fallible retirement. If retirement reports an error,
        // the next attempt must still reset histories rather than treating this replacement as an
        // ordinary matching frame.
        this.realtimeSampleState = this.realtimeSampleState.invalidated();
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
                hex(replacement.stableRadiance.image()),
                hex(replacement.stableRadiance.view()),
                hex(atlasView.texture().vkImage()),
                hex(atlasView.vkImageView()),
                hex(atlasSampler.vkSampler()));
        return true;
    }

    private void ensureScreenshotResources(int width, int height) {
        ScreenshotRenderResources current = this.screenshotResources;
        if (current != null && current.matches(width, height)) {
            return;
        }
        ScreenshotRenderResources replacement =
                ScreenshotRenderResources.create(this.context, width, height);
        this.screenshotResources = replacement;
        this.screenshotSampleCount = 0L;
        if (current != null) {
            this.context.defer(current);
        }
        PrimeClient.LOGGER.info(
                "Created Prime screenshot resources at native {}x{}", width, height);
    }

    private void requireRayDispatchCapacity(int width, int height) {
        long invocationCount = (long) width * height;
        if (invocationCount
                > Integer.toUnsignedLong(
                        this.context.capabilities().maxRayDispatchInvocationCount())) {
            throw new IllegalStateException(
                    "Render dimensions exceed the Vulkan ray dispatch limit");
        }
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

    private static String hex(long handle) {
        return "0x" + Long.toUnsignedString(handle, 16);
    }

    /** One block-atlas snapshot is resolved and synchronized at the frame boundary. */
    private record BlockAtlasFrame(
            VulkanGpuTextureView view,
            VulkanGpuSampler sampler,
            long sourceGeneration,
            long textureRevision) {
    }

}
