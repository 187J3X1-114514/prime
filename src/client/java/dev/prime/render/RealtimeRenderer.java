package dev.prime.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionFrame;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainScene;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.DisplayExposureDiagnostics;
import dev.prime.render.vulkan.LabPbrTextureAtlas;
import dev.prime.render.vulkan.RealtimeFrameExecutor;
import dev.prime.render.vulkan.RealtimeRayTracingPipeline;
import dev.prime.render.vulkan.SunShadowPipeline;
import dev.prime.render.vulkan.TraceBackend;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.dlss.DlssRrNative;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import dev.prime.render.vulkan.reconstruction.ReconstructionBackendRegistry;
import dev.prime.render.vulkan.reconstruction.ReconstructionDebugSettings;
import dev.prime.render.vulkan.reconstruction.ResolvedReconstruction;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionProcessor;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionResources;
import dev.prime.render.vulkan.replay.ReplayProbeController;
import java.util.List;
import java.util.Objects;

/** Owns the complete interactive pipeline, scheduler state, replay, and sized resources. */
final class RealtimeRenderer implements Destroyable {
    private final VulkanContext context;
    private final TraceBackend backend;
    private final RealtimeFrameExecutor executor;
    private final ReplayProbeController replay;
    private final DisplayExposureDiagnostics exposureDiagnostics;
    private final DlssRrNative.Context ngxContext;
    private final ReconstructionBackendRegistry reconstructionRegistry;
    private RealtimeRayTracingPipeline pipeline;
    private VulkanReconstructionResources resources;
    private RealtimeSampleState sampleState = RealtimeSampleState.initial();
    private boolean pipelineInvalid;
    private boolean destroyed;

    RealtimeRenderer(
            VulkanContext context,
            TraceBackend backend,
            DlssRrNative.Context ngxContext) {
        this.context = Objects.requireNonNull(context, "context");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.ngxContext = ngxContext;
        this.reconstructionRegistry = new ReconstructionBackendRegistry(context, ngxContext);
        this.pipeline = new RealtimeRayTracingPipeline(context, backend);
        this.executor = new RealtimeFrameExecutor(context);
        this.replay = new ReplayProbeController(context);
        this.exposureDiagnostics = new DisplayExposureDiagnostics(context);
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

    VulkanReconstructionResources resources() {
        return this.resources;
    }

    DlssRrNative.Context ngxContext() {
        return this.ngxContext;
    }

    boolean hasSizedResources() {
        return this.resources != null;
    }

    long displayExposureStateBuffer() {
        VulkanReconstructionResources current = this.resources;
        if (current == null) {
            throw new IllegalStateException("Realtime exposure requires sized resources");
        }
        return current.processor().displayExposureStateBuffer();
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

    DiagnosticSnapshot diagnosticSnapshot() {
        VulkanReconstructionResources current = this.resources;
        if (current == null) {
            return null;
        }
        return new DiagnosticSnapshot(
                current.selection().effectiveMode(),
                current.selection().quality(),
                current.stableRadiance().width(),
                current.stableRadiance().height(),
                current.output().width(),
                current.output().height(),
                this.sampleIndex(),
                this.exposureDiagnostics.latest());
    }

    DisplayExposureDiagnostics.Snapshot exposureDiagnosticSnapshot() {
        return this.exposureDiagnostics.latest();
    }

    boolean ensureResources(
            AtmospherePipeline atmosphere,
            LabPbrTextureAtlas labPbrAtlas,
            ResolvedReconstruction selection,
            long tlas,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures) {
        this.ensurePipeline();
        VulkanReconstructionResources current = this.resources;
        if (current != null && current.matches(selection)) {
            this.pipeline.ensureDescriptors(
                    tlas,
                    current.stableRadiance(),
                    atlasView,
                    atlasSampler,
                    sceneTextures,
                    labPbrAtlas.normalAtlas(),
                    labPbrAtlas.specularAtlas(),
                    atmosphere,
                    current.processor().rawFrame());
            return false;
        }
        VulkanReconstructionResources replacement =
                this.reconstructionRegistry.createResources(atmosphere, selection);
        this.requireRayDispatchCapacity(
                replacement.selection().extent().width(),
                replacement.selection().extent().height());
        try {
            this.pipeline.ensureDescriptors(
                    tlas,
                    replacement.stableRadiance(),
                    atlasView,
                    atlasSampler,
                    sceneTextures,
                    labPbrAtlas.normalAtlas(),
                    labPbrAtlas.specularAtlas(),
                    atmosphere,
                    replacement.processor().rawFrame());
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
        ResolvedReconstruction requestedSelection = this.reconstructionRegistry.resolve(
                settings.postProcessing(),
                settings.reconstructionQuality(),
                width,
                height);
        this.requireRayDispatchCapacity(
                requestedSelection.extent().width(), requestedSelection.extent().height());
        boolean resized = this.ensureResources(
                input.atmosphere(),
                input.labPbrAtlas(),
                requestedSelection,
                input.scene().tlas(),
                input.atlasView(),
                input.atlasSampler(),
                input.sceneTextures());
        VulkanReconstructionResources images = this.resources;
        if (images == null) {
            return List.of();
        }
        ResolvedReconstruction selection = images.selection();
        int renderWidth = selection.extent().width();
        int renderHeight = selection.extent().height();
        if (resized) {
            PrimeInfo.LOGGER.debug(
                    "Recreated Prime realtime images at display {}x{}, render {}x{}, {} {} "
                            + "(output image={}, view={}; accumulation image={}, view={}; "
                            + "atlas image={}, view={}, sampler={})",
                    width,
                    height,
                    renderWidth,
                    renderHeight,
                    selection.effectiveMode().id(),
                    selection.quality().id(),
                    hex(images.output().image()),
                    hex(images.output().view()),
                    hex(images.stableRadiance().image()),
                    hex(images.stableRadiance().view()),
                    hex(input.atlasView().texture().vkImage()),
                    hex(input.atlasView().vkImageView()),
                    hex(input.atlasSampler().vkSampler()));
        }

        VulkanImage target = images.output();
        VulkanImage history = images.stableRadiance();
        VulkanReconstructionProcessor processor = images.processor();
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
                selection.effectiveMode(),
                selection.quality(),
                selection.transparentGuideMode(),
                settings.lighting(),
                settings.material(),
                processor.rawFrame().usesShInputs(),
                input.controls().triangleDebug(),
                settings.display(),
                resized);
        RealtimeSampleState.Plan sampleFrame = this.planSample(frameInput.sampleStateInput());
        ReconstructionFrameParameters postParameters =
                frameInput.reconstructionInput(sampleFrame.reset());
        ReconstructionDebugSettings debugSettings = new ReconstructionDebugSettings(
                input.controls().nrdDebugView(),
                input.controls().fsrDebugView(),
                input.controls().rrDebugView(),
                input.controls().rrDebugFullscreen());
        VulkanReconstructionProcessor.Frame postFrame =
                processor.beginFrame(postParameters, debugSettings);
        ReconstructionFrame reconstructionFrame = postFrame.semantic();
        RealtimeFramePlan framePlan;
        List<String> debugLines;
        try {
            framePlan = RealtimeFramePlan.complete(
                    frameInput,
                    sampleFrame,
                    postParameters,
                    reconstructionFrame,
                    selection.jitter(reconstructionFrame.frameIndex()),
                    selection.jitterPhase(reconstructionFrame.frameIndex()),
                    selection.packedRayCone(
                            input.camera().projection().m00(),
                            input.camera().projection().m11()),
                    selection.rawNumericalDiagnostic(debugSettings));
            debugLines = selection.debugLines(reconstructionFrame, debugSettings);
        } catch (RuntimeException exception) {
            throw ResourceCleanup.run(() -> processor.abandon(postFrame), exception);
        }
        this.executor.execute(
                selection.executionLabel(),
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
        if (input.controls().rendererDiagnostics()) {
            this.exposureDiagnostics.capture(
                    processor.displayExposureStateBuffer());
        }
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
                settings.display(),
                input.atlasView(),
                input.atlasSampler(),
                input.sceneTextures(),
                input.textureRevision(),
                images.stableRadiance(),
                input.labPbrAtlas().normalAtlas(),
                input.labPbrAtlas().specularAtlas(),
                images.processor().rawFrame()));
        int accumulatedSamples = this.sampleIndex();
        if (accumulatedSamples >= 16
                && (accumulatedSamples & (accumulatedSamples - 1)) == 0) {
            PrimeInfo.LOGGER.debug(
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

    record DiagnosticSnapshot(
            PostProcessingMode postProcessingMode,
            ReconstructionQualityMode quality,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            int accumulatedSamples,
            DisplayExposureDiagnostics.Snapshot exposure) {}

    void releaseSizedResourcesAfterIdle() {
        VulkanReconstructionResources current = this.resources;
        this.resources = null;
        if (current != null) {
            current.destroy();
        }
        this.pipeline.releaseSizedResourcesAfterIdle();
        this.sampleState = this.sampleState.invalidated();
    }

    void reloadActive(AtmospherePipeline atmosphere) {
        RealtimeRayTracingPipeline replacementPipeline = null;
        VulkanReconstructionResources replacementResources = null;
        try {
            replacementPipeline = new RealtimeRayTracingPipeline(this.context, this.backend);
            VulkanReconstructionResources current = this.resources;
            if (current != null) {
                replacementResources = this.reconstructionRegistry.createResources(
                        atmosphere, current.selection());
            }
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(replacementResources, exception);
            ResourceCleanup.destroy(replacementPipeline, exception);
            throw exception;
        }
        RealtimeRayTracingPipeline previousPipeline = this.pipeline;
        VulkanReconstructionResources previousResources = this.resources;
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
        failure = ResourceCleanup.destroy(this.exposureDiagnostics, failure);
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
