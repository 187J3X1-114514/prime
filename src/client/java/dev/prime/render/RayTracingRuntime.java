package dev.prime.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.prime.PrimeClient;
import dev.prime.client.ViewDistanceLimits;
import dev.prime.config.PrimeConfig;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.replay.RenderReplayFixtureStore;
import dev.prime.render.replay.RenderReplayVerification;
import dev.prime.render.scene.vanilla.DynamicSceneFrame;
import dev.prime.render.vulkan.VulkanBootstrap;
import dev.prime.render.vulkan.VulkanCapabilities;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4fc;
import org.lwjgl.glfw.GLFW;

public final class RayTracingRuntime {
    private static final RayTracingRuntime INSTANCE = new RayTracingRuntime();
    private static final SystemToast.SystemToastId UNAVAILABLE_TOAST = new SystemToast.SystemToastId(8_000L);
    private static final SystemToast.SystemToastId REPLAY_TEST_TOAST =
            new SystemToast.SystemToastId(8_001L);

    private final RuntimeStateMachine states = new RuntimeStateMachine();
    private String failureReason = "Prime has not initialized";
    private boolean initialized;
    private boolean notificationShown;
    private boolean unavailabilityLogged;
    private boolean shuttingDown;
    private boolean previousEscape;
    private boolean previousRrCycle;
    private boolean previousRrLayout;
    private boolean previousReplayTest;
    private CompletableFuture<RenderReplayVerification> replayTest;
    private SessionControls controls = SessionControls.defaults();
    // Resource reload preparation may observe the renderer off the client thread. The renderer
    // itself remains client-thread owned; volatile only publishes attachment and detachment.
    private volatile VulkanRenderer renderer;
    // Kept across normal renderer toggles. It owns only Prime's allocator/device boundary; every
    // scene, staging page, texture, pipeline and sized renderer resource belongs to renderer.
    private VulkanContext context;
    // Failure transfers the sole renderer ownership here until the next frame boundary. Closing
    // it inside renderWorld would invalidate resources referenced by that frame's command buffers.
    private VulkanRenderer retiringRenderer;
    private ClientLevel world;
    private ClientLevel terrainWorld;
    private boolean primeOwnsTerrain;

    private RayTracingRuntime() {
    }

    public static RayTracingRuntime instance() {
        return INSTANCE;
    }

    public void initialize() {
        this.initialized = true;
        if (!PrimeConfig.settings().pathTracingEnabled()) {
            this.states.disabled();
        }
    }

    private void tryInitializeRenderer(Minecraft minecraft) {
        if (this.renderer != null
                || this.retiringRenderer != null
                || this.shuttingDown
                || this.states.current() == RuntimeState.FAILED) {
            return;
        }
        VulkanBootstrap.Snapshot bootstrap = VulkanBootstrap.snapshot();
        VulkanCapabilities capabilities = bootstrap.capabilities();
        VulkanDevice device = bootstrap.device();
        if (!capabilities.available() || device == null) {
            this.failureReason = capabilities.unavailableReason();
            this.states.unavailable();
            this.restoreVanillaTerrain(minecraft, true);
        } else {
            try {
                if (this.context == null) {
                    this.context = new VulkanContext(device, capabilities);
                }
                if (minecraft.level == null || minecraft.player == null) {
                    this.states.rendererReady();
                    return;
                }
                this.acquirePrimeTerrain(minecraft);
                this.renderer = new VulkanRenderer(this.context);
                this.failureReason = "";
                this.states.rendererReady();
            } catch (RuntimeException exception) {
                this.failureReason = "Unable to initialize Vulkan ray tracing: " + exception.getMessage();
                this.states.fail();
                this.restoreVanillaTerrain(minecraft, true);
                VulkanContext failedContext = this.context;
                this.context = null;
                ResourceCleanup.close(failedContext, exception);
                PrimeClient.LOGGER.error("Prime Vulkan initialization failed", exception);
            }
        }
    }

    public RuntimeState state() {
        return this.states.current();
    }

    public boolean shouldReplaceWorld() {
        return this.states.current() == RuntimeState.ACTIVE;
    }

    public boolean shouldMaintainVanillaTerrain() {
        return !this.primeOwnsTerrain;
    }

    public int vanillaTerrainDistance(int configuredDistance) {
        return ViewDistanceLimits.vanillaTerrainDistance(
                configuredDistance, this.primeOwnsTerrain);
    }

    public boolean shouldCaptureDynamicScene() {
        VulkanRenderer activeRenderer = this.renderer;
        return activeRenderer != null
                && this.states.current() == RuntimeState.ACTIVE
                && !activeRenderer.screenshotActive();
    }

    public void beginFrame(Minecraft minecraft) {
        this.retireFailedRenderer(minecraft);
        if (!this.initialized) {
            return;
        }
        this.updateSessionShortcuts(minecraft);
        if (!PrimeConfig.settings().pathTracingEnabled()) {
            this.disableRenderer(minecraft);
            return;
        }
        if ((minecraft.level == null || minecraft.player == null)
                && this.renderer != null) {
            this.suspendRenderer(minecraft);
        }
        this.tryInitializeRenderer(minecraft);
        this.finalizeUnavailableReason();
        this.showFailureNotificationOnce(minecraft);
        if (this.states.current() == RuntimeState.FAILED) {
            return;
        }
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer == null) {
            return;
        }
        try {
            ClientLevel currentWorld = minecraft.level;
            this.acquirePrimeTerrain(minecraft);
            if (this.world != currentWorld) {
                this.world = currentWorld;
                this.states.worldChanged();
            }
            SessionControls frameControls = this.controls;
            boolean screenshotRequested = activeRenderer.beginFrame(minecraft, frameControls);
            if (screenshotRequested != frameControls.screenshotRequested()) {
                this.controls = this.controls.withScreenshotRequested(screenshotRequested);
            }
            if (currentWorld == null || minecraft.player == null) {
                this.states.worldAbsent();
            } else {
                this.states.worldStreaming(activeRenderer.isReady());
            }
        } catch (RuntimeException exception) {
            this.fail(exception);
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
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer != null && this.states.current() != RuntimeState.FAILED) {
            activeRenderer.captureCamera(
                    renderedProjection,
                    baseProjection,
                    viewRotation,
                    x,
                    y,
                    z,
                    sunAngleRadians);
        }
    }

    public void renderWorld(RenderTarget mainTarget) {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer == null || this.states.current() != RuntimeState.ACTIVE) {
            return;
        }
        try {
            activeRenderer.render(mainTarget);
        } catch (RuntimeException exception) {
            this.fail(exception);
        }
    }

    public void captureDynamicScene(DynamicSceneFrame frame) {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer == null
                || this.states.current() != RuntimeState.ACTIVE
                || activeRenderer.screenshotActive()) {
            return;
        }
        try {
            activeRenderer.captureDynamicScene(frame);
        } catch (RuntimeException exception) {
            this.fail(exception);
        }
    }

    public boolean handleScreenshotShortcut(
            Minecraft minecraft, InputConstants.Key key, boolean controlDown) {
        long window = minecraft.getWindow().handle();
        boolean alt = pressed(window, GLFW.GLFW_KEY_LEFT_ALT)
                || pressed(window, GLFW.GLFW_KEY_RIGHT_ALT);
        if (!controlDown
                || !alt
                || !minecraft.options.keyScreenshot.matches(key)
                || minecraft.level == null) {
            return false;
        }
        this.requestScreenshot(!this.controls.screenshotRequested());
        return true;
    }

    public boolean screenshotRequested() {
        return this.controls.screenshotRequested();
    }

    public void setPathTracingEnabled(boolean enabled) {
        boolean changed = PrimeConfig.settings().pathTracingEnabled() != enabled;
        PrimeConfig.setPathTracingEnabled(enabled);
        if (!enabled) {
            this.requestScreenshot(false);
        } else if (changed && this.states.current() == RuntimeState.DISABLED) {
            // Enabling after an explicit stop is a fresh initialization attempt.
            this.notificationShown = false;
            this.unavailabilityLogged = false;
        }
    }

    public void setVoxelTextureSurfaces(boolean enabled) {
        boolean changed = PrimeConfig.settings().voxelTextureSurfaces() != enabled;
        PrimeConfig.setVoxelTextureSurfaces(enabled);
        if (changed) {
            this.invalidateAll();
        }
    }

    public void setVoxelTextureSurfaceStrengthSteps(int steps) {
        boolean changed = PrimeConfig.settings().voxelTextureSurfaceStrengthSteps() != steps;
        PrimeConfig.setVoxelTextureSurfaceStrengthSteps(steps);
        if (changed && PrimeConfig.settings().voxelTextureSurfaces()) {
            this.invalidateAll();
        }
    }

    public void requestScreenshot(boolean enabled) {
        this.controls = this.controls.withScreenshotRequested(enabled);
    }

    public boolean screenshotActive() {
        VulkanRenderer activeRenderer = this.renderer;
        return activeRenderer != null && activeRenderer.screenshotActive();
    }

    public boolean triangleDebug() {
        return this.controls.triangleDebug();
    }

    public void setTriangleDebug(boolean value) {
        this.controls = this.controls.withTriangleDebug(value);
    }

    public boolean rendererDiagnostics() {
        return this.controls.rendererDiagnostics();
    }

    public void setRendererDiagnostics(boolean value) {
        this.controls = this.controls.withRendererDiagnostics(value);
    }

    public NrdDiagnostics.Mode nrdDebugView() {
        return this.controls.nrdDebugView();
    }

    public void setNrdDebugView(NrdDiagnostics.Mode value) {
        this.controls = this.controls.withNrdDebugView(value);
    }

    public FsrDebugView fsrDebugView() {
        return this.controls.fsrDebugView();
    }

    public void setFsrDebugView(FsrDebugView value) {
        this.controls = this.controls.withFsrDebugView(value);
    }

    public DlssRrDebugView rrDebugView() {
        return this.controls.rrDebugView();
    }

    public void setRrDebugView(DlssRrDebugView value) {
        this.controls = this.controls.withRrDebugView(value);
    }

    public boolean rrDebugFullscreen() {
        return this.controls.rrDebugFullscreen();
    }

    public void setRrDebugFullscreen(boolean value) {
        this.controls = this.controls.withRrDebugFullscreen(value);
    }

    public void restoreSessionDefaults() {
        this.controls = SessionControls.defaults();
    }

    public List<String> debugLines() {
        VulkanRenderer activeRenderer = this.renderer;
        return activeRenderer == null ? List.of() : activeRenderer.debugLines();
    }

    /** Executes two production sequences from one frame snapshot and compares their outputs. */
    public CompletableFuture<RenderReplayVerification> verifyReplayProbe(
            int width, int height) {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer == null
                || this.states.current() != RuntimeState.ACTIVE) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Prime renderer is not active"));
        }
        try {
            return activeRenderer.verifyReplayProbe(width, height);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public void invalidateBlocks(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ) {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer != null && this.states.current() != RuntimeState.FAILED) {
            activeRenderer.invalidateBlocks(
                    minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
        }
    }

    public void invalidateAll() {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer != null && this.states.current() != RuntimeState.FAILED) {
            activeRenderer.invalidateAll();
        }
    }

    /** Retires the exact renderer resource epoch observed by the prepare executor. */
    public ResourceReload beginResourceReload() {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer == null) {
            return ResourceReload.inactive();
        }
        return new ResourceReload(activeRenderer, activeRenderer.beginResourceReload());
    }

    /** Applies only the renderer epoch captured by {@link #beginResourceReload()}. */
    public void finishResourceReload(ResourceReload reload, boolean reloadShaders) {
        if (reload == null) {
            throw new NullPointerException("reload");
        }
        if (reload.renderer == null) {
            return;
        }
        reload.renderer.finishResourceReload(reload.rendererReload, reloadShaders);
        if (reloadShaders && this.renderer == reload.renderer) {
            this.requestScreenshot(false);
        }
    }

    /** Reopens the retired owner after a failed or cancelled Minecraft reload. */
    public void abortResourceReload(ResourceReload reload) {
        if (reload == null) {
            throw new NullPointerException("reload");
        }
        if (reload.renderer != null) {
            reload.renderer.abortResourceReload(reload.rendererReload);
        }
    }

    public void shutdown() {
        this.shuttingDown = true;
        this.replayTest = null;
        VulkanRenderer activeRenderer = this.renderer;
        VulkanRenderer failedRenderer = this.retiringRenderer;
        VulkanContext activeContext = this.context;
        this.world = null;
        this.controls = SessionControls.defaults();
        RuntimeException failure = null;
        try {
            if (activeRenderer != null) {
                failure = ResourceCleanup.close(activeRenderer, failure);
                if (this.renderer == activeRenderer) {
                    this.renderer = null;
                }
            }
            if (failedRenderer != null && failedRenderer != activeRenderer) {
                failure = ResourceCleanup.close(failedRenderer, failure);
                if (this.retiringRenderer == failedRenderer) {
                    this.retiringRenderer = null;
                }
            }
            if (activeContext != null) {
                failure = ResourceCleanup.close(activeContext, failure);
                if (this.context == activeContext) {
                    this.context = null;
                }
            }
        } finally {
            this.states.shutdown();
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    public static final class ResourceReload {
        private final VulkanRenderer renderer;
        private final VulkanRenderer.ResourceReload rendererReload;

        private ResourceReload(
                VulkanRenderer renderer,
                VulkanRenderer.ResourceReload rendererReload) {
            this.renderer = renderer;
            this.rendererReload = rendererReload;
        }

        private static ResourceReload inactive() {
            return new ResourceReload(null, null);
        }

        public CompletableFuture<Void> ready() {
            return this.rendererReload == null
                    ? CompletableFuture.completedFuture(null)
                    : this.rendererReload.ready();
        }
    }

    public void fail(Throwable failure) {
        this.failureReason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        this.states.fail();
        this.replayTest = null;
        VulkanRenderer failedRenderer = this.renderer;
        this.world = null;
        this.controls = SessionControls.defaults();
        if (failedRenderer != null) {
            if (this.renderer == failedRenderer) {
                this.renderer = null;
            }
            this.retiringRenderer = failedRenderer;
        }
        PrimeClient.LOGGER.error("Prime ray tracing failed; returning to vanilla rendering", failure);
    }

    private void retireFailedRenderer(Minecraft minecraft) {
        VulkanRenderer failedRenderer = this.retiringRenderer;
        if (failedRenderer == null) {
            return;
        }
        try {
            failedRenderer.close();
            if (this.retiringRenderer == failedRenderer) {
                this.retiringRenderer = null;
            }
            this.restoreVanillaTerrain(minecraft, true);
        } catch (RuntimeException exception) {
            PrimeClient.LOGGER.error("Failed to retire Prime Vulkan resources", exception);
        }
    }

    private void disableRenderer(Minecraft minecraft) {
        VulkanRenderer activeRenderer = this.renderer;
        this.world = null;
        this.states.disabled();
        this.replayTest = null;
        if (activeRenderer != null) {
            this.renderer = null;
            try {
                activeRenderer.close();
                this.restoreVanillaTerrain(minecraft, true);
            } catch (RuntimeException exception) {
                this.retiringRenderer = activeRenderer;
                PrimeClient.LOGGER.error(
                        "Failed to stop Prime after path tracing was disabled", exception);
            }
        } else if (this.retiringRenderer == null) {
            this.restoreVanillaTerrain(minecraft, true);
        }
    }

    private void suspendRenderer(Minecraft minecraft) {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer == null) {
            return;
        }
        this.renderer = null;
        this.world = null;
        try {
            activeRenderer.close();
        } catch (RuntimeException exception) {
            this.retiringRenderer = activeRenderer;
            this.fail(exception);
            return;
        }
        this.restoreVanillaTerrain(minecraft, false);
        this.states.rendererReady();
        this.states.worldAbsent();
    }

    private void acquirePrimeTerrain(Minecraft minecraft) {
        ClientLevel currentWorld = minecraft.level;
        boolean ownershipChanged = !this.primeOwnsTerrain;
        boolean worldChanged = this.terrainWorld != currentWorld;
        this.primeOwnsTerrain = true;
        this.terrainWorld = currentWorld;
        if (currentWorld != null && (ownershipChanged || worldChanged)) {
            rebuildVanillaTerrainShell(minecraft);
        }
        if (ownershipChanged) {
            minecraft.options.broadcastOptions();
        }
    }

    private void restoreVanillaTerrain(Minecraft minecraft, boolean clampDistance) {
        boolean ownershipChanged = this.primeOwnsTerrain;
        this.primeOwnsTerrain = false;
        this.terrainWorld = minecraft.level;
        int configuredDistance = minecraft.options.renderDistance().get();
        if (clampDistance
                && configuredDistance > ViewDistanceLimits.VANILLA_MAXIMUM_RENDER_DISTANCE) {
            minecraft.options.renderDistance().set(
                    ViewDistanceLimits.VANILLA_MAXIMUM_RENDER_DISTANCE);
        }
        if (ownershipChanged && minecraft.level != null) {
            rebuildVanillaTerrainShell(minecraft);
        }
        if (ownershipChanged) {
            minecraft.options.broadcastOptions();
        }
    }

    private static void rebuildVanillaTerrainShell(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        minecraft.levelRenderer.resetLevelRenderData();
        minecraft.levelRenderer.invalidateCompiledGeometry(
                level,
                minecraft.options,
                minecraft.gameRenderer.mainCamera(),
                minecraft.getBlockColors());
    }

    private void showFailureNotificationOnce(Minecraft minecraft) {
        RuntimeState state = this.states.current();
        if (this.notificationShown || state != RuntimeState.UNAVAILABLE && state != RuntimeState.FAILED) {
            return;
        }
        if (minecraft.gui == null) {
            return;
        }
        this.notificationShown = true;
        SystemToast.add(
                minecraft.gui.toastManager(),
                UNAVAILABLE_TOAST,
                Component.literal("Prime ray tracing unavailable"),
                Component.literal(this.failureReason));
    }

    private void finalizeUnavailableReason() {
        if (this.unavailabilityLogged || this.states.current() != RuntimeState.UNAVAILABLE) {
            return;
        }
        if (VulkanBootstrap.snapshot().device() == null) {
            String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
            this.failureReason = "Minecraft is using " + backend + "; select the Vulkan graphics backend";
        }
        this.unavailabilityLogged = true;
        PrimeClient.LOGGER.warn("Prime will use vanilla rendering: {}", this.failureReason);
    }

    private void updateSessionShortcuts(Minecraft minecraft) {
        long window = minecraft.getWindow().handle();
        boolean escape = pressed(window, GLFW.GLFW_KEY_ESCAPE);
        if (escape
                && !this.previousEscape
                && (this.controls.screenshotRequested() || this.screenshotActive())) {
            // Escape is exit-only. Vanilla still receives it and may open the pause screen.
            this.requestScreenshot(false);
        }

        boolean control = pressed(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                || pressed(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean alt = pressed(window, GLFW.GLFW_KEY_LEFT_ALT)
                || pressed(window, GLFW.GLFW_KEY_RIGHT_ALT);
        boolean rrCycle = control && alt && pressed(window, GLFW.GLFW_KEY_F12);
        boolean rrLayout = control && alt && pressed(window, GLFW.GLFW_KEY_F11);
        boolean replayTest =
                control && alt && pressed(window, GLFW.GLFW_KEY_F10);
        if (rrCycle && !this.previousRrCycle) {
            this.setRrDebugView(this.controls.rrDebugView().next());
        }
        if (rrLayout && !this.previousRrLayout) {
            this.setRrDebugFullscreen(!this.controls.rrDebugFullscreen());
        }
        if (replayTest && !this.previousReplayTest
                && (this.replayTest == null || this.replayTest.isDone())) {
            CompletableFuture<RenderReplayVerification> requested =
                    this.verifyReplayProbe(64, 64);
            this.replayTest = requested;
            requested.whenComplete((verification, failure) ->
                    minecraft.execute(() -> this.reportReplayTest(
                            minecraft, requested, verification, failure)));
        }
        this.previousEscape = escape;
        this.previousRrCycle = rrCycle;
        this.previousRrLayout = rrLayout;
        this.previousReplayTest = replayTest;
    }

    private void reportReplayTest(
            Minecraft minecraft,
            CompletableFuture<RenderReplayVerification> request,
            RenderReplayVerification verification,
            Throwable failure) {
        if (this.replayTest != request) {
            return;
        }
        this.replayTest = null;
        boolean passed = failure == null
                && verification != null
                && verification.valid();
        boolean phaseMeasured = verification != null
                && verification.referenceJitterPhase().measurable()
                && verification.replayJitterPhase().measurable();
        if (passed) {
            var fixture = minecraft.gameDirectory
                    .toPath()
                    .resolve("prime/replay/last-success.prseq");
            try {
                RenderReplayFixtureStore.save(
                        fixture, verification.reference());
                PrimeClient.LOGGER.info(
                        "Saved validated Prime replay fixture to {}",
                        fixture.toAbsolutePath().normalize());
            } catch (java.io.IOException | RuntimeException exception) {
                PrimeClient.LOGGER.warn(
                        "Prime replay self-test passed, but its fixture could not be saved",
                        exception);
            }
            PrimeClient.LOGGER.info(
                    "Prime 64x64 deterministic NRD jitter replay self-test passed: {}",
                    verification.referenceJitterPhase());
        } else if (failure != null) {
            PrimeClient.LOGGER.error(
                    "Prime deterministic NRD replay self-test failed",
                    failure);
        } else {
            var replayDirectory = minecraft.gameDirectory
                    .toPath()
                    .resolve("prime/replay");
            var referenceFixture = replayDirectory
                    .resolve("last-failure-reference.prseq");
            var replayFixture = replayDirectory
                    .resolve("last-failure-replay.prseq");
            try {
                RenderReplayFixtureStore.save(
                        referenceFixture, verification.reference());
                RenderReplayFixtureStore.save(
                        replayFixture, verification.replay());
                PrimeClient.LOGGER.info(
                        "Saved failed Prime replay captures to reference={} and replay={}",
                        referenceFixture.toAbsolutePath().normalize(),
                        replayFixture.toAbsolutePath().normalize());
            } catch (java.io.IOException | RuntimeException exception) {
                PrimeClient.LOGGER.warn(
                        "Prime replay self-test failed, and its captures could not be saved",
                        exception);
            }
            PrimeClient.LOGGER.error(
                    "Prime deterministic NRD jitter replay self-test failed: semantic reference={}, semantic replay={}, jitter reference={}, jitter replay={}, first divergence={}",
                    verification.referenceSemantics(),
                    verification.replaySemantics(),
                    verification.referenceJitterPhase(),
                    verification.replayJitterPhase(),
                    verification.determinism().firstMismatch());
        }
        if (minecraft.gui != null) {
            SystemToast.add(
                    minecraft.gui.toastManager(),
                    REPLAY_TEST_TOAST,
                    Component.literal("Prime jitter self-test"),
                    Component.literal(!passed
                            ? "Phase mismatch; see the measured amplitudes in the log"
                            : phaseMeasured
                                    ? "NRD boundary/interior phase matched"
                                    : "Aim at detailed glass to measure phase"));
        }
    }

    private static boolean pressed(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }
}
