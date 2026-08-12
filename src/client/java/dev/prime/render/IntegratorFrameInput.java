package dev.prime.render;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.TransparentGuideMode;
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
        AstronomyState astronomy,
        int packedRayCone,
        int maximumBounces,
        int wavefrontRounds,
        int sampleIndex,
        int sampleEpoch,
        int jitterPhase,
        boolean cameraInWater,
        PostProcessingMode postProcessingMode,
        TransparentGuideMode transparentGuideMode,
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        boolean shInput,
        boolean rawNumericalDiagnostic,
        boolean triangleDebug) {
    public IntegratorFrameInput(
            FrameCamera camera,
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
            TransparentGuideMode transparentGuideMode,
            LightingSettings.Snapshot lighting,
            MaterialSettings.Snapshot material,
            boolean shInput,
            boolean rawNumericalDiagnostic,
            boolean triangleDebug) {
        this(
                camera, width, height, astronomy, packedRayCone, maximumBounces,
                WavefrontSettings.DEFAULT_ROUNDS, sampleIndex, sampleEpoch, jitterPhase,
                cameraInWater, postProcessingMode, transparentGuideMode, lighting, material,
                shInput, rawNumericalDiagnostic, triangleDebug);
    }

    public IntegratorFrameInput {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(astronomy, "astronomy");
        Objects.requireNonNull(postProcessingMode, "postProcessingMode");
        Objects.requireNonNull(transparentGuideMode, "transparentGuideMode");
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
        if (!camera.isFinite()) {
            throw new IllegalArgumentException(
                    "Integrator camera must be finite");
        }
        IntegratorSettings.packSampleControl(
                sampleIndex,
                astronomy.settings(),
                material.seamlessGlass(),
                material.airGap());
        IntegratorSettings.packSampleEpoch(sampleEpoch, triangleDebug);
        IntegratorSettings.packPathControl(
                maximumBounces,
                jitterPhase,
                astronomy.settings(),
                cameraInWater,
                transparentGuideMode);
        WavefrontSettings.validateRounds(wavefrontRounds);
        IntegratorSettings.packMaterialLightingControl(
                lighting.sunQuarterSteps(),
                lighting.starQuarterSteps(),
                lighting.blockLightQuarterSteps(),
                material.roughnessSteps(),
                shInput,
                rawNumericalDiagnostic);
    }

    public SunDirection sunDirection() {
        return this.astronomy.sunDirection();
    }
}
