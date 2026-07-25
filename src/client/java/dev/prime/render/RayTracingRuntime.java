package dev.prime.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.prime.PrimeClient;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.vulkan.VulkanBootstrap;
import dev.prime.render.vulkan.VulkanCapabilities;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4fc;
import org.lwjgl.glfw.GLFW;

public final class RayTracingRuntime {
    private static final RayTracingRuntime INSTANCE = new RayTracingRuntime();
    private static final SystemToast.SystemToastId UNAVAILABLE_TOAST = new SystemToast.SystemToastId(8_000L);

    private final RuntimeStateMachine states = new RuntimeStateMachine();
    private String failureReason = "Prime has not initialized";
    private boolean initialized;
    private boolean notificationShown;
    private boolean unavailabilityLogged;
    private boolean shuttingDown;
    private boolean previousEscape;
    private boolean previousRrCycle;
    private boolean previousRrLayout;
    private SessionControls controls = SessionControls.defaults();
    // Resource reload preparation may observe the renderer off the client thread. The renderer
    // itself remains client-thread owned; volatile only publishes attachment and detachment.
    private volatile VulkanRenderer renderer;
    // Failure transfers the sole renderer ownership here until the next frame boundary. Closing
    // it inside renderWorld would invalidate resources referenced by that frame's command buffers.
    private VulkanRenderer retiringRenderer;
    private ClientLevel world;

    private RayTracingRuntime() {
    }

    public static RayTracingRuntime instance() {
        return INSTANCE;
    }

    public void initialize() {
        this.initialized = true;
        this.tryInitializeRenderer();
    }

    private void tryInitializeRenderer() {
        if (this.renderer != null || this.shuttingDown || this.states.current() == RuntimeState.FAILED) {
            return;
        }
        VulkanBootstrap.Snapshot bootstrap = VulkanBootstrap.snapshot();
        VulkanCapabilities capabilities = bootstrap.capabilities();
        VulkanDevice device = bootstrap.device();
        if (!capabilities.available() || device == null) {
            this.failureReason = capabilities.unavailableReason();
            this.states.unavailable();
        } else {
            try {
                this.renderer = new VulkanRenderer(device, capabilities);
                this.failureReason = "";
                this.states.rendererReady();
            } catch (RuntimeException exception) {
                this.failureReason = "Unable to initialize Vulkan ray tracing: " + exception.getMessage();
                this.states.fail();
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

    public void beginFrame(Minecraft minecraft) {
        this.retireFailedRenderer();
        if (!this.initialized) {
            return;
        }
        this.updateSessionShortcuts(minecraft);
        this.tryInitializeRenderer();
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

    /** Thread-safe prepare-phase invalidation; GPU ownership remains on the render thread. */
    public void beginResourceReload() {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer != null && this.states.current() != RuntimeState.FAILED) {
            activeRenderer.requestResourceReload();
        }
    }

    /** Applies the completed reload without issuing a second material-atlas invalidation. */
    public void finishResourceReload(boolean reloadShaders) {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer != null && this.states.current() != RuntimeState.FAILED) {
            // Minecraft's initial resource load completes after Prime has already constructed all
            // pipelines from the packaged SPIR-V. Rebuilding those identical pipelines here made
            // every cold start pay the driver compilation cost twice. Later explicit resource
            // reloads still replace every pipeline before rendering resumes.
            if (reloadShaders) {
                this.requestScreenshot(false);
                activeRenderer.requestShaderReload();
            }
            activeRenderer.invalidateAll();
        }
    }

    public void shutdown() {
        this.shuttingDown = true;
        VulkanRenderer activeRenderer = this.renderer;
        VulkanRenderer failedRenderer = this.retiringRenderer;
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
        } finally {
            this.states.shutdown();
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    public void fail(Throwable failure) {
        this.failureReason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        this.states.fail();
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

    private void retireFailedRenderer() {
        VulkanRenderer failedRenderer = this.retiringRenderer;
        if (failedRenderer == null) {
            return;
        }
        try {
            failedRenderer.close();
            if (this.retiringRenderer == failedRenderer) {
                this.retiringRenderer = null;
            }
        } catch (RuntimeException exception) {
            PrimeClient.LOGGER.error("Failed to retire Prime Vulkan resources", exception);
        }
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
        if (rrCycle && !this.previousRrCycle) {
            this.setRrDebugView(this.controls.rrDebugView().next());
        }
        if (rrLayout && !this.previousRrLayout) {
            this.setRrDebugFullscreen(!this.controls.rrDebugFullscreen());
        }
        this.previousEscape = escape;
        this.previousRrCycle = rrCycle;
        this.previousRrLayout = rrLayout;
    }

    private static boolean pressed(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }
}
