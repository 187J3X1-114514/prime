package dev.prime.render.vulkan.replay;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.AstronomyState;
import dev.prime.render.DisplaySettings;
import dev.prime.render.FrameCamera;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.IntegratorSettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.LightweightIntegratorSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.RealtimeIntegratorMode;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.fsr.FsrReconstructionProfile;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.replay.RayTraceReplayInput;
import dev.prime.render.replay.RenderBinaryFingerprint;
import dev.prime.render.replay.RenderPlatformFingerprint;
import dev.prime.render.replay.RenderReplayCapture;
import dev.prime.render.replay.RenderReplaySequence;
import dev.prime.render.replay.RenderReplayVerification;
import dev.prime.render.vulkan.terrain.TerrainScene;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.RealtimeIntegratorPipeline;
import dev.prime.render.vulkan.TraceBackend;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sole request, execution and lifetime owner of deterministic low-resolution replay probes.
 *
 * <p>The interactive renderer publishes one immutable {@link RunInput}. This boundary owns both
 * isolated NRD histories and all asynchronous completion state; it restores interactive
 * descriptors before returning from the render-thread call.
 */
public final class ReplayProbeController implements Destroyable {
    private static final int JITTER_PHASES = 8;
    private static final int JITTER_PROBE_FRAMES = JITTER_PHASES * 2;
    private final VulkanContext context;
    private final ReplayProbeFrameExecutor executor;
    private final ReplayProbeRequestState<RenderReplayVerification> requests =
            new ReplayProbeRequestState<>();
    private final AtomicReference<Session> activeSession =
            new AtomicReference<>();
    private boolean destroyed;

    public ReplayProbeController(VulkanContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.executor = new ReplayProbeFrameExecutor(context);
    }

    public CompletableFuture<RenderReplayVerification> request(
            int width, int height) {
        return this.requests.request(width, height);
    }

    /**
     * Consumes at most one pending request after the interactive frame has been submitted.
     */
    public void run(RunInput input) {
        Objects.requireNonNull(input, "input");
        ReplayProbeRequestState.Request<RenderReplayVerification> pending =
                this.requests.claim();
        if (pending == null) {
            return;
        }
        NrdReplayProbe referenceProbe = null;
        NrdReplayProbe replayProbe = null;
        Session session = null;
        boolean descriptorsChanged = false;
        try {
            requireRayDispatchCapacity(
                    pending.width(), pending.height());
            RenderPlatformFingerprint platform =
                    PlatformFingerprintProbe.capture(this.context);
            RenderBinaryFingerprint binary =
                    RenderBinaryFingerprint.capture(
                            platform.invocationReorderSupported()
                                    && platform.wavefrontSubgroupSupported(),
                            input.pipeline.mode());
            referenceProbe = NrdReplayProbe.create(
                    this.context,
                    input.atmosphere,
                    pending.width(),
                    pending.height());
            replayProbe = NrdReplayProbe.create(
                    this.context,
                    input.atmosphere,
                    pending.width(),
                    pending.height());
            session = new Session(referenceProbe, replayProbe);
            if (!this.activeSession.compareAndSet(null, session)) {
                throw new IllegalStateException(
                        "A replay probe session is already active");
            }
            descriptorsChanged = true;
            CompletableFuture<RenderReplaySequence> reference =
                    submitSequence(
                            referenceProbe,
                            input,
                            platform,
                            binary,
                            pending.width(),
                            pending.height());
            CompletableFuture<RenderReplaySequence> replay =
                    submitSequence(
                            replayProbe,
                            input,
                            platform,
                            binary,
                            pending.width(),
                            pending.height());
            CompletableFuture<RenderReplayVerification> verification =
                    reference.thenCombine(
                            replay, RenderReplayVerification::compare);
            Session submittedSession = session;
            verification.whenComplete((result, failure) -> {
                this.activeSession.compareAndSet(submittedSession, null);
                if (failure == null) {
                    this.requests.complete(pending, result);
                } else {
                    this.requests.fail(pending, failure);
                }
            });
            referenceProbe = null;
            replayProbe = null;
            session = null;
        } catch (RuntimeException exception) {
            this.requests.fail(pending, exception);
            PrimeInfo.LOGGER.error(
                    "Prime replay verification failed without affecting the interactive frame",
                    exception);
        } finally {
            if (descriptorsChanged) {
                input.pipeline.ensureDescriptors(
                        input.scene.tlas(),
                        input.stableRadiance,
                        input.atlasView,
                        input.atlasSampler,
                        input.sceneTextures,
                        input.labPbrNormalAtlas,
                        input.labPbrSpecularAtlas,
                        input.atmosphere,
                        input.interactiveRawFrame);
            }
            if (session != null) {
                ResourceCleanup.destroy(session, null);
                this.activeSession.compareAndSet(session, null);
            } else {
                ResourceCleanup.destroy(replayProbe, null);
                ResourceCleanup.destroy(referenceProbe, null);
            }
        }
    }

    private CompletableFuture<RenderReplaySequence> submitSequence(
            NrdReplayProbe probe,
            RunInput input,
            RenderPlatformFingerprint platform,
            RenderBinaryFingerprint binary,
            int width,
            int height) {
        ArrayList<CompletableFuture<RenderReplayCapture>>
                frameCaptures = new ArrayList<>(JITTER_PROBE_FRAMES);
        try {
            input.pipeline.ensureDescriptors(
                    input.scene.tlas(),
                    probe.stableRadiance(),
                    input.atlasView,
                    input.atlasSampler,
                    input.sceneTextures,
                    input.labPbrNormalAtlas,
                    input.labPbrSpecularAtlas,
                    input.atmosphere,
                    probe.rawFrame());
            for (int frameIndex = 0;
                    frameIndex < JITTER_PROBE_FRAMES;
                    frameIndex++) {
                // The first Halton cycle warms NRD history. The analyzer fits the second cycle,
                // after the same eight phases have populated every temporal slot once.
                frameCaptures.add(recordFrame(
                        probe,
                        input,
                        input.camera,
                        platform,
                        binary,
                        width,
                        height,
                        frameIndex,
                        frameIndex * 16_666_667L,
                        frameIndex == 0));
            }
            return probe.finish(frameCaptures);
        } catch (RuntimeException exception) {
            return probe.abort(frameCaptures, exception);
        }
    }

    private CompletableFuture<RenderReplayCapture> recordFrame(
            NrdReplayProbe probe,
            RunInput input,
            FrameCamera camera,
            RenderPlatformFingerprint platform,
            RenderBinaryFingerprint binary,
            int width,
            int height,
            int frameIndex,
            long frameTimeNanos,
            boolean forceRestart) {
        FsrReconstructionProfile profile = FsrReconstructionProfile.forQuality(
                ReconstructionQualityMode.NATIVE_AA);
        SubpixelJitter jitter = profile.jitter(frameIndex);
        NrdReplayProbe.PlannedFrame nrdFrame = probe.planFrame(
                camera,
                frameTimeNanos,
                input.scene.temporalRevision(),
                input.textureRevision,
                input.astronomy.sunDirection(),
                jitter.x(),
                jitter.y(),
                forceRestart);
        IntegratorFrameInput frameInput = new IntegratorFrameInput(
                camera,
                width,
                height,
                input.astronomy,
                profile.packedRayCone(
                        camera.projection().m00(),
                        camera.projection().m11(),
                        width,
                        height),
                input.maximumBounces,
                0,
                1,
                profile.jitterPhase(frameIndex),
                input.cameraInWater,
                PostProcessingMode.NRD_FSR,
                TransparentGuideMode.REFLECTION_AND_TRANSMISSION,
                input.lighting,
                input.material,
                true,
                false,
                false);
        RayTraceReplayInput replayInput =
                RayTraceReplayInput.capture(
                        input.pipeline.mode(), frameInput, input.scene);
        return this.executor.execute(
                forceRestart
                        ? "Prime deterministic replay jitter restart"
                        : "Prime deterministic replay jitter phase "
                                + (frameIndex + 1),
                input.pipeline,
                probe,
                nrdFrame,
                input.scene,
                frameInput,
                replayInput,
                input.atlasView,
                input.sceneTextures,
                input.lighting.sunMultiplier(),
                input.display,
                platform,
                binary);
    }

    private void requireRayDispatchCapacity(int width, int height) {
        long invocationCount = (long) width * height;
        if (invocationCount
                > Integer.toUnsignedLong(
                        this.context.capabilities()
                                .maxRayDispatchInvocationCount())) {
            throw new IllegalStateException(
                    "Replay dimensions exceed the Vulkan ray dispatch limit");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        this.requests.destroy();
        Session session = this.activeSession.getAndSet(null);
        ResourceCleanup.destroy(session, null);
    }

    /** Immutable render-thread snapshot required to execute one pending request. */
    public record RunInput(
            RealtimeIntegratorPipeline pipeline,
            AtmospherePipeline atmosphere,
            TerrainScene.ResidentSceneView scene,
            FrameCamera camera,
            AstronomyState astronomy,
            boolean cameraInWater,
            int maximumBounces,
            LightingSettings.Snapshot lighting,
            MaterialSettings.Snapshot material,
            DisplaySettings.Snapshot display,
            VulkanGpuTextureView atlasView,
            VulkanGpuSampler atlasSampler,
            List<TraceBackend.SceneTexture> sceneTextures,
            long textureRevision,
            VulkanImage stableRadiance,
            VulkanImage labPbrNormalAtlas,
            VulkanImage labPbrSpecularAtlas,
            RawWavefrontFrame interactiveRawFrame) {
        public RunInput {
            Objects.requireNonNull(pipeline, "pipeline");
            Objects.requireNonNull(atmosphere, "atmosphere");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(camera, "camera");
            Objects.requireNonNull(astronomy, "astronomy");
            if (pipeline.mode() == RealtimeIntegratorMode.LIGHTWEIGHT) {
                LightweightIntegratorSettings.validateScatters(maximumBounces);
            } else if (maximumBounces != IntegratorSettings.MAXIMUM_BOUNCES) {
                throw new IllegalArgumentException(
                        "The full integrator requires its fixed maximum bounce count");
            }
            Objects.requireNonNull(lighting, "lighting");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(display, "display");
            Objects.requireNonNull(atlasView, "atlasView");
            Objects.requireNonNull(atlasSampler, "atlasSampler");
            sceneTextures = List.copyOf(sceneTextures);
            Objects.requireNonNull(stableRadiance, "stableRadiance");
            Objects.requireNonNull(
                    labPbrNormalAtlas, "labPbrNormalAtlas");
            Objects.requireNonNull(
                    labPbrSpecularAtlas, "labPbrSpecularAtlas");
            Objects.requireNonNull(
                    interactiveRawFrame, "interactiveRawFrame");
        }
    }

    /** Cross-thread completion owner for the two isolated histories. */
    private static final class Session implements Destroyable {
        private final NrdReplayProbe reference;
        private final NrdReplayProbe replay;
        private boolean destroyed;

        private Session(
                NrdReplayProbe reference, NrdReplayProbe replay) {
            this.reference = reference;
            this.replay = replay;
        }

        @Override
        public void destroy() {
            if (this.destroyed) {
                return;
            }
            RuntimeException failure = null;
            failure = ResourceCleanup.destroy(this.replay, failure);
            failure = ResourceCleanup.destroy(this.reference, failure);
            this.destroyed = true;
            ResourceCleanup.throwIfFailed(failure);
        }
    }
}
