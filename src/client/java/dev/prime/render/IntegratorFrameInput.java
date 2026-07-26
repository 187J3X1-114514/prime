package dev.prime.render;

import dev.prime.render.post.PostProcessingMode;
import java.util.Objects;

/**
 * Complete device-address-free semantic input of one wavefront integrator dispatch.
 *
 * <p>GPU residency is bound only by the device executor. The same value can therefore be encoded,
 * persisted and rebound without allocator handles becoming part of render identity.
 */
public record IntegratorFrameInput(
        FrameCamera camera,
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
    public IntegratorFrameInput {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(sunDirection, "sunDirection");
        Objects.requireNonNull(postProcessingMode, "postProcessingMode");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(material, "material");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Integrator extent must be positive");
        }
        if (sampleIndex < 0 || sampleIndex >= 1 << 16) {
            throw new IllegalArgumentException(
                    "Sample index must fit the Sobol sequence");
        }
        float rayConeWidth =
                Float.float16ToFloat((short) packedRayCone);
        float mipBias =
                Float.float16ToFloat((short) (packedRayCone >>> 16));
        if (!(rayConeWidth > 0.0F)
                || !Float.isFinite(rayConeWidth)
                || !Float.isFinite(mipBias)) {
            throw new IllegalArgumentException(
                    "Packed ray cone must contain a positive finite width and finite mip bias");
        }
        if (!camera.projection().isFinite()
                || !camera.viewRotation().isFinite()
                || !camera.inverseViewProjection().isFinite()
                || !Double.isFinite(camera.x())
                || !Double.isFinite(camera.y())
                || !Double.isFinite(camera.z())
                || !Double.isFinite(camera.renderX())
                || !Double.isFinite(camera.renderY())
                || !Double.isFinite(camera.renderZ())) {
            throw new IllegalArgumentException(
                    "Integrator camera must be finite");
        }
        IntegratorSettings.packSampleEpoch(sampleEpoch, triangleDebug);
        IntegratorSettings.packPathControl(
                IntegratorSettings.MAXIMUM_BOUNCES,
                jitterPhase,
                cameraInWater,
                postProcessingMode);
        IntegratorSettings.packMaterialLightingControl(
                lighting.sunQuarterSteps(),
                lighting.starQuarterSteps(),
                lighting.blockLightQuarterSteps(),
                material.roughnessSteps(),
                shInput,
                rawNumericalDiagnostic);
    }
}
