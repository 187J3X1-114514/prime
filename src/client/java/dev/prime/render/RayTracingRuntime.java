package dev.prime.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.prime.PrimeClient;
import dev.prime.render.vulkan.VulkanBootstrap;
import dev.prime.render.vulkan.VulkanCapabilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4fc;

public final class RayTracingRuntime {
    private static final RayTracingRuntime INSTANCE = new RayTracingRuntime();
    private static final SystemToast.SystemToastId UNAVAILABLE_TOAST = new SystemToast.SystemToastId(8_000L);

    private final RuntimeStateMachine states = new RuntimeStateMachine();
    private String failureReason = "Prime has not initialized";
    private boolean initialized;
    private boolean notificationShown;
    private boolean unavailabilityLogged;
    private boolean shuttingDown;
    private volatile VulkanRenderer renderer;
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
        VulkanCapabilities capabilities = VulkanBootstrap.capabilities();
        VulkanDevice device = VulkanBootstrap.device();
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
        if (!this.initialized) {
            return;
        }
        this.tryInitializeRenderer();
        this.finalizeUnavailableReason();
        this.showFailureNotificationOnce(minecraft);
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
            activeRenderer.beginFrame(minecraft);
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
        if (activeRenderer != null) {
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

    public void invalidateBlocks(
            int minimumX,
            int minimumY,
            int minimumZ,
            int maximumX,
            int maximumY,
            int maximumZ) {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer != null) {
            activeRenderer.invalidateBlocks(
                    minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
        }
    }

    public void invalidateAll() {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer != null) {
            activeRenderer.invalidateAll();
        }
    }

    /** Thread-safe prepare-phase invalidation; GPU ownership remains on the render thread. */
    public void beginResourceReload() {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer != null) {
            activeRenderer.requestResourceReload();
        }
    }

    /** Applies the completed reload without issuing a second material-atlas invalidation. */
    public void finishResourceReload(boolean reloadShaders) {
        VulkanRenderer activeRenderer = this.renderer;
        if (activeRenderer != null) {
            // Minecraft's initial resource load completes after Prime has already constructed all
            // pipelines from the packaged SPIR-V. Rebuilding those identical pipelines here made
            // every cold start pay the driver compilation cost twice. Later explicit resource
            // reloads still replace every pipeline before rendering resumes.
            if (reloadShaders) {
                activeRenderer.requestShaderReload();
            }
            activeRenderer.invalidateAll();
        }
    }

    public void shutdown() {
        this.shuttingDown = true;
        VulkanRenderer activeRenderer = this.renderer;
        this.renderer = null;
        this.world = null;
        if (activeRenderer != null) {
            activeRenderer.close();
        }
        this.states.shutdown();
    }

    public void fail(Throwable failure) {
        this.failureReason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        this.states.fail();
        PrimeClient.LOGGER.error("Prime ray tracing failed; returning to vanilla rendering", failure);
        VulkanRenderer failedRenderer = this.renderer;
        this.renderer = null;
        this.world = null;
        if (failedRenderer != null) {
            try {
                failedRenderer.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
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
        if (VulkanBootstrap.device() == null) {
            String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
            this.failureReason = "Minecraft is using " + backend + "; select the Vulkan graphics backend";
        }
        this.unavailabilityLogged = true;
        PrimeClient.LOGGER.warn("Prime will use vanilla rendering: {}", this.failureReason);
    }
}
