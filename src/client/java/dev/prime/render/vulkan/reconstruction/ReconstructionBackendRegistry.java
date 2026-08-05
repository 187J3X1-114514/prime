package dev.prime.render.vulkan.reconstruction;

import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.dlss.DlssRrBootstrap;
import dev.prime.render.vulkan.dlss.DlssRrNative;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Render-thread-owned closed registry for Prime's built-in reconstruction products. */
public final class ReconstructionBackendRegistry {
    private final VulkanContext context;
    private final Map<PostProcessingMode, ReconstructionBackend> backends;
    private final FailureReporter failureReporter;
    private boolean dlssFallbackReported;

    public ReconstructionBackendRegistry(
            VulkanContext context, DlssRrNative.Context ngxContext) {
        this(context, builtIns(ngxContext), new DefaultFailureReporter());
    }

    ReconstructionBackendRegistry(
            VulkanContext context,
            Map<PostProcessingMode, ReconstructionBackend> backends,
            FailureReporter failureReporter) {
        this.context = Objects.requireNonNull(context, "context");
        EnumMap<PostProcessingMode, ReconstructionBackend> copy =
                new EnumMap<>(PostProcessingMode.class);
        copy.putAll(backends);
        for (PostProcessingMode mode : PostProcessingMode.values()) {
            ReconstructionBackend backend = copy.get(mode);
            if (backend == null || backend.mode() != mode) {
                throw new IllegalArgumentException(
                        "Missing or mismatched built-in reconstruction backend " + mode);
            }
        }
        this.backends = Map.copyOf(copy);
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    ReconstructionBackendRegistry(
            Map<PostProcessingMode, ReconstructionBackend> backends,
            FailureReporter failureReporter) {
        this.context = null;
        EnumMap<PostProcessingMode, ReconstructionBackend> copy =
                new EnumMap<>(PostProcessingMode.class);
        copy.putAll(backends);
        for (PostProcessingMode mode : PostProcessingMode.values()) {
            ReconstructionBackend backend = copy.get(mode);
            if (backend == null || backend.mode() != mode) {
                throw new IllegalArgumentException(
                        "Missing or mismatched built-in reconstruction backend " + mode);
            }
        }
        this.backends = Map.copyOf(copy);
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    public ResolvedReconstruction resolve(
            PostProcessingMode requestedMode,
            ReconstructionQualityMode quality,
            int displayWidth,
            int displayHeight) {
        Objects.requireNonNull(requestedMode, "requestedMode");
        Objects.requireNonNull(quality, "quality");
        ReconstructionExtent display = new ReconstructionExtent(displayWidth, displayHeight);
        ReconstructionBackend requested = this.backends.get(requestedMode);
        ReconstructionBackend.Capability capability = requested.capability();
        if (!capability.available()) {
            return this.fallback(
                    requestedMode,
                    quality,
                    display,
                    requested,
                    capability.unavailableReason(),
                    null);
        }
        try {
            ReconstructionExtent render = requested.renderExtent(
                    quality, displayWidth, displayHeight);
            return new ResolvedReconstruction(
                    requestedMode,
                    requestedMode,
                    quality,
                    render,
                    display,
                    requested,
                    java.util.Optional.empty());
        } catch (RuntimeException exception) {
            return this.fallback(
                    requestedMode,
                    quality,
                    display,
                    requested,
                    "optimal-size query failed",
                    exception);
        }
    }

    public VulkanReconstructionResources createResources(
            AtmospherePipeline atmosphere, ResolvedReconstruction selection) {
        if (this.context == null) {
            throw new IllegalStateException(
                    "The selection-only reconstruction registry cannot create Vulkan resources");
        }
        VulkanImage output = null;
        VulkanImage stableRadiance = null;
        try {
            output = this.context.createOutputImage(
                    selection.displayExtent().width(), selection.displayExtent().height());
            stableRadiance = this.context.createAccumulationImage(
                    selection.extent().width(), selection.extent().height());
        } catch (RuntimeException exception) {
            VulkanReconstructionResources.destroy(stableRadiance, exception);
            VulkanReconstructionResources.destroy(output, exception);
            throw exception;
        }

        try {
            VulkanReconstructionProcessor processor = selection.backend().create(
                    new ReconstructionBackend.CreateInput(
                            this.context,
                            atmosphere,
                            stableRadiance,
                            output,
                            selection));
            return new VulkanReconstructionResources(
                    output, stableRadiance, processor, selection);
        } catch (RuntimeException exception) {
            RuntimeException failure = VulkanReconstructionResources.destroy(
                    stableRadiance, exception);
            failure = VulkanReconstructionResources.destroy(output, failure);
            if (selection.backend().fallbackMode() == null) {
                throw failure;
            }
            ResolvedReconstruction fallback = this.recoverCreationFailure(
                    selection, exception);
            return this.createResources(atmosphere, fallback);
        }
    }

    ResolvedReconstruction recoverCreationFailure(
            ResolvedReconstruction selection, RuntimeException exception) {
        return this.fallback(
                selection.requestedMode(),
                selection.quality(),
                selection.displayExtent(),
                selection.backend(),
                "feature creation failed",
                exception);
    }

    private ResolvedReconstruction fallback(
            PostProcessingMode requestedMode,
            ReconstructionQualityMode quality,
            ReconstructionExtent display,
            ReconstructionBackend failed,
            String reason,
            RuntimeException exception) {
        PostProcessingMode fallbackMode = failed.fallbackMode();
        if (fallbackMode == null) {
            if (exception != null) {
                throw exception;
            }
            throw new IllegalStateException(
                    failed.mode() + " reconstruction backend is unavailable: " + reason);
        }
        if (!this.dlssFallbackReported) {
            if (exception != null) {
                this.failureReporter.failed(reason, exception);
            } else {
                this.failureReporter.unavailable(reason);
            }
        }
        this.dlssFallbackReported = true;
        ReconstructionBackend fallback = this.backends.get(fallbackMode);
        ReconstructionBackend.Capability capability = fallback.capability();
        if (!capability.available()) {
            throw new IllegalStateException(
                    fallbackMode + " fallback is unavailable: " + capability.unavailableReason());
        }
        ReconstructionExtent render = fallback.renderExtent(
                quality, display.width(), display.height());
        return new ResolvedReconstruction(
                requestedMode,
                fallbackMode,
                quality,
                render,
                display,
                fallback,
                java.util.Optional.of(reason));
    }

    private static Map<PostProcessingMode, ReconstructionBackend> builtIns(
            DlssRrNative.Context ngxContext) {
        EnumMap<PostProcessingMode, ReconstructionBackend> values =
                new EnumMap<>(PostProcessingMode.class);
        values.put(PostProcessingMode.NRD_FSR, new NrdFsrBackend());
        values.put(PostProcessingMode.DLSS_RR, new DlssRrBackend(ngxContext));
        values.put(PostProcessingMode.DISABLED, new NoisyBackend());
        return values;
    }

    interface FailureReporter {
        void unavailable(String reason);

        void failed(String operation, RuntimeException exception);
    }

    private static final class DefaultFailureReporter implements FailureReporter {
        @Override
        public void unavailable(String reason) {
            PrimeInfo.LOGGER.warn(
                    "DLSS RR selected but unavailable; using NRD-FSR for this session: {}",
                    reason);
        }

        @Override
        public void failed(String operation, RuntimeException exception) {
            DlssRrBootstrap.failSession(
                    "DLSS RR " + operation + "; using NRD-FSR", exception);
        }
    }
}
