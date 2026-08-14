package dev.prime.render.vulkan;

import dev.prime.render.ResourceCleanup;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionFrame;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubmittedFrame;
import dev.prime.render.vulkan.fsr.Fsr3Upscaler;
import dev.prime.render.vulkan.nrd.NrdDenoiser;
import dev.prime.render.post.nrd.NrdDiagnostics;
import dev.prime.render.post.nrd.NrdFrameHistory;
import dev.prime.render.post.nrd.NrdFrameInput;
import dev.prime.render.post.nrd.NrdFramePlan;
import dev.prime.render.vulkan.reconstruction.ReconstructionDebugSettings;
import dev.prime.render.vulkan.reconstruction.VulkanReconstructionProcessor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/** Existing REBLUR/SIGMA + FidelityFX FSR 3.1.4 implementation of the shared boundary. */
public final class NrdFsrPostProcessor implements VulkanReconstructionProcessor {
    private final VulkanContext context;
    private final ReconstructionQualityMode quality;
    private final int renderWidth;
    private final int renderHeight;
    private final int displayWidth;
    private final int displayHeight;
    private final VulkanImage sceneColor;
    private final NrdDenoiser denoiser;
    private final NrdFrameHistory nrdHistory = new NrdFrameHistory();
    private final Fsr3Upscaler upscaler;
    private final VulkanImage displayOutput;
    private NativeDebugPresentPass nrdDebugPresent;
    private boolean destroyed;

    private NrdFsrPostProcessor(
            VulkanContext context,
            ReconstructionQualityMode quality,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            VulkanImage sceneColor,
            NrdDenoiser denoiser,
            Fsr3Upscaler upscaler,
            VulkanImage displayOutput) {
        this.context = context;
        this.quality = quality;
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.sceneColor = sceneColor;
        this.denoiser = denoiser;
        this.upscaler = upscaler;
        this.displayOutput = displayOutput;
    }

    public static NrdFsrPostProcessor create(
            VulkanContext context,
            AtmospherePipeline atmosphere,
            VulkanImage accumulation,
            VulkanImage displayOutput,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            ReconstructionQualityMode quality) {
        VulkanImage sceneColor = null;
        NrdDenoiser denoiser = null;
        Fsr3Upscaler upscaler = null;
        try {
            sceneColor = context.createImage2D(
                    renderWidth,
                    renderHeight,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                    "Prime NRD-FSR linear HDR scene color");
            denoiser = NrdDenoiser.create(
                    context, renderWidth, renderHeight, sceneColor, accumulation, atmosphere);
            upscaler = Fsr3Upscaler.create(
                    context,
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    quality,
                    sceneColor,
                    denoiser.fsrMotion(),
                    denoiser.fsrDepth(),
                    denoiser.fsrReactiveMask(),
                    denoiser.fsrTransparencyCompositionMask(),
                    denoiser.rawFrame(),
                    displayOutput);
            return new NrdFsrPostProcessor(
                    context,
                    quality,
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    sceneColor,
                    denoiser,
                    upscaler,
                    displayOutput);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(upscaler, exception);
            ResourceCleanup.destroy(denoiser, exception);
            ResourceCleanup.destroy(sceneColor, exception);
            throw exception;
        }
    }

    @Override public PostProcessingMode mode() { return PostProcessingMode.NRD_FSR; }
    @Override public ReconstructionQualityMode quality() { return this.quality; }
    @Override public int renderWidth() { return this.renderWidth; }
    @Override public int renderHeight() { return this.renderHeight; }
    @Override public int displayWidth() { return this.displayWidth; }
    @Override public int displayHeight() { return this.displayHeight; }
    @Override public RawWavefrontFrame rawFrame() { return this.denoiser.rawFrame(); }
    @Override public VulkanImage linearHdrOutput() { return this.upscaler.linearOutput(); }
    @Override public long displayExposureStateBuffer() {
        return this.upscaler.displayExposureStateBuffer();
    }

    @Override
    public void requestReset() {
        requireOpen();
        this.upscaler.requestReset();
    }

    @Override
    public FrameToken beginFrame(
            ReconstructionFrameParameters parameters,
            ReconstructionDebugSettings debugSettings) {
        requireOpen();
        Fsr3Upscaler.FrameToken fsr = this.upscaler.beginFrame(
                parameters.camera(),
                parameters.frameTimeNanos(),
                parameters.sceneRevision(),
                parameters.textureRevision(),
                parameters.forceRestart(),
                debugSettings.fsr());
        try {
            SubmittedFrame<NrdFramePlan> nrd = this.nrdHistory.plan(
                    new NrdFrameInput(
                            parameters.camera(),
                            parameters.frameTimeNanos(),
                            parameters.sceneRevision(),
                            parameters.textureRevision(),
                            parameters.sunDirection(),
                            fsr.jitter().x(),
                            fsr.jitter().y(),
                            fsr.reset(),
                            debugSettings.nrd()));
            return new FrameToken(
                    this, fsr, nrd, debugSettings);
        } catch (RuntimeException exception) {
            throw ResourceCleanup.run(
                    () -> this.upscaler.abandon(fsr), exception);
        }
    }

    @Override
    public void prepareForRayTrace(
            VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        requireOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean initialized = initialization.prepare(this.sceneColor);
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcStageMask(initialized
                            ? VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                            : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                    .srcAccessMask(initialized
                            ? VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT
                            : 0L)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(initialized
                            ? VK12.VK_IMAGE_LAYOUT_GENERAL
                            : VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(this.sceneColor.image());
            barrier.get(0).subresourceRange()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
        }
        this.denoiser.prepareForRayTrace(commandBuffer, initialization);
    }

    @Override
    public void record(
            VkCommandBuffer commandBuffer,
            Frame frame,
            ReconstructionFrameParameters parameters,
            VulkanImageInitializationBatch initialization) {
        FrameToken token = requireFrame(frame);
        if (token.recorded) {
            throw new IllegalArgumentException("NRD-FSR frame was already recorded");
        }
        // Recording can fail after emitting commands; such a token must never be retried into the
        // same or another command buffer.
        token.recorded = true;
        token.nrdPrepared =
                this.denoiser.prepareInputs(commandBuffer, token.nrdPlan);
        token.nrd = this.denoiser.recordReconstruction(
                commandBuffer,
                token.nrdPrepared,
                parameters.sunRadianceMultiplier(),
                parameters.display().displayTransformMode().shaderId());
        boolean displayDiagnostic =
                token.nrdPlan.plan().input().diagnostic() != NrdDiagnostics.Mode.OFF
                        || token.debugSettings.fsr() != FsrDebugView.OFF;
        this.upscaler.record(
                commandBuffer,
                token.fsr,
                parameters.display(),
                displayDiagnostic,
                initialization);
        NrdDiagnostics.Mode diagnostic =
                token.nrdPlan.plan().input().diagnostic();
        if (diagnostic != NrdDiagnostics.Mode.OFF) {
            this.nrdDebugPresent().record(
                    commandBuffer,
                    diagnostic.presentSource(),
                    diagnostic.presentation());
        }
    }

    @Override
    public void submitted(Frame frame) {
        requireOpen();
        FrameToken token = requireFrame(frame);
        if (!token.recorded || token.submitted || token.nrd == null) {
            throw new IllegalArgumentException("NRD-FSR frame was not recorded exactly once");
        }
        token.submitted = true;
        RuntimeException failure = null;
        SubmittedFrame<NrdFramePlan> submittedNrd = null;
        try {
            submittedNrd = this.denoiser.submitted(token.nrd);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        if (submittedNrd != null) {
            SubmittedFrame<NrdFramePlan> committedNrd = submittedNrd;
            failure = ResourceCleanup.run(
                    () -> this.nrdHistory.submitted(committedNrd), failure);
        }
        failure = ResourceCleanup.run(
                () -> this.upscaler.submitted(token.fsr), failure);
        ResourceCleanup.throwIfFailed(failure);
    }

    @Override
    public void abandon(Frame frame) {
        requireOpen();
        FrameToken token = requireFrame(frame);
        token.abandoned = true;
        RuntimeException failure = null;
        if (token.nrd != null) {
            failure = ResourceCleanup.run(
                    () -> this.denoiser.abandon(token.nrd), failure);
        }
        failure = ResourceCleanup.run(
                () -> this.nrdHistory.abandon(token.nrdPlan), failure);
        failure = ResourceCleanup.run(
                () -> this.upscaler.abandon(token.fsr), failure);
        ResourceCleanup.throwIfFailed(failure);
    }

    private FrameToken requireFrame(Frame frame) {
        if (!(frame instanceof FrameToken token)
                || token.owner != this
                || token.submitted
                || token.abandoned) {
            throw new IllegalArgumentException("NRD-FSR frame token does not belong to this processor");
        }
        return token;
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("NRD-FSR post-processor is destroyed");
        }
    }

    /** Keeps scene color in GENERAL before NRD's composite writes it. */
    public VulkanImage sceneColor() {
        return this.sceneColor;
    }

    private NativeDebugPresentPass nrdDebugPresent() {
        if (this.nrdDebugPresent == null) {
            this.nrdDebugPresent = NativeDebugPresentPass.create(
                    this.context,
                    this.displayOutput,
                    this.denoiser.validation(),
                    this.denoiser.rawNumericalDiagnostic(),
                    this.sceneColor);
        }
        return this.nrdDebugPresent;
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.nrdDebugPresent, failure);
        failure = ResourceCleanup.destroy(this.upscaler, failure);
        failure = ResourceCleanup.destroy(this.denoiser, failure);
        failure = ResourceCleanup.destroy(this.sceneColor, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }

    public static final class FrameToken implements Frame {
        private final NrdFsrPostProcessor owner;
        private final Fsr3Upscaler.FrameToken fsr;
        private final SubmittedFrame<NrdFramePlan> nrdPlan;
        private final ReconstructionDebugSettings debugSettings;
        private final ReconstructionFrame semantic;
        private NrdDenoiser.PreparedFrame nrdPrepared;
        private NrdDenoiser.FrameToken nrd;
        private boolean recorded;
        private boolean submitted;
        private boolean abandoned;

        private FrameToken(
                NrdFsrPostProcessor owner,
                Fsr3Upscaler.FrameToken fsr,
                SubmittedFrame<NrdFramePlan> nrdPlan,
                ReconstructionDebugSettings debugSettings) {
            this.owner = owner;
            this.fsr = fsr;
            this.nrdPlan = nrdPlan;
            this.debugSettings = debugSettings;
            this.semantic = new ReconstructionFrame(
                    fsr.frameIndex(), fsr.jitter(), fsr.reset());
        }

        @Override public ReconstructionFrame semantic() {
            return this.semantic;
        }
    }
}
