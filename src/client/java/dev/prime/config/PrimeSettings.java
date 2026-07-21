package dev.prime.config;

import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import java.util.Objects;

/** Immutable product settings and renderer revision markers owned by {@link PrimeConfig}. */
public record PrimeSettings(
        PostProcessingMode postProcessingMode,
        ReconstructionQualityMode reconstructionQuality,
        int sunQuarterSteps,
        int blockLightQuarterSteps,
        int oklabOverexposureSteps,
        int defaultRoughnessSteps,
        long lightingRevision,
        long materialRevision) {
    public PrimeSettings {
        postProcessingMode = Objects.requireNonNull(postProcessingMode, "postProcessingMode");
        reconstructionQuality = Objects.requireNonNull(
                reconstructionQuality, "reconstructionQuality");
        LightingSettings.linearMultiplier(sunQuarterSteps);
        LightingSettings.linearMultiplier(blockLightQuarterSteps);
        DisplaySettings.overexposure(oklabOverexposureSteps);
        MaterialSettings.linearRoughness(defaultRoughnessSteps);
        if (lightingRevision < 0L || materialRevision < 0L) {
            throw new IllegalArgumentException("Prime setting revisions must not be negative");
        }
    }

    public static PrimeSettings defaults() {
        return new PrimeSettings(
                PostProcessingMode.DEFAULT,
                ReconstructionQualityMode.DEFAULT,
                LightingSettings.DEFAULT_SUN_QUARTER_STEPS,
                LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS,
                DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS,
                MaterialSettings.DEFAULT_ROUGHNESS_STEPS,
                0L,
                0L);
    }

    public PrimeSettings withPostProcessingMode(PostProcessingMode value) {
        Objects.requireNonNull(value, "value");
        return value == this.postProcessingMode
                ? this
                : new PrimeSettings(
                        value,
                        this.reconstructionQuality,
                        this.sunQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.defaultRoughnessSteps,
                        this.lightingRevision,
                        this.materialRevision);
    }

    public PrimeSettings withReconstructionQuality(ReconstructionQualityMode value) {
        Objects.requireNonNull(value, "value");
        return value == this.reconstructionQuality
                ? this
                : new PrimeSettings(
                        this.postProcessingMode,
                        value,
                        this.sunQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.defaultRoughnessSteps,
                        this.lightingRevision,
                        this.materialRevision);
    }

    public PrimeSettings withSunQuarterSteps(int value) {
        LightingSettings.linearMultiplier(value);
        return value == this.sunQuarterSteps
                ? this
                : new PrimeSettings(
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        value,
                        this.blockLightQuarterSteps,
                        this.oklabOverexposureSteps,
                        this.defaultRoughnessSteps,
                        Math.incrementExact(this.lightingRevision),
                        this.materialRevision);
    }

    public PrimeSettings withBlockLightQuarterSteps(int value) {
        LightingSettings.linearMultiplier(value);
        return value == this.blockLightQuarterSteps
                ? this
                : new PrimeSettings(
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.sunQuarterSteps,
                        value,
                        this.oklabOverexposureSteps,
                        this.defaultRoughnessSteps,
                        Math.incrementExact(this.lightingRevision),
                        this.materialRevision);
    }

    public PrimeSettings withOklabOverexposureSteps(int value) {
        DisplaySettings.overexposure(value);
        return value == this.oklabOverexposureSteps
                ? this
                : new PrimeSettings(
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.sunQuarterSteps,
                        this.blockLightQuarterSteps,
                        value,
                        this.defaultRoughnessSteps,
                        this.lightingRevision,
                        this.materialRevision);
    }

    public PrimeSettings withDefaultRoughnessSteps(int value) {
        MaterialSettings.linearRoughness(value);
        return value == this.defaultRoughnessSteps
                ? this
                : new PrimeSettings(
                        this.postProcessingMode,
                        this.reconstructionQuality,
                        this.sunQuarterSteps,
                        this.blockLightQuarterSteps,
                        this.oklabOverexposureSteps,
                        value,
                        this.lightingRevision,
                        Math.incrementExact(this.materialRevision));
    }

    public LightingSettings.Snapshot lighting() {
        return new LightingSettings.Snapshot(
                this.sunQuarterSteps,
                this.blockLightQuarterSteps,
                LightingSettings.linearMultiplier(this.sunQuarterSteps),
                LightingSettings.linearMultiplier(this.blockLightQuarterSteps),
                this.lightingRevision);
    }

    public MaterialSettings.Snapshot material() {
        return new MaterialSettings.Snapshot(
                this.defaultRoughnessSteps,
                MaterialSettings.linearRoughness(this.defaultRoughnessSteps),
                this.materialRevision);
    }

    public float oklabOverexposure() {
        return DisplaySettings.overexposure(this.oklabOverexposureSteps);
    }
}
