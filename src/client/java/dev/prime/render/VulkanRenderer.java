package dev.prime.render;

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
import dev.prime.render.replay.RenderReplayVerification;
import dev.prime.render.scene.vanilla.DynamicSceneFrame;
import dev.prime.render.vulkan.AtmospherePipeline;
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
    private FrameCamera camera;
    private AstronomyState astronomyState;
    // Resource-reload apply can publish this request off the render thread; all GPU mutation is
    // still consumed and owned by beginFrame on the render thread.
    private volatile boolean shaderReloadRequested;
    private SessionControls frameControls = SessionControls.defaults();
    private List<String> debugLines = List.of();
    private RendererModeLifecycle modeLifecycle = RendererModeLifecycle.initial();
    private boolean screenshotRequestRejected;
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
        screenshotRequested = this.updateOfflineSession(minecraft, screenshotRequested);
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
            if (this.offlineRenderer.updateProjection(baseProjection)) {
                this.modeLifecycle = this.modeLifecycle.releaseOfflineSized();
                PrimeClient.LOGGER.info(
                        "Restarted Prime offline accumulation for a new aspect ratio");
            }
            return;
        }
        this.camera = FrameCamera.tryCreate(
                renderedProjection, baseProjection, viewRotation, x, y, z);
        this.astronomyState = AstronomyState.atSolarHourAngle(
                sunAngleRadians,
                PrimeConfig.settings().astronomy());
    }

    public void captureDynamicScene(DynamicSceneFrame frame) {
        if (this.screenshotActive()) {
            return;
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
        if (this.terrain.updateDynamic(frame)) {
            this.sceneTextures = capturedTextures;
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

    public void render(RenderTarget mainTarget) {
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
                            PrimeConfig.settings().display(),
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
                return;
            }
            this.cancelOfflineSession();
        }
        this.renderRealtime(mainTarget, atlas);
    }

    private void renderRealtime(RenderTarget mainTarget, BlockAtlasFrame atlas) {
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
                        RealtimeRenderSettings.capture(PrimeConfig.settings()),
                        this.frameControls,
                        this.isCameraInWater(Minecraft.getInstance(), frameCamera),
                        this.atmosphere,
                        this.traceBackend.sunShadowPipeline(),
                        this.labPbrAtlas,
                        atlas.view(),
                        atlas.sampler(),
                        atlas.textureRevision(),
                        this.sceneTextures));
        this.debugLines = this.withCompactionDiagnostics(rendererDebugLines);
        if (this.realtimeRenderer.hasSizedResources()) {
            this.modeLifecycle = this.modeLifecycle.allocateRealtimeSized();
        }
    }

    private List<String> withCompactionDiagnostics(List<String> rendererLines) {
        if (!this.frameControls.blasCompactionDebug()) {
            return rendererLines;
        }
        TerrainScene.CompactionStats stats = this.terrain.compactionStats();
        long sourceBytes = Math.addExact(
                Math.addExact(stats.waitingSourceBytes(), stats.readySourceBytes()),
                stats.inFlightSourceBytes());
        ArrayList<String> lines = new ArrayList<>(rendererLines.size() + 4);
        lines.addAll(rendererLines);
        lines.add(String.format(
                Locale.ROOT,
                "Prime BLAS compact  jobs W/R/F %d/%d/%d",
                stats.waiting(),
                stats.ready(),
                stats.retiring()));
        lines.add(String.format(
                Locale.ROOT,
                "Raw source W/R/F %.1f/%.1f/%.1f MiB  total %.1f MiB",
                mebibytes(stats.waitingSourceBytes()),
                mebibytes(stats.readySourceBytes()),
                mebibytes(stats.inFlightSourceBytes()),
                mebibytes(sourceBytes)));
        lines.add(String.format(
                Locale.ROOT,
                "Known reclaim %.1f MiB  reclaimed %.1f MiB  completed %d",
                mebibytes(stats.knownReclaimableBytes()),
                mebibytes(stats.reclaimedBytes()),
                stats.completedCount()));
        lines.add(String.format(
                Locale.ROOT,
                "Compact target %.1f MiB  high-water %.1f MiB",
                mebibytes(stats.reservedTargetBytes()),
                mebibytes(stats.highWaterTargetBytes())));
        return List.copyOf(lines);
    }

    private static double mebibytes(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private boolean updateOfflineSession(Minecraft minecraft, boolean requested) {
        OfflineSession current = this.offlineRenderer.session();
        boolean worldChanged = current != null && !current.matchesWorld(minecraft.level);
        BlockAtlasFrame atlas = this.blockAtlasFrame;
        boolean resourcesChanged = current != null
                && (atlas == null
                        || !current.matchesAtlas(
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
        if (!this.screenshotActive()
                && requested
                && minecraft.level != null
                && this.camera != null
                && this.astronomyState != null
                && this.terrain.residentScene() != null
                && this.realtimeRenderer.hasSizedResources()
                && this.blockAtlasFrame != null) {
            PrimeSettings settings = PrimeConfig.settings();
            BlockAtlasFrame frozenAtlas = this.blockAtlasFrame;
            OfflineSession session = new OfflineSession(
                    minecraft.level,
                    this.terrain.residentScene(),
                    this.camera,
                    this.astronomyState,
                    OfflineRenderSettings.capture(settings),
                    this.isCameraInWater(minecraft, this.camera),
                    frozenAtlas.view().vkImageView(),
                    frozenAtlas.sampler().vkSampler(),
                    frozenAtlas.textureRevision(),
                    this.sceneTextures);
            this.context.awaitIdle();
            this.context.drainDeferredAfterIdle();
            this.realtimeRenderer.releaseSizedResourcesAfterIdle();
            this.modeLifecycle = this.modeLifecycle
                    .releaseRealtimeSized()
                    .enterOffline();
            this.offlineRenderer.begin(session);
            PrimeClient.LOGGER.info(
                    "Entered Prime screenshot mode at scene revision {}",
                    session.scene().revision());
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
        this.realtimeRenderer.invalidateHistory();
        PrimeClient.LOGGER.info("Left Prime screenshot mode; scheduled a full terrain resync");
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
        this.realtimeRenderer.invalidateHistory();
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
        return this.realtimeRenderer.replay().request(width, height);
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
        failure = ResourceCleanup.run(this.context::drainDeferredAfterIdle, failure);
        failure = ResourceCleanup.destroy(this.offlineRenderer, failure);
        failure = ResourceCleanup.destroy(this.realtimeRenderer, failure);
        failure = ResourceCleanup.close(this.terrain, failure);
        failure = ResourceCleanup.close(this.labPbrAtlas, failure);
        failure = ResourceCleanup.destroy(this.traceBackend, failure);
        failure = ResourceCleanup.destroy(this.atmosphere, failure);
        failure = ResourceCleanup.close(this.stagingArena, failure);
        this.debugLines = List.of();
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
            PrimeClient.LOGGER.error("Prime shader reload failed; keeping the previous pipeline", exception);
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
        PrimeClient.LOGGER.info(
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

}
