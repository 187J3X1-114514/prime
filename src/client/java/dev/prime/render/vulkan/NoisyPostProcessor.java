package dev.prime.render.vulkan;

import dev.prime.render.ResourceCleanup;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.RealtimePostProcessor;
import dev.prime.render.post.ReconstructionQualityMode;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Native-resolution 1 spp presentation path with no denoising or temporal reconstruction. */
public final class NoisyPostProcessor implements RealtimePostProcessor {
    private final ReconstructionQualityMode quality;
    private final int width;
    private final int height;
    private final BasicRawWavefrontFrame rawFrame;
    private final NoisyCompositePass composite;
    private final DisplayTransformPass displayTransform;
    private int frameIndex;
    private boolean resetRequested = true;
    private boolean destroyed;

    private NoisyPostProcessor(
            ReconstructionQualityMode quality,
            int width,
            int height,
            BasicRawWavefrontFrame rawFrame,
            NoisyCompositePass composite,
            DisplayTransformPass displayTransform) {
        this.quality = quality;
        this.width = width;
        this.height = height;
        this.rawFrame = rawFrame;
        this.composite = composite;
        this.displayTransform = displayTransform;
    }

    public static NoisyPostProcessor create(
            VulkanContext context,
            AtmospherePipeline atmosphere,
            VulkanImage stableRadiance,
            VulkanImage displayOutput,
            int width,
            int height,
            ReconstructionQualityMode quality) {
        BasicRawWavefrontFrame rawFrame = null;
        NoisyCompositePass composite = null;
        DisplayTransformPass displayTransform = null;
        try {
            rawFrame = BasicRawWavefrontFrame.createRealtime(context, width, height);
            composite = NoisyCompositePass.create(
                    context, rawFrame, stableRadiance, atmosphere);
            displayTransform = DisplayTransformPass.create(
                    context, rawFrame.linearOutput(), displayOutput);
            return new NoisyPostProcessor(
                    quality, width, height, rawFrame, composite, displayTransform);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(displayTransform, exception);
            ResourceCleanup.destroy(composite, exception);
            ResourceCleanup.destroy(rawFrame, exception);
            throw exception;
        }
    }

    @Override public PostProcessingMode mode() { return PostProcessingMode.DISABLED; }
    @Override public ReconstructionQualityMode quality() { return this.quality; }
    @Override public int renderWidth() { return this.width; }
    @Override public int renderHeight() { return this.height; }
    @Override public int displayWidth() { return this.width; }
    @Override public int displayHeight() { return this.height; }
    @Override public RawWavefrontFrame rawFrame() { return this.rawFrame; }
    @Override public VulkanImage linearHdrOutput() { return this.rawFrame.linearOutput(); }

    @Override
    public void requestReset() {
        requireOpen();
        this.resetRequested = true;
    }

    @Override
    public Frame beginFrame(FrameParameters parameters) {
        requireOpen();
        boolean reset = this.resetRequested || parameters.forceRestart();
        int index = reset ? 0 : this.frameIndex;
        return new FrameToken(
                this,
                index,
                this.quality.rrJitter(index),
                reset);
    }

    @Override
    public void prepareForRayTrace(VkCommandBuffer commandBuffer) {
        requireOpen();
        this.rawFrame.prepareForRayTrace(commandBuffer);
    }

    @Override
    public void record(
            VkCommandBuffer commandBuffer, Frame frame, FrameParameters parameters) {
        FrameToken token = requireFrame(frame);
        token.recorded = true;
        this.composite.record(commandBuffer, parameters.sunRadianceMultiplier());
        this.displayTransform.record(commandBuffer, false, parameters.displayOverexposure());
    }

    @Override
    public void submitted(Frame frame) {
        requireOpen();
        if (!(frame instanceof FrameToken token)
                || token.owner != this
                || !token.recorded
                || token.submitted) {
            throw new IllegalArgumentException(
                    "Noisy frame was not recorded exactly once by this processor");
        }
        token.submitted = true;
        this.resetRequested = false;
        this.frameIndex = token.frameIndex + 1;
    }

    private FrameToken requireFrame(Frame frame) {
        requireOpen();
        if (!(frame instanceof FrameToken token)
                || token.owner != this
                || token.submitted
                || token.recorded) {
            throw new IllegalArgumentException("Noisy frame token does not belong to this processor");
        }
        return token;
    }

    private void requireOpen() {
        if (this.destroyed) throw new IllegalStateException("Noisy post-processor is destroyed");
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        RuntimeException failure = null;
        failure = ResourceCleanup.destroy(this.displayTransform, failure);
        failure = ResourceCleanup.destroy(this.composite, failure);
        failure = ResourceCleanup.destroy(this.rawFrame, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }

    private static final class FrameToken implements Frame {
        private final NoisyPostProcessor owner;
        private final int frameIndex;
        private final FsrSettings.Jitter jitter;
        private final boolean reset;
        private boolean recorded;
        private boolean submitted;

        private FrameToken(
                NoisyPostProcessor owner,
                int frameIndex,
                FsrSettings.Jitter jitter,
                boolean reset) {
            this.owner = owner;
            this.frameIndex = frameIndex;
            this.jitter = jitter;
            this.reset = reset;
        }

        @Override public int frameIndex() { return this.frameIndex; }
        @Override public FsrSettings.Jitter jitter() { return this.jitter; }
        @Override public boolean reset() { return this.reset; }
    }
}
