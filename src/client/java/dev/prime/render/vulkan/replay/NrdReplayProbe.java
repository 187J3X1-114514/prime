package dev.prime.render.vulkan.replay;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.FrameCamera;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.SunDirection;
import dev.prime.render.replay.CapturedRenderStage;
import dev.prime.render.replay.NrdPreparationReplayInput;
import dev.prime.render.replay.RayTraceReplayInput;
import dev.prime.render.replay.RenderBinaryFingerprint;
import dev.prime.render.replay.RenderPlatformFingerprint;
import dev.prime.render.replay.RenderReplayCapture;
import dev.prime.render.replay.RenderReplaySequence;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.nrd.NrdDenoiser;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import dev.prime.render.vulkan.nrd.NrdFrameHistory;
import dev.prime.render.vulkan.nrd.NrdFrameInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/**
 * Explicit low-resolution execution of the production wavefront-to-NRD path.
 *
 * <p>It owns independent reconstruction history and never aliases interactive frame resources.
 */
public final class NrdReplayProbe implements Destroyable {
    private final VulkanContext context;
    private final VulkanImage output;
    private final VulkanImage stableRadiance;
    private final NrdDenoiser denoiser;
    private final NrdFrameHistory nrdHistory = new NrdFrameHistory();
    private PlannedFrame planned;
    private RecordedFrame pending;
    private boolean finished;
    private boolean destroyed;

    private NrdReplayProbe(
            VulkanContext context,
            VulkanImage output,
            VulkanImage stableRadiance,
            NrdDenoiser denoiser) {
        this.context = context;
        this.output = output;
        this.stableRadiance = stableRadiance;
        this.denoiser = denoiser;
    }

    public static NrdReplayProbe create(
            VulkanContext context,
            AtmospherePipeline atmosphere,
            int width,
            int height) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(atmosphere, "atmosphere");
        VulkanImage output = null;
        VulkanImage stableRadiance = null;
        NrdDenoiser denoiser = null;
        try {
            output = context.createImage2D(
                    width,
                    height,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT
                            | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime replay-probe NRD output");
            stableRadiance = context.createAccumulationImage(width, height);
            denoiser = NrdDenoiser.create(
                    context,
                    width,
                    height,
                    output,
                    stableRadiance,
                    atmosphere);
            return new NrdReplayProbe(
                    context, output, stableRadiance, denoiser);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(denoiser, exception);
            ResourceCleanup.destroy(stableRadiance, exception);
            ResourceCleanup.destroy(output, exception);
            throw exception;
        }
    }

    public RawWavefrontFrame rawFrame() {
        requireOpen();
        return this.denoiser.rawFrame();
    }

    public VulkanImage stableRadiance() {
        requireOpen();
        return this.stableRadiance;
    }

    public void prepareForTrace(VkCommandBuffer commandBuffer) {
        requireOpen();
        prepareCompositeImages(commandBuffer);
        this.denoiser.prepareForRayTrace(commandBuffer);
    }

    public PlannedFrame planFrame(
            FrameCamera camera,
            long frameTimeNanos,
            long sceneRevision,
            long textureRevision,
            SunDirection sunDirection,
            float cameraJitterX,
            float cameraJitterY,
            boolean forceRestart) {
        requireOpen();
        if (this.finished) {
            throw new IllegalStateException(
                    "Replay-probe sequence is already finished");
        }
        if (this.pending != null) {
            throw new IllegalStateException(
                    "Replay-probe frame must be submitted before recording another");
        }
        if (this.planned != null) {
            throw new IllegalStateException(
                    "Replay-probe frame was already planned");
        }
        PlannedFrame result = new PlannedFrame(
                this,
                this.nrdHistory.plan(new NrdFrameInput(
                        camera,
                        frameTimeNanos,
                        sceneRevision,
                        textureRevision,
                        sunDirection,
                        cameraJitterX,
                        cameraJitterY,
                        forceRestart,
                        NrdDiagnostics.Mode.OFF)));
        this.planned = result;
        return result;
    }

    public RecordedFrame recordAfterTrace(
            VkCommandBuffer commandBuffer,
            PlannedFrame frame,
            float sunRadianceMultiplier,
            float displayOverexposure) {
        requireOpen();
        if (frame == null
                || frame.owner != this
                || frame != this.planned
                || frame.recorded) {
            throw new IllegalArgumentException(
                    "Replay-probe plan does not belong to this recording");
        }
        frame.recorded = true;
        ReplayStageCapturePass rawCapture =
                ReplayStageCapturePass.createRaw(
                        this.context, this.denoiser.rawFrame());
        ReplayStageCapturePass preparedCapture = null;
        NrdDenoiser.FrameToken reconstruction = null;
        try {
            rawCapture.recordAfterRayTrace(commandBuffer);
            NrdDenoiser.PreparedFrame prepared =
                    this.denoiser.prepareInputs(
                            commandBuffer, frame.denoiserPlan);
            NrdPreparationReplayInput preparationInput =
                    NrdPreparationReplayInput.capture(
                            frame.denoiserPlan.plan());
            preparedCapture = ReplayStageCapturePass.createPreparedNrd(
                    this.context, prepared.inputs());
            preparedCapture.recordAfterCompute(commandBuffer);
            reconstruction = this.denoiser.recordReconstruction(
                    commandBuffer,
                    prepared,
                    sunRadianceMultiplier,
                    displayOverexposure);
            RecordedFrame recorded = new RecordedFrame(
                    this,
                    rawCapture,
                    preparedCapture,
                    reconstruction,
                    preparationInput);
            this.planned = null;
            this.pending = recorded;
            return recorded;
        } catch (RuntimeException exception) {
            this.planned = null;
            ResourceCleanup.destroy(preparedCapture, exception);
            ResourceCleanup.destroy(rawCapture, exception);
            throw exception;
        }
    }

    public CompletableFuture<RenderReplayCapture> submitted(
            RecordedFrame recorded,
            RenderPlatformFingerprint platform,
            RenderBinaryFingerprint binary,
            RayTraceReplayInput frame) {
        requireOpen();
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(binary, "binary");
        Objects.requireNonNull(frame, "frame");
        if (recorded.owner != this
                || recorded != this.pending
                || recorded.submitted) {
            throw new IllegalArgumentException(
                    "Replay-probe frame does not belong to this submission");
        }
        recorded.submitted = true;
        this.pending = null;
        this.nrdHistory.submitted(
                this.denoiser.submitted(recorded.reconstruction));
        CompletableFuture<CapturedRenderStage> raw =
                recorded.rawCapture.submitted();
        CompletableFuture<CapturedRenderStage> prepared =
                recorded.preparedCapture.submitted();
        return raw.thenCombine(
                prepared,
                (rawStage, preparedStage) -> new RenderReplayCapture(
                        platform,
                        binary,
                        frame,
                        recorded.preparationInput,
                        rawStage,
                        preparedStage));
    }

    /**
     * Seals the frame list and retires all probe resources after every submitted readback.
     */
    public CompletableFuture<RenderReplaySequence> finish(
            List<CompletableFuture<RenderReplayCapture>> frames) {
        requireOpen();
        Objects.requireNonNull(frames, "frames");
        if (this.finished
                || this.planned != null
                || this.pending != null
                || frames.isEmpty()) {
            throw new IllegalStateException(
                    "Replay-probe sequence cannot be finished in its current state");
        }
        this.finished = true;
        List<CompletableFuture<RenderReplayCapture>> submittedFrames =
                List.copyOf(frames);
        CompletableFuture<?>[] completions =
                submittedFrames.toArray(CompletableFuture[]::new);
        CompletableFuture<RenderReplaySequence> combined =
                CompletableFuture.allOf(completions)
                        .thenApply(ignored -> {
                            ArrayList<RenderReplayCapture> captures =
                                    new ArrayList<>(submittedFrames.size());
                            for (CompletableFuture<RenderReplayCapture> frame
                                    : submittedFrames) {
                                captures.add(frame.join());
                            }
                            return new RenderReplaySequence(captures);
                        });
        CompletableFuture<RenderReplaySequence> result =
                new CompletableFuture<>();
        combined.whenComplete((sequence, failure) -> {
            RuntimeException closeFailure = null;
            try {
                destroy();
            } catch (RuntimeException exception) {
                closeFailure = exception;
            }
            if (failure != null) {
                if (closeFailure != null) {
                    failure.addSuppressed(closeFailure);
                }
                result.completeExceptionally(failure);
            } else if (closeFailure != null) {
                result.completeExceptionally(closeFailure);
            } else {
                result.complete(sequence);
            }
        });
        return result;
    }

    /**
     * Retires a partially submitted sequence before reporting its recording failure.
     */
    public CompletableFuture<RenderReplaySequence> abort(
            List<CompletableFuture<RenderReplayCapture>> submittedFrames,
            RuntimeException failure) {
        requireOpen();
        Objects.requireNonNull(submittedFrames, "submittedFrames");
        Objects.requireNonNull(failure, "failure");
        if (this.finished) {
            throw new IllegalStateException(
                    "Replay-probe sequence is already finished");
        }
        this.finished = true;
        List<CompletableFuture<RenderReplayCapture>> frames =
                List.copyOf(submittedFrames);
        CompletableFuture<?>[] completions =
                frames.toArray(CompletableFuture[]::new);
        CompletableFuture<RenderReplaySequence> result =
                new CompletableFuture<>();
        CompletableFuture.allOf(completions)
                .whenComplete((ignored, readbackFailure) -> {
                    if (readbackFailure != null) {
                        failure.addSuppressed(readbackFailure);
                    }
                    try {
                        destroy();
                    } catch (RuntimeException exception) {
                        failure.addSuppressed(exception);
                    }
                    result.completeExceptionally(failure);
                });
        return result;
    }

    private void prepareCompositeImages(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage[] images = {this.output, this.stableRadiance};
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(images.length, stack);
            for (int index = 0; index < images.length; index++) {
                VulkanImage image = images[index];
                boolean initialized = image.initialized();
                barriers.get(index)
                        .sType$Default()
                        .srcStageMask(initialized
                                ? VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                                : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                        .srcAccessMask(initialized
                                ? VK12.VK_ACCESS_SHADER_READ_BIT
                                        | VK12.VK_ACCESS_SHADER_WRITE_BIT
                                : 0L)
                        .dstStageMask(
                                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                        .dstAccessMask(
                                VK12.VK_ACCESS_SHADER_READ_BIT
                                        | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                        .oldLayout(initialized
                                ? VK12.VK_IMAGE_LAYOUT_GENERAL
                                : VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .image(image.image());
                barriers.get(index)
                        .subresourceRange()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);
                image.markInitialized();
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack)
                            .sType$Default()
                            .pImageMemoryBarriers(barriers));
        }
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("Replay probe is destroyed");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        RuntimeException failure = null;
        RecordedFrame abandoned = this.pending;
        this.planned = null;
        this.pending = null;
        if (abandoned != null) {
            failure = ResourceCleanup.destroy(
                    abandoned.preparedCapture, failure);
            failure = ResourceCleanup.destroy(
                    abandoned.rawCapture, failure);
        }
        failure = ResourceCleanup.destroy(this.denoiser, failure);
        failure = ResourceCleanup.destroy(this.stableRadiance, failure);
        failure = ResourceCleanup.destroy(this.output, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }

    public static final class RecordedFrame {
        private final NrdReplayProbe owner;
        private final ReplayStageCapturePass rawCapture;
        private final ReplayStageCapturePass preparedCapture;
        private final NrdDenoiser.FrameToken reconstruction;
        private final NrdPreparationReplayInput preparationInput;
        private boolean submitted;

        private RecordedFrame(
                NrdReplayProbe owner,
                ReplayStageCapturePass rawCapture,
                ReplayStageCapturePass preparedCapture,
                NrdDenoiser.FrameToken reconstruction,
                NrdPreparationReplayInput preparationInput) {
            this.owner = owner;
            this.rawCapture = rawCapture;
            this.preparedCapture = preparedCapture;
            this.reconstruction = reconstruction;
            this.preparationInput = preparationInput;
        }
    }

    public static final class PlannedFrame {
        private final NrdReplayProbe owner;
        private final NrdFrameHistory.PlannedFrame denoiserPlan;
        private boolean recorded;

        private PlannedFrame(
                NrdReplayProbe owner,
                NrdFrameHistory.PlannedFrame denoiserPlan) {
            this.owner = owner;
            this.denoiserPlan = denoiserPlan;
        }
    }
}
