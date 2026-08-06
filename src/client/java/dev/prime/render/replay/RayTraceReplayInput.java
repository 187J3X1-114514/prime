package dev.prime.render.replay;

import dev.prime.render.AstronomyState;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.IntegratorSettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.LightweightIntegratorSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.RealtimeIntegratorMode;
import dev.prime.render.SunDirection;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.scene.SceneRevisionView;
import java.util.Objects;

/**
 * Device-address-free semantic input for one path-tracing dispatch.
 *
 * <p>The resident scene is rebound only at execution. This prevents allocator-dependent TLAS and
 * section-table addresses from becoming replay identity.
 */
public record RayTraceReplayInput(
        FrameCameraSnapshot camera,
        SceneIdentity scene,
        RealtimeIntegratorMode integratorMode,
        int width,
        int height,
        AstronomyState astronomy,
        int packedRayCone,
        int maximumBounces,
        int sampleIndex,
        int sampleEpoch,
        int jitterPhase,
        boolean cameraInWater,
        PostProcessingMode postProcessingMode,
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        boolean shInput,
        boolean rawNumericalDiagnostic,
        boolean triangleDebug) {
    public RayTraceReplayInput {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(integratorMode, "integratorMode");
        Objects.requireNonNull(astronomy, "astronomy");
        Objects.requireNonNull(postProcessingMode, "postProcessingMode");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(material, "material");
        if (!camera.isFinite()) {
            throw new IllegalArgumentException(
                    "Ray-tracing replay camera must be finite");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Ray-tracing extent must be positive");
        }
        if (sampleIndex < 0 || sampleIndex >= 1 << 16) {
            throw new IllegalArgumentException(
                    "Sample index must fit the Sobol sequence");
        }
        if (integratorMode == RealtimeIntegratorMode.LIGHTWEIGHT) {
            LightweightIntegratorSettings.validateScatters(maximumBounces);
        } else if (maximumBounces != IntegratorSettings.MAXIMUM_BOUNCES) {
            throw new IllegalArgumentException(
                    "The full integrator requires its fixed maximum bounce count");
        }
        IntegratorSettings.packPathControl(
                maximumBounces,
                jitterPhase,
                astronomy.settings(),
                cameraInWater,
                guideMode(postProcessingMode));
    }

    public static RayTraceReplayInput capture(
            IntegratorFrameInput input,
            SceneRevisionView scene) {
        return capture(RealtimeIntegratorMode.DEFAULT, input, scene);
    }

    public static RayTraceReplayInput capture(
            RealtimeIntegratorMode integratorMode,
            IntegratorFrameInput input,
            SceneRevisionView scene) {
        Objects.requireNonNull(integratorMode, "integratorMode");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(scene, "scene");
        if (input.transparentGuideMode() != guideMode(input.postProcessingMode())) {
            throw new IllegalArgumentException(
                    "Replay wire mode cannot represent a non-canonical transparent guide");
        }
        return new RayTraceReplayInput(
                FrameCameraSnapshot.capture(input.camera()),
                SceneIdentity.capture(scene),
                integratorMode,
                input.width(),
                input.height(),
                input.astronomy(),
                input.packedRayCone(),
                input.maximumBounces(),
                input.sampleIndex(),
                input.sampleEpoch(),
                input.jitterPhase(),
                input.cameraInWater(),
                input.postProcessingMode(),
                input.lighting(),
                input.material(),
                input.shInput(),
                input.rawNumericalDiagnostic(),
                input.triangleDebug());
    }

    public IntegratorFrameInput bind(
            SceneRevisionView residentScene) {
        this.scene.requireMatch(residentScene);
        return new IntegratorFrameInput(
                this.camera.materialize(),
                this.width,
                this.height,
                this.astronomy,
                this.packedRayCone,
                this.maximumBounces,
                this.sampleIndex,
                this.sampleEpoch,
                this.jitterPhase,
                this.cameraInWater,
                this.postProcessingMode,
                guideMode(this.postProcessingMode),
                this.lighting,
                this.material,
                this.shInput,
                this.rawNumericalDiagnostic,
                this.triangleDebug);
    }

    public SunDirection sunDirection() {
        return this.astronomy.sunDirection();
    }

    private static TransparentGuideMode guideMode(PostProcessingMode mode) {
        return switch (mode) {
            case NRD_FSR -> TransparentGuideMode.REFLECTION_AND_TRANSMISSION;
            case DLSS_RR -> TransparentGuideMode.TRANSMISSION_ONLY;
            case DISABLED -> TransparentGuideMode.DISABLED;
        };
    }

    public void requireMatch(
            IntegratorFrameInput input,
            SceneRevisionView residentScene) {
        this.requireMatch(RealtimeIntegratorMode.DEFAULT, input, residentScene);
    }

    public void requireMatch(
            RealtimeIntegratorMode integratorMode,
            IntegratorFrameInput input,
            SceneRevisionView residentScene) {
        if (!this.equals(capture(integratorMode, input, residentScene))) {
            throw new IllegalArgumentException(
                    "Integrator input does not match its replay capture");
        }
    }

    public record SceneIdentity(
            int originX,
            int originY,
            int originZ,
            long revision,
            long resetRevision,
            long temporalRevision) {
        public static SceneIdentity capture(
                SceneRevisionView scene) {
            Objects.requireNonNull(scene, "scene");
            return new SceneIdentity(
                    scene.originX(),
                    scene.originY(),
                    scene.originZ(),
                    scene.revision(),
                    scene.resetRevision(),
                    scene.temporalRevision());
        }

        void requireMatch(SceneRevisionView scene) {
            Objects.requireNonNull(scene, "scene");
            if (scene.originX() != this.originX
                    || scene.originY() != this.originY
                    || scene.originZ() != this.originZ
                    || scene.revision() != this.revision
                    || scene.resetRevision() != this.resetRevision
                    || scene.temporalRevision() != this.temporalRevision) {
                throw new IllegalArgumentException(
                        "Resident scene does not match the replay scene identity");
            }
        }
    }
}
