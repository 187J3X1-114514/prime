package dev.prime.render.replay;

import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.SunDirection;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.terrain.TerrainScene;
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
        int width,
        int height,
        SunDirection sunDirection,
        int packedRayCone,
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
        Objects.requireNonNull(sunDirection, "sunDirection");
        Objects.requireNonNull(postProcessingMode, "postProcessingMode");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(material, "material");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Ray-tracing extent must be positive");
        }
        if (sampleIndex < 0 || sampleIndex >= 1 << 16) {
            throw new IllegalArgumentException(
                    "Sample index must fit the Sobol sequence");
        }
    }

    public static RayTraceReplayInput capture(
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(scene, "scene");
        return new RayTraceReplayInput(
                FrameCameraSnapshot.capture(input.camera()),
                SceneIdentity.capture(scene),
                input.width(),
                input.height(),
                input.sunDirection(),
                input.packedRayCone(),
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
            TerrainScene.ResidentSceneView residentScene) {
        this.scene.requireMatch(residentScene);
        return new IntegratorFrameInput(
                this.camera.materialize(),
                this.width,
                this.height,
                this.sunDirection,
                this.packedRayCone,
                this.sampleIndex,
                this.sampleEpoch,
                this.jitterPhase,
                this.cameraInWater,
                this.postProcessingMode,
                this.lighting,
                this.material,
                this.shInput,
                this.rawNumericalDiagnostic,
                this.triangleDebug);
    }

    public void requireMatch(
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView residentScene) {
        if (!this.equals(capture(input, residentScene))) {
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
                TerrainScene.ResidentSceneView scene) {
            Objects.requireNonNull(scene, "scene");
            return new SceneIdentity(
                    scene.originX(),
                    scene.originY(),
                    scene.originZ(),
                    scene.revision(),
                    scene.resetRevision(),
                    scene.temporalRevision());
        }

        void requireMatch(TerrainScene.ResidentSceneView scene) {
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
