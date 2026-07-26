package dev.prime.render.vulkan.dlss;

import dev.prime.render.FrameCamera;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.RealtimePostProcessor;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.TemporalReconstructionState;
import dev.prime.render.post.ReconstructionFrameHistory;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.DisplayTransformPass;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.nrd.NrdCameraTransform;
import java.util.Objects;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/** Prime's complete real-time path-tracing to DLSS Ray Reconstruction frame boundary. */
public final class DlssRrPostProcessor implements RealtimePostProcessor {
    private final VulkanContext context;
    private final DlssRrNative.Context ngxContext;
    private final ReconstructionQualityMode quality;
    private final int renderWidth;
    private final int renderHeight;
    private final int displayWidth;
    private final int displayHeight;
    private final DlssRrTargets targets;
    private final DlssRrPreparePass preparePass;
    private final DlssRrNative.Feature feature;
    private final DisplayTransformPass displayTransform;
    private final DlssRrDebugPass debugPass;
    private final Matrix4f ngxProjection = new Matrix4f();
    private final ReconstructionFrameHistory history =
            new ReconstructionFrameHistory();
    private boolean destroyed;

    private DlssRrPostProcessor(
            VulkanContext context,
            DlssRrNative.Context ngxContext,
            ReconstructionQualityMode quality,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            DlssRrTargets targets,
            DlssRrPreparePass preparePass,
            DlssRrNative.Feature feature,
            DisplayTransformPass displayTransform,
            DlssRrDebugPass debugPass) {
        this.context = context;
        this.ngxContext = ngxContext;
        this.quality = quality;
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.targets = targets;
        this.preparePass = preparePass;
        this.feature = feature;
        this.displayTransform = displayTransform;
        this.debugPass = debugPass;
    }

    public static DlssRrPostProcessor create(
            VulkanContext context,
            DlssRrNative.Context ngxContext,
            AtmospherePipeline atmosphere,
            VulkanImage accumulation,
            VulkanImage displayOutput,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            ReconstructionQualityMode quality) {
        DlssRrTargets targets = null;
        DlssRrPreparePass preparePass = null;
        DlssRrNative.Feature feature = null;
        DisplayTransformPass displayTransform = null;
        DlssRrDebugPass debugPass = null;
        try {
            targets = DlssRrTargets.create(
                    context, renderWidth, renderHeight, displayWidth, displayHeight);
            preparePass = DlssRrPreparePass.create(context, targets, accumulation, atmosphere);
            displayTransform = DisplayTransformPass.create(context, targets.rrOutput(), displayOutput);
            debugPass = DlssRrDebugPass.create(context, targets, displayOutput);
            var encoder = context.commandEncoder();
            VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
            feature = ngxContext.createFeature(
                    commandBuffer,
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    quality);
            VulkanContext.check(
                    VK12.vkEndCommandBuffer(commandBuffer), "end DLSS RR feature creation command buffer");
            encoder.execute(commandBuffer);
            context.awaitIdle();
            return new DlssRrPostProcessor(
                    context,
                    ngxContext,
                    quality,
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    targets,
                    preparePass,
                    feature,
                    displayTransform,
                    debugPass);
        } catch (RuntimeException exception) {
            RuntimeException failure = ResourceCleanup.run(context::awaitIdle, exception);
            failure = ResourceCleanup.close(feature, failure);
            failure = ResourceCleanup.destroy(debugPass, failure);
            failure = ResourceCleanup.destroy(displayTransform, failure);
            failure = ResourceCleanup.destroy(preparePass, failure);
            failure = ResourceCleanup.destroy(targets, failure);
            throw failure;
        }
    }

    @Override public PostProcessingMode mode() { return PostProcessingMode.DLSS_RR; }
    @Override public DlssRrTargets rawFrame() { return this.targets; }
    @Override public VulkanImage linearHdrOutput() { return this.targets.rrOutput(); }
    @Override
    public ReconstructionQualityMode quality() { return this.quality; }
    @Override
    public int renderWidth() { return this.renderWidth; }
    @Override
    public int renderHeight() { return this.renderHeight; }
    @Override
    public int displayWidth() { return this.displayWidth; }
    @Override
    public int displayHeight() { return this.displayHeight; }

    public void requestReset() {
        requireOpen();
        this.history.requestReset();
    }

    public FrameToken beginFrame(
            FrameCamera camera,
            long frameTimeNanos,
            long sceneRevision,
            long textureRevision,
            boolean forceRestart,
            DlssRrDebugView debugView,
            boolean debugFullscreen) {
        requireOpen();
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(debugView, "debugView");
        ReconstructionFrameHistory.PlannedFrame temporal = this.history.plan(
                new TemporalReconstructionState.Input(
                        camera,
                        frameTimeNanos,
                        sceneRevision,
                        textureRevision,
                        forceRestart));
        FsrSettings.Jitter jitter = this.quality.rrJitter(
                temporal.plan().frameIndex());
        return new FrameToken(
                this,
                temporal,
                jitter,
                debugView,
                debugFullscreen);
    }

    @Override
    public FrameToken beginFrame(FrameParameters parameters) {
        return this.beginFrame(
                parameters.camera(),
                parameters.frameTimeNanos(),
                parameters.sceneRevision(),
                parameters.textureRevision(),
                parameters.forceRestart(),
                parameters.rrDebugView(),
                parameters.rrDebugFullscreen());
    }

    public void prepareForRayTrace(VkCommandBuffer commandBuffer) {
        requireOpen();
        this.targets.prepareForRayTrace(commandBuffer);
    }

    public void record(
            VkCommandBuffer commandBuffer,
            FrameToken token,
            float sunRadianceMultiplier,
            float displayOverexposure) {
        requireOpen();
        if (token.owner != this || token.recorded || token.submitted) {
            throw new IllegalArgumentException("DLSS RR frame token does not belong to this recording");
        }
        token.recorded = true;
        TemporalReconstructionState.Plan temporal =
                token.temporal.claimForExecution();
        this.preparePass.record(
                commandBuffer,
                temporal.camera(),
                temporal.historyCamera(),
                token.jitter,
                sunRadianceMultiplier);
        NrdCameraTransform.projectionForNrd(
                temporal.camera().projection(), this.ngxProjection);
        this.feature.evaluate(
                commandBuffer,
                new DlssRrNative.Evaluation(
                        this.renderWidth,
                        this.renderHeight,
                        token.jitter.x(),
                        token.jitter.y(),
                        this.renderWidth,
                        this.renderHeight,
                        temporal.restart(),
                        temporal.deltaMilliseconds(),
                        temporal.camera().viewRotation(),
                        this.ngxProjection,
                        this.targets.material(),
                        this.targets.specularMaterial(),
                        this.targets.rrNormalRoughness(),
                        this.targets.inputColor(),
                        this.targets.rrOutput(),
                        this.targets.viewZ(),
                        this.targets.motion(),
                        this.targets.specularMotion()));
        allCommandsToCompute(commandBuffer);
        this.displayTransform.record(commandBuffer, false, displayOverexposure);
        if (token.debugView != DlssRrDebugView.OFF) {
            allCommandsToCompute(commandBuffer);
            this.debugPass.record(
                    commandBuffer,
                    token.debugView,
                    token.debugFullscreen,
                    temporal.frameIndex(),
                    this.quality.rrJitterPhaseCount(),
                    displayOverexposure);
        }
    }

    @Override
    public void record(
            VkCommandBuffer commandBuffer, Frame frame, FrameParameters parameters) {
        if (!(frame instanceof FrameToken token)) {
            throw new IllegalArgumentException("DLSS RR received another processor's frame token");
        }
        this.record(
                commandBuffer,
                token,
                parameters.sunRadianceMultiplier(),
                parameters.displayOverexposure());
    }

    private static void allCommandsToCompute(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK12.VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
    }

    public void submitted(FrameToken token) {
        requireOpen();
        if (token.owner != this || !token.recorded || token.submitted) {
            throw new IllegalArgumentException("DLSS RR frame token does not belong to this submission");
        }
        token.submitted = true;
        this.history.submitted(token.temporal);
    }

    @Override
    public void submitted(Frame frame) {
        if (!(frame instanceof FrameToken token)) {
            throw new IllegalArgumentException("DLSS RR received another processor's frame token");
        }
        this.submitted(token);
    }

    private void requireOpen() {
        if (this.destroyed) throw new IllegalStateException("DLSS RR post-processor is destroyed");
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        // Do not make a failed wait terminal: no child handle is safe to release until all NGX
        // work has retired, and a later caller must be able to retry this ownership boundary.
        this.context.awaitIdle();
        RuntimeException failure = ResourceCleanup.close(this.feature, null);
        failure = ResourceCleanup.destroy(this.debugPass, failure);
        failure = ResourceCleanup.destroy(this.displayTransform, failure);
        failure = ResourceCleanup.destroy(this.preparePass, failure);
        failure = ResourceCleanup.destroy(this.targets, failure);
        this.destroyed = true;
        ResourceCleanup.throwIfFailed(failure);
    }

    public static final class FrameToken implements Frame {
        private final DlssRrPostProcessor owner;
        private final ReconstructionFrameHistory.PlannedFrame temporal;
        private final FsrSettings.Jitter jitter;
        private final DlssRrDebugView debugView;
        private final boolean debugFullscreen;
        private boolean recorded;
        private boolean submitted;

        private FrameToken(
                DlssRrPostProcessor owner,
                ReconstructionFrameHistory.PlannedFrame temporal,
                FsrSettings.Jitter jitter,
                DlssRrDebugView debugView,
                boolean debugFullscreen) {
            this.owner = owner;
            this.temporal = temporal;
            this.jitter = jitter;
            this.debugView = debugView;
            this.debugFullscreen = debugFullscreen;
        }

        @Override public int frameIndex() { return this.temporal.plan().frameIndex(); }
        @Override public FsrSettings.Jitter jitter() { return this.jitter; }
        @Override public boolean reset() { return this.temporal.plan().restart(); }
    }
}
