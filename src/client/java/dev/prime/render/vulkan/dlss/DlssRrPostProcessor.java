package dev.prime.render.vulkan.dlss;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.CameraDiscontinuity;
import dev.prime.render.FrameCamera;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.PostProcessingSettings;
import dev.prime.render.post.RealtimePostProcessor;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.DisplayTransformPass;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.nrd.NrdCameraTransform;
import java.util.List;
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
    private FrameCamera previousCamera;
    private long previousSceneRevision = Long.MIN_VALUE;
    private long previousAtlasView;
    private long previousAtlasSampler;
    private int frameIndex;
    private long previousFrameNanos;
    private boolean resetRequested = true;
    private boolean initialized;
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
            context.awaitIdle();
            if (feature != null) feature.close();
            if (debugPass != null) debugPass.destroy();
            if (displayTransform != null) displayTransform.destroy();
            if (preparePass != null) preparePass.destroy();
            if (targets != null) targets.destroy();
            throw exception;
        }
    }

    @Override public PostProcessingMode mode() { return PostProcessingMode.DLSS_RR; }
    @Override public DlssRrTargets targets() { return this.targets; }
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
        this.resetRequested = true;
    }

    public FrameToken beginFrame(
            FrameCamera camera,
            long sceneRevision,
            long atlasView,
            long atlasSampler) {
        requireOpen();
        Objects.requireNonNull(camera, "camera");
        boolean cameraCut = this.initialized && CameraDiscontinuity.isCut(this.previousCamera, camera);
        boolean reset = this.resetRequested
                || !this.initialized
                || cameraCut
                || sceneRevision != this.previousSceneRevision
                || atlasView != this.previousAtlasView
                || atlasSampler != this.previousAtlasSampler;
        int currentFrame = reset ? 0 : this.frameIndex;
        FsrSettings.Jitter jitter = this.quality.rrJitter(currentFrame);
        long now = System.nanoTime();
        float deltaMilliseconds = this.previousFrameNanos == 0L
                ? 1000.0F / 60.0F
                : Math.min((now - this.previousFrameNanos) * 1.0e-6F, 1000.0F);
        return new FrameToken(
                this,
                camera,
                reset ? camera : this.previousCamera,
                sceneRevision,
                atlasView,
                atlasSampler,
                currentFrame,
                jitter,
                reset,
                deltaMilliseconds,
                now);
    }

    @Override
    public FrameToken beginFrame(FrameParameters parameters) {
        return this.beginFrame(
                parameters.camera(),
                parameters.sceneRevision(),
                parameters.atlasView(),
                parameters.atlasSampler());
    }

    public void prepareForRayTrace(VkCommandBuffer commandBuffer) {
        requireOpen();
        this.targets.prepareForRayTrace(commandBuffer);
    }

    public void record(
            VkCommandBuffer commandBuffer, FrameToken token, float sunRadianceMultiplier) {
        requireOpen();
        if (token.owner != this || token.recorded || token.submitted) {
            throw new IllegalArgumentException("DLSS RR frame token does not belong to this recording");
        }
        token.recorded = true;
        this.preparePass.record(
                commandBuffer,
                token.camera,
                token.historyCamera,
                token.jitter,
                sunRadianceMultiplier);
        NrdCameraTransform.projectionForNrd(token.camera.projection(), this.ngxProjection);
        this.feature.evaluate(
                commandBuffer,
                new DlssRrNative.Evaluation(
                        this.renderWidth,
                        this.renderHeight,
                        -token.jitter.x(),
                        -token.jitter.y(),
                        this.renderWidth,
                        this.renderHeight,
                        token.reset,
                        token.deltaMilliseconds,
                        token.camera.viewRotation(),
                        this.ngxProjection,
                        List.of(
                                this.targets.material(),
                                this.targets.specularMaterial(),
                                this.targets.rrNormalRoughness(),
                                this.targets.inputColor(),
                                this.targets.colorBeforeTransparency(),
                                this.targets.rrOutput(),
                                this.targets.viewZ(),
                                this.targets.motion(),
                                this.targets.specularHitDistance())));
        allCommandsToCompute(commandBuffer);
        this.displayTransform.record(commandBuffer, false);
        if (PostProcessingSettings.rrDebugView() != DlssRrDebugView.OFF) {
            allCommandsToCompute(commandBuffer);
            this.debugPass.record(
                    commandBuffer, token.frameIndex, this.quality.rrJitterPhaseCount());
        }
    }

    @Override
    public void record(
            VkCommandBuffer commandBuffer, Frame frame, FrameParameters parameters) {
        if (!(frame instanceof FrameToken token)) {
            throw new IllegalArgumentException("DLSS RR received another processor's frame token");
        }
        this.record(commandBuffer, token, parameters.sunRadianceMultiplier());
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
        this.initialized = true;
        this.resetRequested = false;
        this.previousCamera = token.camera;
        this.previousSceneRevision = token.sceneRevision;
        this.previousAtlasView = token.atlasView;
        this.previousAtlasSampler = token.atlasSampler;
        this.previousFrameNanos = token.frameNanos;
        this.frameIndex = token.frameIndex + 1;
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
        this.destroyed = true;
        this.context.awaitIdle();
        this.feature.close();
        this.debugPass.destroy();
        this.displayTransform.destroy();
        this.preparePass.destroy();
        this.targets.destroy();
    }

    public static final class FrameToken implements Frame {
        private final DlssRrPostProcessor owner;
        private final FrameCamera camera;
        private final FrameCamera historyCamera;
        private final long sceneRevision;
        private final long atlasView;
        private final long atlasSampler;
        private final int frameIndex;
        private final FsrSettings.Jitter jitter;
        private final boolean reset;
        private final float deltaMilliseconds;
        private final long frameNanos;
        private boolean recorded;
        private boolean submitted;

        private FrameToken(
                DlssRrPostProcessor owner,
                FrameCamera camera,
                FrameCamera historyCamera,
                long sceneRevision,
                long atlasView,
                long atlasSampler,
                int frameIndex,
                FsrSettings.Jitter jitter,
                boolean reset,
                float deltaMilliseconds,
                long frameNanos) {
            this.owner = owner;
            this.camera = camera;
            this.historyCamera = historyCamera;
            this.sceneRevision = sceneRevision;
            this.atlasView = atlasView;
            this.atlasSampler = atlasSampler;
            this.frameIndex = frameIndex;
            this.jitter = jitter;
            this.reset = reset;
            this.deltaMilliseconds = deltaMilliseconds;
            this.frameNanos = frameNanos;
        }

        @Override public int frameIndex() { return this.frameIndex; }
        @Override public FsrSettings.Jitter jitter() { return this.jitter; }
        @Override public boolean reset() { return this.reset; }
    }
}
