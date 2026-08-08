package dev.prime.render.runtime;

import dev.prime.render.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.vulkan.terrain.TerrainScene;
import dev.prime.render.runtime.terrain.TerrainStreamer;
import dev.prime.render.scene.vanilla.DynamicSceneFrame;
import dev.prime.render.scene.vanilla.DynamicSceneMotion;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.FrozenExposureState;
import dev.prime.render.vulkan.LabPbrTextureAtlas;
import dev.prime.render.vulkan.StagingArena;
import dev.prime.render.vulkan.SunShadowPipeline;
import dev.prime.render.vulkan.TraceBackend;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import dev.prime.render.vulkan.dlss.DlssRrNative;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.tags.FluidTags;
import org.joml.Matrix4fc;

public final class VulkanRenderer implements AutoCloseable {
    private final VulkanContext context;
    private final RealtimeRenderer realtimeRenderer;
    private final OfflineRenderer offlineRenderer;
    private final StagingArena stagingArena;
    private final TerrainStreamer terrain;
    private final LabPbrTextureAtlas labPbrAtlas;
    private final BlockPos.MutableBlockPos cameraBlockPosition = new BlockPos.MutableBlockPos();
    private final TraceBackend traceBackend;
    private AtmospherePipeline atmosphere;
    private BlockAtlasFrame blockAtlasFrame;
    private long blockAtlasTextureRevision;
    private List<TraceBackend.SceneTexture> sceneTextures = List.of();
    private DynamicSceneFrame publishedDynamicFrame;
    private Set<DynamicSceneFrame.CompatibilityIssue> dynamicCompatibilityIssues =
            Set.of();
    private FrameCamera camera;
    private AstronomyState astronomyState;
    private OfflineSession pendingOfflineSession;
    // Resource-reload apply can publish this request off the render thread; all GPU mutation is
    // still consumed and owned by beginFrame on the render thread.
    private volatile boolean shaderReloadRequested;
    private SessionControls frameControls = SessionControls.defaults();
    private List<String> debugLines = List.of();
    private RendererModeLifecycle modeLifecycle = RendererModeLifecycle.initial();
    private boolean screenshotRequestRejected;
    private volatile boolean acceptsResourceReloadEffects = true;
    private boolean closed;
    public VulkanRenderer(VulkanContext context) {
        VulkanContext newContext = java.util.Objects.requireNonNull(context, "context");
        StagingArena newStagingArena = null;
        AtmospherePipeline newAtmosphere = null;
        TraceBackend newTraceBackend = null;
        RealtimeRenderer newRealtimeRenderer = null;
        OfflineRenderer newOfflineRenderer = null;
        TerrainStreamer newTerrain = null;
        LabPbrTextureAtlas newLabPbrAtlas = null;
        DlssRrNative.Context newNgxContext = null;
        try {
            newStagingArena = new StagingArena(newContext);
            newAtmosphere = new AtmospherePipeline(newContext);
            newTraceBackend = new TraceBackend(newContext);
            newTerrain = new TerrainStreamer(newContext, newStagingArena);
            newLabPbrAtlas = new LabPbrTextureAtlas(newContext, newStagingArena);
            newNgxContext = DlssRrBootstrap.initialize(newContext).orElse(null);
            newRealtimeRenderer = new RealtimeRenderer(
                    newContext, newTraceBackend, newNgxContext);
            newOfflineRenderer = new OfflineRenderer(newContext, newTraceBackend);
            this.context = newContext;
            this.realtimeRenderer = newRealtimeRenderer;
            this.offlineRenderer = newOfflineRenderer;
            this.stagingArena = newStagingArena;
            this.traceBackend = newTraceBackend;
            this.atmosphere = newAtmosphere;
            this.terrain = newTerrain;
            this.labPbrAtlas = newLabPbrAtlas;
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(newOfflineRenderer, exception);
            ResourceCleanup.destroy(newRealtimeRenderer, exception);
            if (newRealtimeRenderer == null && newNgxContext != null) {
                DlssRrNative.Context failedNgxContext = newNgxContext;
                ResourceCleanup.run(
                        () -> DlssRrBootstrap.release(failedNgxContext), exception);
            }
            ResourceCleanup.close(newLabPbrAtlas, exception);
            ResourceCleanup.close(newTerrain, exception);
            ResourceCleanup.destroy(newTraceBackend, exception);
            ResourceCleanup.destroy(newAtmosphere, exception);
            ResourceCleanup.close(newStagingArena, exception);
            throw exception;
        }
    }

    public boolean beginFrame(
            Minecraft minecraft,
            SessionControls controls,
            RendererSettings settings) {
        this.frameControls = java.util.Objects.requireNonNull(controls, "controls");
        java.util.Objects.requireNonNull(settings, "settings");
        boolean screenshotRequested = controls.screenshotRequested();
        if (this.screenshotRequestRejected) {
            screenshotRequested = false;
            this.screenshotRequestRejected = false;
        }
        this.reloadPipelineIfRequested();
        this.synchronizeLabPbr(minecraft);
        this.terrain.setVoxelTextureSurfaces(
                settings.voxelTextureSurfaces(),
                settings.voxelTextureSurfaceStrengthSteps());
        screenshotRequested = this.updateOfflineSession(
                minecraft, screenshotRequested, settings);
        if (this.screenshotActive()) {
            return screenshotRequested;
        }
        if (this.pendingOfflineSession != null) {
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
        if (!LabPbrTextureAtlas.hasStitchedSprites(atlas)) {
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
            float sunAngleRadians,
            RendererSettings settings) {
        java.util.Objects.requireNonNull(settings, "settings");
        OfflineSession pending = this.pendingOfflineSession;
        if (pending != null) {
            pending.updateProjection(baseProjection);
            return;
        }
        if (this.screenshotActive()) {
            if (this.offlineRenderer.updateProjection(baseProjection)) {
                this.modeLifecycle = this.modeLifecycle.releaseOfflineSized();
                PrimeInfo.LOGGER.info(
                        "Restarted Prime offline accumulation for a new aspect ratio");
            }
            return;
        }
        this.camera = FrameCamera.tryCreate(
                renderedProjection, baseProjection, viewRotation, x, y, z);
        this.astronomyState = AstronomyState.atSolarHourAngle(
                sunAngleRadians,
                settings.astronomy());
    }

    public void captureDynamicScene(DynamicSceneFrame frame) {
        if (this.screenshotActive() || this.pendingOfflineSession != null) {
            return;
        }
        if (!frame.compatibilityIssues().equals(this.dynamicCompatibilityIssues)) {
            this.dynamicCompatibilityIssues = frame.compatibilityIssues();
            for (DynamicSceneFrame.CompatibilityIssue issue
                    : this.dynamicCompatibilityIssues) {
                PrimeInfo.LOGGER.warn(
                        "Prime dynamic scene compatibility: {}",
                        issue.description());
            }
        }
        ArrayList<TraceBackend.SceneTexture> textures =
                new ArrayList<>(frame.textures().size());
        for (DynamicSceneFrame.SceneTexture texture : frame.textures()) {
            if (!(texture.view() instanceof VulkanGpuTextureView view)
                    || !(texture.sampler() instanceof VulkanGpuSampler sampler)) {
                throw new IllegalStateException(
                        "Prime expected Vulkan dynamic scene textures");
            }
            textures.add(new TraceBackend.SceneTexture(
                    view.texture().vkImage(),
                    view.vkImageView(),
                    sampler.vkSampler()));
        }
        List<TraceBackend.SceneTexture> capturedTextures =
                List.copyOf(textures);
        DynamicSceneMotion motion = DynamicSceneMotion.prepare(
                frame, this.publishedDynamicFrame);
        if (this.terrain.updateDynamic(motion)) {
            this.sceneTextures = capturedTextures;
            this.publishedDynamicFrame = frame;
        }
    }

    public boolean isReady() {
        return this.terrain.isNearCameraReady() && this.terrain.residentScene() != null;
    }

    public boolean screenshotActive() {
        return this.offlineRenderer.active();
    }

    public List<String> debugLines() {
        return this.debugLines;
    }

    public void render(RenderTarget mainTarget, RendererSettings settings) {
        java.util.Objects.requireNonNull(settings, "settings");
        BlockAtlasFrame atlas = this.blockAtlasFrame;
        if (atlas == null) {
            this.debugLines = List.of();
            return;
        }
        if (this.screenshotActive()) {
            this.debugLines = List.of();
            boolean sessionValid = this.offlineRenderer.render(
                    new OfflineRenderer.RenderInput(
                            mainTarget,
                            settings.display(),
                            this.atmosphere,
                            this.traceBackend.sunShadowPipeline(),
                            this.labPbrAtlas,
                            atlas.view(),
                            atlas.sampler(),
                            atlas.textureRevision()));
            if (sessionValid) {
                if (this.offlineRenderer.hasSizedResources()) {
                    this.modeLifecycle = this.modeLifecycle.allocateOfflineSized();
                }
                this.debugLines = this.withRendererDiagnostics(List.of(), settings);
                return;
            }
            this.cancelOfflineSession();
        }
        this.renderRealtime(mainTarget, atlas, settings);
    }

    private void renderRealtime(
            RenderTarget mainTarget,
            BlockAtlasFrame atlas,
            RendererSettings settings) {
        TerrainScene.ResidentSceneView scene = this.terrain.residentScene();
        FrameCamera frameCamera = this.camera;
        AstronomyState frameAstronomy = this.astronomyState;
        if (scene == null || frameCamera == null || frameAstronomy == null) {
            this.debugLines = List.of();
            return;
        }
        List<String> rendererDebugLines = this.realtimeRenderer.render(
                new RealtimeRenderer.RenderInput(
                        mainTarget,
                        scene,
                        frameCamera,
                        frameAstronomy,
                        RealtimeRenderSettings.capture(settings),
                        this.frameControls,
                        this.isCameraInWater(Minecraft.getInstance(), frameCamera),
                        this.atmosphere,
                        this.traceBackend.sunShadowPipeline(),
                        this.labPbrAtlas,
                        atlas.view(),
                        atlas.sampler(),
                        atlas.textureRevision(),
                        this.sceneTextures));
        this.debugLines = this.withRendererDiagnostics(rendererDebugLines, settings);
        if (this.realtimeRenderer.hasSizedResources()) {
            this.modeLifecycle = this.modeLifecycle.allocateRealtimeSized();
        }
    }

    private List<String> withRendererDiagnostics(
            List<String> rendererLines,
            RendererSettings settings) {
        if (!this.frameControls.rendererDiagnostics()) {
            return rendererLines;
        }
        OfflineSession offlineSession = this.offlineRenderer.session();
        TerrainScene.ResidentSceneView scene = offlineSession == null
                ? this.terrain.residentScene()
                : offlineSession.scene();
        if (scene == null) {
            return rendererLines;
        }
        TerrainScene.CompactionStats stats = this.terrain.compactionStats();
        TerrainScene.SceneStatistics sceneStats = scene.statistics();
        long sourceBytes = Math.addExact(
                Math.addExact(stats.waitingSourceBytes(), stats.readySourceBytes()),
                stats.inFlightSourceBytes());
        ArrayList<String> lines = new ArrayList<>(rendererLines.size() + 12);
        lines.addAll(rendererLines);
        lines.add("Prime renderer diagnostics");
        lines.add(String.format(
                Locale.ROOT,
                "Graphics device: %s; shader execution reordering: %s; opacity micromaps: %s",
                this.context.capabilities().deviceName(),
                this.context.capabilities().invocationReorderSupported()
                        ? "enabled"
                        : "unavailable",
                this.context.capabilities().opacityMicromapSupported()
                        ? "enabled"
                        : "unavailable"));
        OfflineRenderer.DiagnosticSnapshot offline =
                this.offlineRenderer.diagnosticSnapshot();
        if (offline != null) {
            lines.add(String.format(
                    Locale.ROOT,
                    "Rendering path: offline path-tracing accumulation; resolution: %d x %d; accumulated samples: %,d",
                    offline.width(),
                    offline.height(),
                    offline.accumulatedSamples()));
        } else {
            RealtimeRenderer.DiagnosticSnapshot realtime =
                    this.realtimeRenderer.diagnosticSnapshot();
            if (realtime != null) {
                lines.add(String.format(
                        Locale.ROOT,
                        "Rendering path: %s; quality: %s",
                        renderingPath(realtime.postProcessingMode()),
                        reconstructionQuality(realtime.quality())));
                lines.add(String.format(
                        Locale.ROOT,
                        "Render resolution: %d x %d; display resolution: %d x %d; accumulated samples: %,d; integrator passes: %d; wavefront bytes: %,d",
                        realtime.renderWidth(),
                        realtime.renderHeight(),
                        realtime.displayWidth(),
                        realtime.displayHeight(),
                        realtime.accumulatedSamples(),
                        realtime.integratorPassCount(),
                        realtime.integratorResourceBytes()));
            }
        }
        lines.add(String.format(
                Locale.ROOT,
                "Top-level acceleration structure instances: %,d; area-light emitters: %,d; top-level light-tree nodes: %,d",
                sceneStats.tlasInstanceCount(),
                sceneStats.areaLightEmitterCount(),
                sceneStats.topLevelLightTreeNodeCount()));
        lines.add(String.format(
                Locale.ROOT,
                "Triangle references after instancing: %,d; unique bottom-level geometry triangles: %,d",
                sceneStats.instancedTriangleCount(),
                sceneStats.uniqueBlasTriangleCount()));
        var exposure = offlineSession == null
                ? this.realtimeRenderer.exposureDiagnosticSnapshot()
                : offlineSession.exposure().diagnosticSnapshot();
        if (exposure == null) {
            lines.add("Automatic exposure metering: waiting for asynchronous GPU readback");
        } else if (!exposure.initialized()) {
            lines.add("Automatic exposure metering: state is not initialized");
        } else if (!exposure.finite()) {
            lines.add(String.format(
                    Locale.ROOT,
                    "Automatic exposure metering: non-finite state (current=%s, target=%s, measured log brightness=%s)",
                    exposure.automaticExposureEv(),
                    exposure.targetExposureEv(),
                    exposure.measuredLogBrightness()));
        } else {
            lines.add(String.format(
                    Locale.ROOT,
                    "Automatic exposure value: %+.2f stops; target: %+.2f stops; metered linear Rec.2020 relative luminance (log2): %+.2f",
                    exposure.automaticExposureEv(),
                    exposure.targetExposureEv(),
                    exposure.measuredLogBrightness()));
            float manualExposure =
                    settings.display().finalExposureQuarterSteps() * 0.25F;
            lines.add(String.format(
                    Locale.ROOT,
                    "Manual final exposure: %+.2f stops; combined display exposure: %+.2f stops",
                    manualExposure,
                    exposure.automaticExposureEv() + manualExposure));
        }
        lines.add(String.format(
                Locale.ROOT,
                "Bottom-level acceleration structure compaction jobs - query pending: %,d; ready: %,d; source retirement pending: %,d",
                stats.waiting(),
                stats.ready(),
                stats.retiring()));
        lines.add(String.format(
                Locale.ROOT,
                "Uncompacted source backing - query pending: %.1f MiB; ready: %.1f MiB; source retirement pending: %.1f MiB; total: %.1f MiB",
                mebibytes(stats.waitingSourceBytes()),
                mebibytes(stats.readySourceBytes()),
                mebibytes(stats.inFlightSourceBytes()),
                mebibytes(sourceBytes)));
        lines.add(String.format(
                Locale.ROOT,
                "Known reclaimable backing: %.1f MiB; cumulatively reclaimed: %.1f MiB; completed compactions: %,d",
                mebibytes(stats.knownReclaimableBytes()),
                mebibytes(stats.reclaimedBytes()),
                stats.completedCount()));
        lines.add(String.format(
                Locale.ROOT,
                "Compacted-target overlap reserved: %.1f MiB; reserved high-water mark: %.1f MiB",
                mebibytes(stats.reservedTargetBytes()),
                mebibytes(stats.highWaterTargetBytes())));
        return List.copyOf(lines);
    }

    private static String renderingPath(
            dev.prime.render.post.PostProcessingMode mode) {
        return switch (mode) {
            case DLSS_RR -> "DLSS Ray Reconstruction";
            case NRD_FSR ->
                    "NVIDIA Real-time Denoisers and FidelityFX Super Resolution 3.1.4";
            case DISABLED -> "native-resolution noisy path tracing";
        };
    }

    private static String reconstructionQuality(
            dev.prime.render.post.ReconstructionQualityMode quality) {
        return switch (quality) {
            case NATIVE_AA -> "native anti-aliasing";
            case QUALITY -> "quality";
            case BALANCED -> "balanced";
            case PERFORMANCE -> "performance";
            case ULTRA_PERFORMANCE -> "ultra performance";
        };
    }

    private static double mebibytes(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private boolean updateOfflineSession(
            Minecraft minecraft,
            boolean requested,
            RendererSettings settings) {
        OfflineSession current = this.offlineRenderer.session();
        OfflineSession tracked = current != null ? current : this.pendingOfflineSession;
        boolean worldChanged = tracked != null && !tracked.matchesWorld(minecraft.level);
        BlockAtlasFrame atlas = this.blockAtlasFrame;
        boolean resourcesChanged = tracked != null
                && (atlas == null
                        || !tracked.matchesAtlas(
                                atlas.view().vkImageView(),
                                atlas.sampler().vkSampler(),
                                atlas.textureRevision()));
        if (worldChanged || resourcesChanged) {
            requested = false;
        }
        if (this.screenshotActive()
                && (!requested || worldChanged || resourcesChanged)) {
            this.stopOfflineSession();
        }
        OfflineSession pending = this.pendingOfflineSession;
        if (pending != null && (!requested || worldChanged || resourcesChanged)) {
            this.pendingOfflineSession = null;
            pending.destroy();
            pending = null;
        }
        if (pending != null && pending.exposure().ready()) {
            this.context.awaitIdle();
            this.context.drainDeferredAfterIdle();
            this.realtimeRenderer.releaseSizedResourcesAfterIdle();
            this.modeLifecycle = this.modeLifecycle
                    .releaseRealtimeSized()
                    .enterOffline();
            this.pendingOfflineSession = null;
            this.offlineRenderer.begin(pending);
            PrimeInfo.LOGGER.info(
                    "Entered Prime screenshot mode at scene revision {}",
                    pending.scene().revision());
            return requested;
        }
        if (!this.screenshotActive()
                && pending == null
                && requested
                && minecraft.level != null
                && this.camera != null
                && this.astronomyState != null
                && this.terrain.residentScene() != null
                && this.realtimeRenderer.hasSizedResources()
                && this.blockAtlasFrame != null) {
            BlockAtlasFrame frozenAtlas = this.blockAtlasFrame;
            FrozenExposureState exposure = FrozenExposureState.capture(
                    this.context,
                    this.realtimeRenderer.displayExposureStateBuffer());
            OfflineSession session = null;
            try {
                session = new OfflineSession(
                        minecraft.level,
                        this.terrain.residentScene(),
                        this.camera,
                        this.astronomyState,
                        OfflineRenderSettings.capture(settings),
                        this.isCameraInWater(minecraft, this.camera),
                        frozenAtlas.view().vkImageView(),
                        frozenAtlas.sampler().vkSampler(),
                        frozenAtlas.textureRevision(),
                        this.sceneTextures,
                        exposure);
                this.pendingOfflineSession = session;
            } catch (RuntimeException exception) {
                if (session != null) {
                    throw ResourceCleanup.destroy(session, exception);
                }
                throw ResourceCleanup.destroy(exposure, exception);
            }
        }
        return requested;
    }

    private void stopOfflineSession() {
        if (!this.screenshotActive()) {
            return;
        }
        this.context.awaitIdle();
        this.context.drainDeferredAfterIdle();
        this.offlineRenderer.stopAfterIdle();
        this.modeLifecycle = this.modeLifecycle
                .releaseOfflineSized()
                .exitOffline();
        // Dirty notifications continue to accumulate while uploads are paused. A full resync on
        // exit also covers animation-driven or external changes that do not expose a precise
        // block range, without invalidating the frozen screenshot while it is converging.
        this.terrain.invalidateAll();
        PrimeInfo.LOGGER.info("Left Prime screenshot mode; scheduled a full terrain resync");
    }

    private void cancelOfflineSession() {
        this.screenshotRequestRejected = true;
        this.stopOfflineSession();
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
    }

    public void requestShaderReload() {
        this.shaderReloadRequested = true;
    }

    public ResourceReload beginResourceReload() {
        return new ResourceReload(this, this.terrain.beginResourceReload());
    }

    public void finishResourceReload(ResourceReload reload, boolean reloadShaders) {
        TerrainStreamer.ResourceReload terrainReload = this.requireReload(reload);
        this.terrain.finishResourceReload(terrainReload);
        if (!this.acceptsResourceReloadEffects) {
            return;
        }
        this.labPbrAtlas.requestReload();
        // Minecraft's initial resource load completes after Prime has already constructed all
        // pipelines from packaged SPIR-V. Later explicit reloads replace them before rendering.
        if (reloadShaders) {
            this.requestShaderReload();
        }
        this.invalidateAll();
    }

    public void abortResourceReload(ResourceReload reload) {
        this.terrain.abortResourceReload(this.requireReload(reload));
    }

    private TerrainStreamer.ResourceReload requireReload(ResourceReload reload) {
        if (reload == null) {
            throw new NullPointerException("reload");
        }
        if (reload.owner != this) {
            throw new IllegalArgumentException("Resource reload belongs to another renderer");
        }
        return reload.terrainReload;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.acceptsResourceReloadEffects = false;
        // A failed wait leaves every GPU child live and permits a later close attempt. After a
        // successful wait, every child is attempted once and failures are aggregated.
        this.context.awaitIdle();
        RuntimeException failure = null;
        failure = ResourceCleanup.run(this.context::drainDeferredAfterIdle, failure);
        failure = ResourceCleanup.destroy(this.pendingOfflineSession, failure);
        failure = ResourceCleanup.destroy(this.offlineRenderer, failure);
        failure = ResourceCleanup.destroy(this.realtimeRenderer, failure);
        failure = ResourceCleanup.close(this.terrain, failure);
        failure = ResourceCleanup.close(this.labPbrAtlas, failure);
        failure = ResourceCleanup.destroy(this.traceBackend, failure);
        failure = ResourceCleanup.destroy(this.atmosphere, failure);
        failure = ResourceCleanup.close(this.stagingArena, failure);
        this.debugLines = List.of();
        this.pendingOfflineSession = null;
        this.closed = true;
        ResourceCleanup.throwIfFailed(failure);
    }

    private void reloadPipelineIfRequested() {
        if (!this.shaderReloadRequested) {
            return;
        }
        this.shaderReloadRequested = false;
        boolean offlineActive = this.screenshotActive();
        AtmospherePipeline replacementAtmosphere = null;
        SunShadowPipeline replacementSunShadow = null;
        try {
            replacementAtmosphere = new AtmospherePipeline(this.context);
            replacementSunShadow = this.traceBackend.prepareSunShadowReload();
            if (offlineActive) {
                this.offlineRenderer.reloadActive();
                this.realtimeRenderer.invalidatePipeline();
            } else {
                this.realtimeRenderer.reloadActive(replacementAtmosphere);
                this.offlineRenderer.invalidatePipeline();
            }
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(replacementSunShadow, exception);
            ResourceCleanup.destroy(replacementAtmosphere, exception);
            PrimeInfo.LOGGER.error("Prime shader reload failed; keeping the previous pipeline", exception);
            return;
        }
        SunShadowPipeline previousSunShadow =
                this.traceBackend.replaceSunShadowPipeline(replacementSunShadow);
        AtmospherePipeline previousAtmosphere = this.atmosphere;
        this.atmosphere = replacementAtmosphere;
        RuntimeException retirementFailure = ResourceCleanup.run(
                () -> this.context.defer(previousSunShadow), null);
        retirementFailure = ResourceCleanup.run(
                () -> this.context.defer(previousAtmosphere), retirementFailure);
        ResourceCleanup.throwIfFailed(retirementFailure);
        PrimeInfo.LOGGER.info(
                "Reloaded Prime {} ray tracing and atmosphere shaders",
                offlineActive ? "offline" : "realtime");
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

    public static final class ResourceReload {
        private final VulkanRenderer owner;
        private final TerrainStreamer.ResourceReload terrainReload;

        private ResourceReload(
                VulkanRenderer owner,
                TerrainStreamer.ResourceReload terrainReload) {
            this.owner = owner;
            this.terrainReload = terrainReload;
        }

        public CompletableFuture<Void> ready() {
            return this.terrainReload.ready();
        }
    }

}
