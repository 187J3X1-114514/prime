package dev.prime.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.PrimeClient;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.RealtimePostProcessor;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.DlssRrDebugStatus;
import dev.prime.render.terrain.TerrainScene;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.LabPbrTextureAtlas;
import dev.prime.render.vulkan.RealtimeFrameExecutor;
import dev.prime.render.vulkan.RealtimeRayTracingPipeline;
import dev.prime.render.vulkan.SunShadowPipeline;
import dev.prime.render.vulkan.TraceBackend;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.dlss.DlssRrNative;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import dev.prime.render.vulkan.replay.ReplayProbeController;
import java.util.List;
import java.util.Objects;

/** Owns the complete interactive pipeline, scheduler state, replay, and sized resources. */
final class RealtimeRenderer implements Destroyable {
    private final VulkanContext context;
    private final TraceBackend backend;
    private final RealtimeFrameExecutor executor;
    private final ReplayProbeController replay;
    private final DlssRrNative.Context ngxContext;
    private RealtimeRayTracingPipeline pipeline;
    private RealtimeRenderResources resources;
    private RealtimeSampleState sampleState = RealtimeSampleState.initial();
    private DlssRrNative.OptimalSettings optimalSettings;
    private ReconstructionQualityMode optimalQuality;
    private int optimalDisplayWidth;
    private int optimalDisplayHeight;
    private boolean rrFallbackReported;
    private boolean pipelineInvalid;
    private boolean destroyed;

    RealtimeRenderer(
            VulkanContext context,
            TraceBackend backend,
            DlssRrNative.Context ngxContext) {
        this.context = Objects.requireNonNull(context, "context");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.ngxContext = ngxContext;
        this.pipeline = new RealtimeRayTracingPipeline(context, backend);
        this.executor = new RealtimeFrameExecutor(context);
        this.replay = new ReplayProbeController(context);
    }

    RealtimeRayTracingPipeline pipeline() {
        this.ensurePipeline();
        return this.pipeline;
    }

    RealtimeFrameExecutor executor() {
        return this.executor;
    }

    ReplayProbeController replay() {
        return this.replay;
    }

    RealtimeRenderResources resources() {
        return this.resources;
    }

    DlssRrNative.Context ngxContext() {
        return this.ngxContext;
    }

    boolean hasSizedResources() {
        return this.resources != null;
    }

    RealtimeSampleState.Plan planSample(RealtimeSampleState.Input input) {
        return this.sampleState.plan(input);
    }

    void commitSample(RealtimeSampleState.Plan plan) {
        this.sampleState = plan.committedState();
    }

    int sampleIndex() {
        return this.sampleState.sampleIndex();
    }

    void invalidateHistory() {
        this.sampleState = this.sampleState.invalidated();
        if (this.resources != null) {
            this.resources.requestReset();
        }
    }

    DlssRrNative.OptimalSettings optimalSettings(
            int displayWidth,
            int displayHeight,
            ReconstructionQualityMode quality) {
        if (this.ngxContext == null) {
            throw new IllegalStateException("DLSS RR is unavailable");
        }
        if (this.optimalSettings != null
                && this.optimalDisplayWidth == displayWidth
                && this.optimalDisplayHeight == displayHeight
                && this.optimalQuality == quality) {
            return this.optimalSettings;
        }
        DlssRrNative.OptimalSettings replacement =
                this.ngxContext.optimalSettings(displayWidth, displayHeight, quality);
        this.optimalDisplayWidth = displayWidth;
        this.optimalDisplayHeight = displayHeight;
        this.optimalQuality = quality;
        this.optimalSettings = replacement;
        return replacement;
    }

    boolean ensureResources(
            AtmospherePipeline atmosphere,
            LabPbrTextureAtlas labPbrAtlas,
            int displayWidth,
            int displayHeight,
            int renderWidth,
            int renderHeight,
            PostProcessingMode mode,
            ReconstructionQualityMode quality,
            long tlas,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures) {
        this.ensurePipeline();
        RealtimeRenderResources current = this.resources;
        if (current != null && current.matches(
                displayWidth, displayHeight, renderWidth, renderHeight, mode, quality)) {
            this.pipeline.ensureDescriptors(
                    tlas,
                    current.stableRadiance,
                    atlasView,
                    atlasSampler,
                    sceneTextures,
                    labPbrAtlas.normalAtlas(),
                    labPbrAtlas.specularAtlas(),
                    atmosphere,
                    current.processor.rawFrame());
            return false;
        }
        RealtimeRenderResources replacement = RealtimeRenderResources.create(
                this.context,
                atmosphere,
                displayWidth,
                displayHeight,
                renderWidth,
                renderHeight,
                mode,
                quality,
                this.ngxContext);
        try {
            this.pipeline.ensureDescriptors(
                    tlas,
                    replacement.stableRadiance,
                    atlasView,
                    atlasSampler,
                    sceneTextures,
                    labPbrAtlas.normalAtlas(),
                    labPbrAtlas.specularAtlas(),
                    atmosphere,
                    replacement.processor.rawFrame());
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(replacement, exception);
            throw exception;
        }
        this.resources = replacement;
        this.sampleState = this.sampleState.invalidated();
        if (current != null) {
            this.context.defer(current);
        }
        return true;
    }

    List<String> render(RenderInput input) {
        Objects.requireNonNull(input, "input");
        if (!(input.mainTarget().getColorTexture() instanceof VulkanGpuTexture mainColor)) {
            throw new IllegalStateException("Prime expected a Vulkan main color texture");
        }
        if (mainColor.getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalStateException("Prime requires an RGBA8_UNORM main target");
        }
        int width = mainColor.getWidth(0);
        int height = mainColor.getHeight(0);
        if (width <= 0
                || height <= 0
                || input.mainTarget().width != width
                || input.mainTarget().height != height) {
            return List.of();
        }

        RealtimeRenderSettings settings = input.settings();
        ReconstructionQualityMode requestedQuality = settings.reconstructionQuality();
        PostProcessingMode effectiveMode = settings.postProcessing();
        int renderWidth;
        int renderHeight;
        if (effectiveMode == PostProcessingMode.DLSS_RR
                && this.ngxContext != null
                && DlssRrBootstrap.deviceReady()) {
            try {
                DlssRrNative.OptimalSettings optimal =
                        this.optimalSettings(width, height, requestedQuality);
                renderWidth = optimal.renderWidth();
                renderHeight = optimal.renderHeight();
                this.rrFallbackReported = false;
            } catch (RuntimeException exception) {
                DlssRrBootstrap.failSession(
                        "DLSS RR optimal-size query failed; using NRD-FSR", exception);
                effectiveMode = PostProcessingMode.NRD_FSR;
                renderWidth = requestedQuality.renderWidth(width);
                renderHeight = requestedQuality.renderHeight(height);
            }
        } else if (effectiveMode == PostProcessingMode.DISABLED) {
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
            renderWidth = requestedQuality.renderWidth(width);
            renderHeight = requestedQuality.renderHeight(height);
        }
        this.requireRayDispatchCapacity(renderWidth, renderHeight);

        boolean resized;
        try {
            resized = this.ensureResources(
                    input.atmosphere(),
                    input.labPbrAtlas(),
                    width,
                    height,
                    renderWidth,
                    renderHeight,
                    effectiveMode,
                    requestedQuality,
                    input.scene().tlas(),
                    input.atlasView(),
                    input.atlasSampler(),
                    input.sceneTextures());
        } catch (RuntimeException exception) {
            if (effectiveMode != PostProcessingMode.DLSS_RR) {
                throw exception;
            }
            DlssRrBootstrap.failSession(
                    "DLSS RR feature creation failed; using NRD-FSR", exception);
            effectiveMode = PostProcessingMode.NRD_FSR;
            renderWidth = requestedQuality.renderWidth(width);
            renderHeight = requestedQuality.renderHeight(height);
            this.requireRayDispatchCapacity(renderWidth, renderHeight);
            resized = this.ensureResources(
                    input.atmosphere(),
                    input.labPbrAtlas(),
                    width,
                    height,
                    renderWidth,
                    renderHeight,
                    effectiveMode,
                    requestedQuality,
                    input.scene().tlas(),
                    input.atlasView(),
                    input.atlasSampler(),
                    input.sceneTextures());
        }
        RealtimeRenderResources images = this.resources;
        if (images == null) {
            return List.of();
        }
        if (resized) {
            PrimeClient.LOGGER.debug(
                    "Recreated Prime realtime images at display {}x{}, render {}x{}, {} {} "
                            + "(output image={}, view={}; accumulation image={}, view={}; "
                            + "atlas image={}, view={}, sampler={})",
                    width,
                    height,
                    renderWidth,
                    renderHeight,
                    effectiveMode.id(),
                    requestedQuality.id(),
                    hex(images.output.image()),
                    hex(images.output.view()),
                    hex(images.stableRadiance.image()),
                    hex(images.stableRadiance.view()),
                    hex(input.atlasView().texture().vkImage()),
                    hex(input.atlasView().vkImageView()),
                    hex(input.atlasSampler().vkSampler()));
        }

        VulkanImage target = images.output;
        VulkanImage history = images.stableRadiance;
        RealtimePostProcessor processor = images.processor;
        RealtimeFrameInput frameInput = new RealtimeFrameInput(
                input.camera(),
                System.nanoTime(),
                input.scene().temporalRevision(),
                input.scene().revision(),
                input.textureRevision(),
                renderWidth,
                renderHeight,
                width,
                height,
                input.astronomy(),
                input.cameraInWater(),
                images.mode,
                images.qualityMode,
                settings.lighting(),
                settings.material(),
                processor.rawFrame().usesShInputs(),
                input.controls().triangleDebug(),
                input.controls().wavefrontDebugMode(),
                settings.display(),
                input.controls().nrdDebugView(),
                input.controls().fsrDebugView(),
                input.controls().rrDebugView(),
                input.controls().rrDebugFullscreen(),
                resized);
        frameInput.requireCompatible(processor);
        RealtimeSampleState.Plan sampleFrame = this.planSample(frameInput.sampleStateInput());
        RealtimePostProcessor.FrameParameters postParameters =
                frameInput.reconstructionInput(sampleFrame.reset());
        RealtimePostProcessor.Frame postFrame = processor.beginFrame(postParameters);
        RealtimeFramePlan framePlan;
        List<String> debugLines;
        try {
            framePlan = RealtimeFramePlan.complete(
                    frameInput, sampleFrame, postParameters, postFrame);
            debugLines = images.mode == PostProcessingMode.DLSS_RR
                    ? DlssRrDebugStatus.lines(
                            images.qualityMode,
                            renderWidth,
                            renderHeight,
                            width,
                            height,
                            true,
                            postFrame.reset(),
                            input.controls().rrDebugView(),
                            input.controls().rrDebugFullscreen())
                    : List.of();
        } catch (RuntimeException exception) {
            throw ResourceCleanup.run(() -> processor.abandon(postFrame), exception);
        }
        this.executor.execute(
                switch (images.mode) {
                    case DLSS_RR ->
                            "Prime 1spp path tracing and DLSS Ray Reconstruction";
                    case NRD_FSR ->
                            "Prime 1spp path tracing, NRD, and FidelityFX FSR 3.1.4";
                    case DISABLED ->
                            "Prime native 1spp path tracing without post-processing";
                },
                this.pipeline(),
                input.sunShadow(),
                input.atmosphere(),
                input.labPbrAtlas(),
                input.scene(),
                framePlan,
                processor,
                postFrame,
                target,
                history,
                input.atlasView(),
                input.sceneTextures(),
                input.textureRevision(),
                mainColor);
        this.commitSample(sampleFrame);
        this.replay.run(new ReplayProbeController.RunInput(
                this.pipeline(),
                input.atmosphere(),
                input.scene(),
                input.camera(),
                input.astronomy(),
                input.cameraInWater(),
                settings.lighting(),
                settings.material(),
                settings.oklabOverexposure(),
                input.atlasView(),
                input.atlasSampler(),
                input.sceneTextures(),
                input.textureRevision(),
                images.stableRadiance,
                input.labPbrAtlas().normalAtlas(),
                input.labPbrAtlas().specularAtlas(),
                images.processor.rawFrame()));
        int accumulatedSamples = this.sampleIndex();
        if (accumulatedSamples >= 16
                && (accumulatedSamples & (accumulatedSamples - 1)) == 0) {
            PrimeClient.LOGGER.debug(
                    "Prime accumulation reached {} samples for scene revision {}",
                    accumulatedSamples,
                    input.scene().revision());
        }
        return debugLines;
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

    private static String hex(long handle) {
        return "0x" + Long.toUnsignedString(handle, 16);
    }

    record RenderInput(
            RenderTarget mainTarget,
            TerrainScene.ResidentSceneView scene,
            FrameCamera camera,
            AstronomyState astronomy,
            RealtimeRenderSettings settings,
            SessionControls controls,
            boolean cameraInWater,
            AtmospherePipeline atmosphere,
            SunShadowPipeline sunShadow,
            LabPbrTextureAtlas labPbrAtlas,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            long textureRevision,
            List<TraceBackend.SceneTexture> sceneTextures) {
        RenderInput {
            Objects.requireNonNull(mainTarget, "mainTarget");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(astronomy, "astronomy");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(controls, "controls");
            Objects.requireNonNull(atmosphere, "atmosphere");
            Objects.requireNonNull(sunShadow, "sunShadow");
            Objects.requireNonNull(labPbrAtlas, "labPbrAtlas");
            Objects.requireNonNull(atlasView, "atlasView");
            Objects.requireNonNull(atlasSampler, "atlasSampler");
            sceneTextures = List.copyOf(sceneTextures);
        }
    }

    void releaseSizedResourcesAfterIdle() {
        RealtimeRenderResources current = this.resources;
        this.resources = null;
        if (current != null) {
            current.destroy();
        }
        this.pipeline.releaseSizedResourcesAfterIdle();
        this.sampleState = this.sampleState.invalidated();
    }

    void reloadActive(AtmospherePipeline atmosphere) {
        RealtimeRayTracingPipeline replacementPipeline = null;
        RealtimeRenderResources replacementResources = null;
        try {
            replacementPipeline = new RealtimeRayTracingPipeline(this.context, this.backend);
            RealtimeRenderResources current = this.resources;
            if (current != null) {
                replacementResources = RealtimeRenderResources.create(
                        this.context,
                        atmosphere,
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
            throw exception;
        }
        RealtimeRayTracingPipeline previousPipeline = this.pipeline;
        RealtimeRenderResources previousResources = this.resources;
        this.pipeline = replacementPipeline;
        this.resources = replacementResources;
        this.pipelineInvalid = false;
        this.sampleState = this.sampleState.invalidated();
        this.context.defer(previousPipeline);
        if (previousResources != null) {
            this.context.defer(previousResources);
        }
    }

    void invalidatePipeline() {
        this.pipelineInvalid = true;
    }

    private void ensurePipeline() {
        if (!this.pipelineInvalid) {
            return;
        }
        RealtimeRayTracingPipeline replacement =
                new RealtimeRayTracingPipeline(this.context, this.backend);
        RealtimeRayTracingPipeline previous = this.pipeline;
        this.pipeline = replacement;
        this.pipelineInvalid = false;
        this.context.defer(previous);
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.replay, failure);
        failure = ResourceCleanup.destroy(this.resources, failure);
        failure = ResourceCleanup.destroy(this.pipeline, failure);
        if (this.ngxContext != null) {
            failure = ResourceCleanup.run(
                    () -> DlssRrBootstrap.release(this.ngxContext), failure);
        }
        this.resources = null;
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }
}
