package dev.prime.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import dev.prime.render.RendererSettings;
import dev.prime.render.LightSamplingDiagnostic;
import dev.prime.render.PrimaryLightDiagnosticView;
import dev.prime.render.runtime.RendererFrameSettings;
import dev.prime.render.runtime.RendererLifecycle;
import dev.prime.render.runtime.RuntimeDiagnostics;
import dev.prime.render.runtime.RuntimeState;
import dev.prime.render.runtime.SessionController;
import dev.prime.render.runtime.SessionControls;
import dev.prime.render.runtime.TerrainOwnership;
import dev.prime.render.runtime.VulkanRenderer;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.scene.vanilla.DynamicSceneFrame;
import dev.prime.render.post.nrd.NrdDiagnostics;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4fc;
import org.lwjgl.glfw.GLFW;

public final class PrimeRuntime {
    private static final PrimeRuntime INSTANCE = new PrimeRuntime();
    private final RendererLifecycle lifecycle = new RendererLifecycle();
    private final RendererFrameSettings frameSettings = new RendererFrameSettings();
    private final TerrainOwnership terrain = new TerrainOwnership();
    private final SessionController session = new SessionController();
    private final RuntimeDiagnostics diagnostics = new RuntimeDiagnostics();

    private PrimeRuntime() {
    }

    public static PrimeRuntime instance() {
        return INSTANCE;
    }

    public void initialize(RendererSettings settings) {
        this.lifecycle.initialize(settings);
        this.frameSettings.beginFrame(settings);
    }

    public RuntimeState state() {
        return this.lifecycle.state();
    }

    public boolean shouldReplaceWorld() {
        return this.lifecycle.state() == RuntimeState.ACTIVE;
    }

    public boolean shouldMaintainVanillaTerrain() {
        return !this.terrain.primeOwned();
    }

    public int vanillaTerrainDistance(int configuredDistance) {
        return this.terrain.vanillaDistance(configuredDistance);
    }

    public boolean shouldCaptureDynamicScene() {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        return activeRenderer != null
                && this.lifecycle.state() == RuntimeState.ACTIVE
                && !activeRenderer.screenshotActive();
    }

    public void beginFrame(Minecraft minecraft, RendererSettings settings) {
        this.frameSettings.beginFrame(settings);
        this.lifecycle.retireFailed(minecraft, this.terrain);
        if (!this.lifecycle.initialized()) {
            return;
        }
        this.updateSessionShortcuts(minecraft);
        if (!settings.pathTracingEnabled()) {
            this.lifecycle.disable(minecraft, this.terrain);
            return;
        }
        if ((minecraft.level == null || minecraft.player == null)
                && this.lifecycle.renderer() != null) {
            this.lifecycle.suspend(minecraft, this.terrain);
        }
        this.lifecycle.tryInitialize(minecraft, this.terrain);
        this.lifecycle.setFailureReason(this.diagnostics.finalizeUnavailableReason(
                this.lifecycle.failureReason(), this.lifecycle.state()));
        this.diagnostics.showFailureOnce(
                minecraft, this.lifecycle.state(), this.lifecycle.failureReason());
        if (this.lifecycle.state() == RuntimeState.FAILED) {
            return;
        }
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        if (activeRenderer == null) {
            return;
        }
        try {
            ClientLevel currentWorld = minecraft.level;
            this.terrain.acquire(minecraft);
            SessionControls frameControls = this.session.controls();
            boolean screenshotRequested = activeRenderer.beginFrame(
                    minecraft, frameControls, settings);
            if (screenshotRequested != frameControls.screenshotRequested()) {
                this.session.requestScreenshot(screenshotRequested);
            }
            this.lifecycle.observeWorld(
                    currentWorld,
                    currentWorld != null
                            && minecraft.player != null
                            && activeRenderer.isReady());
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
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        RendererSettings settings = this.frameSettings.forCamera();
        if (activeRenderer != null
                && settings != null
                && this.lifecycle.state() != RuntimeState.FAILED) {
            activeRenderer.captureCamera(
                    renderedProjection,
                    baseProjection,
                    viewRotation,
                    x,
                    y,
                    z,
                    sunAngleRadians,
                    settings);
        }
    }

    public void renderWorld(RenderTarget mainTarget) {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        RendererSettings settings = this.frameSettings.forRender();
        if (activeRenderer == null
                || settings == null
                || this.lifecycle.state() != RuntimeState.ACTIVE) {
            return;
        }
        try {
            activeRenderer.render(mainTarget, settings);
        } catch (RuntimeException exception) {
            this.fail(exception);
        }
    }

    public void captureDynamicScene(DynamicSceneFrame frame) {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        if (activeRenderer == null
                || this.lifecycle.state() != RuntimeState.ACTIVE
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
        this.requestScreenshot(!this.session.controls().screenshotRequested());
        return true;
    }

    public boolean screenshotRequested() {
        return this.session.controls().screenshotRequested();
    }

    public void pathTracingChanged(boolean enabled) {
        if (!enabled) {
            this.requestScreenshot(false);
        } else if (this.lifecycle.state() == RuntimeState.DISABLED) {
            // Enabling after an explicit stop is a fresh initialization attempt.
            this.diagnostics.resetAvailabilityNotifications();
        }
    }

    public void voxelTextureSurfacesChanged(boolean enabled) {
        this.invalidateAll();
    }

    public void voxelTextureSurfaceStrengthChanged(boolean enabled, int strengthSteps) {
        dev.prime.render.terrain.VoxelSurfaceSettings.maximumHeight(strengthSteps);
        if (enabled) {
            this.invalidateAll();
        }
    }

    public void requestScreenshot(boolean enabled) {
        this.session.requestScreenshot(enabled);
    }

    public boolean screenshotActive() {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        return activeRenderer != null && activeRenderer.screenshotActive();
    }

    public boolean triangleDebug() {
        return this.session.controls().triangleDebug();
    }

    public void setTriangleDebug(boolean value) {
        this.session.setTriangleDebug(value);
    }

    public boolean rendererDiagnostics() {
        return this.session.controls().rendererDiagnostics();
    }

    public void setRendererDiagnostics(boolean value) {
        this.session.setRendererDiagnostics(value);
    }

    public LightSamplingDiagnostic lightSamplingDiagnostic() {
        return this.session.controls().lightSamplingDiagnostic();
    }

    public void setLightSamplingDiagnostic(LightSamplingDiagnostic value) {
        this.session.setLightSamplingDiagnostic(value);
        this.requestRealtimeReset();
    }

    public PrimaryLightDiagnosticView primaryLightDiagnosticView() {
        return this.session.controls().primaryLightDiagnosticView();
    }

    public void setPrimaryLightDiagnosticView(PrimaryLightDiagnosticView value) {
        this.session.setPrimaryLightDiagnosticView(value);
        this.requestRealtimeReset();
    }

    public NrdDiagnostics.Mode nrdDebugView() {
        return this.session.controls().nrdDebugView();
    }

    public void setNrdDebugView(NrdDiagnostics.Mode value) {
        this.session.setNrdDebugView(value);
    }

    public FsrDebugView fsrDebugView() {
        return this.session.controls().fsrDebugView();
    }

    public void setFsrDebugView(FsrDebugView value) {
        this.session.setFsrDebugView(value);
    }

    public DlssRrDebugView rrDebugView() {
        return this.session.controls().rrDebugView();
    }

    public void setRrDebugView(DlssRrDebugView value) {
        this.session.setRrDebugView(value);
    }

    public boolean rrDebugFullscreen() {
        return this.session.controls().rrDebugFullscreen();
    }

    public void setRrDebugFullscreen(boolean value) {
        this.session.setRrDebugFullscreen(value);
    }

    public void restoreSessionDefaults() {
        this.session.restoreDefaults();
    }

    public List<String> debugLines() {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        return activeRenderer == null ? List.of() : activeRenderer.debugLines();
    }

    public void invalidateBlocks(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ) {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        if (activeRenderer != null && this.lifecycle.state() != RuntimeState.FAILED) {
            activeRenderer.invalidateBlocks(
                    minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
        }
    }

    public void invalidateAll() {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        if (activeRenderer != null && this.lifecycle.state() != RuntimeState.FAILED) {
            activeRenderer.invalidateAll();
        }
    }

    private void requestRealtimeReset() {
        VulkanRenderer activeRenderer = this.lifecycle.renderer();
        if (activeRenderer != null && this.lifecycle.state() != RuntimeState.FAILED) {
            activeRenderer.requestRealtimeReset();
        }
    }

    /** Retires the exact renderer resource epoch observed by the prepare executor. */
    public RendererLifecycle.ResourceReload beginResourceReload() {
        return this.lifecycle.beginResourceReload();
    }

    /** Applies only the renderer epoch captured by {@link #beginResourceReload()}. */
    public void finishResourceReload(
            RendererLifecycle.ResourceReload reload, boolean reloadShaders) {
        if (this.lifecycle.finishResourceReload(reload, reloadShaders)) {
            this.requestScreenshot(false);
        }
    }

    /** Reopens the retired owner after a failed or cancelled Minecraft reload. */
    public void abortResourceReload(RendererLifecycle.ResourceReload reload) {
        this.lifecycle.abortResourceReload(reload);
    }

    public void shutdown() {
        this.session.restoreDefaults();
        this.frameSettings.clear();
        this.lifecycle.shutdown();
    }

    public void fail(Throwable failure) {
        this.lifecycle.fail(failure);
        this.session.restoreDefaults();
    }

    /** Prevents reuse of histories advanced into a host submission that later failed. */
    public void minecraftHostSubmissionFailed(RuntimeException failure) {
        if (this.lifecycle.renderer() != null) {
            fail(failure);
        }
    }

    private void updateSessionShortcuts(Minecraft minecraft) {
        long window = minecraft.getWindow().handle();
        boolean escape = pressed(window, GLFW.GLFW_KEY_ESCAPE);
        boolean control = pressed(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                || pressed(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean alt = pressed(window, GLFW.GLFW_KEY_LEFT_ALT)
                || pressed(window, GLFW.GLFW_KEY_RIGHT_ALT);
        boolean rrCycle = control && alt && pressed(window, GLFW.GLFW_KEY_F12);
        boolean rrLayout = control && alt && pressed(window, GLFW.GLFW_KEY_F11);
        this.session.update(
                new SessionController.KeyState(escape, rrCycle, rrLayout),
                this.screenshotActive());
    }

    private static boolean pressed(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }
}
